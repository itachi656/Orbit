package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.orbit.launcher.data.Game
import dev.orbit.launcher.data.GameKind
import dev.orbit.launcher.data.Look
import dev.orbit.launcher.data.Systems
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** Поточна тема доступна всім компонентам */
val LocalTheme = staticCompositionLocalOf { Themes.all.first() }

@Composable
fun HomeScreen(c: HomeController) {
    val theme = c.theme
    val look = c.look
    val tab = c.tab
    val item = c.current
    val game = (item as? RowItem.GameItem)?.game
    val systemId = game?.system ?: (item as? RowItem.Action)?.system ?: tab.id
    // колір з обкладинки (за бажанням) — інакше колір теми / системи
    val coverColor = if (look.coverAccent) rememberCoverColor(game?.cover) else null
    val accent by animateColorAsState(coverColor ?: accentColor(look, theme, systemId), tween(500), label = "accent")

    CompositionLocalProvider(
        LocalTheme provides theme,
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = theme.font, color = theme.text),
    ) {
        Box(Modifier.fillMaxSize().background(theme.bg)) {
            Backdrop(c, game, accent)

            when (theme.layout) {
                Layout.Row -> RowLayout(c, accent)
                Layout.Xmb -> XmbLayout(c, accent)
                Layout.Carousel -> CarouselLayout(c, accent)
                Layout.Grid -> GridLayout(c, accent)
                Layout.List -> CrtLayout(c, accent)
                Layout.Channels -> ChannelsLayout(c, accent)
                Layout.Blades -> BladesLayout(c, accent)
            }

            if (theme.scanlines) Scanlines()
            c.menus.forEach { MenuOverlay(c, it, accent) }
            c.form?.let { FormOverlay(c, it) }
            c.launching?.let { LaunchOverlay(c, it, accent) }
            c.search?.let { SearchOverlay(c, it, accent) }
            c.quick?.let { QuickMenuOverlay(c, it, accent) }
            c.toast?.let { Toast(it) }
            if (c.booting) BootIntro(c, accentColor(look, theme, "").takeIf { look.accent != "auto" || theme.accent != null } ?: Color(0xFF4D7CFF))
        }
    }
}

fun accentColor(look: Look, theme: ThemeSpec, systemId: String): Color = when {
    look.accent != "auto" -> Color(look.accent.toLong(16))
    theme.monthColor -> monthColor()
    theme.accent != null -> theme.accent
    else -> Color(Systems.accent(systemId))
}

// ---------------------------------------------------------------- фон

@Composable
private fun Backdrop(c: HomeController, game: Game?, accent: Color) {
    val theme = LocalTheme.current
    val mode = c.look.background.let { if (it == "auto") theme.background else it }

    when (mode) {
        "animated" -> AnimatedGlow(accent)
        "waves" -> Waves(accent)
        "swirl" -> Swirl(accent)
        "blades" -> BladesBackdrop(accent)
        "channels" -> ChannelsBackdrop()
        "photo" -> if (c.look.bgPhoto.isNotBlank()) {
            AsyncImage(
                model = artRequest(c.look.bgPhoto, 0), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        }
        "plain" -> Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(accent.copy(alpha = if (theme.isLight) 0.10f else 0.16f), Color.Transparent), radius = 1600f),
            ),
        )
        else -> { // art | blur
            AnimatedGlow(accent)
            GameArtBackdrop(game, c.artVersion, forceBlur = mode == "blur")
        }
    }
    // затемнення, щоб текст читався
    if (mode != "plain") {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to theme.bg.copy(alpha = 0.92f), 0.45f to theme.bg.copy(alpha = 0.45f), 1f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to theme.bg.copy(alpha = 0.75f), 0.22f to Color.Transparent,
                    0.55f to theme.bg.copy(alpha = 0.35f), 1f to theme.bg.copy(alpha = 0.97f),
                ),
            ),
        )
    }
}

@Composable
private fun GameArtBackdrop(game: Game?, artVersion: Int, forceBlur: Boolean) {
    // невелика затримка, щоб при швидкому гортанні фон не «мерехтів»
    val wanted = game?.hero ?: game?.cover
    var shown by remember { mutableStateOf(wanted) }
    var isHero by remember { mutableStateOf(game?.hero != null) }
    LaunchedEffect(wanted) {
        delay(if (shown == null) 0 else 220)
        shown = wanted
        isHero = game?.hero != null
    }
    Crossfade(targetState = shown to isHero, animationSpec = tween(650), label = "hero") { (path, heroFile) ->
        if (path != null) {
            // Маленькі картинки (скріншоти libretro 256×224) розмиваємо в атмосферний фон
            val sharp = remember(path, forceBlur) {
                !forceBlur && heroFile && android.graphics.BitmapFactory.Options().run {
                    inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(path, this)
                    outWidth >= 900
                }
            }
            AsyncImage(
                model = artRequest(path, artVersion),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (sharp) Modifier.alpha(0.95f) else Modifier.blur(40.dp).alpha(0.5f)),
            )
        }
    }
}

@Composable
fun AnimatedGlow(accent: Color) {
    val theme = LocalTheme.current
    val t = rememberInfiniteTransition(label = "glow")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(theme.bg)
        val c1 = Offset(w * (0.75f - 0.2f * p), h * (0.2f + 0.3f * p))
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), c1, w * 0.55f), w * 0.55f, c1)
        val c2 = Offset(w * (0.2f + 0.25f * p), h * 0.95f)
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), c2, w * 0.45f), w * 0.45f, c2)
    }
}

/** Хвилі як у XMB */
@Composable
fun Waves(accent: Color) {
    val theme = LocalTheme.current
    val t = rememberInfiniteTransition(label = "waves")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "ph")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(theme.bg, accent.copy(alpha = 0.35f).compositeOver(theme.bg), theme.bg)))
        for (k in 0 until 5) {
            val path = Path()
            val amp = h * (0.05f + 0.025f * k)
            val base = h * (0.55f + 0.03f * k)
            val freq = 1.4f + 0.35f * k
            path.moveTo(0f, base)
            var x = 0f
            while (x <= w) {
                val y = base + amp * sin(x / w * freq * 2 * PI.toFloat() + phase * (1 + 0.3f * k) + k)
                path.lineTo(x, y)
                x += 8f
            }
            drawPath(path, Color.White.copy(alpha = 0.10f + 0.04f * (4 - k)), style = Stroke(width = (1.5f + k * 0.8f).dp.toPx()))
        }
    }
}

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
)

@Composable
private fun Scanlines() {
    val t = rememberInfiniteTransition(label = "crt")
    val flicker by t.animateFloat(0.10f, 0.16f, infiniteRepeatable(tween(90), RepeatMode.Reverse), label = "f")
    Canvas(Modifier.fillMaxSize()) {
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = flicker + 0.12f), Offset(0f, y), Offset(size.width, y), 1.2f)
            y += 4f
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), radius = size.maxDimension * 0.65f))
    }
}

// ---------------------------------------------------------------- верхня панель

@Composable
fun TopTabs(c: HomeController, accent: Color, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val listState = rememberLazyListState()
    LaunchedEffect(c.tabIndex) { listState.animateScrollToItem((c.tabIndex - 2).coerceAtLeast(0)) }
    Row(
        modifier.fillMaxWidth().padding(start = 40.dp, end = 20.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(state = listState, modifier = Modifier.weight(1f).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(c.tabs, key = { _, t -> t.id }) { i, t ->
                val selected = i == c.tabIndex
                val focused = selected && c.zone == Zone.Tabs && c.menus.isEmpty()
                Column(
                    Modifier.clickable(remember { MutableInteractionSource() }, null) { c.selectTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    ConsoleIcon(t.id, Modifier.size(if (selected) 22.dp else 18.dp).graphicsLayer { alpha = if (selected) 1f else 0.6f }, tint = if (selected) theme.text else theme.dim)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        t.label,
                        color = if (selected) theme.text else theme.dim,
                        fontSize = if (selected) 16.sp else 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (focused) theme.text.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    }
                    Box(
                        Modifier.padding(top = 2.dp).height(2.dp).width(if (selected) 22.dp else 0.dp)
                            .clip(RoundedCornerShape(1.dp)).background(accent),
                    )
                }
            }
        }
        c.padBattery?.let {
            Icon(Icons.Filled.SportsEsports, null, tint = if (it <= 15) Color(0xFFFF5A5A) else theme.dim, modifier = Modifier.size(18.dp))
            Text(" $it%   ", color = if (it <= 15) Color(0xFFFF5A5A) else theme.dim, fontSize = 13.sp)
        }
        Clock(c.look.clock24)
        Spacer(Modifier.width(12.dp))
        Icon(
            Icons.Filled.Search, tr("Пошук"), tint = theme.text,
            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { c.openSearch() }.padding(6.dp),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.Settings, tr("Налаштування"), tint = theme.text,
            modifier = Modifier.size(34.dp).clip(CircleShape).clickable { c.openSettings() }.padding(6.dp),
        )
    }
}

@Composable
fun Clock(h24: Boolean) {
    val ctx = LocalContext.current
    val theme = LocalTheme.current
    val state by produceState(initialValue = clockText(ctx, h24), h24) {
        while (true) { value = clockText(ctx, h24); delay(15_000) }
    }
    Text(state, color = theme.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

private fun clockText(ctx: Context, h24: Boolean): String {
    val time = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date())
    val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.let {
        val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0) l * 100 / s else null
    }
    val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
    return if (level != null) "${if (charging) "⚡" else ""}${level}%   ${time}" else time
}

// ---------------------------------------------------------------- плитки

data class TileDims(val w: Dp, val h: Dp)

fun tileDims(look: Look, selected: Boolean, scale: Float = 1f, forceShape: String? = null): TileDims {
    val base = listOf(74f, 86f, 102f)[look.tileSize.coerceIn(0, 2)] * scale
    val shape = forceShape ?: look.tileShape
    val (hMul, ratio) = when (shape) {
        "portrait" -> 1.18f to 0.68f
        "wide" -> 0.78f to 1.65f
        else -> 1f to 1f
    }
    val h = base * hMul * (if (selected) 1.5f else 1f)
    return TileDims((h * ratio).dp, h.dp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(
    item: RowItem,
    dims: TileDims,
    selected: Boolean,
    focused: Boolean,
    accent: Color,
    artVersion: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showBadge: Boolean = true,
) {
    val theme = LocalTheme.current
    val borderAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "border")
    val shape = RoundedCornerShape(if (selected) theme.corner + 2.dp else theme.corner)
    val pulse = if (theme.glowSelect && focused) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pa").value
    } else 1f
    val borderColor = if (theme.glowSelect) accent.copy(alpha = borderAlpha * pulse)
    else (if (theme.isLight) theme.text else Color.White).copy(alpha = borderAlpha)

    Box(
        Modifier
            .width(dims.w).height(dims.h)
            .shadow(if (selected) 18.dp else 4.dp, shape, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(theme.panel)
            .border(if (theme.glowSelect) 4.dp else 2.5.dp, borderColor, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        when (item) {
            is RowItem.GameItem -> GameArt(item.game, artVersion, big = selected)
            is RowItem.Action -> {
                val col = item.system?.let { Color(Systems.accent(it)) } ?: accent
                Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(col.copy(alpha = 0.75f), theme.panel))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.system != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ConsoleIcon(item.system, Modifier.size(dims.h * 0.52f))
                            Text(
                                Systems.label(item.system), color = Color.White, fontWeight = FontWeight.Bold,
                                fontSize = if (selected) 15.sp else 10.sp, textAlign = TextAlign.Center, maxLines = 1,
                            )
                        }
                    } else {
                        Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(if (selected) 44.dp else 30.dp))
                    }
                }
            }
        }
        if (showBadge && item is RowItem.GameItem && !selected && item.game.system != Systems.ANDROID) {
            Text(
                Systems.label(item.game.system),
                color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun GameArt(game: Game, artVersion: Int, big: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val accent = Color(Systems.accent(game.system))
    // якщо картинка не відкрилась — показуємо іконку застосунку / назву, а не порожній квадрат
    var failed by remember(game.cover, artVersion) { mutableStateOf(false) }
    when {
        game.cover != null && !failed -> AsyncImage(
            model = artRequest(game.cover, artVersion),
            contentDescription = game.title,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            onError = { failed = true },
            modifier = modifier,
        )
        game.kind == GameKind.SHORTCUT && game.icon != null -> Box(
            modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), Color(0xFF0E1324)))),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = artRequest(game.icon, artVersion), contentDescription = game.title, modifier = Modifier.fillMaxSize(0.6f))
        }
        game.kind == GameKind.ANDROID && game.packageName != null -> {
            val ctx = LocalContext.current
            val icon = remember(game.packageName) {
                runCatching { ctx.packageManager.getApplicationIcon(game.packageName).toBitmap(256, 256).asImageBitmap() }.getOrNull()
            }
            Box(
                modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), Color(0xFF0E1324)))),
                contentAlignment = Alignment.Center,
            ) {
                icon?.let { Image(it, null, Modifier.fillMaxSize(0.62f).clip(RoundedCornerShape(18))) }
            }
        }
        else -> Box(
            modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.8f), Color(0xFF0E1324)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                game.title,
                color = Color.White,
                fontSize = if (big) 15.sp else 10.sp,
                lineHeight = if (big) 18.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
fun artRequest(path: String, artVersion: Int): ImageRequest {
    val ctx = LocalContext.current
    return remember(path, artVersion) {
        val stamp = File(path).lastModified()
        ImageRequest.Builder(ctx)
            .data(File(path))
            .memoryCacheKey("${path}@${stamp}")
            .diskCacheKey("${path}@${stamp}")
            .crossfade(220)
            .build()
    }
}

// ---------------------------------------------------------------- інформація про гру

@Composable
fun GameInfo(c: HomeController, item: RowItem?, centered: Boolean = false, showButtons: Boolean = true) {
    val theme = LocalTheme.current
    val game = (item as? RowItem.GameItem)?.game
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    Column(horizontalAlignment = align, modifier = Modifier.widthIn(max = 560.dp)) {
        when {
            game != null -> {
                Box(Modifier.heightIn(min = 50.dp), contentAlignment = if (centered) Alignment.BottomCenter else Alignment.BottomStart) {
                    if (game.logo != null) {
                        AsyncImage(
                            model = artRequest(game.logo, c.artVersion),
                            contentDescription = game.title,
                            contentScale = ContentScale.Fit,
                            alignment = if (centered) Alignment.BottomCenter else Alignment.BottomStart,
                            modifier = Modifier.height(60.dp).widthIn(max = 300.dp),
                        )
                    } else {
                        Text(
                            game.title, color = theme.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 29.sp,
                            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(metaLine(game), color = theme.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showButtons) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val inActions = c.zone == Zone.Actions && c.menus.isEmpty()
                        PlayButton(focused = inActions && c.actionIndex == 0) { c.play(game) }
                        RoundButton(if (game.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, inActions && c.actionIndex == 1) {
                            c.toggleFavoriteCurrent()
                        }
                        RoundButton(Icons.Filled.MoreHoriz, inActions && c.actionIndex == 2) { c.openOptions(game) }
                    }
                }
            }
            item is RowItem.Action -> {
                Text(item.title, color = theme.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(item.subtitle, color = theme.dim, fontSize = 13.sp)
                if (showButtons) {
                    Spacer(Modifier.height(10.dp))
                    PlayButton(focused = false, label = tr("Відкрити"), icon = null) { c.activateCurrent() }
                }
            }
        }
    }
}

fun metaLine(g: Game): String {
    val parts = mutableListOf<String>()
    parts += when (g.kind) {
        GameKind.ROM -> Systems.byId[g.system]?.name ?: g.system
        GameKind.ANDROID -> "Android"
        GameKind.MOONLIGHT -> "Moonlight · ${g.pcName}"
        GameKind.SHORTCUT -> tr("Ярлик") + " · " + (Systems.byId[g.system]?.name ?: Systems.label(g.system))
    }
    if (g.lastPlayed > 0) parts += tr("Остання гра: ") + relative(g.lastPlayed)
    if (g.playTime > 60_000) parts += tr("зіграно %s", formatDuration(g.playTime))
    else if (g.playCount > 0) parts += tr("запусків: %s", g.playCount)
    return parts.joinToString("  ·  ")
}

private fun relative(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 2 -> tr("щойно")
        m < 60 -> tr("%s хв тому", m)
        m < 60 * 24 -> tr("%s год тому", m / 60)
        m < 60 * 48 -> tr("вчора")
        else -> tr("%s дн. тому", m / (60 * 24))
    }
}

@Composable
fun PlayButton(focused: Boolean, label: String = tr("Грати"), icon: ImageVector? = Icons.Filled.PlayArrow, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "play")
    val bg = if (theme.isLight) theme.text else Color.White
    val fg = if (theme.isLight) Color.White else Color.Black
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(if (theme.corner == 0.dp) 0.dp else 50.dp))
            .background(if (focused) bg else bg.copy(alpha = 0.88f))
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RoundButton(icon: ImageVector, focused: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "rb")
    val on = if (theme.isLight) theme.text else Color.White
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) on else on.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (focused) (if (theme.isLight) Color.White else Color.Black) else on, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------- підказки кнопок (працюють і дотиком)

@Composable
fun HintBar(c: HomeController, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val progress by c.artProgress.collectAsState()
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        progress?.let { (done, total) ->
            Text(tr("Завантажую картинки %s/%s", done, total), color = theme.dim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
        }
        if (c.current != null) Hint("A", Color(0xFF3DDC84), if (c.currentGame != null) tr("Грати") else tr("Відкрити")) { c.activateCurrent() }
        if (c.currentGame != null) {
            Hint("Y", Color(0xFFF5C518), tr("Опції")) { c.optionsCurrent() }
            Hint("X", Color(0xFF4D9BFF), tr("Улюблене")) { c.toggleFavoriteCurrent() }
        }
        Hint("LB", null, "") { c.switchTab(-1) }
        Hint("RB", null, tr("Вкладки")) { c.switchTab(1) }
        Hint("⧉", null, tr("Пошук")) { c.openSearch() }
        Hint("☰", null, tr("Меню")) { c.openSettings() }
    }
}

@Composable
private fun Hint(glyph: String, color: Color?, label: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val col = color ?: theme.text
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(50))
                .border(1.5.dp, col.copy(alpha = 0.9f), RoundedCornerShape(50))
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = col, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = theme.dim, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- тост

@Composable
private fun Toast(text: String) {
    val theme = LocalTheme.current
    Box(Modifier.fillMaxSize().padding(bottom = 52.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text, color = theme.text, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(theme.panel)
                .border(1.dp, theme.text.copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}


/** Колір місяця, як у PSP: січень — срібний, … грудень — червоний */
fun monthColor(): Color = Color(
    listOf(
        0xFF9AA4B5, 0xFFC9A227, 0xFF7BB661, 0xFFE58FB1, 0xFF3FA34D, 0xFF8E6CC9,
        0xFF2BB3C0, 0xFF3B6FD8, 0xFF7A52C7, 0xFFD9822B, 0xFF9C5B3B, 0xFFC23B3B,
    )[java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)],
)

private val coverColors = java.util.concurrent.ConcurrentHashMap<String, Int>()

/** Яскравий колір з обкладинки (Palette), кешується */
@Composable
fun rememberCoverColor(path: String?): Color? {
    val state = produceState<Color?>(initialValue = path?.let { coverColors[it] }?.let { Color(it) }, path) {
        if (path == null) { value = null; return@produceState }
        coverColors[path]?.let { value = Color(it); return@produceState }
        val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
                val p = androidx.palette.graphics.Palette.from(bmp).maximumColorCount(16).generate()
                val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
                sw?.rgb
            }.getOrNull()
        }
        if (c != null) { coverColors[path] = c; value = Color(c) }
    }
    return state.value
}

/** Помаранчева спіраль, як на Dreamcast */
@Composable
fun Swirl(accent: Color) {
    val theme = LocalTheme.current
    val t = rememberInfiniteTransition(label = "swirl")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(60000, easing = LinearEasing)), label = "r")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(theme.bg, Color(0xFFDDE3EA))))
        val cx = size.width * 0.78f; val cy = size.height * 0.55f
        val path = Path()
        val turns = 3.2f
        val maxR = size.minDimension * 0.42f
        var first = true
        var a = 0f
        while (a < turns * 2 * PI.toFloat()) {
            val r = maxR * (a / (turns * 2 * PI.toFloat()))
            val ang = a - Math.toRadians(rot.toDouble()).toFloat()
            val x = cx + r * kotlin.math.cos(ang); val y = cy + r * kotlin.math.sin(ang)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            a += 0.04f
        }
        drawPath(path, accent.copy(alpha = 0.85f), style = Stroke(width = 14.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Фон «лез» Xbox 360: зелений градієнт зі світлом */
@Composable
fun BladesBackdrop(accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF4FA51B), Color(0xFF1E5E08)), Offset(0f, 0f), Offset(size.width, size.height)))
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent), Offset(size.width * 0.3f, size.height * 0.2f), size.width * 0.5f), size.width * 0.5f, Offset(size.width * 0.3f, size.height * 0.2f))
    }
}

/** Фон «каналів» Wii: світлий з тонкими лініями */
@Composable
fun ChannelsBackdrop() {
    val theme = LocalTheme.current
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color.White, theme.bg)))
        var y = 0f
        while (y < size.height) { drawLine(Color(0x0F000000), Offset(0f, y), Offset(size.width, y), 1f); y += 6f }
    }
}
