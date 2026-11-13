package it.bosiux.camera4u

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.graphics.scale
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.bosiux.camera4u.model.FilmAdjustments
import it.bosiux.camera4u.ui.camera.CameraScreen
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: FilmCameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Full-screen viewfinder, like a camera: the status bar comes back with a swipe.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val initialSheet = if (BuildConfig.DEBUG && savedInstanceState == null) applyDebugLaunchOptions() else null
        setContent {
            CameraScreen(viewModel, initialSheet)
        }
    }

    /**
     * Debug builds accept launch extras for screenshot automation:
     * `sampleScene` (Boolean), `film` (film ID), `adjustments` (JSON) and
     * `sheet` (`library` or `adjustments`, returned for the UI to open).
     */
    private fun applyDebugLaunchOptions(): String? {
        val extras = intent.extras ?: return null
        if (extras.getBoolean("sampleScene")) {
            assets.open("SampleScene.jpg").use(BitmapFactory::decodeStream)?.let { scene ->
                viewModel.camera.startSampleScene(scene.centerCrop(1080, 1440))
            }
        }
        extras.getString("film")?.let { viewModel.select(viewModel.library.film(it)) }
        extras.getString("adjustments")?.let { viewModel.camera.updateAdjustments(FilmAdjustments.fromJson(it)) }
        return extras.getString("sheet")
    }

    private fun Bitmap.centerCrop(width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / this.width, height.toFloat() / this.height)
        val scaled = scale((this.width * scale).roundToInt(), (this.height * scale).roundToInt())
        return Bitmap.createBitmap(scaled, (scaled.width - width) / 2, (scaled.height - height) / 2, width, height)
    }

    /** The volume keys work as a shutter release, as in most Android camera apps. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) viewModel.camera.capture()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
