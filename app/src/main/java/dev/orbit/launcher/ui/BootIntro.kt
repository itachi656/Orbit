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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Іконки застосунку: id псевдоніма в маніфесті (Icon<id>) → назва */
val AppIcons = listOf(
    "Planet" to "Планета",
    "Cross" to "Хрестовина",
    "Stripes" to "Смуги",
    "Pixel" to "Піксель",
)

/** Варіанти анімації запуску (однакові для всіх тем) */
val BootStyles = listOf(
    "orbit" to "Орбіта",
    "slices" to "Зрізи",
    "pulse" to "Імпульс",
    "cartridge" to "Картридж",
    "warp" to "Гіперстрибок",
    "crt" to "Кінескоп",
    "random" to "Випадкова",
)

/** Анімація запуску самого лаунчера. Будь-яка кнопка або дотик — пропустити. */
@Composable
fun BootIntro(c: HomeController, accent: Color) {
    val style = remember {
        c.app.prefs.bootStyle.let { s -> if (s == "random") BootStyles.map { it.first }.filter { it != "random" }.random() else s }
    }
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { c.finishBoot() },
    ) {
        when (style) {
            "slices" -> SlicesIntro(c, accent)
            "pulse" -> PulseIntro(c, accent)
            "cartridge" -> CartridgeIntro(c)
            "warp" -> WarpIntro(c, accent)
            "crt" -> CrtPowerOn(c, accent)
            else -> OrbitIntro(c, accent)
        }
    }
}

/** Спільний годинник: [ms] основна частина, потім зникнення; повертає (прогрес, зникнення) */
@Composable
private fun bootClock(c: HomeController, ms: Int, sound: Boolean = true): Pair<Float, Float> {
    val t = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (sound) c.sounds.play(Sounds.S.Boot)
        t.animateTo(1f, tween(ms, easing = LinearEasing))
        exit.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        c.finishBoot()
    }
    return t.value to exit.value
}

private fun ease(x: Float) = x.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }

@Composable
private fun BootWord(accent: Color, alpha: Float, color: Color = Color.White, font: FontFamily = FontFamily.Default) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
        Text("ORBIT", color = color, fontSize = 44.sp, fontWeight = FontWeight.Black, fontFamily = font, letterSpacing = (24f - 14f * alpha).sp)
        Spacer(Modifier.height(4.dp))
        Text("press start", color = accent.copy(alpha = 0.8f), fontSize = 12.sp, fontFamily = font, letterSpacing = 6.sp)
    }
}

/** Зрізи: кольорові нахилені смуги влітають справа і розходяться, відкриваючи логотип */
@Composable
private fun SlicesIntro(c: HomeController, accent: Color) {
    val (p, e) = bootClock(c, 1900)
    val colors = listOf(Color(0xFFE0457B), accent, Color(0xFF3DDC84), Color(0xFFF5C518), Color(0xFF8B5CF6), Color(0xFF06B6D4), Color(0xFFFF7A59))
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - e }.background(Color(0xFF05070F)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val sw = w / 5.2f; val sk = h * 0.32f
            val open = ease((p - 0.5f) * 2.6f)
            colors.forEachIndexed { i, col ->
                val inP = ease(p * 2.1f - i * 0.07f)
                val side = if (i < colors.size / 2f) -1f else 1f
                val x = -sw * 0.6f + i * sw * 0.98f + (1f - inP) * w * 1.3f + side * open * w * 0.75f
                val path = Path().apply {
                    moveTo(x + sk, 0f); lineTo(x + sk + sw, 0f); lineTo(x + sw, h); lineTo(x, h); close()
                }
                drawPath(path, col.copy(alpha = 0.92f))
            }
        }
        BootWord(accent, ease((p - 0.55f) * 2.5f))
    }
}

/** Імпульс: точка пульсує, вибухає хвилею, на орбіті з'являється планета */
@Composable
private fun PulseIntro(c: HomeController, accent: Color) {
    val (p, e) = bootClock(c, 2000)
    val dots = remember { val r = Random(3); List(40) { r.nextFloat() * 2 * PI.toFloat() to 0.5f + r.nextFloat() * 0.8f } }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - e; scaleX = 1f + e * 0.2f; scaleY = scaleX }.background(Color(0xFF020308)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2; val m = size.minDimension
            if (p < 0.35f) {
                // серцебиття
                val beat = abs(sin(p / 0.35f * 3 * PI.toFloat()))
                drawCircle(accent.copy(alpha = 0.3f), 10.dp.toPx() + beat * 18.dp.toPx(), Offset(cx, cy))
                drawCircle(Color.White, 5.dp.toPx() + beat * 3.dp.toPx(), Offset(cx, cy))
            } else {
                val q = ease((p - 0.35f) / 0.65f)
                // ударна хвиля
                drawCircle(accent.copy(alpha = (1f - q) * 0.8f), q * m * 0.9f, Offset(cx, cy), style = Stroke(3.dp.toPx() * (1f - q) + 1f))
                for ((a, sp) in dots) {
                    val d = q * m * 0.55f * sp
                    drawCircle(Color.White.copy(alpha = (1f - q) * 0.9f), 2.dp.toPx(), Offset(cx + cos(a) * d, cy + sin(a) * d * 0.7f))
                }
                // орбіта з планетою
                val rw = m * 0.62f * q; val rh = rw * 0.3f
                rotate(-14f, Offset(cx, cy)) {
                    drawOval(accent.copy(alpha = 0.8f), Offset(cx - rw / 2, cy - rh / 2), Size(rw, rh), style = Stroke(2.dp.toPx()))
                    val ang = p * 9f
                    drawCircle(Color.White, 6.dp.toPx(), Offset(cx + rw / 2 * cos(ang), cy + rh / 2 * sin(ang)))
                }
            }
        }
        BootWord(accent, ease((p - 0.5f) * 2.5f))
    }
}

/** Картридж: піксельний логотип з'їжджає зверху на зеленому екрані, потім «дзинь» */
@Composable
private fun CartridgeIntro(c: HomeController) {
    val (p, e) = bootClock(c, 2200, sound = false)
    val dark = Color(0xFF0F380F)
    val land = 0.6f
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((2200 * land).toLong())
        c.sounds.play(Sounds.S.Select)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - e }.background(Color(0xFF9BBC0F)),
        contentAlignment = Alignment.TopCenter,
    ) {
        val y = maxHeight / 2 - 40.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = -60.dp + (y + 60.dp) * (p / land).coerceAtMost(1f)),
        ) {
            Text("ORBIT", color = dark, fontSize = 48.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "press start", color = dark.copy(alpha = if (p > land) 1f else 0f),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp,
            )
        }
    }
}

/** Гіперстрибок: зорі розтягуються в лінії, спалах — і логотип */
@Composable
private fun WarpIntro(c: HomeController, accent: Color) {
    val (p, e) = bootClock(c, 1900)
    val stars = remember { val r = Random(11); List(140) { Triple(r.nextFloat() * 2 * PI.toFloat(), r.nextFloat(), 0.5f + r.nextFloat()) } }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - e }.background(Color(0xFF02040A)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2; val far = size.maxDimension * 0.75f
            val speed = ease(p / 0.65f) * (1f - ease((p - 0.65f) * 4f))
            for ((a, r0, v) in stars) {
                val r = (r0 + p * v * 1.6f) % 1.2f
                val d = r * r * far
                val len = speed * far * 0.35f * r
                val dx = cos(a); val dy = sin(a)
                val col = (if (v > 1.2f) accent else Color.White).copy(alpha = (r * 1.8f).coerceAtMost(1f))
                drawLine(col, Offset(cx + dx * d, cy + dy * d), Offset(cx + dx * (d + len + 1f), cy + dy * (d + len + 1f)), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
            // спалах на виході зі стрибка
            val flash = (1f - abs((p - 0.66f) / 0.08f)).coerceIn(0f, 1f)
            if (flash > 0f) drawRect(Color.White.copy(alpha = flash * 0.85f))
        }
        BootWord(accent, ease((p - 0.68f) * 3f))
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
