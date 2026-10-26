package com.coralreef

/**
 * One named, saveable set of filter-criteria settings. App-level settings (Telegram,
 * sound, screen lock/dim, speed mode, cooldown) live directly in FilterSettings/prefs
 * and are shared across all profiles — see FilterProfileStore for the field split.
 */
data class FilterProfile(
    val id: String,
    val name: String,
    val minPayout: Double = 30.0,
    val maxMiles: Double = 20.0,
    val scanSchedule: Map<String, FilterSettings.DaySchedule> = emptyMap(),
    val allowedZones: String = "",
    val blockedZones: String = "",
    val activeHoursEnabled: Boolean = false,
    val activeWindows: List<FilterSettings.TimeWindow> = listOf(FilterSettings.TimeWindow()),
    val perDayScheduleEnabled: Boolean = false,
    val perDaySchedule: Map<String, FilterSettings.DayActiveHours> = emptyMap()
)
