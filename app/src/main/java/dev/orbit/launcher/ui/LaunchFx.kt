package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.orbit.launcher.data.Game
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Анімація між натисканням «Грати» і стартом емулятора */
@Composable
fun LaunchOverlay(c: HomeController, game: Game, accent: Color) {
    when (c.look.launchAnim) {
        "portal" -> PortalLaunch(c, game, accent)
        "ps5" -> CameraLaunch(c, game)
        "none" -> LaunchedEffect(game.id) { c.doLaunch(game) }
        else -> WarpLaunch(c, game, accent)
    }
}

/** Гіперстрибок: зорі розтягуються в лінії, обкладинка летить на тебе, білий спалах */
@Composable
private fun WarpLaunch(c: HomeController, game: Game, accent: Color) {
    val t = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val stars = remember {
        val r = Random(game.id.hashCode())
        List(170) { Triple(r.nextFloat() * 6.283f, 0.03f + r.nextFloat() * 0.5f, 0.4f + r.nextFloat() * 0.6f) }
    }
    LaunchedEffect(game.id) {
        coroutineScope {
            launch { dim.animateTo(1f, tween(160)) }
            t.animateTo(1f, tween(1000, easing = CubicBezierEasing(0.35f, 0f, 0.85f, 0.45f)))
        }
        flash.animateTo(1f, tween(140))
        c.doLaunch(game)
        delay(250)
    }
    val p = t.value
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.85f))
            val cx = size.width / 2; val cy = size.height / 2
            val maxR = size.maxDimension * 0.75f
            for ((angle, start, bright) in stars) {
                val d0 = (start + p * p * 1.6f) % 1.2f
                val len = 0.01f + p * p * 0.35f
                val r0 = d0 * maxR
                val r1 = (d0 + len) * maxR
                val dx = cos(angle); val dy = sin(angle)
                drawLine(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = bright), accent.copy(alpha = bright)),
                        Offset(cx + dx * r0, cy + dy * r0), Offset(cx + dx * r1, cy + dy * r1),
                    ),
                    Offset(cx + dx * r0, cy + dy * r0), Offset(cx + dx * r1, cy + dy * r1),
                    strokeWidth = 1.5f + p * 3f, cap = StrokeCap.Round,
                )
            }
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.5f * p), Color.Transparent), Offset(cx, cy), maxR * 0.4f), maxR * 0.4f, Offset(cx, cy))
        }
        Box(
            Modifier.size(150.dp)
                .graphicsLayer {
                    val s = 0.9f + p * p * 1.6f
                    scaleX = s; scaleY = s
                    alpha = 1f - (p - 0.75f).coerceAtLeast(0f) * 4f
                }
                .clip(RoundedCornerShape(18.dp)),
        ) { GameArt(game, c.artVersion, big = true) }
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
    }
}

/** Портал: кільця розходяться з центру, обкладинка обертається і зникає у світлі */
@Composable
private fun PortalLaunch(c: HomeController, game: Game, accent: Color) {
    val t = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(game.id) {
        coroutineScope {
            launch { spin.animateTo(1f, tween(1100, easing = LinearEasing)) }
            t.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
        }
        c.doLaunch(game)
    }
    val p = t.value
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val maxR = size.maxDimension
            // темрява, що заливає екран колом
            drawCircle(Color.Black, radius = maxR * (p * 1.1f), center = Offset(cx, cy))
            for (k in 0 until 4) {
                val rp = ((p * 1.6f) - k * 0.18f).coerceIn(0f, 1f)
                if (rp <= 0f) continue
                drawCircle(
                    accent.copy(alpha = (1f - rp) * 0.9f),
                    radius = 40f + rp * maxR * 0.6f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 10f * (1f - rp) + 2f),
                )
            }
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = p * 0.8f), accent.copy(alpha = p * 0.4f), Color.Transparent), Offset(cx, cy), 220f * (0.3f + p)), 220f * (0.3f + p), Offset(cx, cy))
        }
        Box(
            Modifier.size(150.dp)
                .graphicsLayer {
                    rotationZ = spin.value * 360f * spin.value
                    val s = 1f - p * 0.85f
                    scaleX = s; scaleY = s
                    alpha = 1f - (p - 0.7f).coerceAtLeast(0f) * 3f
                }
                .clip(RoundedCornerShape(18.dp)),
        ) { GameArt(game, c.artVersion, big = true) }
    }
}

/** Наїзд камери (як на PS5): фон гри наближається, з'являється логотип, затемнення */
@Composable
private fun CameraLaunch(c: HomeController, game: Game) {
    val zoom = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    LaunchedEffect(game.id) {
        coroutineScope {
            launch { zoom.animateTo(1.25f, tween(1300, easing = FastOutSlowInEasing)) }
            launch { logo.animateTo(1f, tween(500)) }
            delay(850)
            fade.animateTo(1f, tween(450))
        }
        c.doLaunch(game)
    }
    val theme = LocalTheme.current
    Box(Modifier.fillMaxSize().background(theme.bg), contentAlignment = Alignment.Center) {
        val bg = game.hero ?: game.cover
        if (bg != null) {
            AsyncImage(
                model = artRequest(bg, c.artVersion), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
            alpha = logo.value
            translationY = (1f - logo.value) * 40f
        }) {
            if (game.logo != null) {
                AsyncImage(model = artRequest(game.logo, c.artVersion), contentDescription = null, modifier = Modifier.height(110.dp))
            } else {
                Text(game.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(tr("Запуск…"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade.value)))
    }
}
