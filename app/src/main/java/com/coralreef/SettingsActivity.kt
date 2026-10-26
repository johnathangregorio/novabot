package com.coralreef

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    // Set right before recreate() during an intentional profile switch/add/delete, so the
    // recreate-triggered onPause()->saveSettings() doesn't clobber the new active profile
    // with the old (about-to-be-discarded) screen's stale values.
    private var isSwitchingProfile = false

    // Class-level refs so saveAndExit() can reach them from back/up navigation
    private lateinit var etMinPayout:     EditText
    private lateinit var etMaxMiles:      EditText
    private lateinit var etAllowedZones:  EditText
    private lateinit var etBlockedZones:  EditText
    private lateinit var cbSelectAllDays: CheckBox
    private lateinit var swTelegramEnabled: Switch
    private lateinit var etTelegramBotToken: EditText
    private lateinit var etTelegramChatId: EditText
    private lateinit var spinnerCooldown: Spinner
    private lateinit var swSoundEnabled: Switch
    private lateinit var swSpeedMode:    Switch
    private lateinit var swSlowMode:     Switch
    private lateinit var spinnerSlowMin: Spinner
    private lateinit var spinnerSlowMax: Spinner
    private lateinit var rowSlowRange:   LinearLayout
    private lateinit var swSlowRandomizeOrder: Switch
    private lateinit var rowSlowRandomizeOrder: LinearLayout
    private lateinit var swSlowRandomizeTabs: Switch
    private lateinit var rowSlowRandomizeTabs: LinearLayout
    private lateinit var swScreenLock:   Switch
    private lateinit var swScreenDim:    Switch
    private lateinit var containerDashboardHistory: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout

    // Active hours
    private lateinit var swActiveHours:      Switch
    private lateinit var swPerDaySchedule:   Switch
    private lateinit var llPerDaySchedule:   LinearLayout
    private lateinit var defaultWindowsUi:   WindowListUi

    private data class DayRowRefs(val swDefault: Switch, val container: LinearLayout, val windowsUi: WindowListUi)
    private val dayRowRefs = mutableMapOf<String, DayRowRefs>()

    // Scan Schedule (merged Scan Days + Pickup Time Windows)
    private data class ScanDayRowRefs(val cbEnabled: CheckBox, val windowsContainer: LinearLayout, val windowsUi: WindowListUi)
    private val scanDayRowRefs = mutableMapOf<String, ScanDayRowRefs>()

    // Tab bar
    private lateinit var btnTabHome:     Button
    private lateinit var btnTabAccount:  Button
    private lateinit var btnTabBot:      Button
    private lateinit var btnTabFilters:  Button
    private lateinit var contentTabHome:    LinearLayout
    private lateinit var contentTabAccount: LinearLayout
    private lateinit var contentTabBot:     LinearLayout
    private lateinit var contentTabFilters: LinearLayout

    private data class WindowRow(var sh: Int, var sm: Int, var eh: Int, var em: Int)

    /**
     * Renders a variable-length list of [FilterSettings.TimeWindow] rows (Start/Stop time
     * buttons + remove button) into [container], plus a trailing "+ Add window" row.
     * [minRows] is the floor below which the remove button is hidden (e.g. 1 for the
     * Active Hours default schedule, so it can never be emptied out entirely).
     */
    private inner class WindowListUi(
        private val container: LinearLayout,
        initial: List<FilterSettings.TimeWindow>,
        private val minRows: Int = 0,
        private val onChange: () -> Unit = {}
    ) {
        private val rows = initial.map { WindowRow(it.startHour, it.startMinute, it.stopHour, it.stopMinute) }.toMutableList()
        private var enabledState = true

        init { rebuild() }

        fun toTimeWindows(): List<FilterSettings.TimeWindow> =
            rows.map { FilterSettings.TimeWindow(it.sh, it.sm, it.eh, it.em) }

        /** Seeds rows from [seed] only if currently empty — used when a day is switched off
         *  "Default" for the first time, so it starts from a sensible value instead of blank. */
        fun seedIfEmpty(seed: List<FilterSettings.TimeWindow>) {
            if (rows.isEmpty() && seed.isNotEmpty()) {
                rows.addAll(seed.map { WindowRow(it.startHour, it.startMinute, it.stopHour, it.stopMinute) })
                rebuild()
            }
        }

        fun setEnabled(on: Boolean) {
            enabledState = on
            container.alpha = if (on) 1.0f else 0.38f
            setTreeEnabled(container, on)
        }

        private fun setTreeEnabled(v: View, on: Boolean) {
            v.isEnabled = on
            if (v is ViewGroup) for (i in 0 until v.childCount) setTreeEnabled(v.getChildAt(i), on)
        }

        private fun rebuild() {
            container.removeAllViews()
            val dp = resources.displayMetrics.density
            val accentBg   = ContextCompat.getColor(this@SettingsActivity, R.color.btn_accent_bg)
            val accentText = ContextCompat.getColor(this@SettingsActivity, R.color.text_section)
            val hintColor  = ContextCompat.getColor(this@SettingsActivity, R.color.text_hint)

            fun mkTimeBtn(label: String) = MaterialButton(this@SettingsActivity).apply {
                text = label
                textSize = 12f
                backgroundTintList = ColorStateList.valueOf(accentBg)
                setTextColor(accentText)
                insetTop = 0; insetBottom = 0
                layoutParams = LinearLayout.LayoutParams((92 * dp).toInt(), (36 * dp).toInt())
            }

            rows.forEachIndexed { index, r ->
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                }
                val btnStart = mkTimeBtn(formatTime(r.sh, r.sm))
                val tvSep = TextView(this@SettingsActivity).apply {
                    text = "–"; textSize = 14f; setTextColor(hintColor)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
                    }
                }
                val btnStop = mkTimeBtn(formatTime(r.eh, r.em))
                btnStart.setOnClickListener {
                    TimePickerDialog(this@SettingsActivity, { _, h, m ->
                        r.sh = h; r.sm = m; btnStart.text = formatTime(h, m); onChange()
                    }, r.sh, r.sm, false).show()
                }
                btnStop.setOnClickListener {
                    TimePickerDialog(this@SettingsActivity, { _, h, m ->
                        r.eh = h; r.em = m; btnStop.text = formatTime(h, m); onChange()
                    }, r.eh, r.em, false).show()
                }
                val spacer = View(this@SettingsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                val btnRemove = TextView(this@SettingsActivity).apply {
                    text = "✕"; textSize = 16f; setTextColor(hintColor)
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
                    isClickable = true; isFocusable = true
                    visibility = if (rows.size > minRows) View.VISIBLE else View.INVISIBLE
                    setOnClickListener {
                        if (rows.size > minRows) { rows.removeAt(index); rebuild(); onChange() }
                    }
                }
                row.addView(btnStart); row.addView(tvSep); row.addView(btnStop)
                row.addView(spacer); row.addView(btnRemove)
                container.addView(row)
            }

            val addRow = TextView(this@SettingsActivity).apply {
                text = "+ Add window"
                textSize = 13f
                setTextColor(accentText)
                setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
                isClickable = true; isFocusable = true
                setOnClickListener {
                    rows.add(WindowRow(7, 0, 22, 0)); rebuild(); onChange()
                }
            }
            container.addView(addRow)
            setTreeEnabled(container, enabledState)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 enables edge-to-edge by default, which draws content behind the
        // status bar unless explicitly opted out — without this, the top of the scrolled
        // content (tab bar) renders partially under it.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = "NovaBot"

        val f = FilterSettings.load(this)

        // ── Filter profile picker ───────────────────────────────────
        val spinnerProfile   = findViewById<Spinner>(R.id.spinner_profile)
        val btnProfileEdit   = findViewById<Button>(R.id.btn_profile_edit)
        val btnProfileAdd    = findViewById<Button>(R.id.btn_profile_add)
        val btnProfileDelete = findViewById<Button>(R.id.btn_profile_delete)

        fun refreshProfileSpinner(selectId: String) {
            val profiles = FilterProfileStore.list(this)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, profiles.map { it.name })
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerProfile.adapter = adapter
            val idx = profiles.indexOfFirst { it.id == selectId }.coerceAtLeast(0)
            spinnerProfile.setSelection(idx)
            btnProfileDelete.isEnabled = profiles.size > 1
            btnProfileDelete.alpha = if (profiles.size > 1) 1.0f else 0.35f
        }
        refreshProfileSpinner(FilterProfileStore.getActiveId(this))

        spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val profiles = FilterProfileStore.list(this@SettingsActivity)
                val selected = profiles.getOrNull(position) ?: return
                if (selected.id == FilterProfileStore.getActiveId(this@SettingsActivity)) return
                isSwitchingProfile = true
                saveSettings()
                FilterProfileStore.setActiveId(this@SettingsActivity, selected.id)
                recreate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnProfileEdit.setOnClickListener {
            val activeId = FilterProfileStore.getActiveId(this)
            val current = FilterProfileStore.get(this, activeId) ?: return@setOnClickListener
            val input = EditText(this).apply {
                setText(current.name)
                setSelection(current.name.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Rename filter")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        FilterProfileStore.rename(this, activeId, newName)
                        refreshProfileSpinner(activeId)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnProfileAdd.setOnClickListener {
            val input = EditText(this).apply { hint = "Filter name" }
            AlertDialog.Builder(this)
                .setTitle("New filter profile")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val name = input.text.toString().trim().ifBlank { "New Filter" }
                    isSwitchingProfile = true
                    saveSettings()
                    val newProfile = FilterProfileStore.add(this, name, FilterProfileStore.getActive(this))
                    FilterProfileStore.setActiveId(this, newProfile.id)
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnProfileDelete.setOnClickListener {
            val activeId = FilterProfileStore.getActiveId(this)
            if (FilterProfileStore.list(this).size <= 1) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Delete this filter profile?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    isSwitchingProfile = true
                    FilterProfileStore.delete(this, activeId)
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        etMinPayout      = findViewById(R.id.et_min_payout)
        etMaxMiles       = findViewById(R.id.et_max_miles)
        etAllowedZones   = findViewById(R.id.et_allowed_zones)
        etBlockedZones   = findViewById(R.id.et_blocked_zones)
        swTelegramEnabled = findViewById(R.id.sw_telegram_enabled)
        etTelegramBotToken = findViewById(R.id.et_telegram_bot_token)
        etTelegramChatId  = findViewById(R.id.et_telegram_chat_id)
        val tvTelegramStatus = findViewById<TextView>(R.id.tv_telegram_status)

        spinnerCooldown  = findViewById(R.id.spinner_cooldown)
        swSoundEnabled   = findViewById(R.id.sw_sound_enabled)
        swSpeedMode      = findViewById(R.id.sw_speed_mode)
        swSlowMode       = findViewById(R.id.sw_slow_mode)
        spinnerSlowMin   = findViewById(R.id.spinner_slow_min)
        spinnerSlowMax   = findViewById(R.id.spinner_slow_max)
        rowSlowRange     = findViewById(R.id.row_slow_range)
        swSlowRandomizeOrder  = findViewById(R.id.sw_slow_randomize_order)
        rowSlowRandomizeOrder = findViewById(R.id.row_slow_randomize_order)
        swSlowRandomizeTabs   = findViewById(R.id.sw_slow_randomize_tabs)
        rowSlowRandomizeTabs  = findViewById(R.id.row_slow_randomize_tabs)
        swScreenLock      = findViewById(R.id.sw_screen_lock)
        swScreenDim       = findViewById(R.id.sw_screen_dim)
        containerDashboardHistory = findViewById(R.id.container_dashboard_history)

        swipeRefresh = findViewById(R.id.swipe_refresh_settings)
        swipeRefresh.setColorSchemeResources(R.color.text_section)
        swipeRefresh.setOnRefreshListener { loadDashboardHistory() }

        val cooldownAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, FilterSettings.COOLDOWN_LABELS)
        cooldownAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCooldown.adapter = cooldownAdapter
        spinnerCooldown.setSelection(FilterSettings.COOLDOWN_OPTIONS.indexOf(f.offerCooldownMinutes).coerceAtLeast(0))

        val slowIntervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, FilterSettings.SLOW_INTERVAL_LABELS)
        slowIntervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSlowMin.adapter = slowIntervalAdapter
        spinnerSlowMax.adapter = slowIntervalAdapter
        spinnerSlowMin.setSelection(FilterSettings.SLOW_INTERVAL_OPTIONS.indexOf(f.slowMinSeconds).coerceAtLeast(0))
        spinnerSlowMax.setSelection(FilterSettings.SLOW_INTERVAL_OPTIONS.indexOf(f.slowMaxSeconds).coerceAtLeast(0))

        swSoundEnabled.isChecked    = f.soundEnabled
        swSpeedMode.isChecked       = f.speedMode
        swSlowMode.isChecked        = f.slowMode
        rowSlowRange.visibility     = if (f.slowMode) View.VISIBLE else View.GONE
        swSlowRandomizeOrder.isChecked  = f.slowRandomizeOrder
        rowSlowRandomizeOrder.visibility = if (f.slowMode) View.VISIBLE else View.GONE
        swSlowRandomizeTabs.isChecked  = f.slowRandomizeTabs
        rowSlowRandomizeTabs.visibility = if (f.slowMode) View.VISIBLE else View.GONE
        swSpeedMode.setOnCheckedChangeListener { _, checked -> if (checked) swSlowMode.isChecked = false }
        swSlowMode.setOnCheckedChangeListener { _, checked ->
            if (checked) swSpeedMode.isChecked = false
            rowSlowRange.visibility = if (checked) View.VISIBLE else View.GONE
            rowSlowRandomizeOrder.visibility = if (checked) View.VISIBLE else View.GONE
            rowSlowRandomizeTabs.visibility = if (checked) View.VISIBLE else View.GONE
        }
        swScreenLock.isChecked      = f.screenLock
        swScreenDim.isChecked       = f.screenDim
        swTelegramEnabled.isChecked = f.telegramEnabled
        etMinPayout.setText("%.2f".format(f.minPayout))
        etMaxMiles.setText("%.1f".format(f.maxMiles))

        // ── Scan Schedule (merged Scan Days + Pickup Time Windows) ─────────────
        cbSelectAllDays = findViewById(R.id.cb_select_all_days)
        fun refreshSelectAll() {
            cbSelectAllDays.isChecked = scanDayRowRefs.values.all { it.cbEnabled.isChecked }
        }
        buildScanScheduleRows(findViewById(R.id.ll_scan_schedule_days), f.scanSchedule, onDayToggled = { refreshSelectAll() })
        refreshSelectAll()
        cbSelectAllDays.setOnClickListener {
            val checkAll = cbSelectAllDays.isChecked
            scanDayRowRefs.values.forEach { it.cbEnabled.isChecked = checkAll }
        }

        etAllowedZones.setText(f.allowedZones)
        etBlockedZones.setText(f.blockedZones)
        etTelegramBotToken.setText(f.telegramBotToken)
        etTelegramChatId.setText(f.telegramNotifyChatId)

        // Show current Telegram connection status
        updateTelegramStatus(tvTelegramStatus, f)

        // Detect — reads the chat id of whoever last messaged the user's bot
        val btnDetect = findViewById<Button>(R.id.btn_telegram_detect)
        btnDetect.setOnClickListener {
            val token = etTelegramBotToken.text.toString().trim()
            if (token.isBlank()) {
                tvTelegramStatus.text = "Paste your bot token first"
                tvTelegramStatus.setTextColor(0xFFB71C1C.toInt())
                return@setOnClickListener
            }
            btnDetect.isEnabled = false
            tvTelegramStatus.text = "Looking for your message…"
            tvTelegramStatus.setTextColor(0xFF888888.toInt())
            TelegramHelper.detectChatId(token) { chatId ->
                runOnUiThread {
                    btnDetect.isEnabled = true
                    if (chatId != null) {
                        etTelegramChatId.setText(chatId)
                        saveSettings()
                        updateTelegramStatus(tvTelegramStatus, FilterSettings.load(this))
                    } else {
                        tvTelegramStatus.text = "No messages found — send your bot a message, then tap Detect again"
                        tvTelegramStatus.setTextColor(0xFFB71C1C.toInt())
                    }
                }
            }
        }

        // Test — sends a message to verify everything works
        findViewById<Button>(R.id.btn_telegram_test).setOnClickListener {
            saveSettings()
            tvTelegramStatus.text = "Sending test…"
            TelegramHelper.sendTest(etTelegramBotToken.text.toString().trim(), etTelegramChatId.text.toString().trim()) { ok, msg ->
                runOnUiThread {
                    tvTelegramStatus.text = if (ok) "✅ $msg" else "❌ $msg"
                    tvTelegramStatus.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt())
                }
            }
        }

        // ── Active hours ─────────────────────────────────────────────
        swActiveHours      = findViewById(R.id.sw_active_hours)
        swPerDaySchedule   = findViewById(R.id.sw_per_day_schedule)
        llPerDaySchedule   = findViewById(R.id.ll_per_day_schedule)
        val tvDefaultWindowLabel = findViewById<TextView>(R.id.tv_default_window_label)

        swActiveHours.isChecked    = f.activeHoursEnabled
        swPerDaySchedule.isChecked = f.perDayScheduleEnabled
        defaultWindowsUi = WindowListUi(findViewById(R.id.ll_active_default_windows), f.activeWindows, minRows = 1)

        buildPerDayRows(llPerDaySchedule, f.perDaySchedule, { defaultWindowsUi.toTimeWindows() }, dayRowRefs)

        fun applyActiveHours(on: Boolean) {
            val alpha = if (on) 1.0f else 0.38f
            tvDefaultWindowLabel.alpha = alpha
            defaultWindowsUi.setEnabled(on)
            swPerDaySchedule.isEnabled = on
            swPerDaySchedule.alpha    = alpha
            llPerDaySchedule.alpha    = alpha
            dayRowRefs.values.forEach { refs ->
                refs.swDefault.isEnabled = on
                refs.windowsUi.setEnabled(on && !refs.swDefault.isChecked)
            }
        }
        fun applyPerDay(on: Boolean) {
            llPerDaySchedule.visibility = if (on && swActiveHours.isChecked) View.VISIBLE else View.GONE
        }
        swActiveHours.setOnCheckedChangeListener    { _, on -> applyActiveHours(on); applyPerDay(swPerDaySchedule.isChecked) }
        swPerDaySchedule.setOnCheckedChangeListener { _, on -> applyPerDay(on) }

        applyActiveHours(f.activeHoursEnabled)
        applyPerDay(f.perDayScheduleEnabled)

        // ─────────────────────────────────────────────────────────────

        findViewById<TextView>(R.id.tv_version).text =
            "NovaBot v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // Save
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            saveAndExit()
        }

        // ── Tab bar ──────────────────────────────────────────────────
        btnTabHome         = findViewById(R.id.btn_tab_home)
        btnTabAccount      = findViewById(R.id.btn_tab_account)
        btnTabBot          = findViewById(R.id.btn_tab_bot)
        btnTabFilters      = findViewById(R.id.btn_tab_filters)
        contentTabHome     = findViewById(R.id.content_tab_home)
        contentTabAccount  = findViewById(R.id.content_tab_account)
        contentTabBot      = findViewById(R.id.content_tab_bot)
        contentTabFilters  = findViewById(R.id.content_tab_filters)

        btnTabHome.setOnClickListener     { selectTab(0) }
        btnTabAccount.setOnClickListener  { selectTab(1) }
        btnTabBot.setOnClickListener      { selectTab(2) }
        btnTabFilters.setOnClickListener  { selectTab(3) }
        selectTab(0)
    }

    // ── Tab toggle ───────────────────────────────────────────────────────────

    private fun selectTab(tab: Int) {
        contentTabHome.visibility     = if (tab == 0) View.VISIBLE else View.GONE
        contentTabAccount.visibility  = if (tab == 1) View.VISIBLE else View.GONE
        contentTabBot.visibility      = if (tab == 2) View.VISIBLE else View.GONE
        contentTabFilters.visibility  = if (tab == 3) View.VISIBLE else View.GONE

        val selectedBg   = ContextCompat.getColor(this, R.color.text_section)
        val unselectedBg = ContextCompat.getColor(this, R.color.bg_screen)
        val hintColor    = ContextCompat.getColor(this, R.color.text_hint)

        listOf(btnTabHome to 0, btnTabAccount to 1, btnTabBot to 2, btnTabFilters to 3).forEach { (btn, i) ->
            val selected = i == tab
            btn.backgroundTintList = ColorStateList.valueOf(if (selected) selectedBg else unselectedBg)
            btn.setTextColor(if (selected) Color.WHITE else hintColor)
        }
    }

    override fun onResume() {
        super.onResume()
        loadDashboardHistory()
    }

    private fun loadDashboardHistory() {
        ClaimHistoryView.populate(this, containerDashboardHistory, ClaimHistoryStore.list(this))
        swipeRefresh.isRefreshing = false
    }

    override fun onPause() {
        super.onPause()
        if (!isSwitchingProfile) saveSettings()
    }

    override fun onSupportNavigateUp(): Boolean { saveAndExit(); return true }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { saveAndExit(); super.onBackPressed() }

    private fun saveAndExit() {
        saveSettings()
        Toast.makeText(this, "Settings saved ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateTelegramStatus(tv: TextView, f: FilterSettings) {
        if (f.telegramBotToken.isNotBlank() && f.telegramNotifyChatId.isNotBlank()) {
            tv.text = "✅ Set up (chat ID: ${f.telegramNotifyChatId})"
            tv.setTextColor(0xFF2E7D32.toInt())
        } else {
            tv.text = "Not set up — follow the steps below"
            tv.setTextColor(0xFF888888.toInt())
        }
    }

    private fun saveSettings() {
        val slowMinSelected = FilterSettings.SLOW_INTERVAL_OPTIONS.getOrElse(spinnerSlowMin.selectedItemPosition) { 30 }
        val slowMaxSelected = FilterSettings.SLOW_INTERVAL_OPTIONS.getOrElse(spinnerSlowMax.selectedItemPosition) { 180 }
        FilterSettings(
            minPayout       = etMinPayout.text.toString().toDoubleOrNull() ?: 30.0,
            maxMiles        = etMaxMiles.text.toString().toDoubleOrNull() ?: 20.0,
            scanSchedule    = FilterSettings.ALL_DAYS.associateWith { day ->
                val refs = scanDayRowRefs[day] ?: return@associateWith FilterSettings.DaySchedule()
                FilterSettings.DaySchedule(
                    enabled = refs.cbEnabled.isChecked,
                    windows = refs.windowsUi.toTimeWindows()
                )
            },
            allowedZones    = etAllowedZones.text.toString().trim(),
            blockedZones    = etBlockedZones.text.toString().trim(),
            telegramEnabled      = swTelegramEnabled.isChecked,
            telegramBotToken     = etTelegramBotToken.text.toString().trim(),
            telegramNotifyChatId = etTelegramChatId.text.toString().trim(),
            offerCooldownMinutes = FilterSettings.COOLDOWN_OPTIONS.getOrElse(spinnerCooldown.selectedItemPosition) { 15 },
            soundEnabled         = swSoundEnabled.isChecked,
            speedMode            = swSpeedMode.isChecked,
            slowMode             = swSlowMode.isChecked,
            slowMinSeconds       = minOf(slowMinSelected, slowMaxSelected),
            slowMaxSeconds       = maxOf(slowMinSelected, slowMaxSelected),
            slowRandomizeOrder   = swSlowRandomizeOrder.isChecked,
            slowRandomizeTabs    = swSlowRandomizeTabs.isChecked,
            screenLock           = swScreenLock.isChecked,
            screenDim            = swScreenDim.isChecked,
            activeHoursEnabled    = swActiveHours.isChecked,
            activeWindows         = defaultWindowsUi.toTimeWindows(),
            perDayScheduleEnabled = swPerDaySchedule.isChecked,
            perDaySchedule        = FilterSettings.ALL_DAYS.associateWith { day ->
                val refs = dayRowRefs[day] ?: return@associateWith FilterSettings.DayActiveHours()
                FilterSettings.DayActiveHours(
                    useDefault = refs.swDefault.isChecked,
                    windows    = refs.windowsUi.toTimeWindows()
                )
            }
        ).save(this)
        DasherAccessibilityService.instance?.clearOfferCache()
    }

    /**
     * Renders one header row + nested [WindowListUi] per day of week into [container].
     * Used for the Active Hours per-day overrides — [saved] is the source data,
     * [defaultWindows] supplies the values to seed a day with the first time it's
     * switched off "Default", and [refsOut] receives the live UI refs so saveSettings()
     * can read back each day's final state.
     */
    private fun buildPerDayRows(
        container: LinearLayout,
        saved: Map<String, FilterSettings.DayActiveHours>,
        defaultWindows: () -> List<FilterSettings.TimeWindow>,
        refsOut: MutableMap<String, DayRowRefs>
    ) {
        val dp = resources.displayMetrics.density
        val primaryText = ContextCompat.getColor(this, R.color.text_primary)
        val hintColor = ContextCompat.getColor(this, R.color.text_hint)
        val dividerColor = ContextCompat.getColor(this, R.color.divider_line)

        val accentText = ContextCompat.getColor(this, R.color.text_section)

        FilterSettings.ALL_DAYS.forEachIndexed { i, day ->
            val daySaved = saved[day] ?: FilterSettings.DayActiveHours()

            // Collapsed header: day label + time-range summary + expand/collapse chevron.
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                isClickable = true; isFocusable = true
            }
            val tvDay = TextView(this).apply {
                text = day.take(3)
                textSize = 13f
                setTextColor(primaryText)
                layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val tvSummary = TextView(this).apply {
                textSize = 12f
                setTextColor(accentText)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvChevron = TextView(this).apply {
                text = "▾"; textSize = 13f; setTextColor(hintColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins((8 * dp).toInt(), 0, 0, 0)
                }
            }
            header.addView(tvDay); header.addView(tvSummary); header.addView(tvChevron)
            container.addView(header)

            // Expanded panel — "Use default" switch + (if off) this day's custom windows.
            val expandedContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            container.addView(expandedContainer)

            val defaultRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((52 * dp).toInt(), 4, (16 * dp).toInt(), 4)
            }
            val tvUseDefault = TextView(this).apply {
                text = "Use default schedule"; textSize = 13f; setTextColor(primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val swDef = Switch(this).apply {
                isChecked = daySaved.useDefault
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            defaultRow.addView(tvUseDefault); defaultRow.addView(swDef)
            expandedContainer.addView(defaultRow)

            // Custom windows for this day — hidden while "Default" is on.
            val windowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((52 * dp).toInt(), 0, (12 * dp).toInt(), (8 * dp).toInt())
                visibility = if (daySaved.useDefault) View.GONE else View.VISIBLE
            }
            expandedContainer.addView(windowsContainer)

            // lateinit so the onChange callback below can close over it before it's assigned —
            // the callback only runs later, on user interaction, by which point it's set.
            lateinit var windowsUi: WindowListUi
            fun currentSummary() = windowsSummaryText(if (swDef.isChecked) defaultWindows() else windowsUi.toTimeWindows())
            windowsUi = WindowListUi(windowsContainer, daySaved.windows, minRows = 0) {
                tvSummary.text = currentSummary()
            }
            refsOut[day] = DayRowRefs(swDef, windowsContainer, windowsUi)
            tvSummary.text = currentSummary()

            var expanded = false
            fun setExpanded(on: Boolean) {
                expanded = on
                expandedContainer.visibility = if (on) View.VISIBLE else View.GONE
                tvChevron.text = if (on) "▴" else "▾"
            }
            header.setOnClickListener { setExpanded(!expanded) }

            swDef.setOnCheckedChangeListener { _, on ->
                windowsContainer.visibility = if (on) View.GONE else View.VISIBLE
                // First time this day is customized, start from the current default windows
                // instead of an empty list — an explicit blackout (saved empty) is untouched.
                if (!on) windowsUi.seedIfEmpty(defaultWindows())
                tvSummary.text = currentSummary()
            }

            if (i < FilterSettings.ALL_DAYS.lastIndex) {
                container.addView(View(this).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                        it.marginStart = (16 * dp).toInt()
                    }
                })
            }
        }
    }

    /**
     * Renders one header row + nested [WindowListUi] per day of week into [container],
     * for the merged Scan Days + Pickup Time Windows section. Unlike [buildPerDayRows],
     * there's no "use default" concept — every day directly owns its enabled flag and
     * its own window list. [onDayToggled] fires whenever a day's checkbox changes, so
     * the caller can keep a "Select All" checkbox in sync.
     */
    private fun buildScanScheduleRows(
        container: LinearLayout,
        saved: Map<String, FilterSettings.DaySchedule>,
        onDayToggled: () -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val primaryText = ContextCompat.getColor(this, R.color.text_primary)
        val hintColor = ContextCompat.getColor(this, R.color.text_hint)
        val dividerColor = ContextCompat.getColor(this, R.color.divider_line)
        val accentText = ContextCompat.getColor(this, R.color.text_section)

        FilterSettings.ALL_DAYS.forEachIndexed { i, day ->
            val daySaved = saved[day] ?: FilterSettings.DaySchedule()

            // Collapsed header: enabled checkbox + day label + window summary + chevron.
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                isClickable = true; isFocusable = true
            }
            val cbEnabled = CheckBox(this).apply {
                isChecked = daySaved.enabled
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins(0, 0, (12 * dp).toInt(), 0)
                }
            }
            val tvDay = TextView(this).apply {
                text = day.take(3)
                textSize = 14f
                setTextColor(primaryText)
                layoutParams = LinearLayout.LayoutParams((42 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val tvSummary = TextView(this).apply {
                textSize = 13f
                setTextColor(accentText)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginStart = (8 * dp).toInt()
                }
            }
            val tvChevron = TextView(this).apply {
                text = "▾"; textSize = 13f; setTextColor(hintColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins((10 * dp).toInt(), 0, 0, 0)
                }
            }
            header.addView(cbEnabled); header.addView(tvDay); header.addView(tvSummary); header.addView(tvChevron)
            container.addView(header)

            // Expanded panel — this day's windows, editable regardless of enabled state.
            val windowsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((50 * dp).toInt(), 0, (12 * dp).toInt(), (14 * dp).toInt())
                visibility = View.GONE
            }
            container.addView(windowsContainer)

            // lateinit so the onChange callback below can close over it before it's assigned —
            // the callback only runs later, on user interaction, by which point it's set.
            lateinit var windowsUi: WindowListUi
            fun refreshRowAppearance() {
                val on = cbEnabled.isChecked
                tvDay.alpha = if (on) 1.0f else 0.45f
                tvSummary.alpha = if (on) 1.0f else 0.45f
                tvSummary.text = windowsSummaryText(windowsUi.toTimeWindows())
            }
            windowsUi = WindowListUi(windowsContainer, daySaved.windows, minRows = 0) { refreshRowAppearance() }
            scanDayRowRefs[day] = ScanDayRowRefs(cbEnabled, windowsContainer, windowsUi)
            refreshRowAppearance()

            var expanded = false
            fun setExpanded(on: Boolean) {
                expanded = on
                windowsContainer.visibility = if (on) View.VISIBLE else View.GONE
                tvChevron.text = if (on) "▴" else "▾"
            }
            header.setOnClickListener { setExpanded(!expanded) }

            cbEnabled.setOnCheckedChangeListener { _, _ ->
                refreshRowAppearance()
                onDayToggled()
            }

            if (i < FilterSettings.ALL_DAYS.lastIndex) {
                container.addView(View(this).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                        it.marginStart = (16 * dp).toInt()
                    }
                })
            }
        }
    }

    private fun windowsSummaryText(windows: List<FilterSettings.TimeWindow>): String =
        if (windows.isEmpty()) "Any time" else windows.joinToString(", ") { it.label() }

    private fun formatTime(hour: Int, minute: Int): String = FilterSettings.TimeWindow.fmt(hour, minute)
}
