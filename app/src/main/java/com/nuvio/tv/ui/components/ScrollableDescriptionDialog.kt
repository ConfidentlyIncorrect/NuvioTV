package com.nuvio.tv.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/**
 * A focusable, D-pad-scrollable overlay for showing a full block of text (a synopsis / episode
 * description) that's otherwise truncated in the UI. Extracted from the hero so the show synopsis
 * and per-episode descriptions share one scrollable presentation.
 *
 * The focusable lives on the fixed-height VIEWPORT (not the tall Text), so opening it doesn't trigger
 * a bring-into-view jump to the bottom; D-pad up/down scroll the inner content manually (a lone
 * scrollable Text isn't D-pad scrollable on its own). Back/OK dismiss via [NuvioDialog].
 */
@Composable
fun ScrollableDescriptionDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    width: Dp = 640.dp,
    maxContentHeight: Dp = 420.dp,
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = subtitle,
        width = width
    ) {
        val scroll = rememberScrollState()
        val focus = remember { FocusRequester() }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            scroll.scrollTo(0)
            focus.requestFocus()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(240f) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-240f) }; true }
                        else -> false
                    }
                }
                .verticalScroll(scroll)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
