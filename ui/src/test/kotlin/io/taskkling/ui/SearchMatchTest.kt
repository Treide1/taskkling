package io.taskkling.ui

import io.taskkling.contract.ComputedDto
import io.taskkling.contract.TaskDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The t-tm10 matcher contract: plain case-insensitive substring over id + title
 * (no prefix rule, no fuzzy), id matches ranked strictly before title matches,
 * export order kept within a tier, one appearance per task.
 */
class SearchMatchTest {

    private fun task(id: String, title: String) = TaskDto(
        id = id,
        title = title,
        status = "open",
        created = "2026-01-01T00:00:00Z",
        computed = ComputedDto(),
    )

    private val tasks = listOf(
        task("t-aaaa", "wire the header search"),
        task("t-tm10", "find-task-by-id search"),
        task("t-bbbb", "unrelated tm10 follow-up"),
        task("t-cccc", "graph layout polish"),
    )

    private fun ids(query: String) = searchMatches(tasks, query).map { it.id }

    @Test
    fun emptyAndBlankQueriesMatchNothing() {
        assertEquals(emptyList(), ids(""))
        assertEquals(emptyList(), ids("   "))
    }

    @Test
    fun idSubstringMatchesWithoutThePrefix() {
        // Users drop the `t-`: `tm10` must still hit `t-tm10`.
        assertEquals(listOf("t-tm10", "t-bbbb"), ids("tm10"))
    }

    @Test
    fun idMatchesRankBeforeTitleMatches() {
        // t-bbbb's TITLE contains "tm10" but t-tm10's ID does — id tier first,
        // even though t-bbbb would win on export order.
        assertEquals(listOf("t-tm10", "t-bbbb"), ids("TM10"))
    }

    @Test
    fun titleMatchingIsCaseInsensitive() {
        assertEquals(listOf("t-aaaa", "t-tm10"), ids("SEARCH"))
    }

    @Test
    fun nonMatchingTasksAreExcluded() {
        assertEquals(listOf("t-cccc"), ids("layout"))
        assertEquals(emptyList(), ids("zzz-no-such"))
    }

    @Test
    fun aTaskMatchingBothTiersAppearsOnceInTheIdTier() {
        val hits = searchMatches(listOf(task("t-find", "find things")), "find")
        assertEquals(listOf("t-find"), hits.map { it.id })
    }

    @Test
    fun queryWhitespaceIsTrimmed() {
        assertEquals(listOf("t-tm10", "t-bbbb"), ids("  tm10  "))
    }
}
