package it.bosiux.camera4u

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.bosiux.camera4u.camera.CameraModel
import it.bosiux.camera4u.library.FilmLibrary
import it.bosiux.camera4u.model.Film
import kotlinx.coroutines.launch

/** Owns the camera and film library for the lifetime of the app's UI. */
class FilmCameraViewModel(application: Application) : AndroidViewModel(application) {
    val library = FilmLibrary(application, viewModelScope)
    val camera = CameraModel(application, viewModelScope)

    init {
        select(library.film(camera.selectedFilmId))
        viewModelScope.launch { library.refreshCatalog() }
    }

    /** Loads a film into the camera, or no film. Films that aren't on device fall back to none. */
    fun select(film: Film?) {
        val reader = film?.let(library::lutReader)
        camera.selectFilm(film?.takeIf { reader != null }, reader)
    }

    /** Removes a film from My Films, unloading it first if it's in the camera. */
    fun remove(film: Film) {
        if (camera.selectedFilmId == film.id) select(null)
        library.remove(film)
    }

    override fun onCleared() {
        camera.release()
    }
}
