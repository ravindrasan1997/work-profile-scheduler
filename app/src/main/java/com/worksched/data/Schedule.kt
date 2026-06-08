package com.worksched.data

import kotlinx.serialization.Serializable

/**
 * One enable + one disable time, applied to Mon–Fri.
 * Internally produces 10 exact alarms (5 weekdays × 2 actions).
 */
@Serializable
data class Schedule(
    val enableHour: Int = 10,
    val enableMinute: Int = 0,
    val disableHour: Int = 19,
    val disableMinute: Int = 0
) {
    fun enableMinutesOfDay(): Int = enableHour * 60 + enableMinute
    fun disableMinutesOfDay(): Int = disableHour * 60 + disableMinute

    companion object {
        fun default() = Schedule()
    }
}
