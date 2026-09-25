package io.github.aritouma1205.quietintentlauncher.context

import java.time.LocalDateTime

/**
 * Result of evaluating one context slot (design 10). A null [matchedRule]
 * means the slot resolved to its default action rather than a rule.
 */
data class SlotEvaluation(
    val slotIndex: Int,
    val actionId: String,
    val matchedRule: ContextRule?,
)

/**
 * Pure evaluation of Context Slots (design 10). The caller injects the
 * current device-local time and the action-usability check, so the
 * evaluator stays free of Android, clock, and timezone concerns — a
 * repeated wall-clock time under DST simply evaluates the same way again.
 */
object ContextEvaluator {

    /**
     * Whether [rule] matches [now]. An empty day set means every day; both
     * times unset means all day and only the day set is consulted. A time
     * range includes its start minute and excludes its end. A cross-day
     * range (start > end) is owned by its start day: it matches when today
     * is in the day set and the time is at or after the start, or when
     * yesterday is in the day set and the time is before the end. An end of
     * 0 therefore means "until midnight" with nothing spilling over.
     *
     * Rules that fail [ContextRules] validation (one-sided or out-of-day
     * times, start == end, out-of-range days) never match; they are
     * rejected at save time and only tolerated defensively here.
     */
    fun ruleMatches(rule: ContextRule, now: LocalDateTime): Boolean {
        if (!ContextRules.isValidDaySet(rule.daysOfWeek)) return false
        val start = rule.startMinuteOfDay
        val end = rule.endMinuteOfDay
        val today = now.dayOfWeek.value
        if (start == null && end == null) {
            return rule.daysOfWeek.isEmpty() || today in rule.daysOfWeek
        }
        if (start == null || end == null ||
            !ContextRules.isValidTimeRange(start, end)
        ) {
            return false
        }
        val minute = now.hour * 60 + now.minute
        val daySetAppliesTo = { day: Int ->
            rule.daysOfWeek.isEmpty() || day in rule.daysOfWeek
        }
        return if (start < end) {
            daySetAppliesTo(today) && minute in start until end
        } else {
            val yesterday = now.minusDays(1).dayOfWeek.value
            (daySetAppliesTo(today) && minute >= start) ||
                (daySetAppliesTo(yesterday) && minute < end)
        }
    }

    /**
     * Evaluates the slots top-down and returns the actions to display, at
     * most [ContextRules.MAX_SLOTS]. Each slot resolves to the first
     * matching rule whose action passes [isActionUsable]; matching rules
     * with missing or unusable actions are skipped in favour of the next
     * rule. When no rule yields a usable action the slot falls back to its
     * default action; a slot that resolves to nothing is omitted. When two
     * slots resolve to the same action the later one is hidden, without
     * promoting its next rule.
     */
    fun evaluate(
        slots: List<ContextSlot>,
        now: LocalDateTime,
        isActionUsable: (String) -> Boolean,
    ): List<SlotEvaluation> {
        val shown = mutableListOf<SlotEvaluation>()
        for ((index, slot) in slots.take(ContextRules.MAX_SLOTS).withIndex()) {
            val matched = slot.rules.firstOrNull { rule ->
                ruleMatches(rule, now) &&
                    rule.actionId != null &&
                    isActionUsable(rule.actionId)
            }
            val actionId = matched?.actionId
                ?: slot.defaultActionId?.takeIf(isActionUsable)
            if (actionId != null && shown.none { it.actionId == actionId }) {
                shown += SlotEvaluation(index, actionId, matched)
            }
        }
        return shown
    }
}
