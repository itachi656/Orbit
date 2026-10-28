package dev.orbit.launcher.data

import android.content.Context
import android.os.Environment
import androidx.core.content.edit

/** Знімок налаштувань вигляду — UI перемальовується, коли він змінюється */
data class Look(
    val theme: String,
    val accent: String,
    val background: String,
    val bgPhoto: String,
    val tileShape: String,
    val tileSize: Int,
    val launchAnim: String,
    val homeCount: Int,
    val homeShortcuts: Boolean,
    val hiddenTabs: Set<String>,
    val volume: Int,
    val clock24: Boolean,
    val coverAccent: Boolean,
    val sortMode: String,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = object {
        operator fun getValue(t: Any?, p: Any?) = sp.getString(key, def) ?: def
        operator fun setValue(t: Any?, p: Any?, v: String) = sp.edit { putString(key, v) }
    }
    private fun int(key: String, def: Int) = object {
        operator fun getValue(t: Any?, p: Any?) = sp.getInt(key, def)
        operator fun setValue(t: Any?, p: Any?, v: Int) = sp.edit { putInt(key, v) }
    }
    private fun bool(key: String, def: Boolean) = object {
        operator fun getValue(t: Any?, p: Any?) = sp.getBoolean(key, def)
        operator fun setValue(t: Any?, p: Any?, v: Boolean) = sp.edit { putBoolean(key, v) }
    }

    var romRoot by str("rom_root", "${Environment.getExternalStorageDirectory().absolutePath}/ROMs")
    var steamGridKey by str("sgdb_key", "")
    /** SAF-дозвіл на папку з іграми (content://…/tree/…) — його Orbit тимчасово передає емуляторам */
    var romTree by str("rom_tree", "")
    var artRules by int("art_rules", 0)
    // ---- гра ----
    /** Яка гра й в якому застосунку запущена останньою — для обліку часу та «Повернутися в гру» */
    var lastGameId by str("last_game_id", "")
    var lastGamePkg by str("last_game_pkg", "")
    /** DuckStation: продовжувати з автозбереження */
    var resumeState by bool("resume_state", false)
    /** Сортування у вкладках: abc | recent | time */
    var sortMode by str("sort_mode", "abc")

    // ---- заряд ----
    var batteryAlerts by bool("battery_alerts", true)

    // ---- музика ----
    /** off | ambient | file */
    var music by str("music", "off")
    var musicFile by str("music_file", "")
    /** 1..10 */
    var musicVolume by int("music_volume", 4)

    // ---- колір ----
    var coverAccent by bool("cover_accent", false)

    /** Мова інтерфейсу: uk / en / ru; порожньо — як у системи */
    var uiLang by str("ui_lang", "")
    /** Розкладка екранної клавіатури пошуку: 0 EN, 1 УКР, 2 РУС */
    var searchLang by int("search_lang", 0)
    /** Анімація запуску самого лаунчера */
    var bootIntro by bool("boot_intro", true)

    /** "vid:pid" прив'язаного геймпада, порожньо = будь-який геймпад */
    var boundController by str("bound_controller", "")
    var autostart by bool("autostart", true)

    /** Виставляється перед запуском гри і знімається, коли Orbit знову на екрані.
     *  Потрібно, щоб перепідключення геймпада посеред гри не перекривало емулятор. */
    var inGameSince: Long
        get() = sp.getLong("in_game_since", 0)
        set(v) = sp.edit { putLong("in_game_since", v) }

    // ---- вигляд ----
    /** "auto" = колір системи, або ARGB у hex */
    var accent by str("accent", "auto")
    var theme by str("theme", "orbit")
    /** auto (як у теми) | art | blur | animated | waves | plain | photo */
    var background by str("background", "auto")
    var bgPhoto by str("bg_photo", "")
    /** square | portrait | wide */
    var tileShape by str("tile_shape", "square")
    /** 0 малі, 1 середні, 2 великі */
    var tileSize by int("tile_size", 1)
    /** warp | portal | ps5 | none */
    var launchAnim by str("launch_anim", "warp")
    var homeCount by int("home_count", 12)
    var homeShortcuts by bool("home_shortcuts", true)
    var hiddenTabs: Set<String>
        get() = sp.getStringSet("hidden_tabs", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("hidden_tabs", v) }
    /** 0 вимк, 1 тихо, 2 нормально */
    var volume by int("volume", 2)
    var clock24 by bool("clock24", true)

    fun look() = Look(theme, accent, background, bgPhoto, tileShape, tileSize, launchAnim, homeCount, homeShortcuts, hiddenTabs, volume, clock24, coverAccent, sortMode)

    // ---- емулятори ----
    fun emulatorFor(system: String): Int? = sp.getInt("emu_$system", -1).takeIf { it >= 0 }
    fun setEmulatorFor(system: String, index: Int?) = sp.edit { if (index == null) remove("emu_$system") else putInt("emu_$system", index) }

    /** Свої застосунки-емулятори для системи: "пакет|SAF" (або PROVIDER / FILE) */
    fun customEmulators(system: String): List<String> =
        sp.getString("custom_emu_$system", "")!!.split(';').filter { it.isNotBlank() }

    fun addCustomEmulator(system: String, pkg: String, mode: DataMode) {
        val list = customEmulators(system).filterNot { it.startsWith("$pkg|") } + "$pkg|${mode.name}"
        sp.edit { putString("custom_emu_$system", list.joinToString(";")) }
    }

    fun removeCustomEmulator(system: String, pkg: String) {
        val list = customEmulators(system).filterNot { it.startsWith("$pkg|") }
        sp.edit { putString("custom_emu_$system", list.joinToString(";")) }
    }
}
