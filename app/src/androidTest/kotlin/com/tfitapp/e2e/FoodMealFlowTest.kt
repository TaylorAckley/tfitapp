package com.tfitapp.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime

/**
 * The saved-meal loop, on the real database with Jev absent (no key on the test
 * device): log a sentence, name today's slot as a meal, log it by name with an
 * omission, log it with one tap, remove it. Design basis §4.4.
 */
@HiltAndroidTest
class FoodMealFlowTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = hilt.inject()

    @Test
    fun logSaveAsMealSayItByNameAndRemove() {
        compose.onNodeWithText("Food").performClick()

        // 1. A sentence becomes an entry (degrade path: top FTS candidate, flagged for review).
        sentence().performTextInput("toast, black coffee")
        compose.onNodeWithText("Go").performClick()
        compose.waitUntilExists(hasText("Log 2 items", substring = true))
        compose.scrollTo(hasText("Log 2 items", substring = true)).performClick()
        compose.waitUntilExists(hasText("Meals"))

        // 2. Today's slot, named. Saving is keyed on the name, so a leftover from an earlier run is replaced, not duplicated.
        val slot = MealSlot.forTime(LocalTime.now()).displayName.lowercase()
        compose.scrollTo(hasText(slot)).performClick()
        compose.scrollTo(hasTestTag("as a meal called")).performTextInput(MEAL)
        compose.scrollTo(hasText("Save meal")).performClick()
        compose.waitUntilExists(hasTestTag("meal-$MEAL"))

        // 3. Said by name with an omission: the coffee comes back as a copy, the toast does not.
        sentence().performTextClearance()
        sentence().performTextInput("$MEAL without toast")
        compose.onNodeWithText("Go").performClick()
        compose.waitUntilExists(hasText("Log 1 item", substring = true))
        compose.scrollTo(hasText("from $MEAL", substring = true)).assertIsDisplayed()
        compose.scrollTo(hasText("Discard")).performClick()

        // 4. One tap logs the whole meal.
        compose.scrollTo(hasText("Log") and hasAnyAncestor(hasTestTag("meal-$MEAL"))).performClick()
        compose.waitUntilExists(hasText("Logged $MEAL"))

        // 5. Remove hides it; the sentence path no longer expands it.
        compose.scrollTo(hasText("Remove") and hasAnyAncestor(hasTestTag("meal-$MEAL"))).performClick()
        compose.waitUntilGone(hasTestTag("meal-$MEAL"))
    }

    private fun sentence() = compose.onAllNodes(hasSetTextAction())[0]

    private companion object {
        const val MEAL = "E2E meal"
    }
}
