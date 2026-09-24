package io.github.aritouma1205.quietintentlauncher.context

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEvaluatorTest {

    // 2025-01-06 is a Monday, so day-of-month offsets map to weekdays.
    private val mon = 6
    private val tue = 7
    private val wed = 8
    private val thu = 9
    private val fri = 10
    private val sun = 12

    private fun at(dayOfMonth: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2025, 1, dayOfMonth, hour, minute)

    private fun rule(
        actionId: String? = "a",
        days: Set<Int> = emptySet(),
        start: Int? = null,
        end: Int? = null,
    ) = ContextRule(
        daysOfWeek = days,
        startMinuteOfDay = start,
        endMinuteOfDay = end,
        actionId = actionId,
    )

    private fun matches(rule: ContextRule, now: LocalDateTime): Boolean =
        ContextEvaluator.ruleMatches(rule, now)

    private fun eval(
        slots: List<ContextSlot>,
        now: LocalDateTime = at(mon, 10),
        usable: Set<String> = setOf("a", "b", "c", "d"),
    ): List<SlotEvaluation> =
        ContextEvaluator.evaluate(slots, now) { it in usable }

    @Test
    fun `empty day set matches every day`() {
        val r = rule(start = 540, end = 1020)
        assertTrue(matches(r, at(mon, 10)))
        assertTrue(matches(r, at(wed, 10)))
        assertTrue(matches(r, at(sun, 10)))
    }

    @Test
    fun `day set restricts matching to listed days`() {
        val r = rule(days = setOf(1), start = 540, end = 1020)
        assertTrue(matches(r, at(mon, 10)))
        assertFalse(matches(r, at(tue, 10)))
    }

    @Test
    fun `multiple days all match`() {
        val r = rule(days = setOf(1, 5), start = 540, end = 1020)
        assertTrue(matches(r, at(mon, 10)))
        assertTrue(matches(r, at(fri, 10)))
        assertFalse(matches(r, at(tue, 10)))
    }

    @Test
    fun `time range includes start and excludes end`() {
        val r = rule(start = 540, end = 1020)
        assertFalse(matches(r, at(mon, 8, 59)))
        assertTrue(matches(r, at(mon, 9, 0)))
        assertTrue(matches(r, at(mon, 16, 59)))
        assertFalse(matches(r, at(mon, 17, 0)))
    }

    @Test
    fun `all day rule ignores the time of day`() {
        val r = rule(days = setOf(1))
        assertTrue(matches(r, at(mon, 0, 0)))
        assertTrue(matches(r, at(mon, 23, 59)))
        assertFalse(matches(r, at(tue, 12, 0)))
    }

    @Test
    fun `all day rule without days always matches`() {
        assertTrue(matches(rule(), at(wed, 3, 33)))
    }

    @Test
    fun `cross day range is owned by its start day`() {
        // Monday 22:00 -> 2:00: applies late Monday and early Tuesday.
        val r = rule(days = setOf(1), start = 1320, end = 120)
        assertTrue(matches(r, at(mon, 22, 0)))
        assertTrue(matches(r, at(mon, 23, 0)))
        assertTrue(matches(r, at(tue, 1, 0)))
        assertTrue(matches(r, at(tue, 1, 59)))
        assertFalse(matches(r, at(tue, 2, 0)))
        assertFalse(matches(r, at(tue, 3, 0)))
        // Tuesday is not a start day, so late Tuesday stays off.
        assertFalse(matches(r, at(tue, 23, 0)))
        // Wednesday early morning would need Tuesday as a start day.
        assertFalse(matches(r, at(wed, 1, 0)))
        // Sunday and early Monday are outside entirely.
        assertFalse(matches(r, at(sun, 23, 0)))
        assertFalse(matches(r, at(mon, 1, 0)))
    }

    @Test
    fun `cross day range spills over from every listed start day`() {
        val r = rule(days = setOf(1, 3), start = 1320, end = 120)
        assertTrue(matches(r, at(mon, 23, 0)))
        assertTrue(matches(r, at(tue, 1, 0)))
        assertTrue(matches(r, at(wed, 23, 0)))
        assertTrue(matches(r, at(thu, 1, 0)))
        assertFalse(matches(r, at(fri, 1, 0)))
        assertFalse(matches(r, at(tue, 23, 0)))
    }

    @Test
    fun `end of zero means until midnight`() {
        val r = rule(days = setOf(1), start = 1320, end = 0)
        assertTrue(matches(r, at(mon, 22, 0)))
        assertTrue(matches(r, at(mon, 23, 59)))
        assertFalse(matches(r, at(mon, 21, 59)))
        assertFalse(matches(r, at(tue, 0, 30)))
    }

    @Test
    fun `invalid rules never match`() {
        val now = at(mon, 12)
        assertFalse(matches(rule(start = 600, end = 600), now))
        assertFalse(matches(rule(start = 600, end = null), now))
        assertFalse(matches(rule(start = null, end = 600), now))
        assertFalse(matches(rule(start = -1, end = 600), now))
        assertFalse(matches(rule(start = 600, end = 1440), now))
        assertFalse(matches(rule(days = setOf(8), start = 540, end = 1020), now))
    }

    @Test
    fun `first matching rule wins`() {
        val r1 = rule("a", days = setOf(1))
        val r2 = rule("b")
        val slot = ContextSlot(rules = listOf(r1, r2))
        assertEquals(listOf(SlotEvaluation(0, "a", r1)), eval(listOf(slot)))
    }

    @Test
    fun `matching rule with unusable action falls through to next rule`() {
        val r1 = rule("a", days = setOf(1))
        val r2 = rule("b", days = setOf(1))
        val slot = ContextSlot(rules = listOf(r1, r2), defaultActionId = "d")
        assertEquals(
            listOf(SlotEvaluation(0, "b", r2)),
            eval(listOf(slot), usable = setOf("b", "d")),
        )
    }

    @Test
    fun `falls back to default when no rule matches`() {
        val r1 = rule("a", days = setOf(2)) // Tuesday only; now is Monday.
        val slot = ContextSlot(rules = listOf(r1), defaultActionId = "d")
        assertEquals(listOf(SlotEvaluation(0, "d", null)), eval(listOf(slot)))
    }

    @Test
    fun `matched rule with unusable action still falls back to default`() {
        val r1 = rule("a", days = setOf(1))
        val slot = ContextSlot(rules = listOf(r1), defaultActionId = "d")
        assertEquals(
            listOf(SlotEvaluation(0, "d", null)),
            eval(listOf(slot), usable = setOf("d")),
        )
    }

    @Test
    fun `slot is hidden when nothing resolves`() {
        val noMatch = rule("a", days = setOf(2))
        // No match and no default.
        assertTrue(eval(listOf(ContextSlot(rules = listOf(noMatch)))).isEmpty())
        // No match and an unusable default.
        val slot = ContextSlot(rules = listOf(noMatch), defaultActionId = "d")
        assertTrue(eval(listOf(slot), usable = setOf("a")).isEmpty())
        // Matched but unusable, and an unusable default.
        val matched = rule("b", days = setOf(1))
        val slot2 = ContextSlot(rules = listOf(matched), defaultActionId = "d")
        assertTrue(eval(listOf(slot2), usable = setOf("a")).isEmpty())
    }

    @Test
    fun `rule referencing a missing action is skipped`() {
        val slot = ContextSlot(rules = listOf(rule("gone", days = setOf(1))))
        assertTrue(eval(listOf(slot), usable = emptySet()).isEmpty())
    }

    @Test
    fun `rule without an action id is skipped`() {
        val slot = ContextSlot(
            rules = listOf(rule(actionId = null, days = setOf(1))),
            defaultActionId = "d",
        )
        assertEquals(listOf(SlotEvaluation(0, "d", null)), eval(listOf(slot)))
    }

    @Test
    fun `no rules and a usable default always shows`() {
        val slot = ContextSlot(defaultActionId = "a")
        assertEquals(
            listOf(SlotEvaluation(0, "a", null)),
            eval(listOf(slot), now = at(sun, 3)),
        )
    }

    @Test
    fun `two slots resolving to the same action hide the later one`() {
        val s1 = ContextSlot(rules = listOf(rule("a", days = setOf(1))))
        val s2 = ContextSlot(defaultActionId = "a")
        val result = eval(listOf(s1, s2))
        assertEquals(1, result.size)
        assertEquals(SlotEvaluation(0, "a", s1.rules[0]), result[0])
    }

    @Test
    fun `hidden slot does not promote its next rule`() {
        val s1 = ContextSlot(defaultActionId = "a")
        val s2 = ContextSlot(
            rules = listOf(rule("a", days = setOf(1)), rule("b", days = setOf(1))),
        )
        assertEquals(listOf(SlotEvaluation(0, "a", null)), eval(listOf(s1, s2)))
    }

    @Test
    fun `two slots with different actions both show in order`() {
        val s1 = ContextSlot(defaultActionId = "a")
        val s2 = ContextSlot(defaultActionId = "b")
        assertEquals(
            listOf(SlotEvaluation(0, "a", null), SlotEvaluation(1, "b", null)),
            eval(listOf(s1, s2)),
        )
    }

    @Test
    fun `hidden first slot keeps the second slot index`() {
        val s1 = ContextSlot(rules = listOf(rule("a", days = setOf(2))))
        val s2 = ContextSlot(defaultActionId = "b")
        assertEquals(listOf(SlotEvaluation(1, "b", null)), eval(listOf(s1, s2)))
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(eval(emptyList()).isEmpty())
    }
}
