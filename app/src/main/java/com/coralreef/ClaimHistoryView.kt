package com.coralreef

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

// Renders the on-device claim history (see ClaimHistoryStore) for the Recent Claims dashboard
// on SettingsActivity's Home tab.
object ClaimHistoryView {

    private fun col(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)

    fun populate(ctx: Context, container: LinearLayout, entries: List<ClaimHistoryStore.ClaimEntry>) {
        container.removeAllViews()
        val dp   = ctx.resources.displayMetrics.density
        val dp4  = (4  * dp).toInt()
        val dp6  = (6  * dp).toInt()
        val dp8  = (8  * dp).toInt()
        val dp12 = (12 * dp).toInt()
        val sdf  = java.text.SimpleDateFormat("MMM d  h:mm a", java.util.Locale.US)

        if (entries.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No claims recorded yet"
                setTextColor(col(ctx, R.color.text_hint))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp8, 0, dp8)
            })
            return
        }

        entries.forEachIndexed { i, entry ->
            if (i > 0) {
                container.addView(View(ctx).apply {
                    setBackgroundColor(col(ctx, R.color.divider_line))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }

            val date = sdf.format(java.util.Date(entry.ts * 1000L))
            val name = entry.restaurant.ifBlank { entry.zone.ifBlank { "Catering offer" } }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp12, 0, dp12)
            }

            val line1 = TextView(ctx).apply {
                text = name
                setTextColor(col(ctx, R.color.text_primary))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val line2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            line2.addView(TextView(ctx).apply {
                text = entry.zone.ifBlank { "—" }
                setTextColor(col(ctx, R.color.text_hint))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, dp4, 0, dp8)
            })
            line2.addView(TextView(ctx).apply {
                text = date
                setTextColor(col(ctx, R.color.text_hint))
                textSize = 11f
                setPadding(0, dp4, 0, dp8)
            })

            val statsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(col(ctx, R.color.stat_row_bg))
                setPadding(dp6, dp6, dp6, dp6)
                addView(TextView(ctx).apply {
                    text = "OFFER"
                    setTextColor(col(ctx, R.color.text_hint))
                    textSize = 9f
                    gravity = android.view.Gravity.CENTER
                    letterSpacing = 0.1f
                })
                addView(TextView(ctx).apply {
                    text = "\$${String.format("%.2f", entry.payout)}"
                    setTextColor(col(ctx, R.color.text_primary))
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }

            row.addView(line1)
            row.addView(line2)
            row.addView(statsRow)
            container.addView(row)
        }
    }
}
