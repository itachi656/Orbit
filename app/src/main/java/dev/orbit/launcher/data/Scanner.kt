package dev.orbit.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File

object RomScanner {
    fun scan(root: File): List<Game> {
        if (!root.isDirectory) return emptyList()
        val result = mutableListOf<Game>()
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val storage = android.os.Environment.getExternalStorageDirectory()
        for (sys in Systems.all) {
            dirs.filter { d -> sys.folders.any { it.equals(d.name, ignoreCase = true) } }.forEach { result += scanSystem(sys, it) }
            // папки, куди застосунки самі експортують ярлики (WinNative, Winlator Cmod)
            sys.extraDirs.map { File(storage, it) }.filter { it.isDirectory }.forEach { result += scanSystem(sys, it) }
        }
        return result
    }

    private fun scanSystem(sys: SystemDef, dir: File): List<Game> {
        val files = dir.walkTopDown()
            .maxDepth(5)
            .onEnter { it.name != "media" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() in sys.extensions && !it.name.startsWith(".") }
            .toList()

        // Усе, на що посилаються .m3u / .cue, ховаємо — показуємо лише «головний» файл
        val referenced = mutableSetOf<String>()
        files.filter { it.extension.lowercase() in setOf("m3u", "cue") }.forEach { f ->
            runCatching {
                f.readLines().forEach { line ->
                    val name = when {
                        f.extension.equals("cue", true) ->
                            Regex("FILE\\s+\"(.+?)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                        else -> line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    } ?: return@forEach
                    referenced += File(f.parentFile, name).canonicalPath
                }
            }
        }

        val visible = files.filter { it.canonicalPath !in referenced }

        // Багатодискові ігри без .m3u: «FF7 (Disc 1).chd», «FF7 (Disc 2).chd» -> одна гра.
        // .m3u створюється поруч з дисками перед першим запуском (так емулятор сам міняє диски).
        val discRe = Regex("\\s*[(\\[](?:disc|disk|cd)\\s*(\\d+)(?:\\s*of\\s*\\d+)?[)\\]]", RegexOption.IGNORE_CASE)
        val multi = sys.extensions.contains("m3u")
        val grouped = if (!multi) emptyMap() else visible
            .filter { it.extension.lowercase() != "m3u" && discRe.containsMatchIn(it.nameWithoutExtension) }
            .groupBy { File(it.parentFile, discRe.replace(it.nameWithoutExtension, "").trim()).path }
            .filterValues { it.size >= 2 }
        val inGroups = grouped.values.flatten().toSet()

        val singles = visible.filter { it !in inGroups }.map { f ->
            Game(
                id = "rom:${f.absolutePath}",
                title = cleanTitle(baseName(f)),
                kind = GameKind.ROM,
                system = sys.id,
                path = f.absolutePath,
            )
        }
        val discGames = grouped.map { (base, discs) ->
            val sorted = discs.sortedBy { d -> discRe.find(d.nameWithoutExtension)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            val m3u = File("$base.m3u")
            Game(
                id = "rom:${m3u.absolutePath}",
                title = cleanTitle(m3u.nameWithoutExtension),
                kind = GameKind.ROM,
                system = sys.id,
                path = m3u.absolutePath,
                discs = sorted.map { it.name },
            )
        }
        return singles + discGames
    }

    /**
     * Усі файли гри на диску: сам файл, усе, на що посилаються .cue / .m3u, диски.
     * Гра-папка («Гра/EBOOT.PBP») — уся папка, але лише якщо вона глибше папки системи.
     */
    fun filesOf(game: Game, root: File): List<File> {
        val main = File(game.path ?: return emptyList())
        val dir = main.parentFile ?: return listOf(main)
        if (main.nameWithoutExtension.lowercase() in genericNames) {
            val rel = dir.canonicalFile.relativeToOrNull(root.canonicalFile)?.invariantSeparatorsPath
            if (rel != null && rel.split('/').size >= 2) return listOf(dir)
        }
        val out = linkedSetOf(main)
        game.discs?.forEach { out += File(dir, it) }
        // .m3u -> диски, .cue -> доріжки .bin
        val queue = ArrayDeque(out.toList())
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            val ext = f.extension.lowercase()
            if (ext != "cue" && ext != "m3u" || !f.isFile) continue
            runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { line ->
                val name = if (ext == "cue") Regex("FILE\\s+\"(.+?)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
                else line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                if (name != null) File(f.parentFile, name).let { if (out.add(it)) queue += it }
            }
        }
        return out.filter { it.exists() }
    }

    /** Файли з «безликими» іменами — назва гри тоді в імені папки (PSP/PS1: Гра/EBOOT.PBP) */
    private val genericNames = setOf("eboot", "game", "disc", "disk", "rom", "main", "default", "boot", "index", "image", "track 1", "track01")

    /** Ім'я, за яким шукаємо назву і картинки: ім'я файлу або папки, якщо файл названий «EBOOT» тощо */
    fun baseName(f: File): String {
        val n = f.nameWithoutExtension
        return if (n.lowercase() in genericNames && f.parentFile != null) f.parentFile!!.name else n
    }

    /** "Super Mario World (USA) [!]" -> "Super Mario World" */
    fun cleanTitle(raw: String): String {
        var t = raw.replace('_', ' ')
        t = t.replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "")
        t = t.replace(Regex("\\s+"), " ").trim()
        // "Legend of Zelda, The" -> "The Legend of Zelda"
        Regex("^(.*?), (The|A|An)(\\s-\\s.*)?$", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            t = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
        }
        return t.ifEmpty { raw }
    }
}

data class InstalledApp(val packageName: String, val label: String, val isGame: Boolean)

object AndroidApps {
    fun list(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { ai ->
                @Suppress("DEPRECATION")
                val game = ai.category == ApplicationInfo.CATEGORY_GAME ||
                    (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                InstalledApp(ai.packageName, pm.getApplicationLabel(ai).toString(), game)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun toGame(app: InstalledApp) = Game(
        id = "app:${app.packageName}",
        title = app.label,
        kind = GameKind.ANDROID,
        system = Systems.ANDROID,
        packageName = app.packageName,
    )
}
