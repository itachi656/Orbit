package dev.orbit.launcher.ui

import dev.orbit.launcher.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


@Composable
fun MenuOverlay(c: HomeController, menu: Menu, accent: Color) {
    val theme = LocalTheme.current
    val Panel = theme.panel
    val Muted = theme.dim
    val selBg = if (theme.isLight) theme.text else Color.White
    val selFg = if (theme.isLight) Color.White else Color.Black
    @Suppress("UNUSED_VARIABLE") val refresh = c.menuVersion + c.games.size // перечитати значення пунктів
    val items = menu.build()
    val slide = remember { Animatable(80f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { alpha.animateTo(1f, tween(160)) }
            slide.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(menu.sel) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || menu.sel <= visible.first().index || menu.sel >= visible.last().index) {
            listState.animateScrollToItem((menu.sel - 3).coerceAtLeast(0))
        }
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(remember { MutableInteractionSource() }, null) { c.closeMenu() },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .graphicsLayer { translationX = slide.value * density }
                .fillMaxHeight()
                .width(420.dp)
                .background(Panel)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(top = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                // кнопка «назад» для дотику
                Text(
                    "‹", color = theme.text, fontSize = 30.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { c.closeMenu() }.padding(horizontal = 12.dp),
                )
                Text(
                    menu.title, color = theme.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.padding(start = 24.dp, top = 6.dp).height(2.dp).width(28.dp).background(accent))
            Spacer(Modifier.height(12.dp))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(items) { i, item ->
                    val selected = i == menu.sel
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) selBg else Color.Transparent)
                            .clickable { c.clickMenuItem(menu, i) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.label,
                            color = if (selected) selFg else theme.text,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        item.value?.let {
                            Text(
                                it,
                                color = if (selected) selFg.copy(alpha = 0.65f) else Muted,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(start = 12.dp).widthIn(max = 190.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FormOverlay(c: HomeController, form: TextForm) {
    val theme = LocalTheme.current
    val Panel = theme.panel
    val Muted = theme.dim
    val first = remember { FocusRequester() }
    LaunchedEffect(form) { runCatching { first.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 18.dp)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Panel)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(form.title, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            form.hint?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 16.sp) }
            form.fields.forEachIndexed { i, f ->
                OutlinedTextField(
                    value = f.value,
                    onValueChange = { f.value = it },
                    label = { Text(f.label) },
                    singleLine = true,
                    visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = if (i == form.fields.lastIndex) ImeAction.Done else ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
                Pill(tr("Скасувати  Ⓑ"), false) { c.form = null }
                Pill(tr("Зберегти  ☰"), true) { c.submitForm(form) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Text(
        text,
        color = if (primary) (if (theme.isLight) Color.White else Color.Black) else theme.text,
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) (if (theme.isLight) theme.text else Color.White) else theme.text.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}
