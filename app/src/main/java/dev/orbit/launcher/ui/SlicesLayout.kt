package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.orbit.launcher.data.Systems
import kotlin.math.ceil
import kotlin.math.roundToInt

// =====================================================================================
// Вітрина (як ES-DE): вибір системи на тлі нахилених смуг з артами її ігор,
// A — список ігор системи, B — назад до систем
// =====================================================================================

@Composable
fun SlicesLayout(c: HomeController, accent: Color) {
    val ti = c.tabIndex.coerceIn(0, c.tabs.lastIndex)
    val inList = c.zone != Zone.Tabs
    val dim by androidx.compose.animation.core.animateFloatAsState(if (inList) 0.76f else 0.5f, tween(400), label = "dim")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(ti, animationSpec = tween(450), label = "slices") { i ->
            c.tabs.getOrNull(i)?.let { SliceStrips(c, it) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))

        Crossfade(inList, animationSpec = tween(300), label = "view") { list ->
            if (list) SliceGameList(c, accent) else SystemPicker(c, accent)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 24.dp)) { Clock(c.look.clock24) }
        SliceHints(c, inList, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}

/** Паралелограм, верх зсунутий праворуч на [skew] */
private fun slantShape(skew: Float): Shape = GenericShape { size, _ ->
    moveTo(skew, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - skew, size.height)
    lineTo(0f, size.height)
    close()
}

/** Смуги з артами ігор, що повільно пливуть вліво */
@Composable
private fun SliceStrips(c: HomeController, tab: Tab) {
    val arts = remember(tab, c.games, c.artVersion) {
        val pool = if (tab.id == "home") c.games.filter { !it.hidden } else tab.items.mapNotNull { (it as? RowItem.GameItem)?.game }
        val rnd = java.util.Random(tab.id.hashCode().toLong())
        pool.mapNotNull { it.hero ?: it.cover }.distinct().shuffled(rnd).take(9)
    }
    // без картинок — смуги в кольорах системи з крапками
    val base = Color(Systems.accent(tab.id))
    // мало картинок — чергуємо їх з кольоровими смугами, щоб не було шпалер з однієї гри
    val sparse = arts.size < 4
    val n = when {
        arts.isEmpty() -> 7
        sparse -> (arts.size * 2).coerceAtLeast(6)
        else -> arts.size.coerceAtLeast(5)
    }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val w = maxWidth / 6.6f
        val skewDp = maxHeight * 0.16f
        val wPx = with(density) { w.toPx() }
        val skew = with(density) { skewDp.toPx() }
        val period = wPx * n
        val reps = ceil((with(density) { maxWidth.toPx() } + skew + period) / period).toInt() + 1
        val shape = remember(skew) { slantShape(skew) }

        val drift = rememberInfiniteTransition(label = "drift")
        val phase by drift.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween((n * 9000), easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )

        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -phase * period - wPx }) {
            for (k in 0 until n * reps) {
                val slot = k % n
                Box(
                    Modifier
                        .offset { IntOffset((k * wPx).roundToInt(), 0) }
                        .width(w + skewDp - 3.dp)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(lerpDark(base, slot)),
                ) {
                    val path = when {
                        arts.isEmpty() -> null
                        sparse -> if (slot % 2 == 0) arts[(slot / 2) % arts.size] else null
                        else -> arts[slot % arts.size]
                    }
                    if (path != null) {
                        // кожна смуга показує свою частину картинки
                        AsyncImage(
                            model = artRequest(path, c.artVersion), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = BiasAlignment((slot % 3 - 1) * 0.9f, 0f),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Dots()
                    }
                }
            }
        }
    }
}

private fun lerpDark(c: Color, i: Int): Color {
    val f = listOf(0.55f, 0.32f, 0.45f, 0.25f, 0.4f, 0.3f, 0.5f)[i % 7]
    return Color(c.red * f, c.green * f, c.blue * f)
}

@Composable
private fun Dots() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 18.dp.toPx()
        var y = step / 2
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) step / 2 else step
            while (x < size.width) {
                drawCircle(Color.White.copy(alpha = 0.16f), 1.6.dp.toPx(), Offset(x, y))
                x += step
            }
            y += step; row++
        }
    }
}

// ---------------------------------------------------------------- вибір системи

@Composable
private fun SystemPicker(c: HomeController, accent: Color) {
    val ti = c.tabIndex.coerceIn(0, c.tabs.lastIndex)
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = {
                    if (drag < -90f) c.switchTab(1) else if (drag > 90f) c.switchTab(-1)
                    drag = 0f
                }) { _, d -> drag += d }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) { c.openTabList() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = ti,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(380)) { it / 3 * dir } + fadeIn(tween(380)))
                    .togetherWith(slideOutHorizontally(tween(300)) { -it / 3 * dir } + fadeOut(tween(220)))
            },
            label = "system",
        ) { i ->
            val t = c.tabs.getOrNull(i) ?: return@AnimatedContent
            val count = if (t.id == "home") c.games.count { !it.hidden } else t.items.count { it is RowItem.GameItem }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConsoleIcon(t.id, Modifier.size(132.dp), tint = Color.White)
                Spacer(Modifier.width(26.dp))
                Column {
                    Text(
                        Systems.byId[t.id]?.name ?: t.label, color = Color.White,
                        fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 52.sp,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 4f), 18f)),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(520.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (count > 0) tr("Ігор: %s", count) else tr("Поки порожньо"),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp,
                    )
                }
            }
        }
        // точки-індикатори систем
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            c.tabs.indices.forEach { i ->
                Box(
                    Modifier.size(if (i == ti) 9.dp else 6.dp).clip(CircleShape)
                        .background(if (i == ti) accent else Color.White.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- список ігор системи

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SliceGameList(c: HomeController, accent: Color) {
    val tab = c.tab
    val item = c.current
    val game = c.currentGame
    val skew = with(LocalDensity.current) { 26.dp.toPx() }
    val shape = remember(skew) { slantShape(skew) }
    val rowShape = remember { slantShape(14f) }

    Row(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 18.dp, bottom = 64.dp)) {
        Column(Modifier.weight(0.48f).fillMaxHeight()) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { c.backToSystems() }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConsoleIcon(tab.id, Modifier.size(34.dp), tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(
                    Systems.byId[tab.id]?.name ?: tab.label, color = Color.White,
                    fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(tab.id, c.sel) { listState.animateScrollToItem((c.sel - 3).coerceAtLeast(0)) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(tab.items, key = { _, it -> it.key }) { i, it ->
                    val sel = i == c.sel
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(rowShape)
                            .background(if (sel) accent else Color.Transparent)
                            .combinedClickable(onClick = { c.clickItem(i) }, onLongClick = { c.longPressItem(i) })
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (it is RowItem.GameItem && it.game.favorite) {
                            Text("★ ", color = if (sel) Color.White else accent, fontSize = 14.sp)
                        }
                        Text(
                            (it as? RowItem.GameItem)?.game?.title ?: (it as RowItem.Action).title,
                            color = if (sel) Color.White else Color.White.copy(alpha = 0.82f),
                            fontSize = 17.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(0.52f).fillMaxHeight().padding(top = 44.dp), verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .shadow(14.dp, shape).clip(shape)
                    .background(Color(0xFF0E1324)),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(item, animationSpec = tween(300), label = "art") { PanelArt(c, it, tab) }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(start = 8.dp)) { GameInfo(c, item, showButtons = false) }
        }
    }
}

/**
 * Картинка в нахиленій панелі: широкий фон гри; якщо є лише обкладинка будь-якого розміру —
 * вона ціла поверх розмитої себе; застосунок — його іконка; розділ — іконка консолі.
 */
@Composable
private fun PanelArt(c: HomeController, item: RowItem?, tab: Tab) {
    val game = (item as? RowItem.GameItem)?.game
    when {
        game?.hero != null -> AsyncImage(
            model = artRequest(game.hero, c.artVersion), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        game?.cover != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = artRequest(game.cover, c.artVersion), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(28.dp).alpha(0.6f),
            )
            AsyncImage(
                model = artRequest(game.cover, c.artVersion), contentDescription = game.title,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 40.dp),
            )
        }
        game != null -> GameArt(game, c.artVersion, big = true)
        else -> {
            val action = item as? RowItem.Action
            val sys = action?.system ?: tab.id.takeIf { action == null }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(Systems.accent(sys ?: tab.id)).copy(alpha = 0.55f), Color(0xFF0E1324))),
                ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sys != null -> ConsoleIcon(sys, Modifier.size(150.dp), tint = Color.White)
                    action != null -> Icon(action.icon, null, tint = Color.White, modifier = Modifier.size(84.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- підказки

@Composable
private fun SliceHints(c: HomeController, inList: Boolean, modifier: Modifier) {
    val progress by c.artProgress.collectAsState()
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        progress?.let { (done, total) ->
            Text(tr("Завантажую картинки %s/%s", done, total), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (inList) {
            if (c.current != null) Hint("A", Color(0xFF3DDC84), if (c.currentGame != null) tr("Грати") else tr("Відкрити")) { c.activateCurrent() }
            Hint("B", Color(0xFFFF5A5A), tr("Назад")) { c.backToSystems() }
            if (c.currentGame != null) {
                Hint("Y", Color(0xFFF5C518), tr("Опції")) { c.optionsCurrent() }
                Hint("X", Color(0xFF4D9BFF), tr("Улюблене")) { c.toggleFavoriteCurrent() }
            }
        } else {
            Hint("◀▶", null, tr("Система")) { c.switchTab(1) }
            Hint("A", Color(0xFF3DDC84), tr("Відкрити")) { c.openTabList() }
        }
        Hint("⧉", null, tr("Пошук")) { c.openSearch() }
        Hint("☰", null, tr("Меню")) { c.openSettings() }
    }
}
