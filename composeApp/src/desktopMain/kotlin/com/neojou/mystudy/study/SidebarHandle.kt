package com.neojou.mystudy.study

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import kotlin.math.abs

/**
 * Six-dp hit target between the vault tree and the note.
 * Dragging reports pixel deltas in dp. A double click resets the width.
 * Left and right arrows nudge by 16 dp when the handle has focus.
 */
@Composable
internal fun SidebarHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onNudge: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startCb by rememberUpdatedState(onDragStart)
    val dragCb by rememberUpdatedState(onDrag)
    val endCb by rememberUpdatedState(onDragEnd)
    val nudgeCb by rememberUpdatedState(onNudge)
    val resetCb by rememberUpdatedState(onReset)
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val active = hovered || focused
    Box(
        modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .hoverable(interaction)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics { contentDescription = "調整側欄寬度" }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        nudgeCb(-16f)
                        true
                    }
                    Key.DirectionRight -> {
                        nudgeCb(16f)
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                val lastTap = longArrayOf(0L)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    focusRequester.requestFocus()
                    val slop = viewConfiguration.touchSlop
                    var total = 0f
                    var dragging = false
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging) {
                                endCb()
                            } else if (total < slop) {
                                val now = System.currentTimeMillis()
                                if (now - lastTap[0] <= 400L) {
                                    lastTap[0] = 0L
                                    resetCb()
                                } else {
                                    lastTap[0] = now
                                }
                            }
                            break
                        }
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        total += abs(dx)
                        if (!dragging && total >= slop) {
                            dragging = true
                            startCb()
                        }
                        if (dragging) {
                            change.consume()
                            dragCb(with(density) { dx.toDp().value })
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (active) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
