package io.taskkling.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * M0 placeholder desktop app: an empty window titled "taskkling".
 *
 * The real UI (PRD §13) is a pure CLI client — it will shell out to the
 * `taskkling` binary, render the DAG from `export` JSON, and mutate via
 * `--export-on-success`. None of that exists yet; this only proves the Compose
 * Desktop (JVM) toolchain and the `:ui` → `:contract` wiring configure/compile.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "taskkling") {
        MaterialTheme {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("taskkling — M0 skeleton")
            }
        }
    }
}
