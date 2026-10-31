package dev.orbit.launcher.art

import android.content.Context
import android.net.Uri
import dev.orbit.launcher.data.Game
import dev.orbit.launcher.data.GameKind
import dev.orbit.launcher.data.Library
import dev.orbit.launcher.data.Prefs
import dev.orbit.launcher.data.Systems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Звідки беруться картинки (по черзі, перше знайдене перемагає):
 *  0. Вибрані вручну з галереї (customArt) — не чіпаємо
 *  1. Свої файли: <ROMs>/media/<система>/{covers,heroes,logos}/<ім'я файлу гри>.png|jpg|webp
 *  2. SteamGridDB (потрібен безкоштовний API-ключ): квадратна обкладинка, широкий фон (hero) і логотип
 *  3. libretro-thumbnails (без ключа): бокс-арт і скріншот. Назва шукається нечітко —
 *     «pokemon_emerald.gba» знайде «Pokemon - Emerald Version (USA, Europe).png»
 */
class ArtFetcher(context: Context, private val prefs: Prefs, private val library: Library) {
    private val dir = File(context.filesDir, "art").apply { mkdirs() }
    private val indexDir = File(context.cacheDir, "lr-index").apply { mkdirs() }
    private val resolver = context.contentResolver
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val indexes = mutableMapOf<String, List<String>>()

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (готово, всього) поки йде завантаження, інакше null */
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    /** Ігри без картинок, які не перевіряли або перевіряли давно (наприклад, не було інтернету) */
    private fun needsArt(g: Game, now: Long) = !g.customArt && (
        !g.artChecked || (g.cover == null && now - g.artCheckedAt > RETRY_MS)
        )

    /** Одночасно працює лише один прохід — інакше два потоки пишуть ті самі файли */
    private val runLock = Mutex()

    suspend fun fetchMissing(force: Boolean = false) = withContext(Dispatchers.IO) { runLock.withLock { fetchMissingLocked(force) } }

    private suspend fun fetchMissingLocked(force: Boolean) {
        val now = System.currentTimeMillis()
        val todo = library.games.value.filter { if (force) !it.customArt else needsArt(it, now) }
        if (todo.isEmpty()) return
        var done = 0
        _progress.value = 0 to todo.size
        val gate = Semaphore(4)
        coroutineScope {
            todo.map { g ->
                async {
                    gate.withPermit {
                        runCatching { fetch(g, force) }
                        synchronized(this@ArtFetcher) { done++ }
                        _progress.value = done to todo.size
                    }
                }
            }.awaitAll()
        }
        _progress.value = null
    }

    suspend fun fetchOne(game: Game) = withContext(Dispatchers.IO) {
        library.edit(game.id) { it.copy(customArt = false) }
        runLock.withLock { runCatching { fetch(game, true) } }
    }

    /** Картинка з галереї як обкладинка / фон / логотип гри */
    suspend fun importImage(game: Game, uri: Uri, kind: String) = withContext(Dispatchers.IO) {
        val target = File(dir, "${key(game)}_$kind")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@withContext
        library.edit(game.id) {
            when (kind) {
                "hero" -> it.copy(hero = target.absolutePath, customArt = true)
                "logo" -> it.copy(logo = target.absolutePath, customArt = true)
                else -> it.copy(cover = target.absolutePath, customArt = true)
            }
        }
    }

    /** Обкладинка за посиланням (з магазину) */
    suspend fun importUrl(game: Game, url: String) = withContext(Dispatchers.IO) {
        val f = download(url, File(dir, "${key(game)}_cover")) ?: return@withContext
        library.edit(game.id) { it.copy(cover = f.absolutePath) }
    }

    /** Свій фон для всього лаунчера */
    suspend fun importWallpaper(uri: Uri): String? = withContext(Dispatchers.IO) {
        val target = File(dir, "wallpaper_${System.currentTimeMillis()}")
        dir.listFiles { f -> f.name.startsWith("wallpaper_") }?.forEach { it.delete() }
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@withContext null
        target.absolutePath
    }

    private fun key(game: Game) = game.id.hashCode().toUInt().toString(16)

    private suspend fun fetch(game: Game, force: Boolean) {
        fun target(kind: String) = File(dir, "${key(game)}_$kind")
        if (force) listOf("cover", "hero", "logo").forEach { target(it).delete() }

        var cover = target("cover").takeIf { isImage(it) }
        var hero = target("hero").takeIf { isImage(it) }
        var logo = target("logo").takeIf { isImage(it) }
        var betterTitle: String? = null

        // 1. Локальні
        local(game, "covers")?.let { cover = it }
        local(game, "heroes")?.let { hero = it }
        local(game, "logos")?.let { logo = it }

        // 1б. Steam: для .steam-файлів (GameNative) — офіційні картинки з магазину за номером гри, без ключа
        steamAppId(game)?.let { id ->
            val cdn = "https://cdn.cloudflare.steamstatic.com/steam/apps/$id"
            if (cover == null) cover = download("$cdn/library_600x900_2x.jpg", target("cover"))
                ?: download("$cdn/library_600x900.jpg", target("cover"))
                ?: download("$cdn/header.jpg", target("cover"))
            if (hero == null) hero = download("$cdn/library_hero.jpg", target("hero"))
            if (logo == null) logo = download("$cdn/logo.png", target("logo"))
        }
        // 1в. Картинки, які передала програма Orbit PC (Epic, GOG тощо)
        if (cover == null) game.coverUrl?.let { cover = download(it, target("cover")) }
        if (hero == null) game.heroUrl?.let { hero = download(it, target("hero")) }

        // 2. SteamGridDB
        val sgdbKey = prefs.steamGridKey
        // для Android — лише справжні ігри: інакше «Календар» отримає обкладинку випадкової гри
        val sgdbAllowed = game.kind != GameKind.ANDROID || isAndroidGame(game.packageName)
        if (sgdbKey.isNotBlank() && sgdbAllowed && (cover == null || hero == null || logo == null)) {
            sgdbGameId(searchTitle(game), sgdbKey)?.let { id ->
                if (cover == null) {
                    cover = sgdbAsset("grids/game/$id?dimensions=1024x1024,512x512", sgdbKey)?.let { download(it, target("cover")) }
                        ?: sgdbAsset("grids/game/$id?dimensions=600x900", sgdbKey)?.let { download(it, target("cover")) }
                        ?: sgdbAsset("grids/game/$id", sgdbKey)?.let { download(it, target("cover")) }
                }
                if (hero == null) hero = sgdbAsset("heroes/game/$id", sgdbKey)?.let { download(it, target("hero")) }
                if (logo == null) logo = sgdbAsset("logos/game/$id", sgdbKey)?.let { download(it, target("logo")) }
            }
        }

        // 3. libretro-thumbnails з нечітким пошуком назви.
        //    Назви однакові в Named_Boxarts / Named_Snaps / Named_Titles, тож шукаємо лише в одному списку.
        val lrSystem = Systems.byId[game.system]?.libretro
        if (game.kind == GameKind.ROM && lrSystem != null && (cover == null || hero == null)) {
            val base = dev.orbit.launcher.data.RomScanner.baseName(File(game.path!!))
            libretroName(lrSystem, base, game.title)?.let { m ->
                val name = m.name
                // «pokemon_emerald» -> «Pokemon - Emerald Version», але лише при впевненому збігу
                // і якщо користувач сам не перейменовував
                if (m.exact && game.title == dev.orbit.launcher.data.RomScanner.cleanTitle(base) && names(lrSystem).contains(name)) {
                    betterTitle = dev.orbit.launcher.data.RomScanner.cleanTitle(name)
                }
                fun url(folder: String) = "https://thumbnails.libretro.com/${enc(lrSystem)}/$folder/${enc(name)}.png"
                if (cover == null) cover = download(url("Named_Boxarts"), target("cover"))
                if (hero == null) hero = download(url("Named_Snaps"), target("hero")) ?: download(url("Named_Titles"), target("hero"))
                // коробки немає — хоча б титульний екран як обкладинка
                if (cover == null) cover = download(url("Named_Titles"), target("cover")) ?: hero?.let { h -> h.copyTo(target("cover"), true) }
            }
        }

        library.edit(game.id) {
            it.copy(
                cover = cover?.absolutePath,
                hero = hero?.absolutePath,
                logo = logo?.absolutePath,
                artChecked = true,
                artCheckedAt = System.currentTimeMillis(),
                title = betterTitle ?: it.title,
            )
        }
    }

    /** Номер гри в Steam: від Orbit PC або вміст .steam-файлу (так їх експортує GameNative) */
    private fun steamAppId(game: Game): Int? {
        game.steamAppId?.let { return it }
        val path = game.path ?: return null
        if (!path.endsWith(".steam", ignoreCase = true)) return null
        return runCatching { File(path).readText().trim().toInt() }.getOrNull()
    }

    private fun searchTitle(game: Game) = when (game.kind) {
        GameKind.MOONLIGHT -> game.appName ?: game.title
        else -> game.title
    }

    private fun local(game: Game, folder: String): File? {
        val base = when (game.kind) {
            GameKind.ROM -> dev.orbit.launcher.data.RomScanner.baseName(File(game.path!!))
            else -> game.title
        }
        val mediaDir = File(prefs.romRoot, "media/${game.system}/$folder")
        return listOf("png", "jpg", "jpeg", "webp").map { File(mediaDir, "$base.$it") }.firstOrNull { it.exists() }
    }

    // ---------- libretro ----------

    private suspend fun libretroName(system: String, fileBase: String, title: String): NameMatch.Match? {
        val names = index(system)
        // індекс недоступний — пробуємо точну назву файлу
        if (names.isEmpty()) return NameMatch.Match(fileBase.replace(Regex("[&*/:`<>?\\\\|\"]"), "_"), false)
        return NameMatch.match(names, fileBase, title)
    }

    private fun names(system: String): List<String> = synchronized(indexes) { indexes[system] } ?: emptyList()

    private val indexLocks = mutableMapOf<String, Mutex>()

    /** Список усіх назв картинок системи (кешується на тиждень). Для різних систем качається паралельно. */
    private suspend fun index(system: String): List<String> {
        val lock = synchronized(indexLocks) { indexLocks.getOrPut(system) { Mutex() } }
        return lock.withLock {
            synchronized(indexes) { indexes[system] }?.let { return@withLock it }
            val cache = File(indexDir, system.hashCode().toUInt().toString(16) + ".txt")
            val list = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 7 * 24 * 3600_000L) {
                cache.readLines()
            } else {
                val html = runCatching { get("https://thumbnails.libretro.com/${enc(system)}/Named_Boxarts/") }.getOrNull()
                if (html == null) {
                    if (cache.exists()) cache.readLines() else emptyList()
                } else {
                    // простий лінійний прохід: регулярний вираз на Android для 2 МБ HTML працює хвилину
                    parseListing(html)
                        .also { if (it.isNotEmpty()) cache.writeText(it.joinToString("\n")) }
                }
            }
            synchronized(indexes) { indexes[system] = list }
            list
        }
    }

    private fun parseListing(html: String): List<String> {
        val out = ArrayList<String>(8000)
        var i = html.indexOf("href=\"")
        while (i >= 0) {
            val start = i + 6
            val end = html.indexOf('"', start)
            if (end < 0) break
            if (end - start > 4 && html.startsWith(".png", end - 4) && html[start] != '?' && html[start] != '/') {
                out += URLDecoder.decode(html.substring(start, end - 4).replace("+", "%2B"), "UTF-8")
            }
            i = html.indexOf("href=\"", end)
        }
        return out
    }

    /** Завчасно качаємо списки назв для всіх систем, де є ігри — паралельно */
    suspend fun warmUp(systems: Collection<String>) = withContext(Dispatchers.IO) {
        coroutineScope {
            systems.mapNotNull { Systems.byId[it]?.libretro }.distinct().map { async { runCatching { index(it) } } }.awaitAll()
        }
    }

    // ---------- мережа ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(url: String, key: String? = null): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Orbit-Launcher/0.2 (Android)")
            .apply { if (key != null) header("Authorization", "Bearer $key") }
            .build()
        return http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    private fun sgdbGameId(title: String, key: String): Int? {
        val body = get("https://www.steamgriddb.com/api/v2/search/autocomplete/${enc(title)}", key) ?: return null
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
        return (data.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.int
    }

    private fun sgdbAsset(path: String, key: String): String? {
        val body = get("https://www.steamgriddb.com/api/v2/$path", key) ?: return null
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
        return (data.firstOrNull() as? JsonObject)?.get("url")?.jsonPrimitive?.content
    }

    private fun download(url: String, to: File): File? = runCatching<File?> {
        val req = Request.Builder().url(url).header("User-Agent", "Orbit-Launcher/0.2 (Android)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { android.util.Log.w("OrbitArt", "HTTP ${r.code} $url"); return null }
            val tmp = File(to.parentFile, to.name + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            // файл має бути справжньою картинкою, яку Android уміє відкрити (не HTML-сторінка, не SVG тощо)
            if (tmp.length() < 512 || !isImage(tmp)) { tmp.delete(); android.util.Log.w("OrbitArt", "not an image: $url"); return null }
            tmp.renameTo(to)
            to
        }
    }.onFailure { android.util.Log.w("OrbitArt", "download failed $url: $it") }.getOrNull()

    companion object {
        private const val RETRY_MS = 6 * 3600_000L

        fun isImage(f: File): Boolean = f.exists() && android.graphics.BitmapFactory.Options().run {
            inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, this)
            outWidth > 0 && outHeight > 0
        }
    }

    private val pm = context.packageManager

    /** Застосунок Android сам позначений як гра — лише для таких шукаємо арт у SteamGridDB */
    private fun isAndroidGame(pkg: String?): Boolean = pkg != null && runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        @Suppress("DEPRECATION")
        ai.category == android.content.pm.ApplicationInfo.CATEGORY_GAME || (ai.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
    }.getOrDefault(false)

    /**
     * Прибирає зламані картинки (файл зник або не відкривається) і чужі картинки
     * у звичайних застосунків — тоді на плитці знову видно рідну іконку.
     */
    suspend fun repair(clearNonGameApps: Boolean) = withContext(Dispatchers.IO) {
        for (g in library.games.value) {
            fun ok(path: String?) = path == null || isImage(File(path))
            val wrongApp = clearNonGameApps && g.kind == GameKind.ANDROID && !g.customArt && !isAndroidGame(g.packageName)
            if (wrongApp || !ok(g.cover) || !ok(g.hero) || !ok(g.logo)) {
                library.edit(g.id) {
                    it.copy(
                        cover = if (wrongApp || !ok(it.cover)) null else it.cover,
                        hero = if (wrongApp || !ok(it.hero)) null else it.hero,
                        logo = if (wrongApp || !ok(it.logo)) null else it.logo,
                        artChecked = wrongApp,
                        artCheckedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }
}

/** Нечітке порівняння назв ігор: регістр, дужки, «The», римські цифри, розділові знаки */
object NameMatch {
    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")
    private val stop = setOf(
        "the", "a", "an", "of", "and", "version", "edition",
        // регіони й мітки, які в іменах файлів пишуть без дужок: Game_USA.iso, game-eur-v1.1.cso
        "usa", "us", "eur", "europe", "jpn", "jp", "japan", "pal", "ntsc", "en", "fr", "de", "es", "it", "ru", "ua",
        "rev", "v1", "v2", "v1.0", "v1.1", "proper", "rip", "iso", "rom", "decrypted", "nkit", "rvz",
    )
    private val brackets = Regex("[\\[(][^\\])]*[\\])]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** Назва, підготовлена до порівняння (рахується один раз на кандидата) */
    class Entry(val name: String) {
        val words: Set<String> = words(name)
        val key: String = words.joinToString(" ")
        val region: Int = regionScore(name)
    }

    /** Кеш підготовлених списків: той самий список назв -> ті самі Entry */
    private val prepared = java.util.IdentityHashMap<List<String>, List<Entry>>()

    private fun prepare(names: List<String>): List<Entry> = synchronized(prepared) {
        prepared.getOrPut(names) { names.map(::Entry) }
    }

    fun words(s: String): Set<String> = s.lowercase()
        .replace(brackets, " ")
        .replace("&", " and ")
        .replace("'", "")
        .replace(nonWord, " ")
        .trim()
        .split(' ')
        .asSequence()
        .filter { it.isNotEmpty() }
        .map { roman[it] ?: it }
        .filter { it !in stop }
        .toCollection(LinkedHashSet())

    /** Регіон, який краще брати, якщо збігів кілька */
    private fun regionScore(name: String): Int = when {
        "(USA" in name -> 4
        "(World" in name -> 3
        "(Europe" in name || "Europe)" in name -> 2
        "(Japan" in name -> 0
        else -> 1
    } - (if ("(Beta" in name || "(Proto" in name || "(Demo" in name || "(Sample" in name) 5 else 0) -
        (if ("(Alternate" in name || "(Alt" in name || "(Unl" in name || "(Pirate" in name) 2 else 0)

    class Match(val name: String, val exact: Boolean)

    fun best(candidates: List<String>, fileBase: String, title: String): String? = match(candidates, fileBase, title)?.name

    fun match(candidates: List<String>, fileBase: String, title: String): Match? {
        val entries = prepare(candidates)
        // 1. точна назва файлу (без урахування регістру)
        entries.firstOrNull { it.name.equals(fileBase, ignoreCase = true) }?.let { return Match(it.name, true) }
        // 2. однакові після нормалізації
        val want = words(fileBase)
        val wanted = setOf(want.joinToString(" "), words(title).joinToString(" ")).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return null
        entries.filter { it.key in wanted }.maxByOrNull { it.region }?.let { return Match(it.name, true) }
        // 3. найбільша схожість за словами
        var bestName: String? = null
        var bestScore = 0.0
        for (e in entries) {
            if (e.words.isEmpty()) continue
            val inter = want.count { it in e.words }
            if (inter == 0) continue
            // жакар + бонус, якщо всі слова з назви файлу є в кандидаті
            var score = inter.toDouble() / (want.size + e.words.size - inter)
            if (inter == want.size) score += 0.25
            score += e.region * 0.01
            if (score > bestScore) { bestScore = score; bestName = e.name }
        }
        return bestName?.takeIf { bestScore >= 0.5 }?.let { Match(it, false) }
    }
}
