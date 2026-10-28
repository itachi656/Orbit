package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.orbit.launcher.data.Game
import dev.orbit.launcher.data.Systems
import dev.orbit.launcher.input.Btn
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Клавіша екранної клавіатури */
sealed class Key {
    data class Ch(val c: Char) : Key()
    data object Lang : Key()
    data object Space : Key()
    data object Back : Key()
    data object Clear : Key()
    data object Done : Key()
}

/**
 * Пошук з власною клавіатурою, яку зручно водити геймпадом
 * (системна клавіатура Android під геймпад не пристосована).
 */
class SearchState(
    initialLang: Int = 0,
    /** Режим введення тексту (не пошук): заголовок і що робити з введеним */
    val entryTitle: String? = null,
    val onSubmit: ((String) -> Unit)? = null,
    private val all: () -> List<Game>,
) {
    val isEntry get() = onSubmit != null
    var query by mutableStateOf("")
    /** 0 — English, 1 — українська, 2 — русский */
    var lang by mutableIntStateOf(initialLang.coerceIn(0, LAYOUTS.lastIndex))
    var row by mutableIntStateOf(1)
    var col by mutableIntStateOf(0)
    var inResults by mutableStateOf(false)
    var resultSel by mutableIntStateOf(0)

    val rows: List<List<Key>> get() = LAYOUTS[lang].map { r -> r.map { Key.Ch(it) } } + listOf(SPECIAL)

    val results: List<Game> by derivedStateOf { rank(all(), query) }

    fun type(c: Char) { if (query.length < 40) query += c }
    fun backspace() { query = query.dropLast(1) }

    /** Перехід між рядами різної довжини — зберігаємо позицію пропорційно */
    fun moveRow(delta: Int) {
        val old = rows[row]
        val nr = (row + delta).coerceIn(0, rows.lastIndex)
        if (nr == row) return
        val new = rows[nr]
        col = if (old.size <= 1) 0 else (col.toFloat() * (new.size - 1) / (old.size - 1)).roundToInt().coerceIn(0, new.lastIndex)
        row = nr
    }

    fun moveCol(delta: Int) {
        val n = rows[row].size
        col = ((col + delta) % n + n) % n
    }

    companion object {
        private val EN = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm-.:")
        private val UA = listOf("1234567890", "йцукенгшщзхї", "фівапролджє", "ячсмитьбюґ'")
        private val RU = listOf("1234567890", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё-")
        val LAYOUTS = listOf(EN, UA, RU)
        val LANG_NAMES = listOf("EN", "УКР", "РУС")
        private val SPECIAL = listOf(Key.Lang, Key.Space, Key.Back, Key.Clear, Key.Done)

        private val nonWord = Regex("[^\\p{L}\\p{N}]+")
        private fun words(s: String) = s.lowercase().replace(nonWord, " ").trim().split(' ').filter { it.isNotEmpty() }

        /** Пошук: усі слова мають знайтись; вище — збіг з початку назви та абревіатура (mhfu) */
        fun rank(games: List<Game>, query: String): List<Game> {
            val q = words(query)
            if (q.isEmpty()) return games.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(20)
            val joined = q.joinToString("")
            return games.mapNotNull { g ->
                val tw = words(g.title)
                val fileWords = words(g.path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "")
                val sysWords = words(Systems.label(g.system) + " " + (Systems.byId[g.system]?.name ?: ""))
                val hayWords = tw + fileWords + sysWords
                val initials = tw.joinToString("") { it.take(1) }
                // кожне слово запиту — початок якогось слова в назві / файлі / системі
                val all = q.all { w -> hayWords.any { it.startsWith(w) } }
                val acronym = joined.length >= 2 && initials.startsWith(joined)
                if (!all && !acronym) return@mapNotNull null
                val title = tw.joinToString(" ")
                val score = when {
                    title.startsWith(q.joinToString(" ")) -> 5.0
                    acronym -> 4.0
                    tw.any { it.startsWith(q.first()) } -> 3.0
                    q.all { it in title } -> 2.0
                    else -> 1.0
                } + (if (g.lastPlayed > 0) 0.3 else 0.0) + (if (g.favorite) 0.2 else 0.0)
                g to score
            }.sortedWith(compareByDescending<Pair<Game, Double>> { it.second }.thenBy { it.first.title.lowercase() })
                .take(60).map { it.first }
        }
    }
}

/** Обробка кнопок у пошуку. Повертає true, якщо пошук треба закрити */
fun HomeController.handleSearchButton(s: SearchState, b: Btn) {
    if (s.inResults) {
        val res = s.results
        when (b) {
            Btn.Up -> if (s.resultSel > 0) { s.resultSel--; uiMove() } else { s.inResults = false; s.row = s.rows.lastIndex; uiMove() }
            Btn.Down -> if (s.resultSel < res.lastIndex) { s.resultSel++; uiMove() }
            Btn.Left, Btn.B, Btn.L1 -> { s.inResults = false; uiMove() }
            Btn.A -> res.getOrNull(s.resultSel)?.let { closeSearch(); goToGame(it) }
            Btn.Start -> res.getOrNull(s.resultSel)?.let { closeSearch(); play(it) }
            Btn.Y -> res.getOrNull(s.resultSel)?.let { closeSearch(); openOptions(it) }
            Btn.Select -> closeSearch()
            else -> Unit
        }
        return
    }
    when (b) {
        Btn.Up -> { s.moveRow(-1); uiMove() }
        Btn.Down -> { s.moveRow(1); uiMove() }
        Btn.Left -> { s.moveCol(-1); uiMove() }
        Btn.Right -> { s.moveCol(1); uiMove() }
        Btn.A -> pressKey(s, s.rows[s.row][s.col])
        Btn.B -> if (s.query.isEmpty()) closeSearch() else { s.backspace(); uiBack() }
        Btn.X -> pressKey(s, Key.Space)
        Btn.Y -> pressKey(s, Key.Lang)
        Btn.L2 -> { s.backspace(); uiBack() }
        Btn.R1, Btn.R2, Btn.Start -> if (s.isEntry) submitEntry(s) else if (s.results.isNotEmpty()) { s.inResults = true; s.resultSel = 0; uiMove() }
        Btn.Select -> closeSearch()
        else -> Unit
    }
}

fun HomeController.submitEntry(s: SearchState) {
    val text = s.query.trim()
    search = null
    uiType()
    s.onSubmit?.invoke(text)
}

fun HomeController.pressKey(s: SearchState, k: Key) {
    when (k) {
        is Key.Ch -> { s.type(k.c); uiType() }
        Key.Space -> { if (s.query.isNotEmpty() && !s.query.endsWith(' ')) s.type(' '); uiType() }
        Key.Back -> { s.backspace(); uiBack() }
        Key.Clear -> { s.query = ""; uiBack() }
        Key.Lang -> {
            s.lang = (s.lang + 1) % SearchState.LAYOUTS.size
            s.col = s.col.coerceAtMost(s.rows[s.row].lastIndex)
            rememberSearchLang(s.lang)
            uiMove()
        }
        Key.Done -> if (s.isEntry) submitEntry(s) else if (s.results.isNotEmpty()) { s.inResults = true; s.resultSel = 0; uiMove() } else closeSearch()
    }
}

// =====================================================================================

@Composable
fun SearchOverlay(c: HomeController, s: SearchState, accent: Color) {
    val theme = LocalTheme.current
    val on = if (theme.isLight) theme.text else Color.White
    val onSel = if (theme.isLight) Color.White else Color.Black
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530); cursor = !cursor } }
    val results = s.results

    Box(
        Modifier.fillMaxSize().background(theme.bg.copy(alpha = 0.99f))
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)) {
            // рядок запиту
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹", color = theme.text, fontSize = 30.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { c.closeSearch() }.padding(horizontal = 10.dp),
                )
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                        .background(on.copy(alpha = 0.08f))
                        .border(2.dp, if (!s.inResults) accent else on.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = theme.dim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (s.query.isEmpty() && !s.isEntry) tr("Назва гри, файл або абревіатура (mhfu)…") else s.query + if (cursor) "│" else " ",
                        color = if (s.query.isEmpty()) theme.dim else theme.text,
                        fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        when {
                            s.isEntry -> s.entryTitle ?: ""
                            s.query.isEmpty() -> tr("Нещодавні")
                            else -> tr("Знайдено: %s", results.size)
                        },
                        color = theme.dim, fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth().weight(1f)) {
                // клавіатура
                Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    s.rows.forEachIndexed { r, keys ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            keys.forEachIndexed { k, key ->
                                val focused = !s.inResults && s.row == r && s.col == k
                                val w = when (key) { Key.Space -> 2.6f; Key.Lang, Key.Clear, Key.Done -> 1.5f; else -> 1f }
                                KeyCap(
                                    key, s.lang, focused, accent, on, onSel,
                                    Modifier.weight(w).fillMaxHeight(),
                                ) {
                                    s.inResults = false; s.row = r; s.col = k
                                    c.pressKey(s, key)
                                }
                            }
                        }
                    }
                    Text(
                        tr("A ввести · B стерти · X пробіл · Y мова (%s) · RB до результатів · Select закрити", SearchState.LANG_NAMES[s.lang]),
                        color = theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(18.dp))
                // результати
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(s.resultSel, s.inResults) {
                        if (s.inResults) listState.animateScrollToItem((s.resultSel - 2).coerceAtLeast(0))
                    }
                    LaunchedEffect(s.query) { s.resultSel = 0; listState.scrollToItem(0) }
                    if (s.isEntry) {
                        Text(s.entryTitle ?: "", color = theme.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            tr("Введи текст і натисни «Готово» або ☰"),
                            color = theme.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                    } else if (results.isEmpty()) {
                        Text(
                            if (s.query.isEmpty()) tr("Почни вводити назву") else tr("Нічого не знайдено"),
                            color = theme.dim, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                        itemsIndexed(results, key = { _, g -> g.id }) { i, g ->
                            val sel = s.inResults && i == s.resultSel
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) on else Color.Transparent)
                                    .clickable { c.closeSearch(); c.goToGame(g) }
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp))) { GameArt(g, c.artVersion, big = false) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.title, color = if (sel) onSel else theme.text, fontSize = 15.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        Systems.byId[g.system]?.name ?: Systems.label(g.system),
                                        color = if (sel) onSel.copy(alpha = 0.65f) else theme.dim, fontSize = 11.sp, maxLines = 1,
                                    )
                                }
                                if (sel) Text(tr("A перейти · ☰ грати"), color = onSel.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: Key, lang: Int, focused: Boolean, accent: Color, on: Color, onSel: Color, modifier: Modifier, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(110), label = "key")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) accent else on.copy(alpha = 0.09f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (focused) (if (accent.luminanceApprox() > 0.6f) Color.Black else Color.White) else on
        when (key) {
            is Key.Ch -> Text(key.c.uppercase(), color = fg, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Key.Back -> Icon(Icons.AutoMirrored.Filled.Backspace, tr("Стерти"), tint = fg, modifier = Modifier.size(20.dp))
            Key.Space -> Text(tr("Пробіл"), color = fg, fontSize = 14.sp)
            Key.Clear -> Text(tr("Очистити"), color = fg, fontSize = 13.sp, textAlign = TextAlign.Center)
            Key.Done -> Text(tr("Готово"), color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Key.Lang -> {
                val names = SearchState.LANG_NAMES
                Text("${names[lang]} → ${names[(lang + 1) % names.size]}", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun Color.luminanceApprox() = 0.299f * red + 0.587f * green + 0.114f * blue
