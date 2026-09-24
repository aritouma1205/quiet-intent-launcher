package io.github.aritouma1205.quietintentlauncher.context

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Context Slots model (design 10, 14.1). Each of at most two slots holds an
 * ordered rule list and an optional default action. A rule is
 * 「曜日＋時刻帯＋行動」: an empty day set means every day, unset times mean
 * all day, and the action id references a stored DO action.
 *
 * [startMinuteOfDay] is inclusive, [endMinuteOfDay] exclusive, both in
 * 0..1439. start == end is invalid input; start > end is a valid cross-day
 * range (e.g. Mon 22:00 -> 2:00 covers early Tuesday). An end of 0 means
 * "until midnight", which the evaluator treats as a cross-day boundary.
 */
@Serializable
data class ContextRule(
    val id: String = UUID.randomUUID().toString(),
    val daysOfWeek: Set<Int> = emptySet(),
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val actionId: String? = null,
)

@Serializable
data class ContextSlot(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val rules: List<ContextRule> = emptyList(),
    val defaultActionId: String? = null,
)

/** Model-level validation failures; the UI maps them to explanations. */
enum class ContextSlotError {
    TooManySlots,
    TooManyRules,
    InvalidDaySet,
    InvalidTimeRange,
    MissingAction,
    InvalidLabel,
}

/**
 * Pure constraints for Context Slots (design 10): at most 2 slots, each with
 * at most 8 rules; days are ISO weekday values 1 (Mon) to 7 (Sun); a time
 * range needs both bounds, distinct, inside the day; every rule needs an
 * action reference; slot labels are optional, up to 32 chars and never
 * blank-only.
 */
object ContextRules {
    const val MAX_SLOTS = 2
    const val MAX_RULES_PER_SLOT = 8
    const val LABEL_MAX_LENGTH = 32
    const val MINUTES_PER_DAY = 24 * 60
    const val DAY_MIN = 1
    const val DAY_MAX = 7

    fun isValidDaySet(days: Set<Int>): Boolean =
        days.all { it in DAY_MIN..DAY_MAX }

    /**
     * Both bounds unset = all day (valid). Both set must stay inside the day
     * and differ; start > end is the allowed cross-day case.
     */
    fun isValidTimeRange(start: Int?, end: Int?): Boolean = when {
        start == null && end == null -> true
        start == null || end == null -> false
        start !in 0 until MINUTES_PER_DAY -> false
        end !in 0 until MINUTES_PER_DAY -> false
        start == end -> false
        else -> true
    }

    fun isValidLabel(label: String): Boolean =
        label.length <= LABEL_MAX_LENGTH && (label.isEmpty() || label.isNotBlank())

    fun validateRule(rule: ContextRule): ContextSlotError? = when {
        !isValidDaySet(rule.daysOfWeek) -> ContextSlotError.InvalidDaySet
        !isValidTimeRange(rule.startMinuteOfDay, rule.endMinuteOfDay) ->
            ContextSlotError.InvalidTimeRange
        rule.actionId == null -> ContextSlotError.MissingAction
        else -> null
    }

    fun validateSlot(slot: ContextSlot): ContextSlotError? = when {
        !isValidLabel(slot.label) -> ContextSlotError.InvalidLabel
        slot.rules.size > MAX_RULES_PER_SLOT -> ContextSlotError.TooManyRules
        else -> slot.rules.firstNotNullOfOrNull { validateRule(it) }
    }

    /** First violation in the list, or null when the slots are saveable. */
    fun validateSlots(slots: List<ContextSlot>): ContextSlotError? {
        if (slots.size > MAX_SLOTS) return ContextSlotError.TooManySlots
        return slots.firstNotNullOfOrNull { validateSlot(it) }
    }

    /**
     * Initially both slots exist but are empty (design 10). Slot ids are
     * deterministic — positional, not random — so two default instances
     * compare equal and decode+re-encode is stable.
     */
    fun defaultSlots(): List<ContextSlot> =
        List(MAX_SLOTS) { ContextSlot(id = defaultSlotId(it)) }

    /**
     * Decode-time normalization (design 14.1): the stored list is truncated
     * or padded to exactly [MAX_SLOTS] so the editor always sees two slots
     * without inventing content for them. Padded slots take a positional id
     * that does not collide with a stored one.
     */
    fun normalized(slots: List<ContextSlot>): List<ContextSlot> {
        val taken = slots.mapTo(HashSet()) { it.id }
        var n = 1
        return List(MAX_SLOTS) { i ->
            slots.getOrNull(i) ?: run {
                while (defaultSlotId(n - 1) in taken) n++
                ContextSlot(id = defaultSlotId(n - 1))
            }
        }
    }

    private fun defaultSlotId(index: Int): String = "context-slot-${index + 1}"

    /** Whether any rule or default of [slots] points at [actionId]. */
    fun referencesAction(slots: List<ContextSlot>, actionId: String): Boolean =
        slots.any { slot ->
            slot.defaultActionId == actionId ||
                slot.rules.any { it.actionId == actionId }
        }

    /**
     * Removes every reference to a deleted action in the same write (design
     * 11.2 / A13): rules pointing at it are dropped and matching defaults are
     * cleared, so no dangling reference survives a save.
     */
    fun removingAction(
        slots: List<ContextSlot>,
        actionId: String,
    ): List<ContextSlot> = slots.map { slot ->
        slot.copy(
            rules = slot.rules.filter { it.actionId != actionId },
            defaultActionId = slot.defaultActionId?.takeIf { it != actionId },
        )
    }
}
