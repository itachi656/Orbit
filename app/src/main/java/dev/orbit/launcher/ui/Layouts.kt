package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import dev.orbit.launcher.data.Systems
import kotlin.math.abs
import kotlin.math.roundToInt

// =====================================================================================
// Ряд (Orbit / Неон / Захід сонця)
// =====================================================================================

@Composable
fun RowLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tab = c.tab
    val look = c.look
    val big = tileDims(look, selected = true)

    Column(Modifier.fillMaxSize()) {
        TopTabs(c, accent)
        Spacer(Modifier.height(if (theme.centered) 2.dp else 6.dp))
        if (theme.centered) {
            // як у Switch: назва над вибраною плиткою
            Text(
                c.currentGame?.title ?: (c.current as? RowItem.Action)?.title ?: "",
                color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(big.h + 12.dp)) {
            val viewport = maxWidth
            val listState = remember(tab.id) { LazyListState(c.sel) }
            val density = LocalDensity.current
            // розмір і перший ключ — щоб після довантаження бібліотеки ряд не «прилипав» не до тієї плитки
            LaunchedEffect(tab.id, c.sel, theme.centered, look.tileSize, look.tileShape, tab.items.size, tab.items.firstOrNull()?.key) {
                val offset = if (theme.centered) with(density) { -((viewport - big.w) / 2 - 40.dp).roundToPx() } else 0
                listState.animateScrollToItem(c.sel, offset)
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 40.dp, end = viewport),
                horizontalArrangement = Arrangement.spacedBy(if (theme.centered) 16.dp else 12.dp),
                verticalAlignment = if (theme.centered) Alignment.CenterVertically else Alignment.Top,
            ) {
                itemsIndexed(tab.items, key = { _, it -> it.key }) { i, item ->
                    val selected = i == c.sel
                    // у «Неоні» всі плитки однакові великі, вибір — рамкою
                    val dims = if (theme.centered) tileDims(look, true, scale = 0.82f) else tileDims(look, selected)
                    AnimatedTile(
                        item, dims, selected,
                        focused = selected && c.zone == Zone.Row && c.menus.isEmpty(),
                        accent = accent, c = c, index = i,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 6.dp), contentAlignment = if (theme.centered) Alignment.TopCenter else Alignment.TopStart) {
            GameInfo(c, c.current, centered = theme.centered)
        }
        Spacer(Modifier.weight(1f))
        HintBar(c)
    }
}

@Composable
private fun AnimatedTile(item: RowItem, target: TileDims, selected: Boolean, focused: Boolean, accent: Color, c: HomeController, index: Int) {
    val w by animateFloatAsState(target.w.value, spring(dampingRatio = 0.72f, stiffness = 480f), label = "w")
    val h by animateFloatAsState(target.h.value, spring(dampingRatio = 0.72f, stiffness = 480f), label = "h")
    Tile(
        item, TileDims(w.dp, h.dp), selected, focused, accent, c.artVersion,
        onClick = { c.clickItem(index) }, onLongClick = { c.longPressItem(index) },
    )
}

// =====================================================================================
// Хвилі (XMB)
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun XmbLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tabs = c.tabs
    val tab = c.tab
    val animTab by animateFloatAsState(c.tabIndex.toFloat(), spring(stiffness = Spring.StiffnessMediumLow), label = "tab")
    val animSel by animateFloatAsState(c.sel.toFloat(), spring(stiffness = Spring.StiffnessMediumLow), label = "sel")
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = {
                    if (abs(dragX) > 60) c.switchTab(if (dragX < 0) 1 else -1)
                    dragX = 0f
                }) { _, d -> dragX += d }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = {
                    if (abs(dragY) > 40) c.setSel(c.sel + if (dragY < 0) 1 else -1)
                    dragY = 0f
                }) { _, d -> dragY += d }
            },
    ) {
        val colX = maxWidth * 0.2f
        val barY = maxHeight * 0.24f
        val spacing = 112.dp

        // часи і дата
        Box(Modifier.fillMaxWidth().padding(top = 14.dp, end = 24.dp), contentAlignment = Alignment.TopEnd) {
            Clock(c.look.clock24)
        }

        // категорії
        tabs.forEachIndexed { i, t ->
            val d = i - animTab
            if (abs(d) > 6) return@forEachIndexed
            val selected = i == c.tabIndex
            Column(
                Modifier
                    .offset(x = colX + spacing * d - 36.dp, y = barY)
                    .width(72.dp)
                    .graphicsLayer { alpha = if (selected) 1f else 0.55f; scaleX = if (selected) 1.1f else 0.85f; scaleY = scaleX }
                    .clickable(remember { MutableInteractionSource() }, null) { c.selectTab(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ConsoleIcon(t.id, Modifier.size(52.dp), tint = theme.text)
                Text(
                    t.label, color = theme.text, fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = if (selected) 1f else 0f },
                )
            }
        }

        // ігри: вибрана одразу під категорією, попередні — над нею
        val itemH = 58.dp
        val first = (c.sel - 4).coerceAtLeast(0)
        val last = (c.sel + 7).coerceAtMost(tab.items.lastIndex)
        for (i in first..last) {
            val item = tab.items[i]
            val d = i - animSel
            val gap = (animSel - i).coerceIn(0f, 1f)
            // під вибраною грою більше місця — вона більша і з підписом
            val after = (i - animSel).coerceIn(0f, 1f)
            val y = barY + 86.dp + itemH * d - 96.dp * gap + 22.dp * after
            val selected = i == c.sel
            val game = (item as? RowItem.GameItem)?.game
            Row(
                Modifier
                    .offset(x = colX - 34.dp, y = y)
                    .graphicsLayer { alpha = if (selected) 1f else (0.75f - abs(d) * 0.08f).coerceAtLeast(0.2f) }
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = { c.clickItem(i) }, onLongClick = { c.longPressItem(i) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val size = if (selected) 64.dp else 48.dp
                Box(
                    Modifier.size(size)
                        .shadow(if (selected) 16.dp else 0.dp, RoundedCornerShape(theme.corner), ambientColor = accent, spotColor = accent)
                        .clip(RoundedCornerShape(theme.corner)),
                ) {
                    if (game != null) GameArt(game, c.artVersion, big = false)
                    else Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.5f)))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        game?.title ?: (item as RowItem.Action).title,
                        color = theme.text, fontSize = if (selected) 19.sp else 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) Text(
                        game?.let(::metaLine) ?: (item as RowItem.Action).subtitle,
                        color = theme.dim, fontSize = 12.sp, maxLines = 1,
                    )
                }
            }
        }
        if (tab.items.isEmpty()) {
            Text(tr("Тут поки порожньо"), color = theme.dim, modifier = Modifier.offset(x = colX - 34.dp, y = barY + 90.dp))
        }

        HintBar(c, Modifier.align(Alignment.BottomCenter))
    }
}

// =====================================================================================
// Карусель (coverflow)
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CarouselLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tab = c.tab
    val animSel by animateFloatAsState(c.sel.toFloat(), spring(dampingRatio = 0.8f, stiffness = 300f), label = "sel")
    var drag by remember { mutableFloatStateOf(0f) }
    val dims = tileDims(c.look, selected = true, scale = 1.05f, forceShape = if (c.look.tileShape == "wide") "wide" else "portrait")

    Column(Modifier.fillMaxSize()) {
        TopTabs(c, accent)
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f)
                .pointerInput(tab.id) {
                    detectHorizontalDragGestures(onDragEnd = {
                        val steps = (-drag / 120f).roundToInt()
                        if (steps != 0) c.setSel(c.sel + steps)
                        drag = 0f
                    }) { _, d -> drag += d }
                },
            contentAlignment = Alignment.Center,
        ) {
            val spacing = dims.w * 0.62f
            val first = (c.sel - 5).coerceAtLeast(0)
            val last = (c.sel + 5).coerceAtMost(tab.items.lastIndex)
            for (i in first..last) {
                val d = i - animSel
                val ad = abs(d)
                val selected = i == c.sel
                Box(
                    Modifier
                        .zIndex(10f - ad)
                        .offset(x = spacing * d + (if (d > 0) 30.dp else if (d < 0) (-30).dp else 0.dp) * ad.coerceAtMost(1f))
                        .graphicsLayer {
                            rotationY = (-d * 48f).coerceIn(-62f, 62f)
                            val s = 1f - 0.16f * ad.coerceAtMost(3f)
                            scaleX = s; scaleY = s
                            cameraDistance = 14f * density
                            alpha = (1f - ad * 0.18f).coerceAtLeast(0f)
                        },
                ) {
                    Tile(
                        tab.items[i], dims, selected,
                        focused = selected && c.menus.isEmpty() && c.zone != Zone.Tabs,
                        accent = accent, artVersion = c.artVersion,
                        onClick = { c.clickItem(i) }, onLongClick = { c.longPressItem(i) },
                        showBadge = false,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
            GameInfo(c, c.current, centered = true, showButtons = false)
        }
        HintBar(c)
    }
}

// =====================================================================================
// Бібліотека (сітка)
// =====================================================================================

@Composable
fun GridLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tab = c.tab
    val shape = if (c.look.tileShape == "wide") "wide" else "portrait"
    val dims = tileDims(c.look, selected = false, scale = 1.25f, forceShape = shape)

    Column(Modifier.fillMaxSize()) {
        TopTabs(c, accent)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val cols = ((maxWidth - 64.dp) / (dims.w + 14.dp)).toInt().coerceAtLeast(2)
            val cellW = (maxWidth - 64.dp - 14.dp * (cols - 1)) / cols
            val cellH = cellW * (dims.h / dims.w)
            LaunchedEffect(cols) { c.gridColumns = cols }
            val gridState = rememberLazyGridState()
            LaunchedEffect(tab.id, c.sel, tab.items.size) {
                gridState.animateScrollToItem(((c.sel / cols) - 1).coerceAtLeast(0) * cols)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(tab.items, key = { _, it -> it.key }) { i, item ->
                    val selected = i == c.sel
                    val focused = selected && c.zone == Zone.Row && c.menus.isEmpty()
                    val scale by animateFloatAsState(if (focused) 1.07f else 1f, tween(160), label = "gs")
                    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.zIndex(if (selected) 1f else 0f), contentAlignment = Alignment.Center) {
                        Tile(
                            item, TileDims(cellW, cellH), selected, focused, accent, c.artVersion,
                            onClick = { c.clickItem(i) }, onLongClick = { c.longPressItem(i) },
                        )
                    }
                }
            }
        }
        // нижня панель з назвою вибраної гри
        Row(
            Modifier.fillMaxWidth().background(theme.panel).padding(start = 32.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    c.currentGame?.title ?: (c.current as? RowItem.Action)?.title ?: "",
                    color = theme.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(c.currentGame?.let(::metaLine) ?: "", color = theme.dim, fontSize = 11.sp, maxLines = 1)
            }
            HintBar(c, Modifier.weight(1.6f))
        }
    }
}

// =====================================================================================
// Ретро CRT (список)
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CrtLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tab = c.tab
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); blink = !blink } }

    Row(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 18.dp)) {
        Column(Modifier.weight(1.25f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◄ ", color = theme.dim, fontSize = 18.sp, modifier = Modifier.clickable { c.switchTab(-1) })
                Text(
                    "${tab.label.uppercase()}  [${if (tab.items.isEmpty()) 0 else c.sel + 1}/${tab.items.size}]",
                    color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(" ►", color = theme.dim, fontSize = 18.sp, modifier = Modifier.clickable { c.switchTab(1) })
                Spacer(Modifier.weight(1f))
                Clock(c.look.clock24)
            }
            Text("─".repeat(60), color = theme.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
            Spacer(Modifier.height(6.dp))
            val visible = 9
            val start = (c.sel - visible / 2).coerceIn(0, (tab.items.size - visible).coerceAtLeast(0))
            for (i in start until (start + visible).coerceAtMost(tab.items.size)) {
                val item = tab.items[i]
                val selected = i == c.sel
                val label = (item as? RowItem.GameItem)?.game?.title ?: (item as RowItem.Action).title
                Text(
                    (if (selected) "> " else "  ") + label + if (selected && blink) "█" else "",
                    color = if (selected) theme.bg else theme.text,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) theme.text else Color.Transparent)
                        .combinedClickable(onClick = { c.clickItem(i) }, onLongClick = { c.longPressItem(i) })
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                tr("[A] ГРАТИ  [Y] ОПЦІЇ  [X] ★  [LB/RB] РОЗДІЛ  [☰] МЕНЮ"),
                color = theme.dim, fontSize = 12.sp,
                modifier = Modifier.clickable { c.openSettings() },
            )
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val game = c.currentGame
            Box(
                Modifier.size(200.dp).border(2.dp, theme.text, RoundedCornerShape(0.dp)).padding(6.dp),
            ) {
                if (game != null) GameArt(game, c.artVersion, big = true)
            }
            Spacer(Modifier.height(10.dp))
            Text(game?.let(::metaLine) ?: "", color = theme.dim, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

// =====================================================================================
// Канали (Wii): сторінки по 12 «телевізорів»
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelsLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tab = c.tab
    LaunchedEffect(Unit) { c.gridColumns = 4 }
    val perPage = 12
    val page = c.sel / perPage
    val pages = ((tab.items.size + perPage - 1) / perPage).coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
        TopTabs(c, accent)
        androidx.compose.animation.AnimatedContent(
            targetState = tab.id to page,
            transitionSpec = {
                val dir = if (targetState.second >= initialState.second) 1 else -1
                (androidx.compose.animation.slideInHorizontally(tween(320)) { it * dir } + androidx.compose.animation.fadeIn(tween(320))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(320)) { -it * dir } + androidx.compose.animation.fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "page",
        ) { (_, pg) ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (r in 0 until 3) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (col in 0 until 4) {
                            val idx = pg * perPage + r * 4 + col
                            val item = tab.items.getOrNull(idx)
                            val focused = idx == c.sel && c.zone == Zone.Row && c.menus.isEmpty()
                            ChannelTile(item, focused, accent, c.artVersion, Modifier.weight(1f).fillMaxHeight(), { c.clickItem(idx) }, { c.longPressItem(idx) })
                        }
                    }
                }
            }
        }
        // нижня панель як у Wii: кнопки по краях, годинник і назва по центру
        Row(
            Modifier.fillMaxWidth().height(62.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF4F6F8), Color(0xFFD9DEE3))))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WiiRoundButton(Icons.Filled.Search) { c.openSearch() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    c.currentGame?.title ?: (c.current as? RowItem.Action)?.title ?: "",
                    color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Clock(c.look.clock24)
                    if (pages > 1) {
                        Spacer(Modifier.width(12.dp))
                        for (p in 0 until pages) {
                            Box(Modifier.padding(2.dp).size(if (p == page) 8.dp else 6.dp).clip(RoundedCornerShape(50)).background(if (p == page) accent else theme.dim.copy(alpha = 0.5f)))
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            WiiRoundButton(Icons.Filled.Settings) { c.openSettings() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelTile(item: RowItem?, focused: Boolean, accent: Color, artVersion: Int, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val pulse = if (focused) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ch")
        t.animateFloat(0.5f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(650), androidx.compose.animation.core.RepeatMode.Reverse), label = "p").value
    } else 0f
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(160), label = "s")
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 12.dp else 2.dp, shape, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(Color.White)
            .border(if (focused) 4.dp else 3.dp, if (focused) accent.copy(alpha = pulse) else Color(0xFFD5DBE0), shape)
            .then(if (item != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier)
            .padding(7.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFFEDF0F2))) {
            when (item) {
                is RowItem.GameItem -> GameArt(item.game, artVersion, big = focused)
                is RowItem.Action -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (item.system != null) ConsoleIcon(item.system, Modifier.fillMaxHeight(0.6f), tint = Color(0xFF8A9199))
                    else Icon(item.icon, null, tint = Color(0xFF8A9199), modifier = Modifier.size(36.dp))
                }
                null -> Unit // порожній «канал»
            }
        }
    }
}

@Composable
private fun WiiRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.White)
            .border(2.dp, Color(0xFFC9D0D6), RoundedCornerShape(50)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color(0xFF6A737C), modifier = Modifier.size(22.dp)) }
}

// =====================================================================================
// Blades (Xbox 360): розділи-«леза» по краях, у центрі — поточний розділ
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BladesLayout(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    val tabs = c.tabs
    val ti = c.tabIndex.coerceIn(0, tabs.lastIndex)
    val tab = c.tab
    val dark = Color(0xFF232B1F)
    val panelTheme = theme.copy(bg = Color(0xFFF4F7F2), panel = Color.White, text = dark, dim = Color(0xFF5E6B57))
    val slide by animateFloatAsState(ti.toFloat(), spring(dampingRatio = 0.85f, stiffness = 380f), label = "blade")
    val offset = (slide - ti) * 120f

    Row(Modifier.fillMaxSize().padding(vertical = 14.dp, horizontal = 8.dp)) {
        tabs.withIndex().toList().take(ti).takeLast(3).forEach { (i, t) -> Blade(t.label, i % 2 == 0) { c.selectTab(i) } }
        androidx.compose.runtime.CompositionLocalProvider(LocalTheme provides panelTheme) {
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp)
                    .graphicsLayer { translationX = offset * density }
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xF5F7FAF4))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tab.label.lowercase(), color = dark, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.weight(1f))
                    Clock(c.look.clock24)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.weight(1f)) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LaunchedEffect(tab.id, c.sel) { listState.animateScrollToItem((c.sel - 3).coerceAtLeast(0)) }
                    androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                        itemsIndexed(tab.items, key = { _, it -> it.key }) { i, item ->
                            val sel = i == c.sel
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (sel) accent else Color.Transparent)
                                    .combinedClickable(onClick = { c.clickItem(i) }, onLongClick = { c.longPressItem(i) })
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE3E8E0))) {
                                    when (item) {
                                        is RowItem.GameItem -> GameArt(item.game, c.artVersion, big = false)
                                        is RowItem.Action -> if (item.system != null) ConsoleIcon(item.system, Modifier.fillMaxSize().padding(4.dp), tint = dark)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    (item as? RowItem.GameItem)?.game?.title ?: (item as RowItem.Action).title,
                                    color = if (sel) Color.White else dark, fontSize = 15.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        c.currentGame?.let { g ->
                            Box(Modifier.size(104.dp).shadow(8.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))) { GameArt(g, c.artVersion, big = true) }
                            Spacer(Modifier.height(6.dp))
                        }
                        GameInfo(c, c.current, showButtons = true)
                    }
                }
                HintBar(c)
            }
        }
        tabs.withIndex().toList().drop(ti + 1).take(5).forEach { (i, t) -> Blade(t.label, i % 2 == 0) { c.selectTab(i) } }
    }
}

@Composable
private fun Blade(label: String, light: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(30.dp).fillMaxHeight().padding(horizontal = 1.5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = if (light) 0.85f else 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.lowercase(), color = Color(0xFF2E3A28), fontSize = 13.sp, maxLines = 1,
            modifier = Modifier.requiredWidth(220.dp).rotate(90f),
            textAlign = TextAlign.Center,
        )
    }
}
