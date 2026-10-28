package dev.orbit.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.orbit.launcher.data.Systems

/**
 * Силуети консолей, намальовані векторно (без брендових логотипів).
 * Координати — у квадраті 100×100, масштабуються під будь-який розмір.
 */
@Composable
fun ConsoleIcon(system: String, modifier: Modifier, tint: Color = Color.White) {
    when (system) {
        "home" -> Icon(Icons.Filled.Home, null, tint = tint, modifier = modifier)
        "fav" -> Icon(Icons.Filled.Star, null, tint = tint, modifier = modifier)
        Systems.ANDROID -> Icon(Icons.Filled.Android, null, tint = tint, modifier = modifier)
        Systems.CLOUD -> Icon(Icons.Filled.Cloud, null, tint = tint, modifier = modifier)
        else -> if (system.startsWith("col:")) Icon(Icons.Filled.Folder, null, tint = tint, modifier = modifier) else ConsoleDrawn(system, modifier, tint)
    }
}

@Composable
private fun ConsoleDrawn(system: String, modifier: Modifier, tint: Color) {
    when (system) {
        in drawers -> Canvas(modifier) { Unit100(this, tint).apply { drawers.getValue(system)(this) } }
        else -> Box(modifier) { Icon(Icons.Filled.SportsEsports, null, tint = tint, modifier = modifier) }
    }
}

private val Dark = Color(0xFF151821)
private val Light = Color(0xFFE4E6EE)
private val Grey = Color(0xFFB9BCC6)

/** Малювання в одиницях 0..100 з центруванням */
private class Unit100(val ds: DrawScope, val tint: Color) {
    val s = ds.size.minDimension / 100f
    val ox = (ds.size.width - 100f * s) / 2
    val oy = (ds.size.height - 100f * s) / 2
    private fun o(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

    fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color, outline: Boolean = false) {
        ds.drawRoundRect(c, o(x, y), Size(w * s, h * s), CornerRadius(r * s))
        if (outline) ds.drawRoundRect(tint.copy(alpha = 0.55f), o(x, y), Size(w * s, h * s), CornerRadius(r * s), style = Stroke(1.6f * s))
    }

    fun circle(x: Float, y: Float, r: Float, c: Color) = ds.drawCircle(c, r * s, o(x, y))
    fun ring(x: Float, y: Float, r: Float, c: Color, w: Float = 2f) = ds.drawCircle(c, r * s, o(x, y), style = Stroke(w * s))

    fun dpad(x: Float, y: Float, size: Float, c: Color = Dark) {
        val t = size / 3
        rr(x - size / 2, y - t / 2, size, t, 1f, c)
        rr(x - t / 2, y - size / 2, t, size, 1f, c)
    }
}

private val drawers: Map<String, Unit100.() -> Unit> = mapOf(
    "gb" to { gameBoy(Light, Color(0xFF8BAC0F)) },
    "gbc" to { gameBoy(Color(0xFF8E44D9), Color(0xFF9BBC0F)) },
    "gba" to {
        rr(8f, 28f, 84f, 44f, 20f, Color(0xFF5B3FD1), outline = true)
        rr(32f, 33f, 36f, 30f, 2f, Dark)
        rr(35f, 36f, 30f, 24f, 1f, Color(0xFF9BBC0F))
        dpad(20f, 48f, 13f)
        circle(80f, 44f, 3.6f, Light); circle(73f, 51f, 3.6f, Light)
    },
    "nds" to { clamshell(Grey, topWide = false) },
    "n3ds" to { clamshell(Color(0xFFD7263D), topWide = true) },
    "psp" to {
        rr(5f, 30f, 90f, 40f, 20f, Dark, outline = true)
        rr(25f, 34f, 50f, 32f, 2f, Color(0xFF2B3A5A))
        dpad(15f, 46f, 11f, Grey)
        circle(85f, 41f, 2.6f, Grey); circle(85f, 51f, 2.6f, Grey); circle(80f, 46f, 2.6f, Grey); circle(90f, 46f, 2.6f, Grey)
        circle(15f, 61f, 3f, Grey)
    },
    "psvita" to {
        rr(4f, 28f, 92f, 44f, 22f, Dark, outline = true)
        rr(24f, 32f, 52f, 36f, 3f, Color(0xFF1E40AF))
        dpad(14f, 42f, 10f, Grey)
        circle(86f, 37f, 2.4f, Grey); circle(86f, 47f, 2.4f, Grey); circle(81f, 42f, 2.4f, Grey); circle(91f, 42f, 2.4f, Grey)
        circle(14f, 60f, 4f, Grey); circle(86f, 60f, 4f, Grey)
    },
    "switch" to {
        rr(8f, 26f, 18f, 48f, 9f, Color(0xFF00B4E6))
        rr(74f, 26f, 18f, 48f, 9f, Color(0xFFFF4554))
        rr(24f, 26f, 52f, 48f, 2f, Dark, outline = true)
        rr(28f, 30f, 44f, 40f, 1f, Color(0xFF2A2F3A))
        circle(17f, 38f, 3.4f, Dark); circle(83f, 58f, 3.4f, Dark)
        circle(17f, 58f, 2f, Dark); circle(83f, 38f, 2f, Dark)
    },
    "nes" to {
        rr(10f, 36f, 80f, 36f, 3f, Grey, outline = true)
        rr(10f, 36f, 80f, 14f, 3f, Color(0xFF8A8D96))
        rr(24f, 40f, 52f, 5f, 1f, Dark)
        rr(18f, 58f, 8f, 6f, 1f, Color(0xFFE4002B)); rr(30f, 58f, 8f, 6f, 1f, Color(0xFFE4002B))
        rr(52f, 56f, 32f, 10f, 1f, Dark)
    },
    "snes" to {
        rr(10f, 36f, 80f, 34f, 10f, Light, outline = true)
        rr(28f, 30f, 44f, 14f, 4f, Grey)
        rr(32f, 34f, 36f, 4f, 1f, Dark)
        rr(18f, 52f, 9f, 6f, 1.5f, Color(0xFF7B5CD6)); rr(73f, 52f, 9f, 6f, 1.5f, Color(0xFF7B5CD6))
        circle(50f, 58f, 3f, Color(0xFF7B5CD6))
    },
    "n64" to {
        // контролер-«тризуб»
        rr(10f, 30f, 80f, 24f, 12f, Grey, outline = true)
        rr(12f, 44f, 18f, 36f, 9f, Grey, outline = true)
        rr(41f, 44f, 18f, 42f, 9f, Grey, outline = true)
        rr(70f, 44f, 18f, 36f, 9f, Grey, outline = true)
        dpad(22f, 40f, 11f)
        circle(50f, 52f, 3.5f, Dark)
        circle(76f, 37f, 3f, Color(0xFF1F9D55)); circle(82f, 43f, 3f, Color(0xFF2B59C3))
        circle(70f, 43f, 2.4f, Color(0xFFF5C518)); circle(76f, 48f, 2.4f, Color(0xFFF5C518))
    },
    "genesis" to {
        rr(8f, 40f, 84f, 32f, 6f, Dark, outline = true)
        ring(38f, 52f, 15f, Color(0xFF444A57), 3f)
        rr(28f, 44f, 20f, 4f, 1f, Color(0xFF000000))
        rr(62f, 50f, 22f, 4f, 1f, Color(0xFFE4002B))
        rr(62f, 58f, 22f, 2f, 1f, Grey)
    },
    "psx" to {
        rr(8f, 34f, 84f, 38f, 4f, Grey, outline = true)
        circle(38f, 53f, 16f, Color(0xFFCDD0D8))
        ring(38f, 53f, 16f, Color(0xFF8A8D96), 1.6f)
        circle(74f, 46f, 3f, Dark); circle(84f, 46f, 3f, Dark)
        rr(68f, 58f, 20f, 3f, 1f, Color(0xFF8A8D96))
    },
    "ps2" to {
        rr(30f, 8f, 40f, 84f, 3f, Dark, outline = true)
        for (i in 0 until 7) rr(34f, 16f + i * 9f, 32f, 1.6f, 0.8f, Color(0xFF2B2F3A))
        rr(66f, 14f, 2f, 60f, 1f, Color(0xFF0A0B10))
        circle(50f, 84f, 2.4f, Color(0xFF2B59C3))
    },
    "gc" to {
        rr(36f, 14f, 28f, 10f, 3f, Color(0xFF4B2A9E))
        rr(18f, 22f, 64f, 64f, 8f, Color(0xFF6D28D9), outline = true)
        circle(50f, 50f, 17f, Color(0xFF5B21B6))
        ring(50f, 50f, 17f, Color(0xFF8B5CF6), 2f)
        circle(50f, 50f, 4f, Color(0xFF8B5CF6))
    },
    "wii" to {
        rr(38f, 8f, 24f, 82f, 5f, Light, outline = true)
        rr(48.5f, 14f, 3f, 52f, 1.5f, Grey)
        circle(50f, 74f, 2.6f, Color(0xFF0EA5E9))
        rr(34f, 88f, 32f, 5f, 2f, Grey)
    },
    "windows" to {
        rr(10f, 14f, 80f, 54f, 4f, Dark, outline = true)
        rr(14f, 18f, 72f, 46f, 2f, Color(0xFF0078D4))
        rr(45f, 68f, 10f, 12f, 1f, Grey)
        rr(30f, 80f, 40f, 6f, 3f, Grey)
    },
)

private fun Unit100.gameBoy(body: Color, screen: Color) {
    rr(26f, 6f, 48f, 88f, 6f, body, outline = true)
    rr(32f, 13f, 36f, 32f, 3f, Color(0xFF5A5E6B))
    rr(37f, 17f, 26f, 23f, 1f, screen)
    dpad(40f, 62f, 13f)
    circle(64f, 59f, 4f, Color(0xFFB0185A)); circle(56f, 66f, 4f, Color(0xFFB0185A))
    rr(42f, 78f, 6f, 2f, 1f, Color(0xFF5A5E6B)); rr(52f, 78f, 6f, 2f, 1f, Color(0xFF5A5E6B))
}

private fun Unit100.clamshell(body: Color, topWide: Boolean) {
    rr(18f, 8f, 64f, 40f, 4f, body, outline = true)
    if (topWide) rr(23f, 12f, 54f, 32f, 2f, Dark) else rr(29f, 13f, 42f, 30f, 2f, Dark)
    rr(18f, 52f, 64f, 40f, 4f, body, outline = true)
    rr(32f, 56f, 36f, 30f, 2f, Dark)
    dpad(24f, 68f, 9f)
    circle(76f, 64f, 2.2f, Dark); circle(76f, 72f, 2.2f, Dark); circle(72f, 68f, 2.2f, Dark); circle(80f, 68f, 2.2f, Dark)
    if (topWide) circle(24f, 59f, 3f, Dark)
    rr(18f, 48f, 64f, 4f, 1f, body.copy(alpha = 0.6f))
}
