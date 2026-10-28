package dev.orbit.launcher

import android.app.Activity
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import dev.orbit.launcher.data.Game
import dev.orbit.launcher.data.GameKind
import dev.orbit.launcher.data.Systems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Коли Orbit — домашній екран, Android надсилає сюди «Додати на головний екран» з інших застосунків
 * (ярлик гри з Winlator, GameNative тощо). Ми одразу додаємо таку гру в бібліотеку.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        val info = req?.shortcutInfo
        if (req == null || req.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || info == null || !req.isValid) {
            finish(); return
        }
        val app = application as OrbitApp
        val pkg = info.`package`
        val title = (info.shortLabel ?: info.longLabel ?: pkg).toString()
        val id = "sc:$pkg/${info.id}"

        // іконка ярлика -> файл (буде видно, поки не знайдеться обкладинка)
        val iconFile = runCatching {
            val d = la.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi) ?: return@runCatching null
            val f = File(File(filesDir, "art").apply { mkdirs() }, "${id.hashCode().toUInt().toString(16)}_icon")
            f.outputStream().use { d.toBitmap(256, 256).compress(Bitmap.CompressFormat.PNG, 100, it) }
            f.absolutePath
        }.getOrNull()

        val pcPackages = Systems.byId["windows"]?.emulators.orEmpty().flatMap { e -> e.components.map { it.substringBefore('/') } }.toSet() +
            setOf("com.winlator", "app.gamenative")
        val game = Game(
            id = id,
            title = title,
            kind = GameKind.SHORTCUT,
            system = if (pkg in pcPackages) "windows" else Systems.ANDROID,
            packageName = pkg,
            shortcutId = info.id,
            icon = iconFile,
        )
        req.accept()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            app.library.upsert(game)
            app.art.fetchOne(game)
        }
        Toast.makeText(this, tr("Додано в Orbit: %s", title), Toast.LENGTH_SHORT).show()
        finish()
    }
}
