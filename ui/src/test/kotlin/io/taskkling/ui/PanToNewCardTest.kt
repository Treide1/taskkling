package io.taskkling.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pan-to-new-card (t-ctbc, t-dm54): creating a card selects it AND centres it. Verified
 * until now by driving the real app and eyeballing that the card landed centred, with
 * nothing to catch a regression. The geometry cannot be mocked — `cardRects` is filled by
 * GraphPane's real measure pass — so this renders the real [App] at a fixed viewport and
 * reads where the card actually landed.
 *
 * ## What this does and does not protect (measured, not assumed)
 *
 * Mutation-tested both ways, because a pan test that cannot fail is decoration:
 * - Deleting the pan (select without centring) FAILS this test. Covered.
 * - Breaking the centring maths or the clamp FAILS it. Covered.
 * - Deleting `navigateToNew`'s `snapshotFlow { cardRects[id] }` wait — the actual t-ctbc
 *   fix — does NOT fail it. NOT covered, and the reason is worth knowing: under the test
 *   harness the LaunchedEffect that calls `navigateToNew` is dispatched AFTER the layout
 *   pass, so the rect it is supposed to wait for already exists and the wait is dead code
 *   here. In production the effect runs before that measure pass, which is the whole reason
 *   the wait exists. The harness's scheduling hides the race rather than reproducing it.
 *
 * So this test guards the pan's OUTCOME, not the mechanism that makes it work on a card
 * that has never been measured. Read `navigateToNew`'s own doc before touching that wait;
 * this test will not stop you deleting it. Recorded on t-dm54 rather than left as a
 * comfortable green.
 */
@OptIn(ExperimentalTestApi::class)
class PanToNewCardTest {
    /**
     * A graph shaped so the assertion is not vacuous, which takes more care than it looks:
     *
     * - The seeded ids sort BEFORE the FakeClient's minted `t-new0`, because [layout] orders a
     *   column's cards alphabetically by id (Layout.kt: `byLayer.getValue(layer).sorted()`) —
     *   NOT by creation order. Roots named `t-r*` would put the new card at the TOP of the
     *   column, on screen from the start, and the test would prove nothing.
     * - Column 0 then carries enough roots that the new card (no depends → layer 0, sorted
     *   last) starts WELL BELOW the viewport, so "it is centred" cannot pass without a pan.
     * - Column 1 is taller still, which is what makes centring reachable at all: the canvas
     *   height is the tallest column's, and `centerScrollOffset` clamps to the scroll range.
     *   Were the new card at the canvas BOTTOM, clamping would legitimately park it near the
     *   viewport's bottom edge and "centred" would be the wrong thing to assert.
     */
    private fun seed(): List<TaskDtoAlias> {
        val roots = (0 until 25).map { task(id = "t-a%02d".format(it), title = "root $it") }
        val leaves = (0 until 60).map { task(id = "t-b%02d".format(it), title = "leaf $it", depends = listOf("t-a00")) }
        return roots + leaves
    }

    private fun SemanticsNodeInteraction.centerY(): Dp = with(getBoundsInRoot()) { top + (bottom - top) / 2 }

    @Test
    fun `creating a card pans the off-screen card to the centre of the graph viewport`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val client = FakeClient(seed())
            lateinit var store: AppStore
            setContent {
                val scope = rememberCoroutineScope()
                // Unconfined stands in for Dispatchers.IO so the FakeClient's in-memory answer
                // lands inline — the store's `io` is injectable for exactly this reason. The
                // wait under test is on a LAYOUT pass, not on the dispatcher, and stays real.
                store = remember { AppStore(client, scope, Dispatchers.Unconfined) }
                TaskklingTheme { App(store) }
            }
            waitForIdle()

            val newCard = onNode(hasText("dig the tunnel") and hasAnyAncestor(hasTestTag(GRAPH_VIEWPORT_TAG)))
            newCard.assertDoesNotExist()

            runOnIdle { store.createTask(listOf("add", "--title", "dig the tunnel")) }
            waitForIdle()

            // The card exists AND is on screen: the t-ctbc bug selected it without panning,
            // leaving it below the fold. This assertion alone fails on that regression.
            newCard.assertIsDisplayed()

            val viewport = onNodeWithTag(GRAPH_VIEWPORT_TAG).getBoundsInRoot()
            val viewportCenterY = viewport.top + (viewport.bottom - viewport.top) / 2
            val drift = abs((newCard.centerY() - viewportCenterY).value)
            assertTrue(
                drift <= 2f,
                "the new card should be centred vertically in the graph viewport, " +
                    "but its centre sits ${drift}dp from the viewport's. " +
                    "card=${newCard.getBoundsInRoot()} viewport=$viewport",
            )
        }
}

/** Local alias so [seed] can name the DTO without importing :contract into every test. */
private typealias TaskDtoAlias = io.taskkling.contract.TaskDto
