package com.tfitapp.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.rule.GrantPermissionRule
import com.tfitapp.timer.TimerController
import com.tfitapp.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Real navigation, real controller, real foreground service. Proves the timer
 * starts from a preset, advances on Skip, pauses, and stops — the flow a user
 * actually performs (design basis §6).
 */
@HiltAndroidTest
class TimerFlowTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)

    // Pre-grant so the system permission dialog cannot cover the activity under test.
    @get:Rule(order = 1) val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var controller: TimerController

    @Before
    fun setUp() {
        hilt.inject()
        controller.stop()
    }

    @Test
    fun startPresetSkipPauseStop() {
        compose.onNodeWithText("Timer").performClick()
        compose.onNodeWithText("Presets").assertIsDisplayed()

        // Tabata is the first preset; its first phase is "Get ready".
        compose.onAllNodesWithTextStart()
        compose.onNodeWithText("Get ready").assertIsDisplayed()
        compose.onNodeWithText("1 of 16").assertIsDisplayed()

        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Work 1/8").assertIsDisplayed()
        compose.onNodeWithText("2 of 16").assertIsDisplayed()

        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("Pause").assertIsDisplayed()

        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Presets").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextStart() {
        // Several "Start" buttons exist (one per preset); the first is Tabata.
        onAllNodes(androidx.compose.ui.test.hasText("Start"))[0].performClick()
    }
}
