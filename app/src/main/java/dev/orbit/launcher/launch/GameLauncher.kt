package dev.orbit.launcher.launch

import dev.orbit.launcher.tr

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import dev.orbit.launcher.data.DataMode
import dev.orbit.launcher.data.Emulator
import dev.orbit.launcher.data.ExtraType
import dev.orbit.launcher.data.Game
import dev.orbit.launcher.data.GameKind
import dev.orbit.launcher.data.Prefs
import dev.orbit.launcher.data.Systems
import java.io.File

sealed class LaunchResult {
    data object Ok : LaunchResult()
    data class Error(val message: String) : LaunchResult()
}

class GameLauncher(private val context: Context, private val prefs: Prefs) {
    companion object {
        /** check() повертає це, коли треба один раз дати Orbit доступ до папки з іграми */
        const val NEED_FOLDER = "@need_folder"
        private const val EXT_AUTH = "com.android.externalstorage.documents"
    }

    private val pm = context.packageManager

    fun isInstalled(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Емулятори системи: відомі профілі + свої застосунки, які додав користувач */
    fun emulatorsFor(system: String): List<Emulator> {
        val sys = Systems.byId[system] ?: return emptyList()
        val custom = prefs.customEmulators(system).mapNotNull { entry ->
            val pkg = entry.substringBefore('|')
            val mode = runCatching { DataMode.valueOf(entry.substringAfter('|')) }.getOrDefault(DataMode.SAF)
            if (!isInstalled(pkg)) return@mapNotNull null
            Emulator(appLabel(pkg) + tr(" (свій)"), listOf(pkg), action = Intent.ACTION_VIEW, data = mode)
        }
        return sys.emulators + custom
    }

    fun appLabel(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Перший встановлений компонент емулятора */
    fun resolve(emu: Emulator): ComponentName? {
        if (emu.isCustom) {
            val pkg = emu.components.first()
            if (!isInstalled(pkg)) return null
            // шукаємо activity, що вміє відкривати файли (VIEW), інакше — головну
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/y.bin"), "*/*").setPackage(pkg)
            val viewer = pm.queryIntentActivities(probe, 0).firstOrNull()?.activityInfo
            return viewer?.let { ComponentName(it.packageName, it.name) }
                ?: pm.getLaunchIntentForPackage(pkg)?.component
        }
        return emu.components.firstNotNullOfOrNull { c ->
            val pkg = c.substringBefore('/')
            val act = c.substringAfter('/').let { if (it.startsWith(".")) pkg + it else it }
            if (isInstalled(pkg)) ComponentName(pkg, act) else null
        }
    }

    /** Емулятор, який реально буде використано для гри */
    fun emulatorIndexFor(game: Game): Int? {
        val list = emulatorsFor(game.system)
        if (list.isEmpty()) return null
        val ext = game.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        fun ok(i: Int) = list[i].accepts(ext)
        game.emulator?.takeIf { it in list.indices }?.let { return it }
        // вибір для системи діє, лише якщо підходить до типу файлу (.steam не відкрити у Winlator)
        prefs.emulatorFor(game.system)?.takeIf { it in list.indices && ok(it) }?.let { return it }
        return list.indices.firstOrNull { ok(it) && resolve(list[it]) != null }
            ?: list.indices.firstOrNull { ok(it) }
            ?: 0
    }

    /** Повернутися до запущеної гри: відкриваємо застосунок емулятора, не перезапускаючи гру */
    fun resumeLast(): Boolean {
        val pkg = prefs.lastGamePkg.ifBlank { return false }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        intent.removeFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        prefs.inGameSince = System.currentTimeMillis()
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Чи можна запустити гру — перевіряємо до анімації, щоб не показувати її даремно */
    fun check(game: Game): String? = runCatching {
        when (game.kind) {
            GameKind.ANDROID -> if (pm.getLaunchIntentForPackage(game.packageName!!) == null) tr("Застосунок %s не встановлено", game.title) else null
            GameKind.MOONLIGHT -> if (moonlightIntent(game) == null) tr("Встанови Moonlight") else null
            GameKind.SHORTCUT -> when {
                !isInstalled(game.packageName!!) -> tr("Застосунок %s не встановлено", appLabel(game.packageName))
                !launcherApps.hasShortcutHostPermission() -> tr("Ярлики працюють, коли Orbit — домашній екран (Меню → Геймпад і запуск)")
                else -> null
            }
            GameKind.ROM -> (romIntent(game) as? IntentOrError.Err)?.msg
        }
    }.getOrElse { it.message }

    private val launcherApps get() = context.getSystemService(android.content.pm.LauncherApps::class.java)

    fun launch(game: Game): LaunchResult =
        if (game.kind == GameKind.SHORTCUT) launchShortcut(game) else launchIntent(game)

    private fun launchIntent(game: Game): LaunchResult = try {
        val intent = when (game.kind) {
            GameKind.SHORTCUT -> return launchShortcut(game)
            GameKind.ANDROID -> pm.getLaunchIntentForPackage(game.packageName!!)
                ?: return LaunchResult.Error(tr("Застосунок %s не встановлено", game.title))
            GameKind.MOONLIGHT -> moonlightIntent(game) ?: return LaunchResult.Error(tr("Встанови Moonlight"))
            GameKind.ROM -> when (val r = romIntent(game)) {
                is IntentOrError.Ok -> r.intent
                is IntentOrError.Err -> return LaunchResult.Error(r.msg)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        prefs.inGameSince = System.currentTimeMillis()
        prefs.lastGameId = game.id
        prefs.lastGamePkg = intent.component?.packageName ?: intent.`package` ?: ""
        try {
            context.startActivity(intent)
        } catch (e: SecurityException) {
            // доступ до всієї папки видати не вдалося — даємо хоча б на сам файл
            intent.clipData?.let { clip -> intent.clipData = ClipData.newRawUri("rom", clip.getItemAt(0).uri) }
            intent.removeFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            context.startActivity(intent)
        }
        LaunchResult.Ok
    } catch (e: Exception) {
        LaunchResult.Error(e.message ?: e.javaClass.simpleName)
    }

    private fun launchShortcut(game: Game): LaunchResult = try {
        prefs.inGameSince = System.currentTimeMillis()
        prefs.lastGameId = game.id
        prefs.lastGamePkg = game.packageName ?: ""
        launcherApps.startShortcut(game.packageName!!, game.shortcutId!!, null, null, android.os.Process.myUserHandle())
        LaunchResult.Ok
    } catch (e: Exception) {
        LaunchResult.Error(e.message ?: e.javaClass.simpleName)
    }

    private sealed class IntentOrError {
        class Ok(val intent: Intent) : IntentOrError()
        class Err(val msg: String) : IntentOrError()
    }

    private fun moonlightIntent(game: Game): Intent? {
        val pkg = listOf("com.limelight", "com.limelight.root", "com.limelight.debug").firstOrNull(::isInstalled) ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, "com.limelight.ShortcutTrampoline")
            // ShortcutTrampoline шукає ПК за Name (якщо немає UUID), а гру — за AppName
            putExtra("Name", game.pcName)
            putExtra("AppName", game.appName)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    private fun romIntent(game: Game): IntentOrError {
        val sys = Systems.byId[game.system] ?: return IntentOrError.Err(tr("Невідома система %s", game.system))
        val list = emulatorsFor(sys.id)
        if (list.isEmpty()) return IntentOrError.Err(tr("Для %s немає емулятора — додай свій у Меню → Емулятори", sys.short))
        val emu = list[emulatorIndexFor(game) ?: 0]
        val component = resolve(emu) ?: return IntentOrError.Err(
            if (list.none { resolve(it) != null && it.accepts(game.path!!.substringAfterLast('.').lowercase()) }) {
                val ext = game.path!!.substringAfterLast('.').lowercase()
                val hint = list.filter { !it.isRetroArch && it.accepts(ext) }.map { it.label.substringBefore(" ") }.distinct().take(2).joinToString(tr(" або "))
                tr("Немає емулятора для %s. Встанови %s — або Y → Емулятор → Інший застосунок", sys.short, hint)
            } else {
                tr("Встанови %s або вибери інший: Y → Емулятор", if (emu.isRetroArch) "RetroArch" else emu.label)
            },
        )
        val file = File(game.path!!)
        // багатодискова гра: створюємо .m3u поруч з дисками (одноразово)
        if (!file.exists() && !game.discs.isNullOrEmpty()) {
            runCatching { file.writeText(game.discs.joinToString("\n", postfix = "\n")) }
        }
        if (!file.exists()) return IntentOrError.Err(tr("Файл не знайдено: %s", file.name))

        val usesSaf = emu.data == DataMode.SAF || emu.extras.any { "{SAF}" in it.value }
        val tree = if (usesSaf) treeFor(file) ?: return IntentOrError.Err(NEED_FOLDER) else null

        val intent = Intent(emu.action ?: Intent.ACTION_MAIN)
        intent.component = component
        emu.category?.let(intent::addCategory)
        if (emu.clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        when (emu.data) {
            DataMode.NONE -> Unit
            DataMode.SAF -> intent.data = safUri(file)
            DataMode.PROVIDER -> {
                // Тимчасовий доступ видаємо лише на свій FileProvider; до SAF-папки емулятор
                // має мати доступ сам (як і в ES-DE: один раз вибрати папку ROMs у емуляторі)
                intent.data = providerUri(file)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            DataMode.FILE -> intent.data = Uri.fromFile(file)
        }

        if (tree != null) {
            // Видаємо емулятору тимчасовий доступ до файлу і всієї папки (для .cue/.bin, .m3u, сейвів поруч)
            val clip = ClipData.newRawUri("rom", safUri(file))
            clip.addItem(ClipData.Item(tree))
            intent.clipData = clip
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }

        for (extra in emu.extras) {
            val value = expand(extra.value, file, component.packageName)
            when (extra.type) {
                ExtraType.STRING -> intent.putExtra(extra.key, value)
                ExtraType.BOOL -> intent.putExtra(extra.key, value.toBoolean())
                ExtraType.INT -> intent.putExtra(extra.key, value.trim().toInt())
                ExtraType.ARRAY -> intent.putExtra(extra.key, value.split(',').toTypedArray())
            }
        }
        return IntentOrError.Ok(intent)
    }

    private fun expand(template: String, file: File, pkg: String): String {
        var v = template
            .replace("{ROM}", file.absolutePath)
            .replace("{RESUME}", prefs.resumeState.toString())
            .replace("{RA_CFG}", "${Environment.getExternalStorageDirectory()}/Android/data/${pkg}/files/retroarch.cfg")
        if ("{SAF}" in v) v = v.replace("{SAF}", safUri(file).toString())
        if ("{PROVIDER}" in v) v = v.replace("{PROVIDER}", providerUri(file).toString())
        if ("{INJECT}" in v) v = v.replace("{INJECT}", runCatching { file.readText().trim() }.getOrDefault(file.nameWithoutExtension))
        v = Regex("\\{RA_CORE:([^}]+)\\}").replace(v) { m -> "/data/data/${pkg}/cores/${m.groupValues[1]}_libretro_android.so" }
        return v
    }

    private fun docIdOf(file: File): String {
        val path = file.absolutePath
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return if (path.startsWith("${primary}/")) {
            "primary:" + path.removePrefix("${primary}/")
        } else {
            // SD-карта: /storage/1234-ABCD/ROMs/... -> 1234-ABCD:ROMs/...
            val rest = path.removePrefix("/storage/")
            rest.substringBefore('/') + ":" + rest.substringAfter('/')
        }
    }

    /** Дозволена папка (з постійним SAF-дозволом), у якій лежить файл, або null */
    private fun treeFor(file: File): Uri? {
        val docId = docIdOf(file)
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
        return granted.firstOrNull { t ->
            t.authority == EXT_AUTH && runCatching {
                val treeId = DocumentsContract.getTreeDocumentId(t)
                docId.startsWith(if (treeId.endsWith(":")) treeId else "${treeId}/")
            }.getOrDefault(false)
        }
    }

    fun hasFolderAccess(): Boolean = treeFor(File(prefs.romRoot, "x")) != null

    /**
     * SAF-посилання на файл. Якщо є дозволена папка — посилання «всередині» неї
     * (content://…/tree/primary%3AROMs/document/primary%3AROMs%2Fpsp%2Fgame.iso),
     * інакше емулятор не зможе його відкрити.
     */
    fun safUri(file: File): Uri {
        val docId = docIdOf(file)
        val tree = treeFor(file)
        return if (tree != null) DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        else DocumentsContract.buildDocumentUri(EXT_AUTH, docId)
    }

    /** Посилання на папку для вікна вибору — щоб воно одразу відкрилось у ROMs */
    fun folderHint(path: String): Uri = DocumentsContract.buildDocumentUri(EXT_AUTH, docIdOf(File(path)))

    private fun providerUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
