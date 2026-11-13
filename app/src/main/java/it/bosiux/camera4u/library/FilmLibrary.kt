package it.bosiux.camera4u.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import it.bosiux.camera4u.model.Film
import it.bosiux.camera4u.rendering.CubeLut
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class BulkDownloadProgress(val total: Int, val completed: Int = 0) {
    val fraction: Float get() = if (total == 0) 1f else completed.toFloat() / total
}

/**
 * The film catalog from github.com/YahiaAngelo/Film-Luts plus the user's
 * downloaded ("My Films") collection, stored on device for offline use.
 */
class FilmLibrary(private val context: Context, private val scope: CoroutineScope) {
    companion object {
        const val REPOSITORY_URL = "https://raw.githubusercontent.com/YahiaAngelo/Film-Luts/main/"
        const val CATALOG_FILE = "film_luts.json"

        /** Films shipped inside the app so it works offline from the first launch. */
        val bundledIds = listOf(
            "luts/negative_new/kodak_portra_400.cube",
            "luts/negative_color/kodak_ektar_100.cube",
            "luts/negative_old/fuji_superia_400.cube",
            "luts/fujixtransiii/fuji_xtrans_iii_classic_chrome.cube",
            "luts/colorslide/fuji_velvia_50.cube",
            "luts/print/kodak_2383_cuspclip.cube",
            "luts/bw/kodak_tri-x_400.cube",
            "luts/bw/ilford_hp_5_plus_400.cube",
        )

        /** Rough on-disk size of a film (LUT + thumbnail), for the download prompt. */
        const val APPROXIMATE_BYTES_PER_FILM = 165_000L

        private const val BULK_CONCURRENCY = 6
        private const val KEY_MY_FILMS = "myFilms"

        private val categoryOrder = listOf(
            "Negative New", "Negative Color", "Negative Old", "Colorslide", "Print",
            "Fujixtransiii", "Bw", "Instant Consumer", "Instant Pro",
        )

        /** Sorts by category (in browsing order), then naturally by name within each. */
        fun sorted(films: List<Film>): List<Film> {
            val rank = { film: Film -> categoryOrder.indexOf(film.category).let { if (it < 0) categoryOrder.size else it } }
            return films.sortedWith(compareBy<Film> { rank(it) }.then { a, b -> NaturalOrder.compare(a.name, b.name) })
        }
    }

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val storage = File(context.filesDir, "Films")
    private val cachedCatalog = File(storage, CATALOG_FILE)

    var films: List<Film> by mutableStateOf(emptyList())
        private set
    /** Ordered IDs of films the user has on device. */
    var myFilmIds: List<String> by mutableStateOf(loadMyFilmIds())
        private set
    var downloading: Set<String> by mutableStateOf(emptySet())
        private set
    /** Progress of a "Download All" run, `null` when none is running. */
    var bulkDownload: BulkDownloadProgress? by mutableStateOf(null)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var errorMessage: String? by mutableStateOf(null)

    private var filmsById: Map<String, Film> = emptyMap()
    private var bulkDownloadJob: Job? = null

    init {
        loadCachedCatalog()
    }

    // MARK: - Queries

    val myFilms: List<Film> get() = myFilmIds.mapNotNull { filmsById[it] }

    /** Categories in browsing order. */
    val categories: List<String> get() = films.map { it.category }.distinct()

    fun film(id: String?): Film? = id?.let { filmsById[it] }

    fun isBundled(film: Film) = film.id in bundledIds

    fun isInMyFilms(film: Film) = film.id in myFilmIds

    private fun downloadedLut(film: Film) = File(storage, film.lutFile)

    fun hasLocalLut(film: Film) = isBundled(film) || downloadedLut(film).exists()

    /** Reads a film's `.cube` text, from downloads or the bundle. Call off the main thread. */
    fun readLut(film: Film): String {
        val downloaded = downloadedLut(film)
        if (downloaded.exists()) return downloaded.readText()
        if (isBundled(film)) return context.assets.open("films/${film.fileStem}.cube").bufferedReader().use { it.readText() }
        throw IOException("${film.displayName} isn't downloaded.")
    }

    /** Opens the film's LUT if it's available on device. */
    fun lutReader(film: Film): (() -> String)? = if (hasLocalLut(film)) ({ readLut(film) }) else null

    /** Local thumbnail if available, otherwise the remote one. */
    fun thumbnailModel(film: Film): String {
        val downloaded = File(storage, film.thumbnail)
        if (downloaded.exists()) return downloaded.toURI().toString()
        if (isBundled(film)) return "file:///android_asset/films/${film.fileStem}.jpg"
        return REPOSITORY_URL + film.thumbnail
    }

    // MARK: - Catalog

    private fun loadCachedCatalog() {
        val sources = listOf(
            { cachedCatalog.takeIf { it.exists() }?.readText() },
            { context.assets.open(CATALOG_FILE).bufferedReader().use { it.readText() } },
        )
        for (source in sources) {
            val films = runCatching { source()?.let(Film::parseCatalog) }.getOrNull() ?: continue
            replaceFilms(films)
            return
        }
    }

    /** Fetches the latest catalog, keeping the cached one when offline. */
    suspend fun refreshCatalog() {
        if (isRefreshing) return
        isRefreshing = true
        try {
            val catalog = withContext(Dispatchers.IO) {
                val data = fetch(REPOSITORY_URL + CATALOG_FILE)
                Film.parseCatalog(String(data)).also { writeAtomically(cachedCatalog, data) }
            }
            replaceFilms(catalog)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Offline: the cached or bundled catalog is still usable.
        } finally {
            isRefreshing = false
        }
    }

    private fun replaceFilms(newFilms: List<Film>) {
        films = sorted(newFilms)
        filmsById = films.associateBy { it.id }
    }

    // MARK: - Downloads

    /** Adds a film to My Films, downloading its LUT and thumbnail if needed. */
    suspend fun download(film: Film, reportsErrors: Boolean = true): Boolean {
        if (hasLocalLut(film)) {
            addToMyFilms(film)
            return true
        }
        if (film.id in downloading) return false
        downloading = downloading + film.id
        try {
            withContext(Dispatchers.IO) {
                val data = fetch(REPOSITORY_URL + film.lutFile)
                // Make sure it's a valid LUT before keeping it.
                CubeLut.parse(String(data))
                writeAtomically(downloadedLut(film), data)

                // The thumbnail is nice to have offline, but not required.
                runCatching { writeAtomically(File(storage, film.thumbnail), fetch(REPOSITORY_URL + film.thumbnail)) }
            }
            addToMyFilms(film)
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (reportsErrors) errorMessage = "Couldn't download ${film.displayName}. ${e.message.orEmpty()}"
            return false
        } finally {
            downloading = downloading - film.id
        }
    }

    /** Films from [films] that aren't in My Films yet. */
    fun notYetDownloaded(films: List<Film>) = films.filter { !isInMyFilms(it) }

    /**
     * Downloads several films, a few at a time. Keeps running if the library
     * sheet is closed; cancel with [cancelBulkDownload].
     */
    fun downloadAll(films: List<Film>) {
        val pending = notYetDownloaded(films)
        if (bulkDownloadJob != null || pending.isEmpty()) return
        bulkDownload = BulkDownloadProgress(total = pending.size)

        bulkDownloadJob = scope.launch {
            val semaphore = Semaphore(BULK_CONCURRENCY)
            var failed = 0
            try {
                pending.map { film ->
                    async {
                        semaphore.withPermit {
                            val succeeded = download(film, reportsErrors = false)
                            if (!succeeded) failed++
                            bulkDownload = bulkDownload?.let { it.copy(completed = it.completed + 1) }
                        }
                    }
                }.awaitAll()
                if (failed > 0) {
                    errorMessage = if (failed == 1) "1 film couldn't be downloaded. Check your connection and try again."
                    else "$failed films couldn't be downloaded. Check your connection and try again."
                }
            } finally {
                bulkDownload = null
                bulkDownloadJob = null
            }
        }
    }

    fun cancelBulkDownload() {
        bulkDownloadJob?.cancel()
    }

    /** Removes a film from My Films and deletes any downloaded files. */
    fun remove(film: Film) {
        saveMyFilmIds(myFilmIds - film.id)
        downloadedLut(film).delete()
        File(storage, film.thumbnail).delete()
    }

    fun moveMyFilm(fromId: String, toId: String) {
        val ids = myFilmIds.toMutableList()
        val from = ids.indexOf(fromId)
        val to = ids.indexOf(toId)
        if (from < 0 || to < 0) return
        ids.add(to, ids.removeAt(from))
        saveMyFilmIds(ids)
    }

    private fun addToMyFilms(film: Film) {
        if (film.id !in myFilmIds) saveMyFilmIds(myFilmIds + film.id)
    }

    private fun saveMyFilmIds(ids: List<String>) {
        myFilmIds = ids
        prefs.edit { putString(KEY_MY_FILMS, JSONArray(ids).toString()) }
    }

    private fun loadMyFilmIds(): List<String> {
        val json = prefs.getString(KEY_MY_FILMS, null) ?: return bundledIds
        return runCatching { JSONArray(json).let { array -> List(array.length()) { array.getString(it) } } }
            .getOrDefault(bundledIds)
    }

    // MARK: - Networking

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) throw IOException("The server returned an error (${connection.responseCode}).")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeAtomically(file: File, data: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Couldn't save ${file.name}.")
        }
    }
}

/** Finder-style ordering: digits compare by value, so "Portra 160" sorts before "Portra 400" and "1600". */
internal object NaturalOrder : Comparator<String> {
    private val chunks = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(a).map { it.value }.toList()
        val right = chunks.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                    ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }
}
