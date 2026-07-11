package io.taskkling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The header settings menu (DESIGN §9): a cogwheel at the end of the count-chip
 * row opening a quiet dropdown of workspace actions. The menu holds no state of
 * its own — every action runs a CLI verb and the app refreshes from its
 * returned export. An entry whose dialog hasn't landed yet arrives as a null
 * action and renders disabled.
 */
@Composable
internal fun SettingsMenu(
    enabled: Boolean,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        QuietIconButton(
            icon = UiIcons.Gear,
            contentDescription = "settings",
            enabled = enabled,
            onClick = { open = true },
            iconSize = 14.dp,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Tk.panel2),
        ) {
            MenuEntry("Archive tasks…", onArchive) { open = false }
            MenuEntry("Prune tasks…", onPrune) { open = false }
        }
    }
}

@Composable
private fun MenuEntry(text: String, action: (() -> Unit)?, dismiss: () -> Unit) {
    DropdownMenuItem(
        enabled = action != null,
        onClick = {
            dismiss()
            action?.invoke()
        },
    ) {
        Text(text, fontSize = 13.sp, color = if (action != null) Tk.txt else Tk.faint)
    }
}
