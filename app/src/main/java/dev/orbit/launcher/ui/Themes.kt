package dev.orbit.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Як розкладені ігри на екрані — від цього залежить і керування */
enum class Layout { Row, Xmb, Carousel, Grid, List, Channels, Blades, Slices }

data class ThemeSpec(
    val id: String,
    val name: String,
    val description: String,
    val layout: Layout,
    val bg: Color,
    val panel: Color,
    val text: Color,
    val dim: Color,
    /** null = колір поточної системи */
    val accent: Color?,
    /** auto-фон теми: art | blur | animated | waves | plain */
    val background: String,
    val corner: Dp = 12.dp,
    val font: FontFamily = FontFamily.Default,
    /** Пульсуюча кольорова рамка навколо вибраної плитки (як у Switch) */
    val glowSelect: Boolean = false,
    val scanlines: Boolean = false,
    /** Ряд вирівняний по центру, а не зліва */
    val centered: Boolean = false,
    /** Колір теми змінюється щомісяця (як на PSP) */
    val monthColor: Boolean = false,
) {
    val isLight get() = bg.luminance() > 0.5f
}

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue

object Themes {
    val all = listOf(
        ThemeSpec(
            "orbit", "Orbit", "Як PS5: ряд плиток, великий арт гри на фоні",
            Layout.Row, Color(0xFF05070F), Color(0xF20B1020), Color.White, Color(0xFF9AA3B8),
            accent = null, background = "art", corner = 12.dp,
        ),
        ThemeSpec(
            "waves", "Хвилі", "Як XMB на PS3: категорії горизонтально, ігри вертикально",
            Layout.Xmb, Color(0xFF0A1630), Color(0xEE0A1630), Color.White, Color(0xFFB8C4E0),
            accent = Color(0xFF6FA8FF), background = "waves", corner = 6.dp,
        ),
        ThemeSpec(
            "neon_light", "Неон світлий", "Як Switch: великі плитки по центру, світла тема",
            Layout.Row, Color(0xFFEBEBEB), Color(0xF7FFFFFF), Color(0xFF2D2D2D), Color(0xFF6B6B6B),
            accent = Color(0xFF00C3E3), background = "plain", corner = 4.dp, glowSelect = true, centered = true,
        ),
        ThemeSpec(
            "neon_dark", "Неон темний", "Як Switch, але темний",
            Layout.Row, Color(0xFF2D2D2D), Color(0xF7383838), Color.White, Color(0xFFADADAD),
            accent = Color(0xFF00C3E3), background = "plain", corner = 4.dp, glowSelect = true, centered = true,
        ),
        ThemeSpec(
            "carousel", "Карусель", "3D-карусель обкладинок, як у класичних фронтендах",
            Layout.Carousel, Color(0xFF0B0B12), Color(0xF0141420), Color.White, Color(0xFFA0A0B8),
            accent = null, background = "blur", corner = 10.dp,
        ),
        ThemeSpec(
            "library", "Бібліотека", "Як Steam Deck: сітка обкладинок",
            Layout.Grid, Color(0xFF14171F), Color(0xF71B2029), Color.White, Color(0xFF8B929E),
            accent = Color(0xFF1A9FFF), background = "blur", corner = 6.dp,
        ),
        ThemeSpec(
            "crt", "Ретро CRT", "Зелений термінал зі смугами, як старий монітор",
            Layout.List, Color(0xFF020A04), Color(0xF5031006), Color(0xFF39FF6A), Color(0xFF1E9E44),
            accent = Color(0xFF39FF6A), background = "plain", corner = 0.dp,
            font = FontFamily.Monospace, scanlines = true,
        ),
        ThemeSpec(
            "blades", "Blades", "Як Xbox 360: розділи-«леза» збоку, ігри списком",
            Layout.Blades, Color(0xFF2B7A0B), Color(0xF0F4F7F2), Color.White, Color(0xFFD6F5C2),
            accent = Color(0xFF7DDB2C), background = "blades", corner = 4.dp,
        ),
        ThemeSpec(
            "channels", "Канали", "Як Wii: сітка «телевізорів», світлий фон",
            Layout.Channels, Color(0xFFE9EDF0), Color(0xF7FFFFFF), Color(0xFF3A3F45), Color(0xFF8A9199),
            accent = Color(0xFF34BEED), background = "channels", corner = 18.dp, glowSelect = true,
        ),
        ThemeSpec(
            "swirl", "Спіраль", "Як Dreamcast: помаранчева спіраль, ряд плиток",
            Layout.Row, Color(0xFFF1F3F6), Color(0xF7FFFFFF), Color(0xFF2A2D33), Color(0xFF7A8088),
            accent = Color(0xFFF26A21), background = "swirl", corner = 8.dp,
        ),
        ThemeSpec(
            "pocket", "Кишеньковий", "Як PSP: хвилі, колір змінюється щомісяця",
            Layout.Xmb, Color(0xFF101418), Color(0xEE14181D), Color.White, Color(0xFFC9D2DC),
            accent = null, background = "waves", corner = 4.dp, monthColor = true,
        ),
        ThemeSpec(
            "sunset", "Захід сонця", "Ряд плиток як Orbit, теплі кольори й анімований фон",
            Layout.Row, Color(0xFF1A0B1E), Color(0xF2261029), Color.White, Color(0xFFD9A7C7),
            accent = Color(0xFFFF7A59), background = "animated", corner = 18.dp,
        ),
        ThemeSpec(
            "showcase", "Вітрина", "Як ES-DE: вибір системи на тлі нахилених смуг з артами ігор",
            Layout.Slices, Color(0xFF07080C), Color(0xF0101218), Color.White, Color(0xFFC4C8D2),
            accent = null, background = "plain", corner = 6.dp,
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Набір акцентних кольорів для налаштувань */
val AccentChoices = listOf(
    "auto" to "Як у теми / системи",
    "FF4D7CFF" to "Синій",
    "FF8B5CF6" to "Фіолетовий",
    "FFE11D48" to "Червоний",
    "FF22C55E" to "Зелений",
    "FFF59E0B" to "Помаранчевий",
    "FF06B6D4" to "Бірюзовий",
    "FFEC4899" to "Рожевий",
    "FFFFFFFF" to "Білий",
)
