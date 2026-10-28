package dev.orbit.launcher.data

import kotlinx.serialization.Serializable

@Serializable
enum class GameKind { ROM, ANDROID, MOONLIGHT, SHORTCUT }

@Serializable
data class Game(
    val id: String,
    val title: String,
    val kind: GameKind,
    /** id системи з [Systems], "android" або "cloud" */
    val system: String,
    /** Абсолютний шлях до ROM (для ROM) */
    val path: String? = null,
    /** Пакет (для ANDROID і SHORTCUT) */
    val packageName: String? = null,
    /** Ярлик, доданий на «головний екран» Orbit (Winlator, GameNative тощо) */
    val shortcutId: String? = null,
    /** Іконка ярлика (файл) */
    val icon: String? = null,
    /** Назва ПК у Moonlight та назва гри в Sunshine/GFE (для MOONLIGHT) */
    val pcName: String? = null,
    val appName: String? = null,
    /** Локальні файли артів */
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val artChecked: Boolean = false,
    /** Коли востаннє шукали картинки — щоб не знайдене пробувати ще раз пізніше */
    val artCheckedAt: Long = 0,
    /** Картинки вибрані вручну з галереї — автоматичний пошук їх не чіпає */
    val customArt: Boolean = false,
    /** Назву змінив користувач — автоматика її не чіпає */
    val titleLocked: Boolean = false,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val lastPlayed: Long = 0,
    val playCount: Int = 0,
    /** Сумарний час у грі, мс */
    val playTime: Long = 0,
    /** Колекції, до яких додана гра */
    val collections: List<String> = emptyList(),
    /** Багатодискова гра без .m3u: файли дисків (m3u створюється перед запуском) */
    val discs: List<String>? = null,
    /** Індекс емулятора в SystemDef.emulators; null = за замовчуванням для системи */
    val emulator: Int? = null,
)

@Serializable
data class LibraryFile(val games: List<Game> = emptyList(), val sessions: List<Session> = emptyList())

/** Одна ігрова сесія — для статистики за тиждень */
@Serializable
data class Session(val gameId: String, val start: Long, val duration: Long)
