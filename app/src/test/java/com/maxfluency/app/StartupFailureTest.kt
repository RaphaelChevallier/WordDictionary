package com.maxfluency.app

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupFailureTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val storageFailure = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            context.deleteDatabase("personal.sqlite")
            val file = context.getDatabasePath("personal.sqlite")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = 999 }
        }

        override fun after() {
            RuntimeEnvironment.getApplication().deleteDatabase("personal.sqlite")
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(storageFailure).around(compose)

    @Test
    fun startupErrorsReleaseTheLaunchScreen() {
        val model = ViewModelProvider(compose.activity)[MaxFluencyViewModel::class.java]
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !model.state.value.loading
        }
        val state = model.state.value
        assertEquals("daily=${state.daily?.date}, query=${state.query}, loading=${state.loading}",
            R.string.error_dictionary, state.error)
        val error = compose.activity.getString(R.string.error_dictionary)
        compose.onNode(hasText(error) and hasAnyAncestor(hasTestTag("today-screen"))).assertIsDisplayed()
        assertFalse(state.loading)
        compose.runOnIdle {
            assertFalse("A startup failure must expose its error, not hold the launch screen",
                compose.activity.window.decorView.viewTreeObserver.dispatchOnPreDraw())
        }
        compose.onNodeWithTag("nav-alternate").performClick()
        compose.onNode(hasText(error) and hasAnyAncestor(hasTestTag("today-screen"))).assertIsDisplayed()
        assertEquals(R.string.error_dictionary, model.state.value.error)
    }
}
