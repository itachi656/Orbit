package dev.orbit.launcher.store

import android.content.Context
import dev.orbit.launcher.data.Prefs
import dev.orbit.launcher.data.Systems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Джерело ігор для магазину.
 * type: orbit — каталог у форматі Orbit (JSON за посиланням, додає користувач);
 *       hbhub — Homebrew Hub (безкоштовні ігри GB/GBC/GBA/NES);
 *       unidb — Universal-DB (безкоштовні ігри 3DS/DS).
 */
@Serializable
data class StoreSource(
    val id: String,
    val name: String,
    val url: String,
    val type: String,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
)

@Serializable
data class StoreItem(
    val key: String,
    val sourceId: String,
    val title: String,
    val system: String,
    val url: String,
    val fileName: String,
    val cover: String? = null,
    val screenshots: List<String> = emptyList(),
    val description: String = "",
    val developer: String = "",
    val size: Long = 0,
    val tags: List<String> = emptyList(),
)

class Store(private val context: Context, private val prefs: Prefs) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dir = File(context.filesDir, "store").apply { mkdirs() }
    private val sourcesFile = File(dir, "sources.json")

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<StoreSource>> = _sources

    private val _items = MutableStateFlow<List<StoreItem>>(emptyList())
    val items: StateFlow<List<StoreItem>> = _items

    /** Помилки завантаження каталогів: id джерела -> текст */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** Прогрес завантажень: ключ гри -> 0..1 */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    companion object {
        val builtins = listOf(
            StoreSource("hbhub", "Homebrew Hub", "https://hh3.gbdev.io", "hbhub", builtin = true),
            StoreSource("unidb", "Universal-DB", "https://db.universal-team.net/data/full.json", "unidb", builtin = true),
        )

        /** "ps1" / "psx" / "3ds" … -> id системи Orbit */
        fun systemFor(name: String): String? {
            val n = name.lowercase().trim()
            Systems.byId[n]?.let { return it.id }
            return Systems.all.firstOrNull { s -> s.folders.any { it.equals(n, true) } || s.short.equals(n, true) }?.id
        }
    }

    private fun loadSources(): List<StoreSource> {
        val saved = runCatching { json.decodeFromString(ListSerializer(StoreSource.serializer()), sourcesFile.readText()) }.getOrNull()
            ?: return builtins
        // нові вбудовані джерела з'являються і в старих установках
        return saved + builtins.filter { b -> saved.none { it.id == b.id } }
    }

    private fun saveSources() {
        sourcesFile.writeText(json.encodeToString(ListSerializer(StoreSource.serializer()), _sources.value))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _sources.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        saveSources()
        _items.update { list -> if (enabled) list else list.filterNot { it.sourceId == id } }
    }

    fun remove(id: String) {
        _sources.update { list -> list.filterNot { it.id == id && !it.builtin } }
        saveSources()
        _items.update { list -> list.filterNot { it.sourceId == id } }
        File(dir, "$id.cache.json").delete()
    }

    /** Додати своє джерело: перевіряємо, що за посиланням справді каталог. Повертає кількість ігор */
    suspend fun addSource(url: String, nameHint: String?): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = url.trim()
            require(clean.startsWith("http://") || clean.startsWith("https://")) { "url" }
            val tmp = StoreSource("u" + clean.hashCode().toUInt().toString(16), nameHint ?: "", clean, "orbit")
            val (name, items) = parseOrbit(tmp, get(clean))
            val src = tmp.copy(name = nameHint?.takeIf { it.isNotBlank() } ?: name ?: URI(clean).host ?: clean)
            _sources.update { list -> list.filterNot { it.id == src.id } + src }
            saveSources()
            cache(src.id, items)
            _items.update { list -> list.filterNot { it.sourceId == src.id } + items }
            items.size
        }
    }

    /** Оновити всі ввімкнені каталоги (паралельно). Невдалі — з кешу */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        _loading.value = true
        val errs = mutableMapOf<String, String>()
        val all = coroutineScope {
            _sources.value.filter { it.enabled }.map { src ->
                async {
                    runCatching {
                        val items = when (src.type) {
                            "hbhub" -> fetchHomebrewHub(src)
                            "unidb" -> fetchUniversalDb(src)
                            else -> parseOrbit(src, get(src.url)).second
                        }
                        cache(src.id, items)
                        items
                    }.getOrElse { e ->
                        synchronized(errs) { errs[src.id] = e.message ?: e.javaClass.simpleName }
                        cached(src.id)
                    }
                }
            }.awaitAll().flatten()
        }
        _items.value = all.distinctBy { it.key }
        _errors.value = errs
        _loading.value = false
    }

    /** Показати кеш одразу, поки йде оновлення */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_items.value.isEmpty()) _items.value = _sources.value.filter { it.enabled }.flatMap { cached(it.id) }.distinctBy { it.key }
    }

    private fun cache(id: String, items: List<StoreItem>) =
        File(dir, "$id.cache.json").writeText(json.encodeToString(ListSerializer(StoreItem.serializer()), items))

    private fun cached(id: String): List<StoreItem> = runCatching {
        json.decodeFromString(ListSerializer(StoreItem.serializer()), File(dir, "$id.cache.json").readText())
    }.getOrDefault(emptyList())

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "Orbit-Launcher (Android)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body!!.string()
        }
    }

    // ---------- формати каталогів ----------

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.strList(): List<String> = (this as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()

    /**
     * Формат Orbit:
     * { "name": "…", "games": [ { "title", "system", "url", "file"?, "cover"?, "screenshots"?,
     *   "description"?, "developer"?, "size"?, "tags"? } ] }
     * Відносні посилання рахуються від адреси каталогу.
     */
    private fun parseOrbit(src: StoreSource, body: String): Pair<String?, List<StoreItem>> {
        val root = json.parseToJsonElement(body)
        val obj = root as? JsonObject
        val games = (obj?.get("games") ?: root) as? JsonArray ?: error("not a catalog")
        val base = URI(src.url)
        fun abs(u: String?) = u?.let { runCatching { base.resolve(u.replace(" ", "%20")).toString() }.getOrDefault(u) }
        val items = games.mapNotNull { el ->
            val g = el as? JsonObject ?: return@mapNotNull null
            val title = g["title"].str() ?: return@mapNotNull null
            val url = abs(g["url"].str()) ?: return@mapNotNull null
            val system = g["system"].str()?.let(::systemFor) ?: return@mapNotNull null
            val file = g["file"].str() ?: java.net.URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "UTF-8")
            StoreItem(
                key = "${src.id}:${url.hashCode()}",
                sourceId = src.id,
                title = title,
                system = system,
                url = url,
                fileName = file,
                cover = abs(g["cover"].str()),
                screenshots = g["screenshots"].strList().mapNotNull { abs(it) },
                description = g["description"].str() ?: "",
                developer = g["developer"].str() ?: "",
                size = g["size"]?.jsonPrimitive?.longOrNull ?: 0,
                tags = g["tags"].strList(),
            )
        }
        return obj?.get("name").str() to items
    }

    /** Homebrew Hub: https://hh3.gbdev.io/api/search — ігри з файлами */
    private fun fetchHomebrewHub(src: StoreSource): List<StoreItem> {
        val out = mutableListOf<StoreItem>()
        var page = 1
        var total = 1
        while (page <= total && page <= 40) {
            val d = json.parseToJsonElement(get("${src.url}/api/search?page=$page&results=100")).jsonObject
            total = d["page_total"]?.jsonPrimitive?.int ?: 1
            for (e in d["entries"]?.jsonArray.orEmpty()) {
                val o = e.jsonObject
                if (o["typetag"].str() != "game") continue
                val files = o["files"] as? JsonArray ?: continue
                val f = files.map { it.jsonObject }.firstOrNull { it["default"]?.jsonPrimitive?.contentOrNull == "true" }
                    ?: files.map { it.jsonObject }.firstOrNull { it["playable"]?.jsonPrimitive?.contentOrNull == "true" }
                    ?: continue
                val fileName = f["filename"].str() ?: continue
                val ext = fileName.substringAfterLast('.').lowercase()
                val system = when (ext) {
                    "gb" -> "gb"; "gbc" -> "gbc"; "gba" -> "gba"; "nes" -> "nes"
                    else -> continue
                }
                val slug = o["slug"].str() ?: continue
                val basepath = o["basepath"].str() ?: continue
                val baseUrl = "${src.url}/static/$basepath/entries/$slug"
                val shots = o["screenshots"].strList().map { "$baseUrl/$it" }
                out += StoreItem(
                    key = "hbhub:$slug",
                    sourceId = src.id,
                    title = o["title"].str() ?: slug,
                    system = system,
                    url = "$baseUrl/$fileName",
                    fileName = fileName,
                    cover = shots.firstOrNull(),
                    screenshots = shots,
                    description = o["description"].str() ?: "",
                    developer = (o["developer"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("name").str() ?: it.str() }?.joinToString(", ") ?: "",
                    tags = o["tags"].strList(),
                )
            }
            page++
        }
        return out
    }

    /** Universal-DB: безкоштовні ігри 3DS (.3dsx) і DS (.nds) */
    private fun fetchUniversalDb(src: StoreSource): List<StoreItem> {
        val arr = json.parseToJsonElement(get(src.url)).jsonArray
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o["categories"].strList().none { it.equals("game", true) }) return@mapNotNull null
            val downloads = o["downloads"] as? JsonObject ?: return@mapNotNull null
            val (file, info, system) = downloads.entries.firstNotNullOfOrNull { (name, v) ->
                when {
                    name.endsWith(".3dsx", true) -> Triple(name, v.jsonObject, "n3ds")
                    name.endsWith(".nds", true) -> Triple(name, v.jsonObject, "nds")
                    else -> null
                }
            } ?: return@mapNotNull null
            val slug = o["slug"].str() ?: file
            StoreItem(
                key = "unidb:$slug:$system",
                sourceId = src.id,
                title = o["title"].str() ?: file,
                system = system,
                url = info["url"].str() ?: return@mapNotNull null,
                fileName = file,
                cover = o["image"].str() ?: o["icon"].str(),
                screenshots = listOfNotNull(o["image"].str()),
                description = o["description"].str() ?: "",
                developer = o["author"].str() ?: "",
                size = info["size"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    // ---------- завантаження ----------

    /** Куди ляже гра: <ROMs>/<папка системи>/ */
    fun targetDir(item: StoreItem): File {
        val sys = Systems.byId[item.system]
        val root = File(prefs.romRoot)
        val existing = sys?.folders?.map { File(root, it) }?.firstOrNull { it.isDirectory }
        return existing ?: File(root, sys?.folders?.first() ?: item.system)
    }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(150)

    /** Чи вже є гра на телефоні (за ім'ям файлу або розпакованим вмістом) */
    fun installedFile(item: StoreItem): File? {
        val dir = targetDir(item)
        val f = File(dir, safeName(item.fileName))
        if (f.exists()) return f
        val marker = File(dir, ".orbit-" + item.key.hashCode().toUInt().toString(16))
        return marker.takeIf { it.exists() }?.readText()?.let { File(it) }?.takeIf { it.exists() }
    }

    /** Завантажити гру. Архіви .zip розпаковуються, якщо емулятори системи їх не відкривають */
    suspend fun download(item: StoreItem): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = targetDir(item).apply { mkdirs() }
            val name = safeName(item.fileName)
            val part = File(dir, ".$name.part")
            _progress.update { it + (item.key to 0f) }
            val req = Request.Builder().url(item.url).header("User-Agent", "Orbit-Launcher (Android)").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: item.size
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastReport > 256 * 1024) {
                                lastReport = done
                                _progress.update { it + (item.key to (done.toFloat() / total).coerceIn(0f, 0.99f)) }
                            }
                        }
                    }
                }
            }
            val sys = Systems.byId[item.system]
            val isZip = name.endsWith(".zip", true)
            val result = if (isZip && sys != null && "zip" !in sys.extensions) {
                // розпаковуємо лише файли, які відкривають емулятори цієї системи
                var main: File? = null
                ZipInputStream(part.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val fname = safeName(entry.name.substringAfterLast('/'))
                        val ext = fname.substringAfterLast('.').lowercase()
                        if (ext !in sys.extensions && ext !in setOf("bin", "img", "sub")) continue
                        val out = File(dir, fname)
                        out.outputStream().use { zip.copyTo(it) }
                        if (ext in sys.extensions && (main == null || ext in setOf("cue", "m3u"))) main = out
                    }
                }
                part.delete()
                val m = main ?: error("archive")
                File(dir, ".orbit-" + item.key.hashCode().toUInt().toString(16)).writeText(m.absolutePath)
                m
            } else {
                val out = File(dir, name)
                part.renameTo(out)
                out
            }
            _progress.update { it - item.key }
            result
        }.onFailure { _progress.update { p -> p - item.key } }
    }
}
