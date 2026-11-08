package com.tfitapp.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.FeedbackSource
import com.tfitapp.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Plan → start → finish → check-out, with Jev absent so the taps carry the verdict.
 * Asserts through the repository that the feedback and the sentence were persisted
 * against the finished session (design basis §5.5).
 */
@HiltAndroidTest
class CheckOutFlowTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var sessions: SessionRepository

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun finishThenCheckOutPersistsFeelAndText() {
        compose.onNodeWithText("Train").performClick()
        // "Plan again" when an unfinished session survived an earlier run.
        compose.onAllNodes(hasText("Plan today") or hasText("Plan again"))[0].performClick()
        compose.waitUntilExists(hasText("Start session"))
        compose.scrollTo(hasText("Start session")).performClick()
        compose.waitUntilExists(hasText("Finish session"))
        compose.scrollTo(hasText("Finish session")).performClick()

        compose.waitUntilExists(hasText("How did it go?"))
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("first block was easy")
        compose.scrollTo(hasText("Read it")).performClick()
        // No key on the test device: Jev is skipped and the taps stand.
        compose.waitUntilExists(hasText("Jev skipped", substring = true))
        compose.onAllNodesWithText("Easy")[0].performClick()
        compose.scrollTo(hasText("Save session")).performClick()
        compose.waitUntilExists(hasText("Plan today"))
        compose.onNodeWithText("Nothing planned yet", substring = true).assertIsDisplayed()

        runBlocking {
            val finished = sessions.recent(1).single()
            assertEquals("first block was easy", finished.checkOutText)
            val feedback = sessions.feedback(finished.id).single()
            assertEquals(finished.candidate.blocks.first().id, feedback.blockId)
            assertEquals(BlockFeel.TOO_EASY, feedback.feel)
            assertEquals(FeedbackSource.MANUAL, feedback.source)
        }
    }
}
