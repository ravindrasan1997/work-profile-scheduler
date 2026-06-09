package com.worksched.data

import java.util.Calendar
import kotlinx.serialization.Serializable

/**
 * One enable (resume) + one disable (pause) time, applied to the selected [days].
 *
 * [days] holds Calendar day-of-week ints (Calendar.SUNDAY=1 .. Calendar.SATURDAY=7).
 * It has a default so older saved JSON without the field decodes to Mon–Fri unchanged.
 */
@Serializable
data class Schedule(
    val enableHour: Int = 10,
    val enableMinute: Int = 0,
    val disableHour: Int = 19,
    val disableMinute: Int = 0,
    val days: List<Int> = DEFAULT_DAYS
) {
    fun enableMinutesOfDay(): Int = enableHour * 60 + enableMinute
    fun disableMinutesOfDay(): Int = disableHour * 60 + disableMinute

    /** Human-readable day summary, e.g. "Mon–Fri", "Every day", "Mon, Wed, Sat", "No days". */
    fun daysLabel(): String {
        val set = days.toSet()
        if (set.isEmpty()) return "no days"
        if (set == ALL_DAYS.toSet()) return "every day"
        if (set == DEFAULT_DAYS.toSet()) return "Mon–Fri"
        if (set == WEEKEND.toSet()) return "Sat–Sun"
        // Otherwise list short names in week order (Mon-first).
        return ORDER.filter { it in set }.joinToString(", ") { SHORT[it] ?: "?" }
    }

    companion object {
        fun default() = Schedule()

        val DEFAULT_DAYS = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY
        )
        val WEEKEND = listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        val ALL_DAYS = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        /** Display order, Monday-first. */
        val ORDER = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        val SHORT = mapOf(
            Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
    }
}
