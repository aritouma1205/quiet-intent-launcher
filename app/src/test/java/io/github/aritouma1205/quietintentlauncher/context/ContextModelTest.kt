package io.github.aritouma1205.quietintentlauncher.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextModelTest {

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

    @Test
    fun `valid slot passes validation`() {
        val slot = ContextSlot(
            label = "morning music",
            rules = listOf(rule(days = setOf(1, 5), start = 540, end = 1020)),
            defaultActionId = "d",
        )
        assertNull(ContextRules.validateSlot(slot))
        assertNull(ContextRules.validateSlots(listOf(slot)))
    }

    @Test
    fun `more than two slots is rejected`() {
        assertEquals(
            ContextSlotError.TooManySlots,
            ContextRules.validateSlots(List(3) { ContextSlot() }),
        )
        assertNull(ContextRules.validateSlots(ContextRules.defaultSlots()))
    }

    @Test
    fun `more than eight rules is rejected`() {
        val slot = ContextSlot(rules = List(9) { rule() })
        assertEquals(ContextSlotError.TooManyRules, ContextRules.validateSlot(slot))
        assertEquals(
            ContextSlotError.TooManyRules,
            ContextRules.validateSlots(listOf(slot)),
        )
        assertNull(ContextRules.validateSlot(ContextSlot(rules = List(8) { rule() })))
    }

    @Test
    fun `day values outside 1 to 7 are rejected`() {
        assertEquals(
            ContextSlotError.InvalidDaySet,
            ContextRules.validateRule(rule(days = setOf(0))),
        )
        assertEquals(
            ContextSlotError.InvalidDaySet,
            ContextRules.validateRule(rule(days = setOf(8))),
        )
        assertEquals(
            ContextSlotError.InvalidDaySet,
            ContextRules.validateRule(rule(days = setOf(1, 8))),
        )
        assertNull(ContextRules.validateRule(rule(days = setOf(1, 7))))
    }

    @Test
    fun `equal start and end is rejected`() {
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = 600, end = 600)),
        )
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = 0, end = 0)),
        )
    }

    @Test
    fun `one sided time range is rejected`() {
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = 600, end = null)),
        )
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = null, end = 600)),
        )
    }

    @Test
    fun `minutes outside the day are rejected`() {
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = -1, end = 600)),
        )
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = 1440, end = 600)),
        )
        assertEquals(
            ContextSlotError.InvalidTimeRange,
            ContextRules.validateRule(rule(start = 0, end = 1440)),
        )
        assertNull(ContextRules.validateRule(rule(start = 0, end = 1439)))
        // A cross-day range (start > end) is valid.
        assertNull(ContextRules.validateRule(rule(start = 1320, end = 120)))
    }

    @Test
    fun `rule without an action is rejected`() {
        assertEquals(
            ContextSlotError.MissingAction,
            ContextRules.validateRule(rule(actionId = null)),
        )
    }

    @Test
    fun `validation reports the first violation`() {
        // Bad day set plus missing action reports the day set.
        assertEquals(
            ContextSlotError.InvalidDaySet,
            ContextRules.validateRule(rule(actionId = null, days = setOf(0))),
        )
        // A bad label is reported before rule violations.
        val slot = ContextSlot(label = "x".repeat(33), rules = listOf(rule(actionId = null)))
        assertEquals(ContextSlotError.InvalidLabel, ContextRules.validateSlot(slot))
    }

    @Test
    fun `label allows empty and up to 32 chars`() {
        assertNull(ContextRules.validateSlot(ContextSlot(label = "")))
        assertNull(ContextRules.validateSlot(ContextSlot(label = "x".repeat(32))))
        assertEquals(
            ContextSlotError.InvalidLabel,
            ContextRules.validateSlot(ContextSlot(label = "x".repeat(33))),
        )
        assertEquals(
            ContextSlotError.InvalidLabel,
            ContextRules.validateSlot(ContextSlot(label = "   ")),
        )
    }

    @Test
    fun `default slots are two empty slots`() {
        val slots = ContextRules.defaultSlots()
        assertEquals(2, slots.size)
        assertTrue(
            slots.all {
                it.label.isEmpty() && it.rules.isEmpty() && it.defaultActionId == null
            },
        )
    }

    @Test
    fun `normalized pads and truncates to exactly two slots`() {
        val padded = ContextRules.normalized(emptyList())
        assertEquals(2, padded.size)
        assertTrue(padded.all { it.rules.isEmpty() && it.defaultActionId == null })

        val one = ContextSlot(label = "one")
        val two = ContextSlot(label = "two")
        val three = ContextSlot(label = "three")

        val fromOne = ContextRules.normalized(listOf(one))
        assertEquals(2, fromOne.size)
        assertEquals(one, fromOne[0])

        assertEquals(listOf(one, two), ContextRules.normalized(listOf(one, two, three)))
    }

    @Test
    fun `referencesAction finds rule and default references`() {
        val slots = listOf(
            ContextSlot(rules = listOf(rule("a"))),
            ContextSlot(defaultActionId = "d"),
        )
        assertTrue(ContextRules.referencesAction(slots, "a"))
        assertTrue(ContextRules.referencesAction(slots, "d"))
        assertFalse(ContextRules.referencesAction(slots, "x"))
        assertFalse(ContextRules.referencesAction(emptyList(), "a"))
    }

    @Test
    fun `removingAction drops rule references and clears defaults`() {
        val keep = rule("b")
        val slots = listOf(
            ContextSlot(rules = listOf(rule("a"), keep), defaultActionId = "a"),
            ContextSlot(rules = listOf(rule("a")), defaultActionId = "b"),
        )
        val result = ContextRules.removingAction(slots, "a")
        assertEquals(listOf(keep), result[0].rules)
        assertNull(result[0].defaultActionId)
        assertTrue(result[1].rules.isEmpty())
        assertEquals("b", result[1].defaultActionId)
        assertFalse(ContextRules.referencesAction(result, "a"))
    }
}
