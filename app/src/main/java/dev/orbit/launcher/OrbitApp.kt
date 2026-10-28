package dev.orbit.launcher

import android.app.Application
import dev.orbit.launcher.art.ArtFetcher
import dev.orbit.launcher.data.Library
import dev.orbit.launcher.data.Prefs
import dev.orbit.launcher.launch.GameLauncher

class OrbitApp : Application() {
    lateinit var prefs: Prefs
    lateinit var library: Library
    lateinit var launcher: GameLauncher
    lateinit var art: ArtFetcher

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        library = Library(this, prefs)
        launcher = GameLauncher(this, prefs)
        art = ArtFetcher(this, prefs, library)
    }
}
