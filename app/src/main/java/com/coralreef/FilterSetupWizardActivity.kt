package com.coralreef

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * A 5-question guided setup, shown from MainActivity's "Configure Filters" step. Every field
 * is pre-filled from the current FilterSettings/active FilterProfile before page 1 shows, so
 * skipping at any point is safe — it just keeps whatever was already there.
 */
class FilterSetupWizardActivity : AppCompatActivity() {

    private val totalPages = 5
    private var currentPage = 0
    private lateinit var pages: List<View>

    private lateinit var tvPageIndicator: TextView
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button

    private lateinit var etMinPayout:     EditText
    private lateinit var etMaxMiles:      EditText
    private lateinit var etAllowedZones:  EditText
    private lateinit var etBlockedZones:  EditText
    private lateinit var cbSelectAll:     CheckBox
    private val dayCheckboxes = mutableMapOf<String, CheckBox>()
    private lateinit var swPickupRestrict: Switch
    private lateinit var pickupTimeRow:    LinearLayout
    private lateinit var btnPickupStart:   MaterialButton
    private lateinit var btnPickupStop:    MaterialButton
    private var pickupStartH = 7;  private var pickupStartM = 0
    private var pickupStopH  = 22; private var pickupStopM  = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_filter_wizard)

        pages = listOf(
            findViewById(R.id.page_1), findViewById(R.id.page_2), findViewById(R.id.page_3),
            findViewById(R.id.page_4), findViewById(R.id.page_5)
        )
        tvPageIndicator = findViewById(R.id.tv_page_indicator)
        btnBack = findViewById(R.id.btn_wizard_back)
        btnNext = findViewById(R.id.btn_wizard_next)

        etMinPayout     = findViewById(R.id.et_wizard_min_payout)
        etMaxMiles      = findViewById(R.id.et_wizard_max_miles)
        etAllowedZones  = findViewById(R.id.et_wizard_allowed_zones)
        etBlockedZones  = findViewById(R.id.et_wizard_blocked_zones)
        cbSelectAll     = findViewById(R.id.cb_wizard_select_all)
        swPickupRestrict = findViewById(R.id.sw_wizard_pickup_restrict)
        pickupTimeRow    = findViewById(R.id.ll_wizard_pickup_time)
        btnPickupStart   = findViewById(R.id.btn_wizard_pickup_start)
        btnPickupStop    = findViewById(R.id.btn_wizard_pickup_stop)

        prefillFromCurrentSettings()
        buildScanDayRows()

        cbSelectAll.setOnCheckedChangeListener { _, checked ->
            dayCheckboxes.values.forEach { it.isChecked = checked }
        }
        swPickupRestrict.setOnCheckedChangeListener { _, checked ->
            pickupTimeRow.visibility = if (checked) View.VISIBLE else View.GONE
        }
        btnPickupStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                pickupStartH = h; pickupStartM = m
                btnPickupStart.text = formatTime(h, m)
            }, pickupStartH, pickupStartM, false).show()
        }
        btnPickupStop.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                pickupStopH = h; pickupStopM = m
                btnPickupStop.text = formatTime(h, m)
            }, pickupStopH, pickupStopM, false).show()
        }

        btnBack.setOnClickListener {
            if (currentPage > 0) { currentPage--; showPage() }
        }
        btnNext.setOnClickListener {
            if (currentPage < totalPages - 1) { currentPage++; showPage() } else finishWizard()
        }
        findViewById<TextView>(R.id.tv_skip_wizard).setOnClickListener { finishWizard() }

        showPage()
    }

    private fun prefillFromCurrentSettings() {
        val f = FilterSettings.load(this)
        etMinPayout.setText("%.2f".format(f.minPayout))
        etMaxMiles.setText("%.1f".format(f.maxMiles))
        etAllowedZones.setText(f.allowedZones)
        etBlockedZones.setText(f.blockedZones)

        val firstWindow = f.scanSchedule.values.firstOrNull { it.windows.isNotEmpty() }?.windows?.firstOrNull()
        if (firstWindow != null) {
            pickupStartH = firstWindow.startHour; pickupStartM = firstWindow.startMinute
            pickupStopH  = firstWindow.stopHour;  pickupStopM  = firstWindow.stopMinute
        }
        swPickupRestrict.isChecked = firstWindow != null
        pickupTimeRow.visibility = if (firstWindow != null) View.VISIBLE else View.GONE
        btnPickupStart.text = formatTime(pickupStartH, pickupStartM)
        btnPickupStop.text  = formatTime(pickupStopH, pickupStopM)
    }

    private fun buildScanDayRows() {
        val f = FilterSettings.load(this)
        val container = findViewById<LinearLayout>(R.id.ll_wizard_scan_days)
        val dp = resources.displayMetrics.density
        FilterSettings.ALL_DAYS.forEach { day ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }
            val cb = CheckBox(this).apply {
                isChecked = f.scanSchedule[day]?.enabled ?: true
            }
            val label = TextView(this).apply {
                text = day
                textSize = 14f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@FilterSetupWizardActivity, R.color.text_secondary))
                setPadding((6 * dp).toInt(), 0, 0, 0)
            }
            row.addView(cb); row.addView(label)
            container.addView(row)
            dayCheckboxes[day] = cb
        }
    }

    private fun showPage() {
        pages.forEachIndexed { i, v -> v.visibility = if (i == currentPage) View.VISIBLE else View.GONE }
        btnBack.visibility = if (currentPage == 0) View.INVISIBLE else View.VISIBLE
        btnNext.text = if (currentPage == totalPages - 1) "Finish" else "Next"
        tvPageIndicator.text = "Question ${currentPage + 1} of $totalPages"
    }

    private fun finishWizard() {
        val minPayout = etMinPayout.text.toString().toDoubleOrNull() ?: 30.0
        val maxMiles  = etMaxMiles.text.toString().toDoubleOrNull() ?: 20.0
        val pickupWindows = if (swPickupRestrict.isChecked)
            listOf(FilterSettings.TimeWindow(pickupStartH, pickupStartM, pickupStopH, pickupStopM))
        else emptyList()
        val scanSchedule = FilterSettings.ALL_DAYS.associateWith { day ->
            FilterSettings.DaySchedule(
                enabled = dayCheckboxes[day]?.isChecked ?: true,
                windows = pickupWindows
            )
        }

        FilterSettings.load(this).copy(
            minPayout = minPayout,
            maxMiles = maxMiles,
            allowedZones = etAllowedZones.text.toString().trim(),
            blockedZones = etBlockedZones.text.toString().trim(),
            scanSchedule = scanSchedule
        ).save(this)

        FilterSettings.markFiltersConfigured(this)
        finish()
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val h12  = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
        return "%d:%02d %s".format(h12, minute, amPm)
    }
}
