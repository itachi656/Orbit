package dev.orbit.launcher.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.orbit.launcher.data.Systems
import dev.orbit.launcher.input.Btn
import dev.orbit.launcher.store.StoreItem
import dev.orbit.launcher.tr

/** Стан екрана магазину */
class StoreUi {
    /** "all" або id системи */
    var filter by mutableStateOf("all")
    var sel by mutableIntStateOf(0)
    var query by mutableStateOf("")
}

const val STORE_COLS = 4

/** Ігри магазину з урахуванням фільтра і пошуку */
fun HomeController.storeItems(ui: StoreUi, all: List<StoreItem>): List<StoreItem> {
    val q = ui.query.lowercase().split(' ').filter { it.isNotBlank() }
    return all.asSequence()
        .filter { ui.filter == "all" || it.system == ui.filter }
        .filter { item -> q.all { w -> item.title.lowercase().contains(w) || item.developer.lowercase().contains(w) } }
        .sortedBy { it.title.lowercase().trimStart { ch -> !ch.isLetterOrDigit() } }
        .toList()
}

fun storeFilters(all: List<StoreItem>): List<String> =
    listOf("all") + Systems.all.map { it.id }.filter { id -> all.any { it.system == id } }

fun HomeController.handleStoreButton(ui: StoreUi, b: Btn) {
    val all = app.store.items.value
    val items = storeItems(ui, all)
    val filters = storeFilters(all)
    fun setSel(i: Int) {
        if (items.isEmpty()) return
        val n = i.coerceIn(0, items.lastIndex)
        if (n != ui.sel) { ui.sel = n; uiMove() }
    }
    fun switchFilter(d: Int) {
        val i = (filters.indexOf(ui.filter).coerceAtLeast(0) + d).coerceIn(0, filters.lastIndex)
        if (filters[i] != ui.filter) { ui.filter = filters[i]; ui.sel = 0; sounds.play(Sounds.S.Tab) }
    }
    when (b) {
        Btn.Left -> setSel(ui.sel - 1)
        Btn.Right -> setSel(ui.sel + 1)
        Btn.Up -> setSel(ui.sel - STORE_COLS)
        Btn.Down -> setSel(ui.sel + STORE_COLS)
        Btn.L1 -> switchFilter(-1)
        Btn.R1 -> switchFilter(1)
        Btn.L2 -> setSel(ui.sel - STORE_COLS * 3)
        Btn.R2 -> setSel(ui.sel + STORE_COLS * 3)
        Btn.A -> items.getOrNull(ui.sel)?.let { storeAction(it) }
        Btn.Y -> openStoreSources()
        Btn.Select -> openTextEntry(tr("Пошук у магазині"), ui.query) { q -> ui.query = q; ui.sel = 0; store = ui }
        Btn.X -> if (ui.query.isNotEmpty()) { ui.query = ""; ui.sel = 0; uiBack() }
        Btn.B -> if (ui.query.isNotEmpty()) { ui.query = ""; ui.sel = 0; uiBack() } else closeStore()
        Btn.Start -> openStoreSources()
        else -> Unit
    }
}

@Composable
fun StoreOverlay(c: HomeController, ui: StoreUi, accent: Color) {
    val theme = LocalTheme.current
    val on = if (theme.isLight) theme.text else Color.White
    val onSel = if (theme.isLight) Color.White else Color.Black
    val all by c.app.store.items.collectAsState()
    val loading by c.app.store.loading.collectAsState()
    val progress by c.app.store.progress.collectAsState()
    val errors by c.app.store.errors.collectAsState()
    val items = c.storeItems(ui, all)
    val filters = storeFilters(all)
    val sel = items.getOrNull(ui.sel.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    @Suppress("UNUSED_VARIABLE") val tick = c.storeVersion // перемалювати стан «встановлено»

    Box(
        Modifier.fillMaxSize().background(theme.bg.copy(alpha = 0.99f))
            .clickable(remember { MutableInteractionSource() }, null) {},
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp)) {
            // верх: назва, фільтри за системами, стан
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = theme.text, fontSize = 30.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { c.closeStore() }.padding(horizontal = 10.dp))
                Icon(Icons.Filled.Storefront, null, tint = accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Магазин"), color = theme.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                val chips = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(ui.filter, filters.size) { chips.animateScrollToItem((filters.indexOf(ui.filter) - 2).coerceAtLeast(0)) }
                LazyRow(Modifier.weight(1f), state = chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(filters) { _, f ->
                        val selected = f == ui.filter
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (selected) on else on.copy(alpha = 0.08f))
                                .clickable { ui.filter = f; ui.sel = 0 }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (f != "all") {
                                ConsoleIcon(f, Modifier.size(18.dp), tint = if (selected) onSel else on)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                if (f == "all") tr("Усі") else Systems.label(f),
                                color = if (selected) onSel else on, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Text(
                    when {
                        loading -> tr("Оновлюю каталоги…")
                        ui.query.isNotEmpty() -> tr("«%s»: %s", ui.query, items.size)
                        else -> tr("Ігор: %s", items.size)
                    },
                    color = theme.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            Row(Modifier.weight(1f)) {
                // сітка ігор
                val grid = rememberLazyGridState()
                LaunchedEffect(ui.sel, ui.filter, ui.query) {
                    if (items.isNotEmpty()) grid.animateScrollToItem(((ui.sel / STORE_COLS) - 1).coerceAtLeast(0) * STORE_COLS)
                }
                Box(Modifier.weight(1.45f).fillMaxHeight()) {
                    if (items.isEmpty()) {
                        Text(
                            if (loading) tr("Завантажую каталоги…") else if (errors.isNotEmpty()) tr("Не вдалося завантажити каталоги — перевір інтернет") else tr("Тут поки порожньо"),
                            color = theme.dim, fontSize = 15.sp, modifier = Modifier.padding(top = 30.dp, start = 8.dp),
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(STORE_COLS), state = grid,
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp, top = 4.dp, start = 4.dp, end = 4.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                            val selected = i == ui.sel
                            val scale by animateFloatAsState(if (selected) 1.05f else 1f, tween(140), label = "s")
                            Column(
                                Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) on.copy(alpha = 0.14f) else Color.Transparent)
                                    .border(if (selected) 2.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable { if (selected) c.storeAction(item) else ui.sel = i }
                                    .padding(6.dp),
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(theme.panel)) {
                                    if (item.cover != null) {
                                        AsyncImage(model = item.cover, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else {
                                        ConsoleIcon(item.system, Modifier.fillMaxSize().padding(22.dp), tint = theme.dim)
                                    }
                                    val p = progress[item.key]
                                    if (p != null) {
                                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.5f))) {
                                            Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.03f)).fillMaxHeight().background(accent))
                                        }
                                    } else if (c.app.store.installedFile(item) != null) {
                                        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF3DDC84), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
                                    }
                                    Text(
                                        Systems.label(item.system), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(item.title, color = theme.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(18.dp))
                // деталі вибраної гри
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    sel?.let { item ->
                        val shot = item.screenshots.firstOrNull() ?: item.cover
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(theme.panel)) {
                            if (shot != null) AsyncImage(model = shot, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(item.title, color = theme.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val source = c.app.store.sources.value.firstOrNull { it.id == item.sourceId }?.name ?: ""
                        Text(
                            listOfNotNull(
                                Systems.byId[item.system]?.name,
                                item.developer.ifBlank { null },
                                item.size.takeIf { it > 0 }?.let { formatSize(it) },
                                source,
                            ).joinToString("  ·  "),
                            color = theme.dim, fontSize = 12.sp, maxLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        val p = progress[item.key]
                        val installed = c.app.store.installedFile(item) != null
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(if (installed) on.copy(alpha = 0.9f) else accent)
                                .clickable { c.storeAction(item) }.padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                when { p != null -> Icons.Filled.Download; installed -> Icons.Filled.PlayArrow; else -> Icons.Filled.Download },
                                null, tint = if (installed) onSel else Color.White, modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    p != null -> tr("Завантаження %s%%", (p * 100).toInt())
                                    installed -> tr("Грати")
                                    else -> tr("Завантажити")
                                },
                                color = if (installed) onSel else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            item.description, color = theme.text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 17.sp,
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
            Text(
                tr("A завантажити / грати · LB RB система · Select пошук · Y джерела · B назад") + "   ·   " +
                    tr("Свої джерела — на твою відповідальність"),
                color = theme.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format("%d KB", (bytes / 1024).coerceAtLeast(1))
}
