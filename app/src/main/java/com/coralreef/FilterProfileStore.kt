package com.coralreef

import android.content.Context
import org.json.JSONObject

/**
 * Persists the list of named [FilterProfile]s and which one is active, in the same
 * "catering_filters" SharedPreferences file FilterSettings already uses. Each profile is
 * stored as one JSON string value — cheaper to add/clone/delete than namespacing the
 * ~36 flat keys a profile's schedule data would otherwise need per profile.
 */
object FilterProfileStore {
    private const val KEY_PROFILE_IDS = "profile_ids"
    private const val KEY_ACTIVE_ID   = "active_profile_id"
    private const val KEY_MIGRATED    = "profiles_migrated_v1"
    private fun profileKey(id: String) = "profile_$id"

    fun list(ctx: Context): List<FilterProfile> {
        ensureMigrated(ctx)
        val ids = FilterSettings.prefs(ctx).getString(KEY_PROFILE_IDS, "")
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        return ids.mapNotNull { get(ctx, it) }
    }

    fun getActiveId(ctx: Context): String {
        ensureMigrated(ctx)
        return FilterSettings.prefs(ctx).getString(KEY_ACTIVE_ID, "default") ?: "default"
    }

    fun setActiveId(ctx: Context, id: String) {
        FilterSettings.prefs(ctx).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun get(ctx: Context, id: String): FilterProfile? {
        val raw = FilterSettings.prefs(ctx).getString(profileKey(id), null) ?: return null
        return profileFromJson(id, raw)
    }

    fun getActive(ctx: Context): FilterProfile {
        ensureMigrated(ctx)
        return get(ctx, getActiveId(ctx)) ?: FilterProfile(id = "default", name = "Default")
    }

    /** Upsert by id. */
    fun save(ctx: Context, profile: FilterProfile) {
        val p = FilterSettings.prefs(ctx)
        val ids = (p.getString(KEY_PROFILE_IDS, "")
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
        if (profile.id !in ids) ids.add(profile.id)
        p.edit()
            .putString(profileKey(profile.id), profileToJson(profile))
            .putString(KEY_PROFILE_IDS, ids.joinToString(","))
            .apply()
    }

    /** Clones [cloneFrom]'s criteria under a new id/name — a fresh blank profile would start
     *  from data-class defaults ($30 min payout etc.), which is a worse starting point than
     *  duplicating whatever's already configured. */
    fun add(ctx: Context, name: String, cloneFrom: FilterProfile): FilterProfile {
        val newProfile = cloneFrom.copy(id = java.util.UUID.randomUUID().toString(), name = name)
        save(ctx, newProfile)
        return newProfile
    }

    fun rename(ctx: Context, id: String, newName: String) {
        val existing = get(ctx, id) ?: return
        save(ctx, existing.copy(name = newName))
    }

    /** No-op if [id] is the only remaining profile — the app must always have at least one. */
    fun delete(ctx: Context, id: String) {
        val ids = list(ctx).map { it.id }
        if (ids.size <= 1 || id !in ids) return
        val remaining = ids.filter { it != id }
        FilterSettings.prefs(ctx).edit()
            .remove(profileKey(id))
            .putString(KEY_PROFILE_IDS, remaining.joinToString(","))
            .apply()
        if (getActiveId(ctx) == id) setActiveId(ctx, remaining.first())
    }

    /** One-time migration from the pre-profile flat-key format into a single "Default"
     *  profile. Guarded so it only ever runs once; the old flat keys are left in place
     *  (harmless orphans) as a zero-data-loss safety net. */
    internal fun ensureMigrated(ctx: Context) {
        val p = FilterSettings.prefs(ctx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        if (!p.getString(KEY_PROFILE_IDS, "").isNullOrBlank()) {
            p.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        val defaultProfile = FilterSettings.loadLegacyCriteria(ctx)
        p.edit()
            .putString(profileKey(defaultProfile.id), profileToJson(defaultProfile))
            .putString(KEY_PROFILE_IDS, defaultProfile.id)
            .putString(KEY_ACTIVE_ID, defaultProfile.id)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    // ── JSON encode/decode ───────────────────────────────────────────────────

    private fun profileToJson(p: FilterProfile): String = JSONObject().apply {
        put("name", p.name)
        put("minPayout", p.minPayout)
        put("maxMiles", p.maxMiles)
        put("scanSchedule", scanScheduleToJson(p.scanSchedule))
        put("allowedZones", p.allowedZones)
        put("blockedZones", p.blockedZones)
        put("activeHoursEnabled", p.activeHoursEnabled)
        put("activeWindows", FilterSettings.encodeWindows(p.activeWindows))
        put("perDayScheduleEnabled", p.perDayScheduleEnabled)
        put("perDaySchedule", dayScheduleToJson(p.perDaySchedule))
    }.toString()

    private fun profileFromJson(id: String, raw: String): FilterProfile {
        val o = JSONObject(raw)
        return FilterProfile(
            id = id,
            name = o.optString("name", "Untitled"),
            minPayout = o.optDouble("minPayout", 30.0),
            maxMiles = o.optDouble("maxMiles", 20.0),
            scanSchedule = if (o.has("scanSchedule")) {
                scanScheduleFromJson(o.optString("scanSchedule", ""))
            } else {
                // Pre-merge profile — derive once from the old fields still present in this JSON blob.
                FilterSettings.deriveScanSchedule(
                    scanDays = o.optString("scanDays", "")
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    pickupWindows = FilterSettings.decodeWindows(o.optString("pickupWindows", "")),
                    pickupPerDayEnabled = o.optBoolean("pickupPerDayEnabled", false),
                    pickupPerDaySchedule = dayScheduleFromJson(o.optString("pickupPerDaySchedule", ""))
                )
            },
            allowedZones = o.optString("allowedZones", ""),
            blockedZones = o.optString("blockedZones", ""),
            activeHoursEnabled = o.optBoolean("activeHoursEnabled", false),
            activeWindows = FilterSettings.decodeWindows(o.optString("activeWindows", ""))
                .ifEmpty { listOf(FilterSettings.TimeWindow()) },
            perDayScheduleEnabled = o.optBoolean("perDayScheduleEnabled", false),
            perDaySchedule = dayScheduleFromJson(o.optString("perDaySchedule", ""))
        )
    }

    private fun dayScheduleToJson(m: Map<String, FilterSettings.DayActiveHours>): String =
        JSONObject().apply {
            m.forEach { (day, dh) ->
                put(day, JSONObject().apply {
                    put("useDefault", dh.useDefault)
                    put("windows", FilterSettings.encodeWindows(dh.windows))
                })
            }
        }.toString()

    private fun dayScheduleFromJson(raw: String?): Map<String, FilterSettings.DayActiveHours> {
        if (raw.isNullOrBlank()) return emptyMap()
        val o = JSONObject(raw)
        val result = mutableMapOf<String, FilterSettings.DayActiveHours>()
        o.keys().forEach { day ->
            val dayObj = o.getJSONObject(day)
            result[day] = FilterSettings.DayActiveHours(
                useDefault = dayObj.optBoolean("useDefault", true),
                windows = FilterSettings.decodeWindows(dayObj.optString("windows", ""))
            )
        }
        return result
    }

    private fun scanScheduleToJson(m: Map<String, FilterSettings.DaySchedule>): String =
        JSONObject().apply {
            m.forEach { (day, ds) ->
                put(day, JSONObject().apply {
                    put("enabled", ds.enabled)
                    put("windows", FilterSettings.encodeWindows(ds.windows))
                })
            }
        }.toString()

    private fun scanScheduleFromJson(raw: String?): Map<String, FilterSettings.DaySchedule> {
        if (raw.isNullOrBlank()) return emptyMap()
        val o = JSONObject(raw)
        val result = mutableMapOf<String, FilterSettings.DaySchedule>()
        o.keys().forEach { day ->
            val dayObj = o.getJSONObject(day)
            result[day] = FilterSettings.DaySchedule(
                enabled = dayObj.optBoolean("enabled", true),
                windows = FilterSettings.decodeWindows(dayObj.optString("windows", ""))
            )
        }
        return result
    }
}
