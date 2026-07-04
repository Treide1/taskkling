package io.taskkling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small rounded capsules — the shared substrate for every tag, pill, and flag
 * (DESIGN §8). Radius 10, padding ~1×7, text at 10sp. [fill]/[border]/[textColor]
 * and [bold] select the variant; there are no bespoke chip shapes elsewhere.
 */
@Composable
public fun Chip(
    text: String,
    textColor: Color,
    fill: Color = Tk.panel2,
    border: Color? = Tk.line,
    bold: Boolean = false,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .let { if (border != null) it.border(1.dp, border, RoundedCornerShape(10.dp)) else it }
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = textColor,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Filled primary-state pill: state-colour fill, no border, bold `bg` text (DESIGN §8). */
@Composable
public fun StatePill(state: TaskState) {
    Chip(text = state.label, textColor = pillTextColor(state), fill = state.color, border = null, bold = true)
}

/** Outline tag: `panel2` fill, `line` border, `muted` text — optionally tinted (DESIGN §8). */
@Composable
public fun OutlineTag(text: String, textColor: Color = Tk.muted, border: Color = Tk.line) {
    Chip(text = text, textColor = textColor, fill = Tk.panel2, border = border, bold = false)
}

/** Header count chip: `panel2`/`line` capsule with a state-dot, bold count, muted label (DESIGN §8/§9). */
@Composable
public fun CountChip(color: Color, count: Int, label: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Tk.panel2)
            .border(1.dp, Tk.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tk.txt)
        Text(label, fontSize = 13.sp, color = Tk.muted)
    }
}

/**
 * A computed-flag chip in the detail panel (DESIGN §8): all five flags always
 * render. Inactive reads as an outline tag; active fills with [activeColor] and
 * bold `bg` text.
 */
@Composable
public fun FlagChip(name: String, active: Boolean, activeColor: Color) {
    if (active) {
        Chip(text = name, textColor = Tk.bg, fill = activeColor, border = null, bold = true)
    } else {
        Chip(text = name, textColor = Tk.muted, fill = Tk.panel2, border = Tk.line, bold = false)
    }
}
