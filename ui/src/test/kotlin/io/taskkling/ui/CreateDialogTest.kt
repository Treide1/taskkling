package io.taskkling.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The add-card dialog's affordances (t-ctbc, t-dm54). These exist only on screen, so they
 * were verified by driving the real app and reading a screenshot — nothing failed if they
 * regressed, which is the gap this closes.
 *
 * Deliberately at the DIALOG's level, not the app's: [AppStore] already owns and tests the
 * store half of this flow (`createTask closes the dialog and selects the minted id`, `a
 * create failure keeps the dialog open with its own error`, `dismissing the create dialog
 * mid-submit is refused`). What no test could reach is the composable's own contract —
 * which clicks dismiss, which do not, and whether the DRAFT survives a failure. Every input
 * the dialog reacts to is hoisted ([CreateCardDialog]'s `submitting`, `error`, and the two
 * callbacks), so driving it directly tests that contract and nothing else.
 *
 * The scene size is fixed so the scrim's coordinates are known: the panel is a 360dp column
 * centred in it, so the top-left corner is always scrim and never the panel.
 */
@OptIn(ExperimentalTestApi::class)
class CreateDialogTest {
    private fun dialog(
        submitting: Boolean = false,
        error: String? = null,
        onCreate: (List<String>) -> Unit = {},
        onDismiss: () -> Unit = {},
    ): @Composable () -> Unit = {
        TaskklingTheme {
            CreateCardDialog(
                defaultThread = "demo",
                submitting = submitting,
                error = error,
                onCreate = onCreate,
                onDismiss = onDismiss,
            )
        }
    }

    @Test
    fun `clicking the scrim does not dismiss`() = runDesktopComposeUiTest(width = 900, height = 700) {
        var dismissed = false
        setContent(dialog(onDismiss = { dismissed = true }))
        // The corner is scrim: the panel is 360dp wide and centred in a 900px scene.
        onRoot().performMouseInput { click(Offset(4f, 4f)) }
        waitForIdle()
        assertFalse(dismissed, "a stray scrim click threw away the half-typed form (t-ctbc)")
    }

    @Test
    fun `cancel dismisses`() = runDesktopComposeUiTest(width = 900, height = 700) {
        var dismissed = false
        setContent(dialog(onDismiss = { dismissed = true }))
        onNodeWithText("cancel").performClick()
        waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `escape dismisses`() = runDesktopComposeUiTest(width = 900, height = 700) {
        var dismissed = false
        setContent(dialog(onDismiss = { dismissed = true }))
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(dismissed, "Escape is one of the dialog's only two deliberate outs")
    }

    /**
     * The ticket's "submitting disables the fields". Asserted through SEMANTICS, not the
     * 0.4 alpha: mid-flight the fields are not merely dimmed, they stop being text inputs
     * altogether, so nothing can be typed into a form that is already on its way to the CLI.
     *
     * Escape mid-flight is deliberately NOT asserted here. It is inert, but a mutation test
     * proved a dialog-level test cannot show WHY: with the fields disabled nothing in the
     * dialog holds focus, so [CreateCardDialog]'s onPreviewKeyEvent never fires, and the
     * assertion passes even with the `!submitting` guard deleted. A test that green-lights
     * the very bug it exists to catch is worse than no test at all. The store-side guard is
     * covered by AppStoreTest's "dismissing the create dialog mid-submit is refused"; the
     * finding that the dialog's own guard is unreachable is recorded on t-dm54.
     */
    @Test
    fun `submitting takes the fields out of reach, not just dims them`() = runDesktopComposeUiTest(width = 900, height = 700) {
        var submitting by mutableStateOf(false)
        setContent {
            TaskklingTheme {
                CreateCardDialog(
                    defaultThread = "demo",
                    submitting = submitting,
                    error = null,
                    onCreate = { submitting = true },
                    onDismiss = {},
                )
            }
        }
        onAllNodes(hasSetTextAction()).assertCountEquals(2) // title + thread, before submit
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("dig the tunnel")
        onNodeWithText("create").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `submitting takes both outs off the row, leaving the spinner`() = runDesktopComposeUiTest(width = 900, height = 700) {
        setContent(dialog(submitting = true))
        onNodeWithText("cancel").assertDoesNotExist()
        onNodeWithText("create").assertDoesNotExist()
    }

    @Test
    fun `create submits the typed title once, as one add invocation`() = runDesktopComposeUiTest(width = 900, height = 700) {
        val submissions = mutableListOf<List<String>>()
        setContent(dialog(onCreate = { submissions += it }))
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("dig the tunnel")
        onNodeWithText("create").performClick()
        waitForIdle()
        assertEquals(1, submissions.size, "the form submits once, as one add")
        val args = submissions.single()
        assertEquals("add", args.first())
        assertTrue("dig the tunnel" in args, "the typed title reached the CLI args: $args")
    }

    /**
     * The regression that matters most on the failure path: the form is DRAFT state with
     * nothing to recover it from, so a failed create must not cost the user what they typed.
     * Drives the real sequence — type, submit, the parent flips to submitting, the CLI
     * refuses — and asserts the draft survived and `create` is usable again.
     */
    @Test
    fun `a failed create keeps the typed input intact and re-enables create`() = runDesktopComposeUiTest(width = 900, height = 700) {
        var submitting by mutableStateOf(false)
        var error: String? by mutableStateOf(null)
        setContent {
            TaskklingTheme {
                CreateCardDialog(
                    defaultThread = "demo",
                    submitting = submitting,
                    error = error,
                    onCreate = { submitting = true },
                    onDismiss = {},
                )
            }
        }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("dig the tunnel")
        onNodeWithText("create").performClick()
        waitForIdle()

        // The CLI refuses. The store's half of this (showCreate stays true, createError set,
        // creating cleared) is AppStoreTest's; this is what the user is left looking at.
        submitting = false
        error = "taskkling: title already taken"
        waitForIdle()

        onNodeWithText("dig the tunnel").assertIsDisplayed()
        onNodeWithText("error: taskkling: title already taken").assertIsDisplayed()
        onNodeWithText("create").assertIsDisplayed()
    }
}
