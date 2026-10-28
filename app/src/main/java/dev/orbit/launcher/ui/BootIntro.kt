package dev.orbit.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Анімація запуску самого лаунчера. Будь-яка кнопка або дотик — пропустити. */
@Composable
fun BootIntro(c: HomeController, accent: Color) {
    val theme = LocalTheme.current
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { c.finishBoot() },
    ) {
        if (theme.scanlines) CrtPowerOn(c, accent) else OrbitIntro(c, accent)
    }
}

@Composable
private fun OrbitIntro(c: HomeController, accent: Color) {
    val t = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val sparks = remember {
        val r = Random(7)
        List(46) { Triple(r.nextFloat() * 2 * PI.toFloat(), 0.4f + r.nextFloat() * 0.6f, 1.5f + r.nextFloat() * 3f) }
    }
    LaunchedEffect(Unit) {
        c.sounds.play(Sounds.S.Boot)
        t.animateTo(1f, tween(1700, easing = LinearEasing))
        exit.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        c.finishBoot()
    }
    val p = t.value
    val e = exit.value
    val sweep = (p / 0.55f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) } // easeOut
    val word = ((p - 0.35f) / 0.35f).coerceIn(0f, 1f)

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - e; scaleX = 1f + e * 0.25f; scaleY = scaleX }
            .background(Color(0xFF020308)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val rw = size.minDimension * 0.62f; val rh = rw * 0.34f
            // світіння позаду
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * sweep), Color.Transparent), Offset(cx, cy), rw * 0.7f),
                rw * 0.7f, Offset(cx, cy),
            )
            rotate(-14f, Offset(cx, cy)) {
                val topLeft = Offset(cx - rw / 2, cy - rh / 2)
                // хвіст орбіти
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, accent, Color.White), Offset(cx, cy)),
                    startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false,
                    topLeft = topLeft, size = Size(rw, rh),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
                // «планета» на орбіті
                val a = Math.toRadians((-90.0 + 360.0 * sweep)).toFloat()
                val px = cx + rw / 2 * cos(a); val py = cy + rh / 2 * sin(a)
                drawCircle(Color.White, 6.dp.toPx() * (0.6f + 0.4f * sweep), Offset(px, py))
                drawCircle(accent.copy(alpha = 0.5f), 14.dp.toPx() * sweep, Offset(px, py))
            }
            // іскри, коли кільце замикається
            val burst = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (burst > 0f) for ((ang, speed, rad) in sparks) {
                val d = rw * 0.25f + burst * rw * 0.55f * speed
                drawCircle(
                    (if (speed > 0.75f) Color.White else accent).copy(alpha = (1f - burst) * 0.9f),
                    rad.dp.toPx() * (1f - burst * 0.6f),
                    Offset(cx + cos(ang) * d, cy + sin(ang) * d * 0.6f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = word }) {
            Text(
                "ORBIT", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black,
                letterSpacing = (24f - 14f * word).sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("press start", color = accent.copy(alpha = 0.8f), fontSize = 12.sp, letterSpacing = 6.sp)
        }
    }
}

/** Для теми CRT: старий кінескоп вмикається — точка, лінія, екран */
@Composable
private fun CrtPowerOn(c: HomeController, accent: Color) {
    val line = remember { Animatable(0f) }
    val open = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        c.sounds.play(Sounds.S.Boot)
        line.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        coroutineScope {
            launch { open.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
        }
        fade.animateTo(1f, tween(380))
        c.finishBoot()
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val lw = w * line.value
        val lh = 3f + (h - 3f) * open.value
        val left = (w - lw) / 2; val top = (h - lh) / 2
        // все довкола «вимкненого» екрана — чорне
        val black = Color.Black.copy(alpha = 1f - fade.value)
        drawRect(black, Offset(0f, 0f), Size(w, top))
        drawRect(black, Offset(0f, top + lh), Size(w, h - top - lh))
        drawRect(black, Offset(0f, top), Size(left, lh))
        drawRect(black, Offset(left + lw, top), Size(w - left - lw, lh))
        // яскравий спалах всередині
        drawRect(
            Color.White.copy(alpha = (1f - open.value * 0.8f) * (1f - fade.value)),
            Offset(left, top), Size(lw, lh),
        )
        drawRect(accent.copy(alpha = 0.25f * (1f - fade.value)), Offset(left, top), Size(lw, lh))
    }
}
