package dev.orbit.launcher.data

/**
 * Профілі запуску взяті з es_systems.xml / es_find_rules.xml від ES-DE (Android),
 * тож поводяться так само «безшовно»: гра стартує одразу, без меню емулятора.
 *
 * Шаблони значень:
 *  {ROM}       абсолютний шлях до файлу
 *  {SAF}       content://com.android.externalstorage.documents/... URI
 *  {PROVIDER}  наш FileProvider URI з тимчасовим доступом на читання
 *  {RA_CORE:x} /data/data/<retroarch>/cores/x_libretro_android.so
 *  {RA_CFG}    конфіг RetroArch
 *  {INJECT}    вміст файлу гри (для .psvita — title id)
 */
enum class DataMode { NONE, SAF, PROVIDER, FILE }
enum class ExtraType { STRING, BOOL, INT, ARRAY }

data class Extra(val key: String, val value: String, val type: ExtraType = ExtraType.STRING)

data class Emulator(
    val label: String,
    /** "package/activity" — перший встановлений буде використано. Activity може починатися з "." */
    val components: List<String>,
    val action: String? = null,
    val category: String? = null,
    val data: DataMode = DataMode.NONE,
    val extras: List<Extra> = emptyList(),
    val clearTask: Boolean = false,
) {
    val isRetroArch get() = components.first().startsWith("com.retroarch")
    /** Свій застосунок, обраний користувачем (компонент визначається під час запуску) */
    val isCustom get() = components.size == 1 && '/' !in components.first()

    /** Чи вміє цей профіль відкривати файл з таким розширенням (важливо для PC: .desktop vs .steam) */
    fun accepts(ext: String): Boolean = when {
        isCustom -> true
        ext == "steam" -> label.startsWith("GameNative Steam") || label.startsWith("GameHub")
        ext == "epic" -> label.startsWith("GameNative Epic") || label.startsWith("WinNative")
        ext == "gog" -> label.startsWith("GameNative GOG")
        ext == "amazon" -> label.startsWith("GameNative Amazon")
        ext == "pcgame" -> label.startsWith("GameNative Custom")
        ext == "desktop" -> label.startsWith("Winlator") || label.startsWith("WinNative")
        else -> !label.startsWith("GameNative") && !label.startsWith("GameHub")
    }
}

data class SystemDef(
    val id: String,
    val name: String,
    val short: String,
    val accent: Long,
    val extensions: Set<String>,
    val emulators: List<Emulator>,
    /** Назва системи в libretro-thumbnails */
    val libretro: String? = null,
    val folders: List<String> = listOf(id),
    /** Додаткові папки відносно кореня пам'яті, де застосунки самі складають ярлики */
    val extraDirs: List<String> = emptyList(),
)

object Systems {
    private fun exts(vararg e: String) = e.map { it.lowercase() }.toSet()

    private fun sys(
        id: String, name: String, short: String, accent: Long, extensions: Set<String>,
        libretro: String? = null, folders: List<String> = listOf(id), extraDirs: List<String> = emptyList(),
    ) = SystemDef(id, name, short, accent, extensions, ordered(id), libretro, folders, extraDirs)

    /** Найпопулярніші емулятори — першими (з них і вибирається «авто») */
    private val preferred = mapOf(
        "gba" to listOf("My Boy!", "Pizza Boy GBA"),
        "gb" to listOf("My OldBoy!", "Pizza Boy GBC"),
        "gbc" to listOf("My OldBoy!", "Pizza Boy GBC"),
        "nds" to listOf("melonDS", "DraStic"),
        "n3ds" to listOf("Azahar", "Lime3DS"),
    )

    private fun ordered(id: String): List<Emulator> {
        val list = EsdeEmulators.bySystem[id].orEmpty()
        val top = preferred[id].orEmpty()
        return list.sortedBy { e -> top.indexOf(e.label).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    val all: List<SystemDef> = listOf(
        sys("nes", "Nintendo Entertainment System", "NES", 0xFFE4002B, exts("nes", "fds", "unf", "unif", "zip", "7z"), "Nintendo - Nintendo Entertainment System"),
        sys("snes", "Super Nintendo", "SNES", 0xFF7B5CD6, exts("sfc", "smc", "fig", "swc", "bs", "zip", "7z"), "Nintendo - Super Nintendo Entertainment System"),
        sys("n64", "Nintendo 64", "N64", 0xFF1F9D55, exts("n64", "z64", "v64", "ndd", "zip", "7z"), "Nintendo - Nintendo 64"),
        sys("gb", "Game Boy", "GB", 0xFF8BAC0F, exts("gb", "zip", "7z"), "Nintendo - Game Boy"),
        sys("gbc", "Game Boy Color", "GBC", 0xFFE3A21A, exts("gbc", "zip", "7z"), "Nintendo - Game Boy Color"),
        sys("gba", "Game Boy Advance", "GBA", 0xFF5B3FD1, exts("gba", "agb", "zip", "7z"), "Nintendo - Game Boy Advance"),
        sys("genesis", "Sega Mega Drive / Genesis", "MD", 0xFF1D4ED8, exts("md", "gen", "smd", "bin", "sms", "gg", "32x", "zip", "7z"), "Sega - Mega Drive - Genesis", listOf("genesis", "megadrive")),
        sys("nds", "Nintendo DS", "NDS", 0xFF8E9AAF, exts("nds", "dsi", "zip", "7z"), "Nintendo - Nintendo DS"),
        sys("n3ds", "Nintendo 3DS", "3DS", 0xFFD7263D, exts("3ds", "3dsx", "cci", "cxi", "app", "z3dsx", "zcci", "zcxi"), "Nintendo - Nintendo 3DS", listOf("n3ds", "3ds")),
        sys("psx", "PlayStation", "PS1", 0xFF9CA3AF, exts("chd", "cue", "pbp", "m3u", "iso", "img", "ccd", "exe"), "Sony - PlayStation", listOf("psx", "ps1")),
        sys("ps2", "PlayStation 2", "PS2", 0xFF2B59C3, exts("chd", "iso", "cso", "zso", "gz", "m3u", "bin", "elf"), "Sony - PlayStation 2"),
        sys("psp", "PlayStation Portable", "PSP", 0xFF374151, exts("iso", "cso", "chd", "pbp", "elf", "prx"), "Sony - PlayStation Portable"),
        sys("psvita", "PlayStation Vita", "Vita", 0xFF1E40AF, exts("psvita"), "Sony - PlayStation Vita"),
        sys("gc", "Nintendo GameCube", "GC", 0xFF6D28D9, exts("rvz", "iso", "gcm", "gcz", "ciso", "wia", "dol", "elf", "m3u"), "Nintendo - GameCube", listOf("gc", "gamecube")),
        sys("wii", "Nintendo Wii", "Wii", 0xFF0EA5E9, exts("rvz", "wbfs", "iso", "wia", "gcz", "ciso", "wad", "dol", "elf"), "Nintendo - Wii"),
        sys("switch", "Nintendo Switch", "Switch", 0xFFE60012, exts("nsp", "xci", "nca", "nro", "nso")),
        sys(
            "windows", "PC (Winlator / GameNative)", "PC", 0xFF0078D4,
            exts("desktop", "steam", "epic", "gog", "amazon", "pcgame"),
            folders = listOf("windows", "pc", "winlator", "steam"),
            // сюди WinNative сам складає ярлики; Winlator Cmod — куди вкажеш у «Frontend Export Path»
            extraDirs = listOf("WinNative/Shortcuts", "WinNative/Shorcuts", "Winlator/Frontend", "Download/Winlator/Frontend"),
        ),
    )

    val byId = all.associateBy { it.id }

    /** Пакети емуляторів — не показуємо їх як «Android-ігри» */
    val emulatorPackages: Set<String> =
        all.flatMap { s -> s.emulators.flatMap { e -> e.components.map { it.substringBefore('/') } } }.toSet() +
            setOf("com.limelight", "com.limelight.root", "org.es_de.frontend", "com.swordfish.lemuroid")

    const val ANDROID = "android"
    const val CLOUD = "cloud"

    fun label(id: String) = when (id) {
        ANDROID -> "Android"
        CLOUD -> dev.orbit.launcher.tr("Хмара")
        else -> byId[id]?.short ?: id.removePrefix("col:")
    }

    fun accent(id: String): Long = when (id) {
        ANDROID -> 0xFF22C55E
        CLOUD -> 0xFF76B900
        else -> byId[id]?.accent ?: 0xFF4D7CFF
    }
}
