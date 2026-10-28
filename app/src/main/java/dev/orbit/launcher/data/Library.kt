package dev.orbit.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class Library(private val context: Context, private val prefs: Prefs) {
    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val saveLock = Mutex()

    private val _games = MutableStateFlow<List<Game>>(emptyList())
    /** Історія сесій для статистики (останні 500) */
    @Volatile var sessions: List<Session> = emptyList()
        private set
    val games: StateFlow<List<Game>> = _games.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching { json.decodeFromString<LibraryFile>(file.readText()) }.getOrDefault(LibraryFile())
        sessions = loaded.sessions
        _games.value = loaded.games
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        saveLock.withLock {
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(json.encodeToString(LibraryFile.serializer(), LibraryFile(_games.value, sessions)))
            tmp.renameTo(file)
        }
    }

    /** Пересканувати ROM-и та Android-ігри. Метадані (арти, улюблені, час гри) зберігаються. */
    suspend fun rescan(): Int = withContext(Dispatchers.IO) {
        val roms = RomScanner.scan(File(prefs.romRoot))
        val apps = AndroidApps.list(context)
        val androidGames = apps
            .filter { it.isGame && it.packageName !in Systems.emulatorPackages }
            .map(AndroidApps::toGame)
        val installed = apps.map { it.packageName }.toSet()

        _games.update { old ->
            val byId = old.associateBy { it.id }
            val scanned = (roms + androidGames).map { fresh ->
                val old = byId[fresh.id] ?: return@map fresh
                // стара автоматична назва (наприклад «EBOOT») -> нова, якщо користувач не перейменовував
                val autoOld = old.path?.let { RomScanner.cleanTitle(File(it).nameWithoutExtension) }
                if (old.kind == GameKind.ROM && old.title == autoOld && fresh.title != autoOld) old.copy(title = fresh.title) else old
            }
            val scannedIds = scanned.map { it.id }.toSet()
            // Вручну додані Android-ігри та Moonlight лишаються, якщо застосунок ще встановлений
            val manual = old.filter { g ->
                g.id !in scannedIds && when (g.kind) {
                    GameKind.MOONLIGHT -> true
                    GameKind.ANDROID, GameKind.SHORTCUT -> g.packageName in installed
                    GameKind.ROM -> false
                }
            }
            scanned + manual
        }
        save()
        _games.value.size
    }

    suspend fun upsert(game: Game) {
        _games.update { list ->
            if (list.any { it.id == game.id }) list.map { if (it.id == game.id) game else it } else list + game
        }
        save()
    }

    suspend fun edit(id: String, block: (Game) -> Game) {
        _games.update { list -> list.map { if (it.id == id) block(it) else it } }
        save()
    }

    suspend fun remove(id: String) {
        _games.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Після оновлення правил пошуку — ще раз шукаємо картинки для ігор, де їх не знайшли */
    suspend fun retryMissingArt() {
        _games.update { list -> list.map { if (it.cover == null && !it.customArt) it.copy(artChecked = false) else it } }
        save()
    }

    /** Повертає ROM-іграм назви з імені файлу (крім перейменованих вручну) і перешукує картинки/назви */
    suspend fun restoreAutoTitles() {
        _games.update { list ->
            list.map { g ->
                if (g.kind == GameKind.ROM && !g.titleLocked && g.path != null) {
                    g.copy(title = RomScanner.cleanTitle(RomScanner.baseName(File(g.path))), artChecked = false)
                } else g
            }
        }
        save()
    }

    /** Записати зіграний час */
    suspend fun addSession(gameId: String, start: Long, duration: Long) {
        sessions = (sessions + Session(gameId, start, duration)).takeLast(500)
        _games.update { list -> list.map { if (it.id == gameId) it.copy(playTime = it.playTime + duration) else it } }
        save()
    }

    fun get(id: String) = _games.value.firstOrNull { it.id == id }
}
