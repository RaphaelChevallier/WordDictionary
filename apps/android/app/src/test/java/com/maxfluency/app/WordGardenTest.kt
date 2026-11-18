package com.maxfluency.app

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maxfluency.app.ui.MaxFluencyTheme
import com.maxfluency.app.ui.WordGarden
import com.maxfluency.core.Language
import com.maxfluency.core.Sense
import com.maxfluency.core.WordEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordGardenTest {
    @get:Rule val compose = createComposeRule()

    private val words = listOf("clarity", "wonder", "resilience", "tranquil", "luminous", "kindness",
        "wander", "solace", "flourish", "serene", "courage", "delight", "harmony", "gentle",
        "vivid", "insight", "patient", "uncharacteristically").map { word ->
        WordEntry("en:$word", Language.ENGLISH, word, "", listOf(Sense("noun", "A test meaning.")), emptyList(), "en")
    }

    @Test
    fun cloudVariesAndRotatedWordsOpen() {
        var opened: String? = null
        showGarden(350, 1f) { opened = it }
        assertSeparateTargets()
        val vertical = compose.onNodeWithText("tranquil", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The cloud should contain sideways words", vertical.height > vertical.width)
        val large = compose.onNodeWithText("clarity", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val small = compose.onNodeWithText("wonder", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Typography should visibly vary in size", large.bottom - large.top > (small.bottom - small.top) * 1.2f)
        compose.onNodeWithText("tranquil").performScrollTo().performClick()
        assertEquals("en:tranquil", opened)
    }

    @Test
    fun narrowLargeTextKeepsEveryWord() {
        showGarden(280, 1.3f) {}
        assertSeparateTargets()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("uncharacteristically", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse("Long words should fit rather than lose their ending", layouts.single().isLineEllipsized(0))
    }

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h1040dp")
    fun viewportScalesWithoutMoreWords() {
        var width by mutableStateOf(350)
        var height by mutableStateOf(480)
        compose.setContent {
            MaxFluencyTheme {
                Surface(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                    WordGarden(words, {}, height.dp)
                }
            }
        }
        val small = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("clarity", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(small) }
        compose.runOnIdle { width = 760; height = 760 }
        val large = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("clarity", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(large) }
        assertTrue("A larger viewport should grow the typography, not add words",
            large.single().layoutInput.style.fontSize.value > small.single().layoutInput.style.fontSize.value * 1.3f)
        assertSeparateTargets()
    }

    private fun showGarden(width: Int, scale: Float, onWord: (String) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaxFluencyTheme {
                    Surface(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        WordGarden(words, onWord, 600.dp)
                    }
                }
            }
        }
    }

    private fun assertSeparateTargets() {
        compose.onNodeWithTag("daily-grid").onChildren().assertCountEquals(words.size)
        val cloud = compose.onNodeWithTag("daily-grid").getUnclippedBoundsInRoot()
        val targets = words.map {
            val node = compose.onNodeWithTag("garden-word-${it.id}").assertHasClickAction()
            node.getUnclippedBoundsInRoot()
        }
        assertTrue("The cloud should not leave a large top margin", targets.minOf { it.top } - cloud.top <= 12.dp)
        assertTrue("The cloud should not leave a large bottom margin", cloud.bottom - targets.maxOf { it.bottom } <= 12.dp)
        targets.forEachIndexed { index, rect ->
            assertTrue("Word $index needs an accessible touch target", rect.right - rect.left >= 48.dp && rect.bottom - rect.top >= 48.dp)
            assertTrue("Word $index must stay inside the cloud",
                rect.left >= cloud.left && rect.right <= cloud.right &&
                    rect.top >= cloud.top && rect.bottom <= cloud.bottom)
            targets.take(index).forEach { other ->
                assertFalse("Word touch targets must not overlap",
                    rect.left < other.right && rect.right > other.left &&
                        rect.top < other.bottom && rect.bottom > other.top)
            }
        }
    }
}
