package dev.orbit.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.orbit.launcher.input.Btn
import dev.orbit.launcher.tr

/** Пункт швидкого меню. slider != null — повзунок 0..1 */
class QuickItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val slider: Float? = null,
)

class QuickState {
    var sel by mutableIntStateOf(0)
    var brightness by mutableFloatStateOf(0.5f)
    var volume by mutableFloatStateOf(0.5f)
}

fun HomeController.quickItems(): List<QuickItem> = buildList {
    val q = quick ?: return@buildList
    lastGameTitle()?.let { add(QuickItem("resume", tr("Повернутися: %s", it), Icons.Filled.PlayArrow)) }
    add(QuickItem("brightness", tr("Яскравість"), Icons.Filled.BrightnessMedium, q.brightness))
    add(QuickItem("volume", tr("Гучність"), Icons.Filled.VolumeUp, q.volume))
    add(QuickItem("wifi", "Wi-Fi", Icons.Filled.Wifi))
    add(QuickItem("bt", "Bluetooth", Icons.Filled.Bluetooth))
    add(QuickItem("search", tr("Пошук"), Icons.Filled.Search))
    add(QuickItem("settings", tr("Налаштування"), Icons.Filled.Settings))
    add(QuickItem("android", tr("Домашній екран Android"), Icons.Filled.Home))
}

fun HomeController.handleQuickButton(b: Btn) {
    val q = quick ?: return
    val items = quickItems()
    val item = items.getOrNull(q.sel)
    when (b) {
        Btn.Up -> if (q.sel > 0) { q.sel--; uiMove() }
        Btn.Down -> if (q.sel < items.lastIndex) { q.sel++; uiMove() }
        Btn.Left, Btn.Right -> if (item?.slider != null) {
            val d = if (b == Btn.Right) 0.0625f else -0.0625f
            adjustQuick(item.id, d)
        }
        Btn.A -> item?.let { activateQuick(it.id) }
        Btn.B, Btn.Guide -> closeQuick()
        else -> Unit
    }
}

@Composable
fun QuickMenuOverlay(c: HomeController, q: QuickState, accent: Color) {
    val theme = LocalTheme.current
    val on = if (theme.isLight) theme.text else Color.White
    val onSel = if (theme.isLight) Color.White else Color.Black
    val slide = remember { Animatable(-60f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
    val items = c.quickItems()

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, null) { c.closeQuick() },
    ) {
        Column(
            Modifier
                .graphicsLayer { translationX = slide.value * density }
                .fillMaxHeight().width(380.dp)
                .background(theme.panel)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // статус: годинник, заряд телефона й геймпада
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)) {
                Clock(c.look.clock24)
                Spacer(Modifier.weight(1f))
                c.padBattery?.let {
                    Icon(Icons.Filled.SportsEsports, null, tint = if (it <= 15) Color(0xFFFF5A5A) else theme.dim, modifier = Modifier.size(18.dp))
                    Text(" $it%", color = if (it <= 15) Color(0xFFFF5A5A) else theme.dim, fontSize = 13.sp)
                }
            }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(q.sel) { listState.animateScrollToItem((q.sel - 2).coerceAtLeast(0)) }
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items.size) { i ->
                    val item = items[i]
                    val selected = i == q.sel
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) on else on.copy(alpha = 0.06f))
                            .clickable { q.sel = i; if (item.slider == null) c.activateQuick(item.id) }
                            .padding(horizontal = 14.dp, vertical = if (item.slider != null) 8.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(item.icon, null, tint = if (selected) onSel else on, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        if (item.slider != null) {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, color = if (selected) onSel else on, fontSize = 14.sp)
                                Spacer(Modifier.height(5.dp))
                                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background((if (selected) onSel else on).copy(alpha = 0.2f))) {
                                    Box(Modifier.fillMaxWidth(item.slider.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(accent))
                                }
                            }
                            Text(
                                " ◀ ▶", color = (if (selected) onSel else on).copy(alpha = 0.6f), fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            Row {
                                Text(" −", color = if (selected) onSel else on, fontSize = 20.sp, modifier = Modifier.clickable { q.sel = i; c.adjustQuick(item.id, -0.1f) }.padding(horizontal = 6.dp))
                                Text("+", color = if (selected) onSel else on, fontSize = 20.sp, modifier = Modifier.clickable { q.sel = i; c.adjustQuick(item.id, 0.1f) }.padding(horizontal = 6.dp))
                            }
                        } else {
                            Text(
                                item.label, color = if (selected) onSel else on, fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(tr("Guide / B — закрити"), color = theme.dim, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
