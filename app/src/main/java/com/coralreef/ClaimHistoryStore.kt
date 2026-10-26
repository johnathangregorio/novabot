package com.coralreef

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// On-device claim history shown on the Home tab — newest first, capped at MAX_ENTRIES.
object ClaimHistoryStore {

    private const val PREFS = "claim_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 100

    data class ClaimEntry(
        val ts:         Long,     // epoch seconds
        val payout:     Double,
        val restaurant: String,
        val zone:       String
    )

    @Synchronized
    fun record(ctx: Context, payout: Double, restaurant: String, zone: String) {
        val entries = list(ctx).toMutableList()
        entries.add(0, ClaimEntry(System.currentTimeMillis() / 1000L, payout, restaurant, zone))
        val arr = JSONArray()
        entries.take(MAX_ENTRIES).forEach { e ->
            arr.put(JSONObject().apply {
                put("ts", e.ts)
                put("payout", e.payout)
                put("restaurant", e.restaurant)
                put("zone", e.zone)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun list(ctx: Context): List<ClaimEntry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ClaimEntry(
                    ts         = o.optLong("ts"),
                    payout     = o.optDouble("payout", 0.0),
                    restaurant = o.optString("restaurant"),
                    zone       = o.optString("zone")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
