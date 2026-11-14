package com.maxfluency.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxfluency.core.WordEntry
import kotlin.math.roundToInt

@Composable
internal fun WordGarden(words: List<WordEntry>, onWord: (String) -> Unit, viewportHeight: Dp) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = minOf(maxWidth.value / 350f, viewportHeight.value / 460f).coerceIn(0.85f, 1.6f)
        Layout(
            modifier = Modifier.fillMaxWidth().testTag("daily-grid"),
            content = {
                words.forEachIndexed { index, word ->
                    key(word.id) { GardenWord(word, index, scale, onWord) }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            if (width == 0 || measurables.isEmpty()) return@Layout layout(width, 0) {}
            val placeables = measurables.map { it.measure(Constraints(maxWidth = width)) }
            val sizes = placeables.map { IntSize(it.width, it.height) }
            val gap = 4.dp.roundToPx()
            var height = maxOf(viewportHeight.roundToPx(), sizes.maxOf { it.height })
            val limit = maxOf(height, 2 * sizes.sumOf { it.height + gap })
            var positions = packWords(sizes, width, height, gap)
            while (positions == null) {
                height += 48.dp.roundToPx()
                check(height <= limit) { "Word garden could not fit its measured words" }
                positions = packWords(sizes, width, height, gap)
            }
            val placed = positions
            val top = placed.minOf { it.y }
            val bottom = placed.indices.maxOf { placed[it].y + placeables[it].height }
            val inset = minOf(8.dp.roundToPx(), (height - (bottom - top)) / 2)
            val stretch = placed.indices.filter { placed[it].y > top }.minOfOrNull {
                (height - 2 * inset - placeables[it].height).toDouble() / (placed[it].y - top)
            }
            layout(width, constraints.constrainHeight(height)) {
                placeables.forEachIndexed { index, word ->
                    // Expanding vertical gaps preserves separation without shrinking words or their tap targets.
                    val y = if (stretch == null) (height - word.height) / 2
                        else inset + ((placed[index].y - top) * stretch.coerceAtLeast(1.0)).toInt()
                    word.placeRelative(placed[index].x, y)
                }
            }
        }
    }
}

@Composable
private fun GardenWord(word: WordEntry, index: Int, scale: Float, onWord: (String) -> Unit) {
    val size = (listOf(34, 20, 28, 18, 30, 22, 17, 26)[index % 8] * scale).roundToInt().coerceAtLeast(15)
    val sideways = index % 5 == 3
    val rotation = if (!sideways) 0f else if (index % 2 == 0) 90f else -90f
    val autoSize = remember(size) { TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = size.sp, stepSize = 1.sp) }
    Layout(
        modifier = Modifier.testTag("garden-word-${word.id}").clickable(role = Role.Button) { onWord(word.id) },
        content = {
            BasicText(
                word.word,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = size.sp,
                    lineHeight = (size + 4).sp,
                    fontWeight = if (index % 3 == 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (index % 5 == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                ),
                autoSize = autoSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) { measurables, constraints ->
        val padding = 4.dp.roundToPx()
        val label = measurables.single().measure(Constraints(maxWidth = (constraints.maxWidth - 2 * padding).coerceAtLeast(0)))
        val touch = 48.dp.roundToPx()
        val width = maxOf(touch, (if (sideways) label.height else label.width) + 2 * padding)
            .coerceAtMost(constraints.maxWidth)
        val height = maxOf(touch, (if (sideways) label.width else label.height) + 2 * padding)
        layout(width, height) {
            // Swap the measured bounds as well as rotating the glyphs, keeping the tap target on the word.
            label.placeRelativeWithLayer((width - label.width) / 2, (height - label.height) / 2) {
                rotationZ = rotation
            }
        }
    }
}

private fun packWords(sizes: List<IntSize>, width: Int, height: Int, gap: Int): List<IntOffset>? {
    var spaces = listOf(IntRect(0, 0, width, height))
    val positions = MutableList(sizes.size) { IntOffset.Zero }
    for (index in sizes.indices.sortedByDescending { sizes[it].width.toLong() * sizes[it].height }) {
        val size = sizes[index]
        val placed = spaces.filter { it.width >= size.width && it.height >= size.height }.map { space ->
            val x = ((width - size.width) / 2).coerceIn(space.left, space.right - size.width)
            val y = ((height - size.height) / 2).coerceIn(space.top, space.bottom - size.height)
            IntRect(x, y, x + size.width, y + size.height)
        }.minByOrNull {
            val dx = (it.left + it.right - width).toLong()
            val dy = (it.top + it.bottom - height).toLong()
            dx * dx + dy * dy
        } ?: return null
        positions[index] = IntOffset(placed.left, placed.top)
        val occupied = IntRect(placed.left - gap, placed.top - gap, placed.right + gap, placed.bottom + gap)
        val remaining = spaces.flatMap { space ->
            if (space.right <= occupied.left || space.left >= occupied.right ||
                space.bottom <= occupied.top || space.top >= occupied.bottom) {
                listOf(space)
            } else buildList {
                if (occupied.left > space.left) add(IntRect(space.left, space.top, occupied.left, space.bottom))
                if (occupied.right < space.right) add(IntRect(occupied.right, space.top, space.right, space.bottom))
                if (occupied.top > space.top) add(IntRect(space.left, space.top, space.right, occupied.top))
                if (occupied.bottom < space.bottom) add(IntRect(space.left, occupied.bottom, space.right, space.bottom))
            }
        }.distinct()
        spaces = remaining.filter { candidate ->
            remaining.none { other ->
                other != candidate && other.left <= candidate.left && other.top <= candidate.top &&
                    other.right >= candidate.right && other.bottom >= candidate.bottom
            }
        }
    }
    return positions
}
