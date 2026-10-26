package com.coralreef

import android.content.Context
import android.content.SharedPreferences

data class FilterSettings(
    val minPayout:            Double      = 30.0,
    val maxMiles:             Double      = 20.0,
    // Per-day scan/pickup schedule. Missing day = enabled=true, any pickup time accepted —
    // consistent with the old empty-scanDays/empty-pickupWindows "no restriction" defaults.
    // This is the single source of truth for both which days scan (including "today") and
    // which pickup times are accepted each day (including weekends) — no separate overrides.
    val scanSchedule:         Map<String, DaySchedule> = emptyMap(),
    val allowedZones:         String      = "",
    val blockedZones:         String      = "",
    val telegramEnabled:      Boolean     = true,
    // Bring-your-own Telegram bot: the user creates a bot with @BotFather and pastes its
    // token here, plus the chat id alerts should go to. Both blank = Telegram alerts off.
    val telegramBotToken:     String      = "",
    val telegramNotifyChatId: String      = "",
    val offerCooldownMinutes: Int         = 15,           // 0 = never repeat this session
    val soundEnabled:         Boolean     = true,
    val speedMode:            Boolean     = false,
    val slowMode:             Boolean     = false,
    val slowMinSeconds:       Int         = 30,
    val slowMaxSeconds:       Int         = 180,
    val slowRandomizeOrder:   Boolean     = false,
    val slowRandomizeTabs:    Boolean     = false,
    val screenLock:           Boolean     = false,
    val screenDim:            Boolean     = false,
    // Active hours
    val activeHoursEnabled:    Boolean                    = false,
    val activeWindows:         List<TimeWindow>           = listOf(TimeWindow()),
    val perDayScheduleEnabled: Boolean                    = false,
    val perDaySchedule:        Map<String, DayActiveHours> = emptyMap()
) {

    /** A single start–stop time-of-day range. Supports overnight wrap (e.g. 22:00–02:00). */
    data class TimeWindow(
        val startHour:   Int = 7,
        val startMinute: Int = 0,
        val stopHour:    Int = 22,
        val stopMinute:  Int = 0
    ) {
        fun startMins() = startHour * 60 + startMinute
        fun stopMins()  = stopHour * 60 + stopMinute

        fun contains(mins: Int): Boolean {
            val s = startMins(); val e = stopMins()
            return if (e >= s) mins in s..e else mins >= s || mins <= e
        }

        fun label(): String = "${fmt(startHour, startMinute)} – ${fmt(stopHour, stopMinute)}"
        fun encode(): String = "$startHour:$startMinute-$stopHour:$stopMinute"

        companion object {
            fun fmt(hour: Int, minute: Int): String {
                val amPm = if (hour < 12) "AM" else "PM"
                val h12  = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
                return "%d:%02d %s".format(h12, minute, amPm)
            }
            fun decode(raw: String): TimeWindow? {
                val m = Regex("""(\d{1,2}):(\d{1,2})-(\d{1,2}):(\d{1,2})""").matchEntire(raw.trim()) ?: return null
                val (sh, sm, eh, em) = m.destructured
                return TimeWindow(sh.toInt(), sm.toInt(), eh.toInt(), em.toInt())
            }
        }
    }

    /**
     * useDefault=true  -> follow the global [activeWindows] schedule.
     * useDefault=false -> follow [windows] instead; an empty list deliberately means
     *                     this day is fully blacked out (bot never runs that day).
     */
    data class DayActiveHours(
        val useDefault: Boolean          = true,
        val windows:    List<TimeWindow> = emptyList()
    )

    /** One day's entry in [scanSchedule] — whether the bot scans that day at all, and
     *  which pickup-time windows are accepted that day (empty = any time). */
    data class DaySchedule(
        val enabled: Boolean          = true,
        val windows: List<TimeWindow> = emptyList()
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun allowedZoneList() = allowedZones.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    fun blockedZoneList()  = blockedZones.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun offerCooldownMs(): Long =
        if (offerCooldownMinutes <= 0) Long.MAX_VALUE / 2   // never expire within a session
        else offerCooldownMinutes * 60_000L

    fun cooldownLabel(): String = when (offerCooldownMinutes) {
        0    -> "Never repeat"
        60   -> "1 hour"
        else -> "$offerCooldownMinutes min"
    }

    // Returns true if the bot should scan this date tab.
    // A day missing from scanSchedule is enabled by default. "Today" follows the same
    // per-day enabled flag as any other day — no separate same-day override.
    fun shouldScanDay(label: String): Boolean {
        val dayName = dayNameFromLabel(label) ?: return true
        return scanSchedule[dayName]?.enabled ?: true
    }

    /** Resolves the pickup-time windows that apply to [dateLabel]'s day — empty means
     *  any pickup time is accepted that day. */
    fun windowsForDay(dateLabel: String): List<TimeWindow> {
        val day = dayNameFromLabel(dateLabel) ?: return emptyList()
        return scanSchedule[day]?.windows ?: emptyList()
    }

    fun minPickupSummary(dateLabel: String = ""): String {
        val windows = windowsForDay(dateLabel)
        return if (windows.isEmpty()) "Any time" else windows.joinToString(", ") { it.label() }
    }

    /**
     * Returns true if the current wall-clock time falls within the configured
     * active window.  Always returns true when activeHoursEnabled=false.
     * Handles overnight ranges (e.g. 22:00 – 02:00).
     */
    fun isWithinActiveHours(): Boolean {
        if (!activeHoursEnabled) return true
        val cal = java.util.Calendar.getInstance()
        val nowMins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

        val todayOverride = if (perDayScheduleEnabled) perDaySchedule[currentDayName()] else null
        val useOverride   = todayOverride != null && !todayOverride.useDefault
        val windows       = if (useOverride) todayOverride!!.windows else activeWindows

        return windows.any { it.contains(nowMins) }
    }

    // isToday/isWeekend are threaded through from DasherAccessibilityService's date-tab scan
    // loop but no longer affect filtering here — same-day and weekend offers now follow the
    // same per-day scanSchedule windows as any other day (see shouldScanDay/windowsForDay).
    fun check(
        offer: DasherAccessibilityService.OfferDetails,
        isToday: Boolean,
        isWeekend: Boolean,
        dateLabel: String = ""
    ): Pair<Boolean, String> {
        if (offer.payout < minPayout)
            return false to "payout $${"%.2f".format(offer.payout)} < min $${"%.2f".format(minPayout)}"
        if (offer.miles > maxMiles)
            return false to "${"%.1f".format(offer.miles)}mi > max ${"%.1f".format(maxMiles)}mi"
        val effectiveWindows = windowsForDay(dateLabel)
        if (offer.pickupTime.isNotEmpty() && effectiveWindows.isNotEmpty()) {
            val pickupMins = parsePickupMinutes(offer.pickupTime)
            if (pickupMins != null && effectiveWindows.none { it.contains(pickupMins) })
                return false to "pickup at ${offer.pickupTime} not in allowed window(s) (${minPickupSummary(dateLabel)})"
        }
        val zone = offer.zone.lowercase().trim()
        if (zone.isNotEmpty()) {
            blockedZoneList().forEach { if (zone.contains(it)) return false to "zone '$zone' is blocked" }
            val allowed = allowedZoneList()
            if (allowed.isNotEmpty() && allowed.none { zone.contains(it) })
                return false to "zone '$zone' not in allowed list"
        }
        return true to "ok"
    }

    private fun parsePickupMinutes(time: String): Int? {
        val m12 = Regex("""(\d{1,2}):(\d{2})\s*([AP]M)""", RegexOption.IGNORE_CASE).find(time)
        if (m12 != null) {
            var h = m12.groupValues[1].toInt()
            val min = m12.groupValues[2].toInt()
            val ap = m12.groupValues[3].uppercase()
            if (ap == "PM" && h != 12) h += 12
            if (ap == "AM" && h == 12) h = 0
            return h * 60 + min
        }
        val m24 = Regex("""(\d{1,2}):(\d{2})""").find(time)
        if (m24 != null) {
            val h = m24.groupValues[1].toInt(); val min = m24.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return h * 60 + min
        }
        return null
    }

    fun isTelegramConfigured(): Boolean =
        telegramEnabled && telegramBotToken.isNotBlank() && telegramNotifyChatId.isNotBlank()

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * App-level fields (shared across all profiles) go straight to prefs as before.
     * Filter-criteria fields go to the currently-active [FilterProfile] instead — see
     * FilterProfileStore. Every existing call site of `.save(ctx)` needs no changes;
     * the split happens transparently here.
     */
    fun save(ctx: Context) {
        prefs(ctx).edit().apply {
            putBoolean("telegramEnabled",      telegramEnabled)
            putString("telegramBotToken",      telegramBotToken)
            putString("telegramNotifyChatId",  telegramNotifyChatId)
            putInt("offerCooldownMinutes",     offerCooldownMinutes)
            putBoolean("soundEnabled",         soundEnabled)
            putBoolean("speedMode",            speedMode)
            putBoolean("slowMode",             slowMode)
            putInt("slowMinSeconds",           slowMinSeconds)
            putInt("slowMaxSeconds",           slowMaxSeconds)
            putBoolean("slowRandomizeOrder",   slowRandomizeOrder)
            putBoolean("slowRandomizeTabs",    slowRandomizeTabs)
            putBoolean("screenLock",           screenLock)
            putBoolean("screenDim",            screenDim)
            apply()
        }
        val activeId = FilterProfileStore.getActiveId(ctx)
        val name = FilterProfileStore.get(ctx, activeId)?.name ?: "Default"
        FilterProfileStore.save(ctx, FilterProfile(
            id = activeId, name = name,
            minPayout = minPayout, maxMiles = maxMiles,
            scanSchedule = scanSchedule,
            allowedZones = allowedZones, blockedZones = blockedZones,
            activeHoursEnabled = activeHoursEnabled, activeWindows = activeWindows,
            perDayScheduleEnabled = perDayScheduleEnabled, perDaySchedule = perDaySchedule
        ))
    }


    companion object {
        val ALL_DAYS = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")

        val COOLDOWN_OPTIONS = listOf(5, 15, 30, 60, 0)   // minutes; 0 = never repeat
        val COOLDOWN_LABELS  = listOf("5 min", "15 min", "30 min", "1 hour", "Never repeat")

        val SLOW_INTERVAL_OPTIONS = listOf(15, 30, 60, 120, 300, 600, 900)   // seconds
        val SLOW_INTERVAL_LABELS  = listOf("15 sec", "30 sec", "1 min", "2 min", "5 min", "10 min", "15 min")

        fun currentDayName(): String {
            val cal = java.util.Calendar.getInstance()
            return listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        }

        /**
         * Extracts a full day name from a scraped date-tab label, e.g.
         * "Today, Wednesday 2026-05-20" -> "Wednesday", "Thursday 2026-05-21" -> "Thursday".
         * Returns null only when the label is blank (day can't be determined).
         */
        fun dayNameFromLabel(label: String): String? = when {
            label.isBlank() -> null
            label.startsWith("Today,") ->
                label.substringAfter("Today,").trim().split(" ").firstOrNull() ?: currentDayName()
            label.startsWith("Today") -> currentDayName()
            else -> label.split(" ").firstOrNull()
        }

        /** Backed by EncryptedSharedPreferences (AES256-GCM, Keystore-managed master key) —
         *  this file holds live bearer credentials (userToken) alongside filter settings,
         *  so it shouldn't sit in plaintext on disk. */
        internal fun prefs(ctx: Context): SharedPreferences {
            val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            return androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx,
                "catering_filters",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        internal fun encodeWindows(w: List<TimeWindow>): String = w.joinToString("|") { it.encode() }
        internal fun decodeWindows(raw: String?): List<TimeWindow> =
            raw?.split("|")?.filter { it.isNotBlank() }?.mapNotNull { TimeWindow.decode(it) } ?: emptyList()

        /** Derives the new per-day [scanSchedule] map from the old scanDays/pickupWindows/
         *  pickupPerDayEnabled/pickupPerDaySchedule fields — used both by [loadLegacyCriteria]
         *  (flat SharedPreferences) and by FilterProfileStore's JSON migration, so the
         *  derivation only exists once and both paths stay identical. */
        fun deriveScanSchedule(
            scanDays: Set<String>,
            pickupWindows: List<TimeWindow>,
            pickupPerDayEnabled: Boolean,
            pickupPerDaySchedule: Map<String, DayActiveHours>
        ): Map<String, DaySchedule> = ALL_DAYS.associateWith { day ->
            val enabled = scanDays.isEmpty() || day in scanDays
            val windows = if (!pickupPerDayEnabled) pickupWindows
                else pickupPerDaySchedule[day]?.let { if (it.useDefault) pickupWindows else it.windows } ?: pickupWindows
            DaySchedule(enabled = enabled, windows = windows)
        }

        /** App-level-only save of the Telegram bot credentials — never touches
         *  filter-criteria fields, so it can't clobber the active profile. */
        fun saveTelegramCredentials(ctx: Context, botToken: String, chatId: String) {
            prefs(ctx).edit().apply {
                putString("telegramBotToken", botToken)
                putString("telegramNotifyChatId", chatId)
                apply()
            }
        }

        /** One-time onboarding milestone — distinct from the filter values themselves, since
         *  "defaults happen to match what the wizard would produce" isn't the same as "the
         *  user actually went through it." Used by MainActivity's 5th setup step. */
        fun markFiltersConfigured(ctx: Context) {
            prefs(ctx).edit().putBoolean("filtersConfigured", true).apply()
        }
        fun isFiltersConfigured(ctx: Context): Boolean =
            prefs(ctx).getBoolean("filtersConfigured", false)

        /**
         * Reconstructs the pre-multi-profile flat-key criteria as a [FilterProfile] named
         * "Default" — used exactly once by FilterProfileStore.ensureMigrated() to seed the
         * first profile. This is the same fallback logic `load()` used before profiles
         * existed; kept verbatim so an upgrading user's settings survive unchanged.
         */
        internal fun loadLegacyCriteria(ctx: Context): FilterProfile {
            val p = prefs(ctx)

            val pickupWindows = if (p.contains("pickupWindows")) decodeWindows(p.getString("pickupWindows", ""))
            else {
                // Fallback default is "no restriction" (0,0 -> emptyList below), not the old
                // Mac Hammerspoon script's hardcoded 11:10 AM pickup deadline — that was a
                // personal setting, not a sensible generic default for a fresh install.
                val h = p.getInt("minPickupHour", 0); val m = p.getInt("minPickupMinute", 0)
                if (h == 0 && m == 0) emptyList() else listOf(TimeWindow(h, m, 23, 59))
            }

            val activeWindows = if (p.contains("activeWindows")) decodeWindows(p.getString("activeWindows", ""))
            else listOf(TimeWindow(
                p.getInt("activeStartHour", 7), p.getInt("activeStartMinute", 0),
                p.getInt("activeStopHour", 22), p.getInt("activeStopMinute", 0)
            ))

            val legacyScanDays = p.getString("scanDays", "")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
            val legacyPickupPerDayEnabled = p.getBoolean("pickupPerDayEnabled", false)
            val legacyPickupPerDaySchedule = ALL_DAYS.associateWith { day ->
                DayActiveHours(
                    useDefault = p.getBoolean("sched_pickup_${day}_default", true),
                    windows    = decodeWindows(p.getString("sched_pickup_${day}_windows", ""))
                )
            }

            return FilterProfile(
                id = "default", name = "Default",
                minPayout       = p.getFloat("minPayout",         30f).toDouble(),
                maxMiles        = p.getFloat("maxMiles",          20f).toDouble(),
                scanSchedule    = deriveScanSchedule(legacyScanDays, pickupWindows, legacyPickupPerDayEnabled, legacyPickupPerDaySchedule),
                allowedZones    = p.getString("allowedZones",     "") ?: "",
                blockedZones    = p.getString("blockedZones",     "") ?: "",
                activeHoursEnabled    = p.getBoolean("activeHoursEnabled",    false),
                activeWindows         = activeWindows,
                perDayScheduleEnabled = p.getBoolean("perDayScheduleEnabled", false),
                perDaySchedule        = ALL_DAYS.associateWith { day ->
                    val windows = if (p.contains("sched_${day}_windows"))
                        decodeWindows(p.getString("sched_${day}_windows", ""))
                    else listOf(TimeWindow(
                        p.getInt("sched_${day}_startH", 7), p.getInt("sched_${day}_startM", 0),
                        p.getInt("sched_${day}_stopH", 22), p.getInt("sched_${day}_stopM", 0)
                    ))
                    DayActiveHours(
                        useDefault = p.getBoolean("sched_${day}_default", true),
                        windows    = windows
                    )
                }
            )
        }

        /** Returns the merged effective settings: app-level fields from prefs + the
         *  currently-active profile's filter-criteria fields (see FilterProfileStore). */
        fun load(ctx: Context): FilterSettings {
            FilterProfileStore.ensureMigrated(ctx)
            val p = prefs(ctx)
            val profile = FilterProfileStore.getActive(ctx)

            return FilterSettings(
                minPayout       = profile.minPayout,
                maxMiles        = profile.maxMiles,
                scanSchedule    = profile.scanSchedule,
                allowedZones    = profile.allowedZones,
                blockedZones    = profile.blockedZones,
                telegramEnabled      = p.getBoolean("telegramEnabled", true),
                telegramBotToken     = p.getString("telegramBotToken", "") ?: "",
                telegramNotifyChatId = p.getString("telegramNotifyChatId", "") ?: "",
                offerCooldownMinutes = p.getInt("offerCooldownMinutes", 15),
                soundEnabled        = p.getBoolean("soundEnabled", true),
                speedMode           = p.getBoolean("speedMode",    false),
                slowMode            = p.getBoolean("slowMode",     false),
                slowMinSeconds      = p.getInt("slowMinSeconds",   30),
                slowMaxSeconds      = p.getInt("slowMaxSeconds",   180),
                slowRandomizeOrder  = p.getBoolean("slowRandomizeOrder", false),
                slowRandomizeTabs   = p.getBoolean("slowRandomizeTabs", false),
                screenLock          = p.getBoolean("screenLock",   false),
                screenDim           = p.getBoolean("screenDim",    false),
                activeHoursEnabled    = profile.activeHoursEnabled,
                activeWindows         = profile.activeWindows,
                perDayScheduleEnabled = profile.perDayScheduleEnabled,
                perDaySchedule        = profile.perDaySchedule
            )
        }
    }
}
