package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.orbit.launcher.OrbitApp
import dev.orbit.launcher.UsbAttachActivity
import dev.orbit.launcher.input.Gamepad
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), HomeController.Host {
    companion object {
        const val EXTRA_FROM_CONTROLLER = "from_controller"
    }

    private lateinit var app: OrbitApp
    private lateinit var controller: HomeController
    private lateinit var pad: Gamepad

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        // постійний дозвіл: Orbit передаватиме його емуляторам при кожному запуску
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        app.prefs.romTree = uri.toString()
        treeToPath(uri)?.let { controller.setRomRoot(it) }
    }

    private var onImagePicked: ((Uri) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onImagePicked?.invoke(it) }
        onImagePicked = null
    }

    override fun pickImage(onPicked: (Uri) -> Unit) {
        onImagePicked = onPicked
        runCatching {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure { controller.showToast(tr("Не вдалося відкрити галерею")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as OrbitApp
        dev.orbit.launcher.I18n.lang = app.prefs.uiLang.ifBlank { dev.orbit.launcher.I18n.systemDefault() }
        controller = HomeController(app, lifecycleScope, this)
        pad = Gamepad(controller::onButton)
        // анімація лише при «холодному» старті, не при повороті екрана
        if (savedInstanceState == null && app.prefs.bootIntro) controller.booting = true
        watchBattery()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        lifecycleScope.launch { app.library.games.collect { controller.games = it } }
        lifecycleScope.launch {
            app.library.load()
            controller.startup()
            controller.startBatteryWatch()
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7FA2FF),
                    background = Color(0xFF05070F),
                    surface = Color(0xFF0B1020),
                ),
            ) {
                HomeScreen(controller)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        controller.onResume()
    }

    override fun onPause() {
        super.onPause()
        pad.release()
        controller.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.sounds.release()
        controller.music.release()
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- ввід: перехоплюємо геймпад до того, як його побачить Compose ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // фізична клавіатура (не геймпад) друкує в пошук напряму
        val search = controller.search
        val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (search != null && !fromGamepad && event.action == KeyEvent.ACTION_DOWN) {
            val ch = event.unicodeChar
            when {
                event.keyCode == KeyEvent.KEYCODE_DEL -> { search.backspace(); return true }
                event.keyCode == KeyEvent.KEYCODE_ENTER -> { controller.pressKey(search, Key.Done); return true }
                ch > 0 && !Character.isISOControl(ch) && event.keyCode !in navKeys -> {
                    if (ch == ' '.code) controller.pressKey(search, Key.Space) else search.type(Char(ch).lowercaseChar())
                    return true
                }
            }
        }
        if (controller.form != null) {
            // Поки відкрита форма з текстом — клавіатура працює як звичайно, крім B та Start
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_START) {
                return pad.onKey(event)
            }
            return super.dispatchKeyEvent(event)
        }
        return pad.onKey(event) || super.dispatchKeyEvent(event)
    }

    private val navKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
    )

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (controller.form == null && pad.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // ---------- Host ----------

    override fun hasAllFiles() = Environment.isExternalStorageManager()

    override fun openAllFilesSettings() {
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${packageName}"))
        runCatching { startActivity(specific) }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun pickRomFolder() {
        runCatching { folderPicker.launch(app.launcher.folderHint(app.prefs.romRoot)) }
            .onFailure { controller.showToast(tr("Не вдалося відкрити вибір папки")) }
    }

    private val homeAlias get() = ComponentName(this, "dev.orbit.launcher.HomeAlias")

    override fun launcherIcon(): String = AppIcons.firstOrNull { (id, _) ->
        packageManager.getComponentEnabledSetting(iconAlias(id)).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && id == "Planet")
        }
    }?.first ?: "Planet"

    override fun setLauncherIcon(id: String) {
        // спершу вмикаємо нову, потім вимикаємо решту — щоб іконка не зникла зовсім
        packageManager.setComponentEnabledSetting(iconAlias(id), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        AppIcons.map { it.first }.filter { it != id }.forEach {
            packageManager.setComponentEnabledSetting(iconAlias(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    private fun iconAlias(id: String) = ComponentName(this, "dev.orbit.launcher.Icon$id")

    override fun isHomeEnabled() =
        packageManager.getComponentEnabledSetting(homeAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    override fun setHomeEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            homeAlias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (enabled) {
            controller.showToast(tr("Вибери Orbit як домашній екран"))
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }
    }

    override fun usbGamepads(): List<Pair<String, String>> {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values
            .filter { UsbAttachActivity.looksLikeGamepad(it) }
            .map { d -> UsbAttachActivity.id(d) to (d.productName ?: d.manufacturerName ?: tr("USB-геймпад")) }
    }

    // ---------- заряд телефона ----------

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0) controller.onPhoneBattery(level * 100 / scale, plugged)
        }
    }

    private fun watchBattery() {
        registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // ---------- музика ----------

    private var onAudioPicked: ((Uri) -> Unit)? = null
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onAudioPicked?.invoke(uri)
        }
        onAudioPicked = null
    }

    override fun pickAudio(onPicked: (Uri) -> Unit) {
        onAudioPicked = onPicked
        runCatching { audioPicker.launch(arrayOf("audio/*")) }
            .onFailure { controller.showToast(dev.orbit.launcher.tr("Не вдалося відкрити вибір файлу")) }
    }

    // ---------- швидке меню ----------

    override fun getBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    override fun setBrightness(v: Float) {
        // одразу міняємо яскравість вікна; системну — якщо є дозвіл «Змінювати системні налаштування»
        window.attributes = window.attributes.apply { screenBrightness = v }
        if (Settings.System.canWrite(this)) {
            runCatching {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (v * 255).toInt().coerceIn(1, 255))
            }
        } else if (!askedWriteSettings) {
            askedWriteSettings = true
            controller.showToast(dev.orbit.launcher.tr("Щоб яскравість діяла і в іграх — дай дозвіл у налаштуваннях, що відкрились"))
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    private var askedWriteSettings = false

    private val audio get() = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    override fun getVolume(): Float {
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v * max).toInt().coerceIn(0, max), 0)
    }

    override fun openWifi() {
        runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            .onFailure { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    override fun openBluetooth() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun goAndroidHome() {
        // якщо Orbit сам домашній екран — відкриваємо вибір іншого лаунчера
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isHomeEnabled()) runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        else startActivity(home)
    }

    override fun controllerBattery(): Int? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        return android.view.InputDevice.getDeviceIds().asSequence()
            .mapNotNull { android.view.InputDevice.getDevice(it) }
            .filter { it.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD }
            .mapNotNull { d -> d.batteryState.takeIf { it.isPresent }?.capacity?.takeIf { !it.isNaN() && it >= 0f } }
            .map { (it * 100).toInt() }
            .minOrNull()
    }

    override fun haptic() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** content://…/tree/primary%3AROMs -> /storage/emulated/0/ROMs */
    private fun treeToPath(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/${volume}"
        return if (rel.isEmpty()) base else "${base}/${rel}"
    }
}
