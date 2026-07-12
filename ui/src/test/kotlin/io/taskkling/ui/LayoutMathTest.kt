package io.taskkling.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure layout/viewport math (DESIGN §9, §10): per-column card stacking ([stackTops]) and the
 * pan-to-card clamp/centre seams ([clampScroll], [centerScrollOffset]). These ship
 * compile+visual-only in the app, so they are exercised here in isolation — plain JVM over the
 * pure functions, no Compose UI instantiated.
 */
class LayoutMathTest {

    // --- stackTops (DESIGN §10) ---------------------------------------------------------

    @Test
    fun stackTopsPlacesFirstCardAtStartAndAccumulatesHeightPlusGap() {
        // tops[0] = start; each next = previous top + previous measured height + gap.
        //   28
        //   28 + 100 + 24 = 152
        //   152 + 50 + 24 = 226
        assertEquals(listOf(28, 152, 226), stackTops(heights = listOf(100, 50, 80), gap = 24, start = 28))
    }

    @Test
    fun stackTopsSingleCardNeverAppliesGap() {
        // A lone card sits exactly at start; the trailing gap after the last card is never emitted.
        assertEquals(listOf(28), stackTops(heights = listOf(96), gap = 24, start = 28))
    }

    @Test
    fun stackTopsEmptyInputYieldsEmptyStack() {
        assertEquals(emptyList(), stackTops(heights = emptyList(), gap = 24, start = 28))
    }

    @Test
    fun stackTopsKeepsExactlyGapBetweenAdjacentCards() {
        // The stacking invariant: the space between card i's bottom and card i+1's top is `gap`,
        // for every adjacent pair — independent of the individual card heights.
        val heights = listOf(120, 37, 200, 96)
        val gap = 24
        val tops = stackTops(heights, gap, start = 28)
        assertEquals(heights.size, tops.size)
        for (i in 0 until heights.size - 1) {
            assertEquals(gap, tops[i + 1] - (tops[i] + heights[i]), "gap after card $i")
        }
    }

    @Test
    fun stackTopsIsTranslationInvariantInStart() {
        // Columns stack independently by measured height; shifting the start offset shifts every
        // top uniformly, so the relative layout is unchanged.
        val heights = listOf(100, 50, 80)
        val a = stackTops(heights, gap = 24, start = 0)
        val b = stackTops(heights, gap = 24, start = 28)
        assertEquals(a.map { it + 28 }, b)
    }

    // --- clampScroll: the pan bound (DESIGN §9) -----------------------------------------
    // Axis-agnostic: the same function clamps both the horizontal and vertical ScrollState.

    @Test
    fun clampScrollCollapsesToZeroWhenContentFitsViewport() {
        // Content smaller-than-or-equal-to viewport => maxScroll == 0 => nothing scrolls.
        assertEquals(0, clampScroll(0, maxScroll = 0))
        assertEquals(0, clampScroll(500, maxScroll = 0))
        assertEquals(0, clampScroll(-30, maxScroll = 0))
    }

    @Test
    fun clampScrollPullsOutOfRangeOffsetsToTheNearestEdge() {
        // Content larger than viewport => scrollable range 0..maxScroll.
        val max = 400
        assertEquals(0, clampScroll(-20, max)) // past the top/left edge
        assertEquals(150, clampScroll(150, max)) // in range, untouched
        assertEquals(400, clampScroll(999, max)) // past the bottom/right edge
    }

    @Test
    fun clampScrollExactFitBoundariesAreReachable() {
        // Both ends of the range are valid, inclusive.
        assertEquals(0, clampScroll(0, maxScroll = 400))
        assertEquals(400, clampScroll(400, maxScroll = 400))
    }

    @Test
    fun clampScrollIsIdempotent() {
        val max = 400
        for (offset in listOf(-100, 0, 137, 400, 999)) {
            val once = clampScroll(offset, max)
            assertEquals(once, clampScroll(once, max), "clamp of clamped($offset)")
        }
    }

    // --- centerScrollOffset: pan-to-card centring (DESIGN §9) ---------------------------

    @Test
    fun centerScrollOffsetCentresACardWhenThereIsRoom() {
        // center 500 in a 200-wide viewport => scroll to 500 - 100 = 400, so the card's centre
        // lands at content 500 - 400 = 100 == viewport / 2. Fully centred.
        assertEquals(400, centerScrollOffset(center = 500f, viewport = 200, maxScroll = 1000))
    }

    @Test
    fun centerScrollOffsetClampsAtTheNearEdge() {
        // A card near the content start can't be centred (would need a negative scroll); it lands
        // flush at offset 0 instead.
        assertEquals(0, centerScrollOffset(center = 50f, viewport = 200, maxScroll = 1000))
    }

    @Test
    fun centerScrollOffsetClampsAtTheFarEdge() {
        // A card near the content end clamps to maxScroll rather than over-scrolling past it.
        assertEquals(1000, centerScrollOffset(center = 1180f, viewport = 200, maxScroll = 1000))
    }

    @Test
    fun centerScrollOffsetReachesTheFarBoundaryExactly() {
        // center - viewport/2 == maxScroll: the boundary is reachable, not clamped further.
        assertEquals(1000, centerScrollOffset(center = 1100f, viewport = 200, maxScroll = 1000))
    }

    @Test
    fun centerScrollOffsetWithNoScrollRoomStaysAtZero() {
        // maxScroll == 0 (content fits): centring degrades to no scroll.
        assertEquals(0, centerScrollOffset(center = 500f, viewport = 200, maxScroll = 0))
    }

    @Test
    fun centerScrollOffsetTruncatesTowardZeroLikeTheInlineMath() {
        // Locks the `.toInt()` truncation of the original inline expression: 500.9 - 201/2f
        // = 500.9 - 100.5 = 400.4 -> 400.
        assertEquals(400, centerScrollOffset(center = 500.9f, viewport = 201, maxScroll = 1000))
        assertTrue(centerScrollOffset(center = 500.9f, viewport = 201, maxScroll = 1000) <= 1000)
    }

    // --- clampPanelWidth: the detail-panel drag bounds (t-q8i2) --------------------------

    @Test
    fun clampPanelWidthLeavesAnInRangeWidthUntouched() {
        // A width between the min and the 60% cap on a roomy window passes through unchanged.
        // cap = 1600 * 0.6 = 960; 400 is comfortably inside [280, 960].
        assertEquals(400f, clampPanelWidth(desired = 400f, windowWidth = 1600f))
    }

    @Test
    fun clampPanelWidthPullsUpToTheReadableMinimum() {
        // A drag that would take the panel below the minimum lands exactly at the minimum.
        assertEquals(PANEL_MIN_W, clampPanelWidth(desired = 100f, windowWidth = 1600f))
    }

    @Test
    fun clampPanelWidthCapsAtSixtyPercentOfTheWindow() {
        // The panel may not exceed 60% of the window, so the graph canvas stays usable.
        // cap = 1000 * 0.6 = 600; a 900 drag clamps to 600.
        assertEquals(600f, clampPanelWidth(desired = 900f, windowWidth = 1000f))
    }

    @Test
    fun clampPanelWidthMinWinsWhenSixtyPercentFallsBelowTheMinimum() {
        // Degenerate tiny window: cap = 400 * 0.6 = 240 < 280. The minimum wins — the panel sits at
        // 280 and cannot grow, even though that exceeds 60% of this very small window.
        assertEquals(PANEL_MIN_W, clampPanelWidth(desired = 999f, windowWidth = 400f))
        assertEquals(PANEL_MIN_W, clampPanelWidth(desired = 50f, windowWidth = 400f))
    }

    @Test
    fun clampPanelWidthReClampsAnOverWidePanelWhenTheWindowShrinks() {
        // The width state is re-clamped every layout pass: a 700-wide panel that was fine on a wide
        // window is pulled back within the cap once the window shrinks to 1000 (cap 600).
        val wide = clampPanelWidth(desired = 700f, windowWidth = 1400f) // cap 840 -> stays 700
        assertEquals(700f, wide)
        assertEquals(600f, clampPanelWidth(desired = wide, windowWidth = 1000f))
    }

    @Test
    fun clampPanelWidthIsIdempotent() {
        for (desired in listOf(50f, 280f, 400f, 900f, 5000f)) {
            val once = clampPanelWidth(desired, windowWidth = 1000f)
            assertEquals(once, clampPanelWidth(once, windowWidth = 1000f), "clamp of clamped($desired)")
        }
    }
}
