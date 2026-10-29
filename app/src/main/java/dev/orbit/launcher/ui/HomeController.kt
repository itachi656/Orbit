package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import dev.orbit.launcher.BuildConfig
import dev.orbit.launcher.I18n
import dev.orbit.launcher.OrbitApp
import dev.orbit.launcher.data.AndroidApps
import dev.orbit.launcher.data.DataMode
import dev.orbit.launcher.data.Game
import dev.orbit.launcher.data.GameKind
import dev.orbit.launcher.data.Look
import dev.orbit.launcher.data.Systems
import dev.orbit.launcher.input.Btn
import dev.orbit.launcher.launch.LaunchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class RowItem {
    abstract val key: String

    data class GameItem(val game: Game) : RowItem() {
        override val key get() = game.id
    }

    data class Action(
        val id: String,
        val title: String,
        val subtitle: String,
        val icon: ImageVector,
        /** для ярликів систем — колір плитки */
        val system: String? = null,
    ) : RowItem() {
        override val key get() = "action:${id}"
    }
}

data class Tab(val id: String, val label: String, val items: List<RowItem>)

enum class Zone { Tabs, Row, Actions }

class MenuItem(
    val label: String,
    val value: String? = null,
    val action: () -> Unit,
)

class Menu(val title: String, val build: () -> List<MenuItem>) {
    var sel by mutableIntStateOf(0)
}

class FormField(val label: String, initial: String = "", val secret: Boolean = false) {
    var value by mutableStateOf(initial)
}

class TextForm(val title: String, val hint: String?, val fields: List<FormField>, val onSave: (List<String>) -> Unit)

class HomeController(
    val app: OrbitApp,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        fun hasAllFiles(): Boolean
        fun openAllFilesSettings()
        fun pickRomFolder()
        fun pickImage(onPicked: (Uri) -> Unit)
        fun isHomeEnabled(): Boolean
        fun setHomeEnabled(enabled: Boolean)
        fun usbGamepads(): List<Pair<String, String>>
        fun haptic()
        fun pickAudio(onPicked: (android.net.Uri) -> Unit)
        /** 0..1; null — не вдалося прочитати */
        fun getBrightness(): Float
        fun setBrightness(v: Float)
        fun getVolume(): Float
        fun setVolume(v: Float)
        fun openWifi()
        fun openBluetooth()
        fun goAndroidHome()
        /** Заряд підключеного геймпада у %, якщо Android його віддає */
        fun controllerBattery(): Int?
        /** Іконка застосунку на телефоні: id з [AppIcons] */
        fun launcherIcon(): String
        fun setLauncherIcon(id: String)
    }

    private val prefs = app.prefs

    private companion object {
        /** Збільшуй, коли покращуєш пошук картинок — ігри без картинок перешукаються */
        const val ART_RULES = 4
    }
    val sounds = Sounds(app).also { it.level = prefs.volume }

    var look by mutableStateOf(prefs.look())
        private set
    val theme: ThemeSpec get() = Themes.byId(look.theme)

    var games by mutableStateOf(emptyList<Game>())
    var hasFiles by mutableStateOf(true)
    var tabIndex by mutableIntStateOf(0)
    private val selByTab = mutableStateMapOf<String, Int>()
    var zone by mutableStateOf(if (Themes.byId(look.theme).layout == Layout.Slices) Zone.Tabs else Zone.Row)
    var actionIndex by mutableIntStateOf(0)
    val menus = mutableStateListOf<Menu>()
    var form by mutableStateOf<TextForm?>(null)
    var toast by mutableStateOf<String?>(null)
    var launching by mutableStateOf<Game?>(null)
    /** Йде анімація запуску лаунчера */
    var booting by mutableStateOf(false)

    fun finishBoot() { booting = false }
    /** Збільшується, коли картинки перезавантажено — щоб Coil не показував старий кеш */
    var artVersion by mutableIntStateOf(0)
    /** Збільшується після дії в меню — значення пунктів (увімк./вимк.) перемальовуються */
    var menuVersion by mutableIntStateOf(0)
    /** Скільки колонок у сітці (виставляє сам layout) */
    var gridColumns by mutableIntStateOf(6)
    private var lastScan = 0L
    private var toastJob: Job? = null

    val artProgress = app.art.progress

    val tabs: List<Tab> by derivedStateOf { buildTabs(games, hasFiles, look) }
    val tab: Tab get() = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    val sel: Int get() = (selByTab[tab.id] ?: 0).coerceIn(0, (tab.items.size - 1).coerceAtLeast(0))
    val current: RowItem? get() = tab.items.getOrNull(sel)
    val currentGame: Game? get() = (current as? RowItem.GameItem)?.game

    // ---------- Бібліотека ----------

    private fun buildTabs(all: List<Game>, files: Boolean, look: Look): List<Tab> {
        val visible = all.filter { !it.hidden }
        val abc = compareBy<Game> { it.title.lowercase() }
        val sorter = when (look.sortMode) {
            "recent" -> compareByDescending<Game> { it.lastPlayed }.then(abc)
            "time" -> compareByDescending<Game> { it.playTime }.then(abc)
            else -> abc
        }
        fun items(list: List<Game>) = list.sortedWith(sorter).map { RowItem.GameItem(it) }

        val systemTabs = Systems.all.mapNotNull { sys ->
            val list = visible.filter { it.system == sys.id }
            if (list.isEmpty()) null else Tab(sys.id, sys.short, items(list))
        }
        val android = Tab(
            Systems.ANDROID, "Android",
            items(visible.filter { it.system == Systems.ANDROID }) +
                RowItem.Action("add_android", tr("Додати Android-гру"), tr("Будь-який встановлений застосунок"), Icons.Filled.Add),
        )
        val cloud = Tab(
            Systems.CLOUD, tr("Хмара"),
            items(visible.filter { it.kind == GameKind.MOONLIGHT }) +
                RowItem.Action("add_cloud", tr("Додати гру з ПК"), tr("Стрім через Moonlight з твого ПК (Sunshine / Apollo / GeForce Experience)"), Icons.Filled.Cloud),
        )
        val favs = visible.filter { it.favorite }.takeIf { it.isNotEmpty() }?.let { Tab("fav", tr("★ Улюблене"), items(it)) }
        val collections = visible.flatMap { it.collections }.distinct().sortedBy { it.lowercase() }.map { name ->
            Tab("col:$name", name, items(visible.filter { name in it.collections }))
        }
        val shown = (listOfNotNull(favs) + collections + systemTabs + android + cloud).filter { it.id !in look.hiddenTabs }

        // «Головна» — лише нещодавні ігри + ярлики на розділи, а не вся бібліотека
        val home = mutableListOf<RowItem>()
        if (!files) home += RowItem.Action("files", tr("Дай доступ до файлів"), tr("Потрібно, щоб знайти ROM-и та передати їх емуляторам"), Icons.Filled.Folder)
        home += visible.filter { it.lastPlayed > 0 }
            .sortedByDescending { it.lastPlayed }
            .take(look.homeCount)
            .map { RowItem.GameItem(it) }
        home += RowItem.Action("store", tr("Магазин"), tr("Безкоштовні ігри та твої джерела"), Icons.Filled.Storefront)
        if (look.homeShortcuts || home.isEmpty()) {
            shown.forEach { t ->
                val count = t.items.count { it is RowItem.GameItem }
                home += RowItem.Action(
                    "tab:${t.id}", Systems.byId[t.id]?.name ?: t.label,
                    if (count > 0) tr("Ігор: %s", count) else tr("Поки порожньо"),
                    if (t.id == Systems.CLOUD) Icons.Filled.Cloud else Icons.Filled.SportsEsports,
                    system = t.id,
                )
            }
        }
        if (visible.isEmpty()) home += RowItem.Action("settings", tr("Налаштуй бібліотеку"), tr("Поклади ігри в %s/<система>/ і натисни «Пересканувати»", prefs.romRoot), Icons.Filled.Settings)

        return listOf(Tab("home", tr("Головна"), home)) + shown
    }

    fun startup() {
        hasFiles = host.hasAllFiles()
        scope.launch {
            // зламані картинки (і чужі обкладинки у звичайних застосунків — один раз при оновленні)
            app.art.repair(clearNonGameApps = prefs.artRules < 4)
            if (prefs.artRules < 3) {
                // у 0.2.0 нечіткий пошук міг дати грі чужу назву — повертаємо назви з файлів
                app.library.restoreAutoTitles()
            }
            prefs.artRules = ART_RULES
            rescan(quiet = true)
        }
    }

    /** Повернулись з гри: записуємо зіграний час */
    fun onReturnFromGame() {
        val since = prefs.inGameSince
        val id = prefs.lastGameId
        prefs.inGameSince = 0
        if (since <= 0 || id.isBlank()) return
        val dur = System.currentTimeMillis() - since
        // < 15 с — гра не запустилась; > 8 год — найімовірніше, гру лишили відкритою
        if (dur < 15_000) return
        scope.launch { app.library.addSession(id, since, dur.coerceAtMost(8 * 3600_000L)) }
    }

    fun onPause() {
        music.pause()
    }

    fun onResume() {
        onReturnFromGame()
        applyMusic(resume = true)
        val had = hasFiles
        hasFiles = host.hasAllFiles()
        launching = null
        if ((hasFiles && !had) || System.currentTimeMillis() - lastScan > 5 * 60_000) {
            scope.launch { rescan(quiet = true) }
        }
    }

    private suspend fun rescan(quiet: Boolean) {
        lastScan = System.currentTimeMillis()
        val n = app.library.rescan()
        if (!quiet) showToast(tr("Знайдено ігор: %s", n))
        app.art.fetchMissing()
        artVersion++
    }

    fun setRomRoot(path: String) {
        prefs.romRoot = path
        showToast(tr("Папка ROM: %s", path))
        scope.launch { rescan(quiet = false) }
    }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch { delay(2800); toast = null }
    }

    private fun refreshLook() {
        look = prefs.look()
        sounds.level = look.volume
        menuVersion++
    }

    // ---------- Музика ----------

    val music = BgMusic(app, scope)

    private fun applyMusic(resume: Boolean = false) {
        music.apply(prefs.music, prefs.musicFile, prefs.musicVolume)
        if (resume && prefs.music != "off" && launching == null) music.resume()
        if (prefs.music == "off") music.pause(200)
    }

    // ---------- Заряд ----------

    var padBattery by mutableStateOf<Int?>(null)
    private var phoneWarned = 100
    private var padWarned = 100

    fun onPhoneBattery(level: Int, charging: Boolean) {
        if (charging) { phoneWarned = 100; return }
        if (!prefs.batteryAlerts) return
        val threshold = listOf(5, 15).firstOrNull { level <= it && phoneWarned > it } ?: return
        phoneWarned = threshold
        alert(tr("🔋 Заряд телефона %s%% — підключи зарядку", level))
    }

    fun startBatteryWatch() = scope.launch {
        while (true) {
            val b = host.controllerBattery()
            padBattery = b
            if (b != null && prefs.batteryAlerts) {
                if (b > 20) padWarned = 100
                val threshold = listOf(5, 15).firstOrNull { b <= it && padWarned > it }
                if (threshold != null) { padWarned = threshold; alert(tr("🎮 Заряд геймпада %s%%", b)) }
            }
            delay(60_000)
        }
    }

    /** Помітне попередження — довше за звичайний тост */
    fun alert(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch { delay(6000); toast = null }
        sounds.play(Sounds.S.Back)
    }

    // ---------- Швидке меню ----------

    var quick by mutableStateOf<QuickState?>(null)

    fun openQuick() {
        menus.clear(); search = null
        quick = QuickState().apply {
            brightness = host.getBrightness()
            volume = host.getVolume()
        }
        padBattery = host.controllerBattery()
        sounds.play(Sounds.S.Tab)
    }

    fun closeQuick() { quick = null; sounds.play(Sounds.S.Back) }

    fun lastGameTitle(): String? {
        if (prefs.lastGamePkg.isBlank()) return null
        return app.library.get(prefs.lastGameId)?.title
    }

    fun adjustQuick(id: String, delta: Float) {
        val q = quick ?: return
        when (id) {
            "brightness" -> { q.brightness = (q.brightness + delta).coerceIn(0.02f, 1f); host.setBrightness(q.brightness) }
            "volume" -> { q.volume = (q.volume + delta).coerceIn(0f, 1f); host.setVolume(q.volume) }
        }
        sounds.play(Sounds.S.Move)
    }

    fun activateQuick(id: String) {
        when (id) {
            "resume" -> { quick = null; music.pause(); if (!app.launcher.resumeLast()) showToast(tr("Гру вже закрито")) }
            "wifi" -> { quick = null; host.openWifi() }
            "bt" -> { quick = null; host.openBluetooth() }
            "search" -> { quick = null; openSearch() }
            "store" -> { quick = null; openStore() }
            "settings" -> { quick = null; openSettings() }
            "android" -> { quick = null; host.goAndroidHome() }
        }
    }

    // ---------- Магазин ----------

    var store by mutableStateOf<StoreUi?>(null)
    /** Збільшується після завантаження — щоб оновились позначки «встановлено» */
    var storeVersion by mutableIntStateOf(0)

    fun openStore() {
        menus.clear(); quick = null
        store = StoreUi()
        sounds.play(Sounds.S.Tab)
        scope.launch {
            app.store.loadCached()
            app.store.refresh()
        }
    }

    fun closeStore() { store = null; sounds.play(Sounds.S.Back) }

    /** A на грі в магазині: завантажити, а якщо вже є — грати */
    fun storeAction(item: dev.orbit.launcher.store.StoreItem) {
        if (item.key in app.store.progress.value) return
        app.store.installedFile(item)?.let { f ->
            val g = games.firstOrNull { it.path == f.absolutePath }
            if (g != null) { closeStore(); play(g); return }
        }
        if (!host.hasAllFiles()) { host.openAllFilesSettings(); return }
        sounds.play(Sounds.S.Select)
        showToast(tr("Завантажую: %s", item.title))
        scope.launch {
            app.store.download(item)
                .onSuccess { f ->
                    app.library.rescan()
                    storeVersion++
                    val g = app.library.games.value.firstOrNull { it.path == f.absolutePath }
                    if (g != null) {
                        // назва з магазину гарніша за ім'я файлу
                        if (!g.titleLocked) app.library.edit(g.id) { it.copy(title = item.title) }
                        app.art.fetchOne(g)
                        // нема картинки з баз — беремо обкладинку з магазину
                        if (app.library.get(g.id)?.cover == null && item.cover != null) app.art.importUrl(g, item.cover)
                        artVersion++
                    }
                    showToast(tr("Готово: %s — вже в бібліотеці", item.title))
                }
                .onFailure { showToast(tr("Не вдалося завантажити: %s", it.message ?: "")) }
        }
    }

    fun openStoreSources() = push(Menu(tr("Джерела магазину")) {
        val counts = app.store.items.value.groupingBy { it.sourceId }.eachCount()
        val errs = app.store.errors.value
        buildList {
            app.store.sources.value.forEach { src ->
                val state = when {
                    !src.enabled -> tr("вимк.")
                    src.id in errs -> tr("помилка")
                    else -> tr("ігор: %s", counts[src.id] ?: 0)
                }
                add(MenuItem(src.name, state) {
                    push(Menu(src.name) {
                        buildList {
                            add(MenuItem(if (src.enabled) tr("Вимкнути") else tr("Увімкнути")) {
                                app.store.setEnabled(src.id, !src.enabled)
                                menus.removeLastOrNull()
                                scope.launch { app.store.refresh() }
                            })
                            add(MenuItem(tr("Адреса"), src.url) {})
                            if (!src.builtin) add(MenuItem(tr("Видалити джерело")) {
                                app.store.remove(src.id); menus.removeLastOrNull()
                            })
                        }
                    })
                })
            }
            add(MenuItem(tr("+ Додати джерело за посиланням")) { openAddSourceForm() })
            add(MenuItem(tr("Оновити каталоги")) { scope.launch { app.store.refresh(); showToast(tr("Каталоги оновлено")) } })
        }
    })

    private fun openAddSourceForm() {
        form = TextForm(
            tr("Нове джерело"),
            tr("Посилання на каталог у форматі Orbit (JSON). Ти відповідаєш за вміст джерел, які додаєш, — завантажуй лише ігри, на які маєш право."),
            listOf(FormField(tr("Посилання (https://…)")), FormField(tr("Назва (необов'язково)"))),
        ) { (url, name) ->
            if (url.isBlank()) return@TextForm
            showToast(tr("Перевіряю джерело…"))
            scope.launch {
                app.store.addSource(url, name.ifBlank { null })
                    .onSuccess { n -> showToast(tr("Джерело додано: ігор %s", n)); menuVersion++ }
                    .onFailure { e ->
                        showToast(
                            if (e is java.io.IOException) tr("Не вдалося відкрити посилання: %s", e.message ?: "")
                            else tr("Це не схоже на каталог Orbit"),
                        )
                    }
            }
        }
    }

    // ---------- Текст через свою клавіатуру ----------

    /** Введення тексту екранною клавіатурою Orbit (керується геймпадом) */
    fun openTextEntry(title: String, initial: String = "", onSubmit: (String) -> Unit) {
        menus.clear()
        search = SearchState(prefs.searchLang, entryTitle = title, onSubmit = onSubmit) { emptyList() }.also { it.query = initial }
        sounds.play(Sounds.S.Tab)
    }

    // ---------- Ввід ----------

    /** Відкритий пошук з екранною клавіатурою */
    var search by mutableStateOf<SearchState?>(null)

    fun openSearch() {
        menus.clear()
        search = SearchState(prefs.searchLang) { games.filter { it.kind != GameKind.ROM || it.path != null } }
        sounds.play(Sounds.S.Tab)
    }

    fun closeSearch() {
        search = null
        sounds.play(Sounds.S.Back)
    }

    fun rememberSearchLang(lang: Int) { prefs.searchLang = lang }

    fun setLanguage(code: String) {
        I18n.lang = code
        prefs.uiLang = code
        // заголовки відкритих меню вже перекладені старою мовою — відкриваємо налаштування заново
        menus.clear()
        openSettings()
    }

    // звуки для пошуку
    fun uiMove() { move() }
    fun uiType() { sounds.play(Sounds.S.Move) }
    fun uiBack() { sounds.play(Sounds.S.Back) }

    fun onButton(b: Btn) {
        if (booting) { finishBoot(); return }
        quick?.let { handleQuickButton(b); return }
        if (b == Btn.Guide && launching == null) { openQuick(); return }
        search?.let { if (launching == null && form == null) { handleSearchButton(it, b); return } }
        if (launching != null) return
        form?.let { f ->
            when (b) {
                Btn.B -> { form = null; sounds.play(Sounds.S.Back) }
                Btn.Start -> submitForm(f)
                else -> Unit
            }
            return
        }
        menus.lastOrNull()?.let { handleMenu(it, b); return }
        store?.let { handleStoreButton(it, b); return }

        // у «Вітрині» на екрані систем гра не вибрана — Y/X нічого не роблять
        val onSystems = theme.layout == Layout.Slices && zone == Zone.Tabs
        // спільні для всіх тем кнопки
        when (b) {
            Btn.L1 -> return switchTab(-1)
            Btn.R1 -> return switchTab(1)
            Btn.Start -> return openSettings()
            Btn.Select -> return openSearch()
            Btn.Y -> { if (!onSystems) currentGame?.let(::openOptions); return }
            Btn.R3 -> { cycleSort(); return }
            Btn.X -> { if (!onSystems) currentGame?.let(::toggleFavorite); return }
            else -> Unit
        }
        when (theme.layout) {
            Layout.Row, Layout.Carousel -> when (zone) {
                Zone.Tabs -> handleTabs(b)
                Zone.Row -> handleRow(b)
                Zone.Actions -> handleActions(b)
            }
            Layout.Xmb, Layout.List, Layout.Blades -> handleVertical(b)
            Layout.Grid, Layout.Channels -> if (zone == Zone.Tabs) handleTabs(b) else handleGrid(b)
            Layout.Slices -> if (zone == Zone.Tabs) handleSystems(b) else handleSliceList(b)
        }
    }

    /** «Вітрина»: відкрити список ігор вибраної системи */
    fun openTabList() {
        if (tab.items.isEmpty()) return
        zone = Zone.Row
        sounds.play(Sounds.S.Select); host.haptic()
    }

    fun backToSystems() {
        zone = Zone.Tabs
        sounds.play(Sounds.S.Back)
    }

    private fun handleSystems(b: Btn) {
        when (b) {
            Btn.Left -> cycleTab(-1)
            Btn.Right -> cycleTab(1)
            Btn.L2 -> switchTab(-4)
            Btn.R2 -> switchTab(4)
            Btn.A, Btn.Down -> openTabList()
            Btn.B -> if (tabIndex != 0) { tabIndex = 0; sounds.play(Sounds.S.Back) }
            else -> Unit
        }
    }

    /** Систему вибирають по колу */
    private fun cycleTab(delta: Int) {
        if (tabs.size > 1) { tabIndex = Math.floorMod(tabIndex + delta, tabs.size); sounds.play(Sounds.S.Tab); host.haptic() }
    }

    private fun handleSliceList(b: Btn) {
        val n = tab.items.size
        when (b) {
            // список по колу
            Btn.Up -> if (n > 1) setSel(Math.floorMod(sel - 1, n))
            Btn.Down -> if (n > 1) setSel(Math.floorMod(sel + 1, n))
            Btn.L2 -> setSel(sel - 8)
            Btn.R2 -> setSel(sel + 8)
            Btn.Left -> switchTab(-1)
            Btn.Right -> switchTab(1)
            Btn.A -> current?.let(::activate)
            Btn.B -> backToSystems()
            else -> Unit
        }
    }

    private fun move() { sounds.play(Sounds.S.Move); host.haptic() }

    fun switchTab(delta: Int) {
        val next = (tabIndex + delta).coerceIn(0, tabs.lastIndex)
        if (next != tabIndex) {
            tabIndex = next
            sounds.play(Sounds.S.Tab); host.haptic()
        }
    }

    fun selectTab(i: Int) {
        tabIndex = i.coerceIn(0, tabs.lastIndex)
        zone = Zone.Row
        sounds.play(Sounds.S.Tab)
    }

    private fun handleTabs(b: Btn) {
        when (b) {
            Btn.Left -> switchTab(-1)
            Btn.Right -> switchTab(1)
            Btn.Down, Btn.A, Btn.B -> { zone = Zone.Row; move() }
            else -> Unit
        }
    }

    private fun backInRow() {
        if (sel > 0) setSel(0)
        else if (tabIndex != 0) { tabIndex = 0; sounds.play(Sounds.S.Back) }
    }

    private fun handleRow(b: Btn) {
        when (b) {
            Btn.Left -> setSel(sel - 1)
            Btn.Right -> setSel(sel + 1)
            Btn.L2 -> setSel(sel - 6)
            Btn.R2 -> setSel(sel + 6)
            Btn.Up -> { zone = Zone.Tabs; move() }
            Btn.Down -> if (currentGame != null) { zone = Zone.Actions; actionIndex = 0; move() }
            Btn.A -> current?.let(::activate)
            Btn.B -> backInRow()
            else -> Unit
        }
    }

    /** XMB і CRT: категорії вліво-вправо, ігри вгору-вниз */
    private fun handleVertical(b: Btn) {
        when (b) {
            Btn.Left -> switchTab(-1)
            Btn.Right -> switchTab(1)
            Btn.Up -> setSel(sel - 1)
            Btn.Down -> setSel(sel + 1)
            Btn.L2 -> setSel(sel - 8)
            Btn.R2 -> setSel(sel + 8)
            Btn.A -> current?.let(::activate)
            Btn.B -> backInRow()
            else -> Unit
        }
    }

    private fun handleGrid(b: Btn) {
        val cols = gridColumns.coerceAtLeast(1)
        when (b) {
            Btn.Left -> setSel(sel - 1)
            Btn.Right -> setSel(sel + 1)
            Btn.Up -> if (sel < cols) { zone = Zone.Tabs; move() } else setSel(sel - cols)
            Btn.Down -> setSel(sel + cols)
            Btn.L2 -> setSel(sel - cols * 3)
            Btn.R2 -> setSel(sel + cols * 3)
            Btn.A -> current?.let(::activate)
            Btn.B -> backInRow()
            else -> Unit
        }
    }

    private fun handleActions(b: Btn) {
        val game = currentGame ?: run { zone = Zone.Row; return }
        when (b) {
            Btn.Left -> if (actionIndex > 0) { actionIndex--; move() }
            Btn.Right -> if (actionIndex < 2) { actionIndex++; move() }
            Btn.Up, Btn.B -> { zone = Zone.Row; move() }
            Btn.A -> when (actionIndex) {
                0 -> play(game)
                1 -> toggleFavorite(game)
                else -> openOptions(game)
            }
            else -> Unit
        }
    }

    fun setSel(i: Int) {
        val n = tab.items.size
        if (n == 0) return
        val next = i.coerceIn(0, n - 1)
        if (next != sel) {
            selByTab[tab.id] = next
            move()
        }
    }

    /** Дотик: перший тап вибирає, другий запускає */
    fun clickItem(i: Int) {
        if (i == sel) tab.items.getOrNull(i)?.let(::activate) else {
            selByTab[tab.id] = i
            zone = Zone.Row
            move()
        }
    }

    /** Довгий дотик — опції гри */
    fun longPressItem(i: Int) {
        selByTab[tab.id] = i
        (tab.items.getOrNull(i) as? RowItem.GameItem)?.game?.let(::openOptions)
    }

    fun activateCurrent() { current?.let(::activate) }

    fun toggleFavoriteCurrent() { currentGame?.let(::toggleFavorite) }

    fun optionsCurrent() { currentGame?.let(::openOptions) }

    private fun activate(item: RowItem) {
        when (item) {
            is RowItem.GameItem -> play(item.game)
            is RowItem.Action -> {
                sounds.play(Sounds.S.Select)
                when {
                    item.id == "files" -> host.openAllFilesSettings()
                    item.id == "settings" -> openSettings()
                    item.id == "add_android" -> openAppPicker()
                    item.id == "add_cloud" -> openMoonlightForm(null)
                    item.id == "store" -> openStore()
                    item.id.startsWith("tab:") -> {
                        val i = tabs.indexOfFirst { it.id == item.id.removePrefix("tab:") }
                        if (i >= 0) { tabIndex = i; zone = Zone.Row }
                    }
                }
            }
        }
    }

    // ---------- Запуск ----------

    fun play(game: Game) {
        app.launcher.check(game)?.let { err ->
            sounds.play(Sounds.S.Back)
            if (err == dev.orbit.launcher.launch.GameLauncher.NEED_FOLDER) {
                showToast(tr("Один раз: вибери папку %s і натисни «Використати цю папку» → «Дозволити»", prefs.romRoot.substringAfterLast('/')))
                host.pickRomFolder()
            } else {
                showToast(err)
            }
            return
        }
        sounds.play(Sounds.S.Select)
        launching = game
    }

    /** Викликається з UI після анімації запуску */
    fun doLaunch(game: Game) {
        music.pause(300)
        when (val r = app.launcher.launch(game)) {
            LaunchResult.Ok -> scope.launch {
                app.library.edit(game.id) { it.copy(lastPlayed = System.currentTimeMillis(), playCount = it.playCount + 1) }
                selByTab["home"] = 0
                // якщо емулятор так і не відкрився — не лишаємо чорний екран
                delay(5000)
                launching = null
            }
            is LaunchResult.Error -> {
                launching = null
                showToast(r.message)
            }
        }
    }

    private fun toggleFavorite(game: Game) {
        sounds.play(Sounds.S.Tab)
        scope.launch { app.library.edit(game.id) { it.copy(favorite = !it.favorite) } }
        showToast(if (game.favorite) tr("Прибрано з улюбленого") else tr("Додано в улюблене"))
    }

    // ---------- Меню ----------

    private fun handleMenu(m: Menu, b: Btn) {
        val items = m.build()
        when (b) {
            // по колу: з останнього пункту — на перший і навпаки
            Btn.Up -> if (items.size > 1) { m.sel = if (m.sel > 0) m.sel - 1 else items.lastIndex; move() }
            Btn.Down -> if (items.size > 1) { m.sel = if (m.sel < items.lastIndex) m.sel + 1 else 0; move() }
            Btn.L1, Btn.L2 -> { m.sel = (m.sel - 8).coerceAtLeast(0); move() }
            Btn.R1, Btn.R2 -> { m.sel = (m.sel + 8).coerceAtMost(items.lastIndex); move() }
            Btn.A -> items.getOrNull(m.sel)?.let { run(it) }
            Btn.B -> closeMenu()
            Btn.Start -> { menus.clear(); sounds.play(Sounds.S.Back) }
            else -> Unit
        }
    }

    private fun run(item: MenuItem) {
        sounds.play(Sounds.S.Select)
        item.action()
        refreshLook()
    }

    fun clickMenuItem(m: Menu, i: Int) {
        m.sel = i
        m.build().getOrNull(i)?.let { run(it) }
    }

    fun closeMenu() {
        menus.removeLastOrNull()
        sounds.play(Sounds.S.Back)
    }

    private fun push(m: Menu) { menus += m }

    private fun live(id: String) = app.library.get(id)

    /** Пункт-перемикач: кожне натискання — наступне значення по колу */
    private fun <T> cycle(label: String, options: List<Pair<T, String>>, current: T, set: (T) -> Unit): MenuItem {
        val i = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        return MenuItem(label, options[i].second) { set(options[(i + 1) % options.size].first) }
    }

    fun openOptions(game: Game) {
        sounds.play(Sounds.S.Tab)
        push(Menu(game.title) {
            val g = live(game.id) ?: return@Menu emptyList()
            buildList {
                add(MenuItem(tr("Грати")) { menus.clear(); play(g) })
                add(MenuItem(if (g.favorite) tr("Прибрати з улюбленого") else tr("Додати в улюблене")) { toggleFavorite(g) })
                if (g.kind == GameKind.ROM) {
                    val list = app.launcher.emulatorsFor(g.system)
                    val emu = app.launcher.emulatorIndexFor(g)?.let { list.getOrNull(it) }
                    add(MenuItem(tr("Емулятор"), (emu?.label ?: tr("немає")) + if (g.emulator == null) tr(" · авто") else "") { openEmulatorPicker(g.system, g) })
                }
                if (g.kind == GameKind.MOONLIGHT) add(MenuItem(tr("Змінити ПК / назву гри")) { openMoonlightForm(g) })
                add(MenuItem(tr("Колекції"), g.collections.joinToString(", ").ifBlank { null }) { openCollectionsFor(g) })
                if (g.playTime > 0) add(MenuItem(tr("Зіграно"), formatDuration(g.playTime)) {})
                add(MenuItem(tr("Перейменувати")) { openRenameForm(g) })
                add(MenuItem(tr("Обкладинка з галереї")) { pickArt(g, "cover") })
                add(MenuItem(tr("Фон з галереї")) { pickArt(g, "hero") })
                add(MenuItem(tr("Логотип з галереї")) { pickArt(g, "logo") })
                add(MenuItem(tr("Знайти картинки автоматично")) {
                    showToast(tr("Шукаю картинки…"))
                    scope.launch {
                        app.art.fetchOne(g)
                        artVersion++
                        showToast(if (live(g.id)?.cover != null) tr("Готово") else tr("Нічого не знайдено — спробуй ключ SteamGridDB"))
                    }
                })
                add(MenuItem(tr("Сховати")) {
                    scope.launch { app.library.edit(g.id) { it.copy(hidden = true) } }
                    menus.clear()
                    showToast(tr("Сховано. Повернути: Налаштування → Бібліотека"))
                })
                if (g.kind != GameKind.ROM) add(MenuItem(tr("Видалити з Orbit")) {
                    scope.launch { app.library.remove(g.id) }
                    menus.clear()
                })
                if (g.kind == GameKind.ROM && g.path != null) add(MenuItem(tr("Видалити гру")) { confirmDelete(g) })
                g.path?.let { add(MenuItem(tr("Файл"), it.substringAfterLast('/')) {}) }
            }
        })
    }

    /** Видалення гри з пам'яті телефона — з підтвердженням */
    private fun confirmDelete(g: Game) {
        val files = dev.orbit.launcher.data.RomScanner.filesOf(g, java.io.File(prefs.romRoot))
        val size = files.sumOf { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length() }
        val what = if (files.size == 1) files[0].name else tr("файлів: %s", files.size)
        push(Menu(tr("Видалити «%s»?", g.title)) {
            listOf(
                MenuItem(tr("Так, видалити"), "$what · ${formatSize(size)}") {
                    menus.clear()
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            files.all { if (it.isDirectory) it.deleteRecursively() else it.delete() || !it.exists() }.also {
                                // картинки гри, що лежать у самому Orbit
                                listOfNotNull(g.cover, g.hero, g.logo).map { java.io.File(it) }
                                    .filter { it.path.startsWith(app.filesDir.path) }.forEach { it.delete() }
                            }
                        }
                        app.library.remove(g.id)
                        sounds.play(Sounds.S.Back)
                        showToast(if (ok) tr("Гру видалено") else tr("Не всі файли вдалося видалити"))
                    }
                },
                MenuItem(tr("Скасувати")) { menus.removeLastOrNull() },
            )
        }.also { it.sel = 1 }) // курсор на «Скасувати», щоб не видалити випадковим подвійним A
    }

    private fun pickArt(g: Game, kind: String) = host.pickImage { uri ->
        scope.launch {
            app.art.importImage(g, uri, kind)
            artVersion++
            showToast(tr("Картинку змінено"))
        }
    }

    /** Вибір емулятора зі списку. game == null — для всієї системи */
    private fun openEmulatorPicker(system: String, game: Game?) {
        val sys = Systems.byId[system] ?: return
        push(Menu(if (game == null) tr("Емулятор для %s", sys.short) else tr("Емулятор: %s", game.title)) {
            val list = app.launcher.emulatorsFor(system)
            val current = if (game != null) live(game.id)?.emulator else prefs.emulatorFor(system)
            buildList {
                add(MenuItem(tr("Автоматично"), if (current == null) tr("✓ перший встановлений") else tr("перший встановлений")) {
                    if (game != null) scope.launch { app.library.edit(game.id) { it.copy(emulator = null) } }
                    else prefs.setEmulatorFor(system, null)
                    menus.removeLastOrNull()
                })
                // встановлені — вгорі списку
                list.withIndex()
                    .sortedByDescending { app.launcher.resolve(it.value) != null }
                    .forEach { (i, emu) ->
                        val installed = app.launcher.resolve(emu) != null
                        val mark = when {
                            current == i -> tr("✓ вибрано")
                            installed -> tr("встановлено")
                            else -> tr("не встановлено")
                        }
                        add(MenuItem(emu.label, mark) {
                            if (!installed) { showToast(tr("%s не встановлено", emu.label)); return@MenuItem }
                            if (game != null) scope.launch { app.library.edit(game.id) { it.copy(emulator = i) } }
                            else prefs.setEmulatorFor(system, i)
                            menus.removeLastOrNull()
                            showToast(tr("Емулятор: %s", emu.label))
                        })
                    }
                add(MenuItem(tr("+ Інший застосунок…"), tr("будь-який встановлений")) { openCustomEmulatorPicker(system) })
            }
        })
    }

    private fun openCustomEmulatorPicker(system: String) {
        val apps = AndroidApps.list(app)
        push(Menu(tr("Свій емулятор для %s", Systems.label(system))) {
            apps.map { a ->
                MenuItem(a.label, a.packageName.takeLast(28)) {
                    // FileProvider дає доступ до файлу без налаштувань у самому емуляторі
                    prefs.addCustomEmulator(system, a.packageName, DataMode.PROVIDER)
                    val idx = app.launcher.emulatorsFor(system).indexOfFirst { it.components.first() == a.packageName }
                    if (idx >= 0) prefs.setEmulatorFor(system, idx)
                    menus.removeLastOrNull()
                    menus.removeLastOrNull()
                    showToast(tr("%s — емулятор для %s", a.label, Systems.label(system)))
                }
            }
        })
    }

    fun openSettings() {
        sounds.play(Sounds.S.Tab)
        push(Menu(tr("Налаштування")) {
            listOf(
                cycle("Мова / Language / Язык", I18n.languages, I18n.lang) { setLanguage(it) },
                MenuItem(tr("Знайти гру"), "Select") { openSearch() },
                MenuItem(tr("Магазин")) { menus.clear(); openStore() },
                MenuItem(tr("Тема"), tr(theme.name)) { openThemePicker() },
                MenuItem(tr("Вигляд")) { openLookMenu() },
                MenuItem(tr("Бібліотека та ігри")) { openLibraryMenu() },
                MenuItem(tr("Емулятори")) { openEmulatorDefaults() },
                MenuItem(tr("Картинки"), if (prefs.steamGridKey.isBlank()) tr("без ключа SteamGridDB") else null) { openArtMenu() },
                MenuItem(tr("Геймпад і запуск")) { openControllerMenu() },
                MenuItem(tr("Версія"), BuildConfig.VERSION_NAME) {},
            )
        })
    }

    private fun openThemePicker() = push(Menu(tr("Тема")) {
        Themes.all.map { t ->
            MenuItem(tr(t.name), if (t.id == look.theme) "✓ " + tr(t.description) else tr(t.description)) {
                prefs.theme = t.id
                zone = if (t.layout == Layout.Slices) Zone.Tabs else Zone.Row
            }
        }
    })

    private fun openIconPicker() = push(Menu(tr("Іконка Orbit")) {
        val now = host.launcherIcon()
        AppIcons.map { (id, name) ->
            MenuItem(tr(name), if (id == now) "✓" else null) {
                if (id != now) {
                    host.setLauncherIcon(id)
                    showToast(tr("Іконка зміниться за кілька секунд"))
                }
            }
        }
    })

    private fun openLookMenu() = push(Menu(tr("Вигляд")) {
        listOf(
            MenuItem(tr("Тема"), tr(theme.name)) { openThemePicker() },
            cycle(tr("Акцентний колір"), AccentChoices.map { it.first to tr(it.second) }, prefs.accent) { prefs.accent = it },
            cycle(
                tr("Фон"),
                listOf(
                    "auto" to tr("як у теми"), "art" to tr("арт гри"), "blur" to tr("розмитий арт"),
                    "animated" to tr("анімований"), "waves" to tr("хвилі"), "plain" to tr("однотонний"), "photo" to tr("своє фото"),
                ),
                prefs.background,
            ) { prefs.background = it },
            MenuItem(tr("Своє фото на фон"), if (prefs.bgPhoto.isBlank()) tr("не вибрано") else tr("вибрано ✓")) {
                host.pickImage { uri ->
                    scope.launch {
                        app.art.importWallpaper(uri)?.let { prefs.bgPhoto = it; prefs.background = "photo"; refreshLook() }
                    }
                }
            },
            cycle(tr("Форма плиток"), listOf("square" to tr("квадрат"), "portrait" to tr("обкладинка 2:3"), "wide" to tr("широкі 16:9")), prefs.tileShape) { prefs.tileShape = it },
            cycle(tr("Розмір плиток"), listOf(0 to tr("малі"), 1 to tr("середні"), 2 to tr("великі")), prefs.tileSize) { prefs.tileSize = it },
            cycle(
                tr("Анімація запуску"),
                listOf("warp" to tr("гіперстрибок"), "portal" to tr("портал"), "ps5" to tr("наїзд камери"), "none" to tr("без анімації")),
                prefs.launchAnim,
            ) { prefs.launchAnim = it },
            cycle(
                tr("Анімація запуску лаунчера"),
                BootStyles.map { it.first to tr(it.second) } + ("off" to tr("вимк.")),
                if (prefs.bootIntro) prefs.bootStyle else "off",
            ) {
                prefs.bootIntro = it != "off"
                if (it != "off") prefs.bootStyle = it
            },
            MenuItem(tr("Показати анімацію запуску")) { menus.clear(); booting = true },
            MenuItem(tr("Іконка Orbit"), tr(AppIcons.firstOrNull { it.first == host.launcherIcon() }?.second ?: "")) { openIconPicker() },
            cycle(tr("Колір з обкладинки гри"), listOf(false to tr("вимк."), true to tr("увімк.")), prefs.coverAccent) { prefs.coverAccent = it },
            cycle(tr("Звуки"), listOf(2 to tr("нормально"), 1 to tr("тихо"), 0 to tr("вимк.")), prefs.volume) { prefs.volume = it },
            cycle(tr("Фонова музика"), listOf("off" to tr("вимк."), "ambient" to tr("ембієнт Orbit"), "file" to tr("свій трек")), prefs.music) { m ->
                if (m == "file" && prefs.musicFile.isBlank()) pickMusic() else { prefs.music = m; applyMusic(resume = true) }
            },
            MenuItem(tr("Свій трек"), if (prefs.musicFile.isBlank()) tr("не вибрано") else tr("вибрано ✓")) { pickMusic() },
            cycle(tr("Гучність музики"), (1..10).map { it to "$it" }, prefs.musicVolume) { prefs.musicVolume = it; applyMusic() },
            cycle(tr("Годинник"), listOf(true to tr("24 год"), false to tr("12 год")), prefs.clock24) { prefs.clock24 = it },
        )
    })

    private fun openLibraryMenu() = push(Menu(tr("Бібліотека та ігри")) {
        buildList {
            add(MenuItem(tr("Доступ до файлів"), if (host.hasAllFiles()) tr("є ✓") else tr("немає — натисни")) { host.openAllFilesSettings() })
            add(MenuItem(tr("Папка з іграми"), prefs.romRoot) { host.pickRomFolder() })
            add(MenuItem(tr("Доступ емуляторів до папки"), if (app.launcher.hasFolderAccess()) tr("є ✓") else tr("немає — натисни")) { host.pickRomFolder() })
            add(MenuItem(tr("Пересканувати ігри")) { menus.clear(); scope.launch { rescan(quiet = false) } })
            add(cycle(tr("Нещодавніх на «Головній»"), listOf(6 to "6", 12 to "12", 20 to "20", 0 to tr("не показувати")), prefs.homeCount) { prefs.homeCount = it })
            add(cycle(tr("Ярлики розділів на «Головній»"), listOf(true to tr("так"), false to tr("ні")), prefs.homeShortcuts) { prefs.homeShortcuts = it })
            add(MenuItem(tr("Вкладки зверху")) { openTabsMenu() })
            add(MenuItem(tr("Додати Android-гру")) { openAppPicker() })
            add(MenuItem(tr("Додати гру з ПК (Moonlight)")) { openMoonlightForm(null) })
            val hidden = games.count { it.hidden }
            add(MenuItem(tr("Приховані ігри"), "${hidden}") { if (hidden > 0) openHidden() })
            add(cycle(tr("Сортування (R3)"), sortModes, prefs.sortMode) { prefs.sortMode = it })
            add(MenuItem(tr("Колекції")) { openCollectionsManager() })
            add(MenuItem(tr("Статистика")) { openStats() })
            add(cycle(tr("DuckStation: продовжувати з автозбереження"), listOf(false to tr("ні"), true to tr("так")), prefs.resumeState) { prefs.resumeState = it })
        }
    })

    private fun openTabsMenu() = push(Menu(tr("Вкладки зверху")) {
        val ids = listOf("fav" to tr("★ Улюблене")) +
            Systems.all.filter { s -> games.any { it.system == s.id } }.map { it.id to it.name } +
            listOf(Systems.ANDROID to "Android", Systems.CLOUD to tr("Хмара (Moonlight)"))
        ids.map { (id, name) ->
            val hidden = id in prefs.hiddenTabs
            MenuItem(name, if (hidden) tr("приховано") else tr("показано")) {
                prefs.hiddenTabs = if (hidden) prefs.hiddenTabs - id else prefs.hiddenTabs + id
                tabIndex = 0
            }
        }
    })

    private fun openArtMenu() = push(Menu(tr("Картинки")) {
        listOf(
            MenuItem(tr("Ключ SteamGridDB"), if (prefs.steamGridKey.isBlank()) tr("не задано — дуже раджу") else "••••" + prefs.steamGridKey.takeLast(4)) { openKeyForm() },
            MenuItem(tr("Завантажити відсутні картинки")) {
                menus.clear(); showToast(tr("Завантажую картинки…"))
                scope.launch {
                    games.filter { it.cover == null && !it.customArt }.forEach { g -> app.library.edit(g.id) { it.copy(artChecked = false) } }
                    app.art.fetchMissing(); artVersion++
                    val missing = games.count { it.cover == null && it.kind == GameKind.ROM }
                    showToast(if (missing == 0) tr("Картинки оновлено") else tr("Без картинки лишилось: %s", missing))
                }
            },
            MenuItem(tr("Перезавантажити всі картинки")) {
                menus.clear(); showToast(tr("Перезавантажую всі картинки…"))
                scope.launch { app.art.fetchMissing(force = true); artVersion++; showToast(tr("Готово")) }
            },
        )
    })

    private fun pickMusic() = host.pickAudio { uri ->
        prefs.musicFile = uri.toString()
        prefs.music = "file"
        applyMusic(resume = true)
        refreshLook()
        showToast(tr("Музику вибрано"))
    }

    private fun openControllerMenu() = push(Menu(tr("Геймпад і запуск")) {
        listOf(
            cycle(tr("Нагадування про заряд"), listOf(true to tr("увімк."), false to tr("вимк.")), prefs.batteryAlerts) { prefs.batteryAlerts = it },
            MenuItem(tr("Швидке меню"), tr("кнопка Home / Guide")) { menus.clear(); openQuick() },
            cycle(tr("Автостарт при підключенні геймпада"), listOf(true to tr("увімк."), false to tr("вимк.")), prefs.autostart) { prefs.autostart = it },
            MenuItem(tr("Геймпад для автостарту"), prefs.boundController.ifBlank { tr("будь-який") }) { openControllerBinding() },
            cycle(tr("Orbit як домашній екран"), listOf(true to tr("увімк."), false to tr("вимк.")), host.isHomeEnabled()) { host.setHomeEnabled(it) },
        )
    })

    private fun openEmulatorDefaults() = push(Menu(tr("Емулятори за замовчуванням")) {
        Systems.all.map { sys ->
            val list = app.launcher.emulatorsFor(sys.id)
            val chosen = prefs.emulatorFor(sys.id)?.takeIf { it in list.indices }
            val idx = chosen ?: list.indexOfFirst { app.launcher.resolve(it) != null }
            val emu = list.getOrNull(idx)
            val value = when {
                emu == null -> tr("жоден не встановлено")
                app.launcher.resolve(emu) == null -> "${emu.label} ✗"
                else -> emu.label
            } + if (chosen == null && emu != null) tr(" · авто") else ""
            MenuItem(sys.name, value) { openEmulatorPicker(sys.id, null) }
        }
    })

    private fun openControllerBinding() = push(Menu(tr("Геймпад для автостарту")) {
        buildList {
            add(MenuItem(tr("Будь-який геймпад"), if (prefs.boundController.isBlank()) "✓" else null) {
                prefs.boundController = ""; menus.removeLastOrNull()
            })
            val pads = host.usbGamepads()
            if (pads.isEmpty()) add(MenuItem(tr("Підключи геймпад, щоб прив'язати саме його")) {})
            pads.forEach { (id, name) ->
                add(MenuItem(name, if (prefs.boundController == id) "${id} ✓" else id) {
                    prefs.boundController = id; showToast(tr("Прив'язано: %s", name)); menus.removeLastOrNull()
                })
            }
        }
    })

    private fun openHidden() = push(Menu(tr("Приховані ігри")) {
        games.filter { it.hidden }.map { g ->
            MenuItem(g.title, Systems.label(g.system)) {
                scope.launch { app.library.edit(g.id) { it.copy(hidden = false) } }
            }
        }
    })

    private fun openAppPicker() {
        val existing = games.map { it.id }.toSet()
        val apps = AndroidApps.list(app).filter { "app:${it.packageName}" !in existing }
        push(Menu(tr("Додати Android-гру")) {
            val nowExisting = games.map { it.id }.toSet()
            apps.map { a ->
                val added = "app:${a.packageName}" in nowExisting
                MenuItem(a.label, if (added) tr("додано ✓") else if (a.isGame) tr("гра") else null) {
                    if (!added) {
                        val g = AndroidApps.toGame(a)
                        scope.launch {
                            app.library.upsert(g)
                            app.art.fetchOne(g); artVersion++
                        }
                        showToast(tr("Додано: %s", a.label))
                    }
                }
            }
        })
    }

    // ---------- Форми з текстом ----------

    private fun openKeyForm() {
        form = TextForm(
            tr("Ключ SteamGridDB"),
            tr("Безкоштовно: steamgriddb.com → увійти через Steam → Preferences → API → Generate. ") +
                tr("Дає квадратні обкладинки, великі фони та логотипи для будь-яких ігор, зокрема Android і Moonlight."),
            listOf(FormField("API key", prefs.steamGridKey, secret = true)),
        ) { (key) ->
            prefs.steamGridKey = key
            refreshLook()
            showToast(tr("Ключ збережено, шукаю картинки…"))
            scope.launch {
                games.filter { !it.customArt && (it.hero == null || it.logo == null) }
                    .forEach { g -> app.library.edit(g.id) { it.copy(artChecked = false) } }
                app.art.fetchMissing(); artVersion++
                showToast(tr("Картинки оновлено"))
            }
        }
    }

    private fun openSearchForm() {
        form = TextForm(tr("Знайти гру"), tr("Шукає за назвою та за ім'ям файлу, зокрема серед прихованих."), listOf(FormField(tr("Назва або файл")))) { (q) ->
            if (q.isBlank()) return@TextForm
            val words = q.lowercase().split(' ').filter { it.isNotBlank() }
            val found = games.filter { g ->
                val hay = (g.title + " " + (g.path?.substringAfterLast('/') ?: "") + " " + (g.packageName ?: "")).lowercase()
                words.all { it in hay }
            }.sortedBy { it.title.lowercase() }
            if (found.isEmpty()) { showToast(tr("Нічого не знайдено: %s", q)); return@TextForm }
            push(Menu(tr("Знайдено: %s", found.size)) {
                found.map { g ->
                    MenuItem(g.title, Systems.label(g.system) + (if (g.hidden) tr(" · сховано") else "") + (g.path?.let { " · " + it.substringAfterLast('/') } ?: "")) {
                        menus.clear()
                        showGame(g)
                    }
                }
            })
        }
    }

    /** Перейти до гри у її вкладці (і показати, якщо була схована) */
    fun goToGame(g: Game) = showGame(g)

    private fun showGame(g: Game) {
        scope.launch {
            if (g.hidden) app.library.edit(g.id) { it.copy(hidden = false) }
            delay(50)
            val ti = tabs.indexOfFirst { t -> t.id != "home" && t.items.any { it.key == g.id } }
            if (ti >= 0) {
                tabIndex = ti
                selByTab[tabs[ti].id] = tabs[ti].items.indexOfFirst { it.key == g.id }
                zone = Zone.Row
            } else showToast(tr("Вкладка %s прихована — увімкни її в Бібліотека → Вкладки", Systems.label(g.system)))
        }
    }

    private fun openRenameForm(g: Game) {
        openTextEntry(tr("Перейменувати"), g.title) { title ->
            if (title.isNotBlank()) scope.launch { app.library.edit(g.id) { it.copy(title = title.trim(), titleLocked = true) } }
        }
    }

    // ---------- Колекції ----------

    private fun allCollections() = games.flatMap { it.collections }.distinct().sortedBy { it.lowercase() }

    private fun openCollectionsFor(g: Game) = push(Menu(tr("Колекції")) {
        val cur = live(g.id)?.collections.orEmpty()
        buildList {
            allCollections().forEach { name ->
                val inside = name in cur
                add(MenuItem(name, if (inside) "✓" else null) {
                    scope.launch { app.library.edit(g.id) { it.copy(collections = if (inside) it.collections - name else it.collections + name) } }
                })
            }
            add(MenuItem(tr("+ Нова колекція…")) {
                openTextEntry(tr("Назва колекції")) { name ->
                    val n = name.trim()
                    if (n.isNotEmpty()) scope.launch { app.library.edit(g.id) { it.copy(collections = (it.collections + n).distinct()) } }
                    showToast(tr("Додано в «%s»", n))
                }
            })
        }
    })

    private fun openCollectionsManager() = push(Menu(tr("Колекції")) {
        val list = allCollections()
        if (list.isEmpty()) listOf(MenuItem(tr("Поки немає — додай гру через Y → Колекції")) {})
        else list.map { name ->
            MenuItem(name, tr("Ігор: %s", games.count { name in it.collections })) {
                push(Menu(name) {
                    listOf(
                        MenuItem(tr("Перейменувати")) {
                            openTextEntry(tr("Назва колекції"), name) { nn ->
                                val n = nn.trim()
                                if (n.isNotEmpty()) scope.launch {
                                    games.filter { name in it.collections }.forEach { g ->
                                        app.library.edit(g.id) { it.copy(collections = it.collections.map { c -> if (c == name) n else c }.distinct()) }
                                    }
                                }
                            }
                        },
                        MenuItem(tr("Видалити колекцію"), tr("ігри лишаться")) {
                            scope.launch {
                                games.filter { name in it.collections }.forEach { g -> app.library.edit(g.id) { it.copy(collections = it.collections - name) } }
                            }
                            menus.removeLastOrNull()
                        },
                    )
                })
            }
        }
    })

    // ---------- Сортування і статистика ----------

    private val sortModes get() = listOf("abc" to tr("за абеткою"), "recent" to tr("нещодавні"), "time" to tr("найбільше граю"))

    private fun cycleSort() {
        val modes = sortModes
        val i = modes.indexOfFirst { it.first == prefs.sortMode }
        val next = modes[(i + 1) % modes.size]
        prefs.sortMode = next.first
        refreshLook()
        showToast(tr("Сортування: %s", next.second))
    }

    private fun openStats() = push(Menu(tr("Статистика")) {
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
        val week = app.library.sessions.filter { it.start >= weekAgo }
        val weekByGame = week.groupBy { it.gameId }.mapValues { (_, v) -> v.sumOf { it.duration } }
        buildList {
            add(MenuItem(tr("Цього тижня"), formatDuration(week.sumOf { it.duration })) {})
            add(MenuItem(tr("За весь час"), formatDuration(games.sumOf { it.playTime })) {})
            if (weekByGame.isNotEmpty()) add(MenuItem(tr("— Найбільше цього тижня —")) {})
            weekByGame.entries.sortedByDescending { it.value }.take(8).forEach { (id, t) ->
                val g = live(id) ?: return@forEach
                add(MenuItem(g.title, formatDuration(t)) { menus.clear(); goToGame(g) })
            }
            val top = games.filter { it.playTime > 0 }.sortedByDescending { it.playTime }.take(8)
            if (top.isNotEmpty()) add(MenuItem(tr("— Найбільше за весь час —")) {})
            top.forEach { g -> add(MenuItem(g.title, formatDuration(g.playTime)) { menus.clear(); goToGame(g) }) }
        }
    })

    private fun openMoonlightForm(existing: Game?) {
        form = TextForm(
            if (existing == null) tr("Гра з ПК через Moonlight") else tr("Змінити гру Moonlight"),
            tr("Назви мають точно збігатися з тим, що показує Moonlight: ім'я ПК і назва програми в Sunshine/Apollo ") +
                tr("(наприклад «Desktop», «Steam Big Picture» або конкретна гра)."),
            listOf(
                FormField(tr("Назва ПК у Moonlight"), existing?.pcName ?: games.firstOrNull { it.pcName != null }?.pcName ?: ""),
                FormField(tr("Назва гри / програми"), existing?.appName ?: ""),
            ),
        ) { (pc, appName) ->
            if (pc.isBlank() || appName.isBlank()) { showToast(tr("Заповни обидва поля")); return@TextForm }
            val g = (existing ?: Game(id = "ml:${pc}/${appName}", title = appName, kind = GameKind.MOONLIGHT, system = Systems.CLOUD))
                .copy(pcName = pc.trim(), appName = appName.trim(), title = appName.trim(), artChecked = false)
            scope.launch {
                app.library.upsert(g)
                app.art.fetchOne(g); artVersion++
            }
            menus.clear()
            tabIndex = tabs.indexOfFirst { it.id == Systems.CLOUD }.coerceAtLeast(0)
            showToast(tr("Додано: %s", g.title))
        }
    }

    fun submitForm(f: TextForm) {
        form = null
        sounds.play(Sounds.S.Select)
        f.onSave(f.fields.map { it.value.trim() })
    }
}

/** 8_100_000 мс -> «2 год 15 хв» */
fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> tr("менше хвилини")
        min < 60 -> tr("%s хв", min)
        min % 60 == 0L -> tr("%s год", min / 60)
        else -> tr("%s год %s хв", min / 60, min % 60)
    }
}
