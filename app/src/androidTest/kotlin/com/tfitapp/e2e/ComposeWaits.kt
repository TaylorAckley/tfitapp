package com.tfitapp.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog

/**
 * Polls until a node matching [matcher] is composed. Every tab is a `LazyColumn`
 * tagged "screen" that only composes what is on screen, so each poll also tries to
 * scroll the list to the node; use cases run off the main thread, so the tree lags
 * the tap and the first polls may find nothing at all.
 */
fun ComposeTestRule.waitUntilExists(matcher: SemanticsMatcher, timeoutMillis: Long = 10_000) {
    try {
        waitUntil(timeoutMillis) {
            if (onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return@waitUntil true
            val screens = onAllNodes(hasTestTag("screen")).fetchSemanticsNodes()
            if (screens.isNotEmpty()) runCatching { onAllNodes(hasTestTag("screen"))[0].performScrollToNode(matcher) }
            onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    } catch (t: ComposeTimeoutException) {
        onRoot().printToLog("E2E")
        throw t
    }
}

fun ComposeTestRule.waitUntilGone(matcher: SemanticsMatcher, timeoutMillis: Long = 10_000) =
    waitUntil(timeoutMillis) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

/** Waits for the node, then scrolls it fully into view: a click on a row half under the list edge lands nowhere. */
fun ComposeTestRule.scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    waitUntilExists(matcher)
    return onNode(matcher).performScrollTo()
}
