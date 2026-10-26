package com.coralreef

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

// First-run setup wizard — one full-screen step at a time (Welcome, Accessibility, Overlay,
// Notifications, Telegram, Done). Telegram is purely optional and skippable (auto-skipped if
// already configured, and always re-doable later from SettingsActivity). Ends by handing off to
// FilterSetupWizardActivity, then MainActivity (which routes straight to Settings once
// everything is done).
class OnboardingWizardActivity : AppCompatActivity() {

    private val TOTAL_STEPS = 5

    private lateinit var tvStepLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvBack: TextView

    private lateinit var stepWelcome: LinearLayout
    private lateinit var stepAccessibility: LinearLayout
    private lateinit var stepOverlay: LinearLayout
    private lateinit var stepNotif: LinearLayout
    private lateinit var stepTelegram: LinearLayout
    private lateinit var stepDone: LinearLayout

    // Welcome and Done each get their own flexible FrameLayout (so an animation can fill the
    // whole space behind/over the centered content) instead of the simple spacer-pair pattern
    // the middle steps use — see activity_onboarding.xml.
    private lateinit var welcomeContainer: View
    private lateinit var doneContainer: View
    private lateinit var lottieConfetti: com.airbnb.lottie.LottieAnimationView

    // Steps 1..4 (Accessibility, Overlay, Notifications, Telegram) are
    // centered via a matching pair of flexible spacer Views (weight=1 above/below) — index i
    // corresponds to step (i + 1).
    private lateinit var spacerTops: List<View>
    private lateinit var spacerBottoms: List<View>

    private lateinit var btnWelcomeStart: Button

    private lateinit var btnObAccessibility: Button
    private lateinit var btnObOverlay: Button
    private lateinit var btnObNotif: Button

    private lateinit var etObTelegramToken: EditText
    private lateinit var etObTelegramChatId: EditText
    private lateinit var btnObTelegramDetect: Button
    private lateinit var btnObTelegramConnect: Button
    private lateinit var tvObTelegramStatus: TextView
    private lateinit var tvObTelegramSkip: TextView

    private lateinit var tvObDoneSub: TextView
    private lateinit var btnObFinish: Button

    private var currentStep = 0
    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { advanceIfOnStep(3) { isNotifPermissionGranted() } }

    private val filterWizardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { finishWizard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_onboarding)
        supportActionBar?.hide()

        tvStepLabel = findViewById(R.id.tv_step_label)
        progressBar = findViewById(R.id.progress_onboarding)
        tvBack = findViewById(R.id.tv_ob_back)

        stepWelcome = findViewById(R.id.step_welcome)
        stepAccessibility = findViewById(R.id.step_accessibility)
        stepOverlay = findViewById(R.id.step_overlay)
        stepNotif = findViewById(R.id.step_notif)
        stepTelegram = findViewById(R.id.step_telegram)
        stepDone = findViewById(R.id.step_done)
        welcomeContainer = findViewById(R.id.welcome_container)
        doneContainer = findViewById(R.id.done_container)
        lottieConfetti = findViewById(R.id.lottie_confetti)

        spacerTops = listOf(
            findViewById(R.id.accessibility_spacer_top),
            findViewById(R.id.overlay_spacer_top),
            findViewById(R.id.notif_spacer_top),
            findViewById(R.id.telegram_spacer_top)
        )
        spacerBottoms = listOf(
            findViewById(R.id.accessibility_spacer_bottom),
            findViewById(R.id.overlay_spacer_bottom),
            findViewById(R.id.notif_spacer_bottom),
            findViewById(R.id.telegram_spacer_bottom)
        )

        btnWelcomeStart = findViewById(R.id.btn_welcome_start)

        btnObAccessibility = findViewById(R.id.btn_ob_accessibility)
        btnObOverlay = findViewById(R.id.btn_ob_overlay)
        btnObNotif = findViewById(R.id.btn_ob_notif)

        etObTelegramToken = findViewById(R.id.et_ob_telegram_token)
        etObTelegramChatId = findViewById(R.id.et_ob_telegram_chat_id)
        btnObTelegramDetect = findViewById(R.id.btn_ob_telegram_detect)
        btnObTelegramConnect = findViewById(R.id.btn_ob_telegram_connect)
        tvObTelegramStatus = findViewById(R.id.tv_ob_telegram_status)
        tvObTelegramSkip = findViewById(R.id.tv_ob_telegram_skip)

        tvObDoneSub = findViewById(R.id.tv_ob_done_sub)
        btnObFinish = findViewById(R.id.btn_ob_finish)

        btnWelcomeStart.setOnClickListener { goToStep(1) }
        btnObAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnObOverlay.setOnClickListener { openOverlaySettings() }
        btnObNotif.setOnClickListener { requestNotifPermission() }
        btnObTelegramDetect.setOnClickListener { detectTelegramChatId() }
        btnObTelegramConnect.setOnClickListener { testAndSaveTelegram() }
        tvObTelegramSkip.setOnClickListener { goToStep(5) }
        btnObFinish.setOnClickListener { launchFilterWizard() }
        tvBack.setOnClickListener { if (currentStep > 0) goToStep(currentStep - 1) }

        // MainActivity routes here for ANY incomplete setup, not just fresh installs — e.g. a
        // permission got revoked after setup was finished. Skip Welcome in that case; each step
        // auto-skips itself if already done.
        if (FilterSettings.isFiltersConfigured(this)) goToStep(1) else goToStep(0)
    }

    // Accessibility/Overlay grants happen in an external Settings screen with no result
    // callback, so re-check on resume and auto-advance if the relevant step is still showing.
    override fun onResume() {
        super.onResume()
        advanceIfOnStep(1) { isServiceEnabled() }
        advanceIfOnStep(2) { Settings.canDrawOverlays(this) }
    }

    private fun advanceIfOnStep(step: Int, granted: () -> Boolean) {
        if (currentStep == step && granted()) goToStep(step + 1)
    }

    // ── Step navigation ─────────────────────────────────────────────────────

    private fun goToStep(step: Int) {
        currentStep = step
        listOf(stepWelcome,
            stepAccessibility, stepOverlay, stepNotif, stepTelegram, stepDone).forEach { it.visibility = View.GONE }
        welcomeContainer.visibility = View.GONE
        doneContainer.visibility = View.GONE
        spacerTops.forEach { it.visibility = View.GONE }
        spacerBottoms.forEach { it.visibility = View.GONE }
        when (step) {
            0 -> {
                welcomeContainer.visibility = View.VISIBLE
                stepWelcome.visibility = View.VISIBLE
                tvStepLabel.visibility = View.GONE
                progressBar.visibility = View.GONE
            }
            1 -> {
                if (isServiceEnabled()) { goToStep(2); return }
                spacerTops[0].visibility = View.VISIBLE; spacerBottoms[0].visibility = View.VISIBLE
                setProgress(1, "ACCESSIBILITY", stepAccessibility); stepAccessibility.visibility = View.VISIBLE
            }
            2 -> {
                if (Settings.canDrawOverlays(this)) { goToStep(3); return }
                spacerTops[1].visibility = View.VISIBLE; spacerBottoms[1].visibility = View.VISIBLE
                setProgress(2, "OVERLAY", stepOverlay); stepOverlay.visibility = View.VISIBLE
            }
            3 -> {
                if (isNotifPermissionGranted()) { goToStep(4); return }
                spacerTops[2].visibility = View.VISIBLE; spacerBottoms[2].visibility = View.VISIBLE
                setProgress(3, "NOTIFICATIONS", stepNotif); stepNotif.visibility = View.VISIBLE
            }
            4 -> {
                // Already configured (from a prior wizard pass or Settings) — skip straight to Done.
                if (FilterSettings.load(this).isTelegramConfigured()) { goToStep(5); return }
                spacerTops[3].visibility = View.VISIBLE; spacerBottoms[3].visibility = View.VISIBLE
                setProgress(4, "TELEGRAM", stepTelegram); stepTelegram.visibility = View.VISIBLE
                tvObTelegramStatus.text = ""
            }
            5 -> {
                // Filters already configured means this isn't a first-time completion — it's a
                // stale wizard instance resuming (e.g. a permission got auto-revoked and just got
                // re-granted) after everything was already finished before. Skip the celebration
                // and hand off straight to MainActivity, which will route to Settings.
                if (FilterSettings.isFiltersConfigured(this)) { finishWizard(); return }
                doneContainer.visibility = View.VISIBLE
                setProgress(5, "DONE", stepDone); stepDone.visibility = View.VISIBLE
                // Replay from the start every time this step is reached — autoPlay alone only
                // fires once on initial layout, not on repeat visits.
                lottieConfetti.cancelAnimation()
                lottieConfetti.progress = 0f
                lottieConfetti.playAnimation()
            }
        }
        tvBack.visibility = if (step in 1..4) View.VISIBLE else View.GONE
    }

    // The "STEP X OF N" label + progress bar are single shared views (not duplicated per step)
    // so they can carry a live-updating value — reparented into whichever step's container is
    // becoming active so they sit directly above that step's content instead of staying pinned
    // at the very top, disconnected from the (vertically centered) content below.
    private fun setProgress(step: Int, label: String, target: LinearLayout) {
        (tvStepLabel.parent as? ViewGroup)?.removeView(tvStepLabel)
        (progressBar.parent as? ViewGroup)?.removeView(progressBar)
        target.addView(progressBar, 0)
        target.addView(tvStepLabel, 0)
        tvStepLabel.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvStepLabel.text = "STEP $step OF $TOTAL_STEPS — $label"
        progressBar.progress = step
    }

    // ── Steps: on-device permissions ─────────────────────────────────────────

    private fun isServiceEnabled(): Boolean {
        val name = "$packageName/${DasherAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").any { it.equals(name, ignoreCase = true) }
    }

    private fun isNotifPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enable Accessibility Service")
                .setMessage(
                    "Android blocks sideloaded apps from enabling this directly. Do this once:\n\n" +
                    "1. Tap \"Accessibility Settings\" below — you'll likely see a message about " +
                    "\"Restricted Settings\". That's expected, not an error.\n\n" +
                    "2. Tap \"Open Apps List\", find NovaBot, tap ⋮ → \"Allow restricted settings\".\n\n" +
                    "3. Come back here and tap \"Enable\" again — it'll work this time."
                )
                .setPositiveButton("Accessibility Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNeutralButton("Open Apps List") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            goToStep(4)
        }
    }

    // ── Step: Telegram (optional) ───────────────────────────────────────────

    private fun detectTelegramChatId() {
        val token = etObTelegramToken.text.toString().trim()
        if (token.isBlank()) { tvObTelegramStatus.text = "Paste your bot token first"; return }
        btnObTelegramDetect.isEnabled = false
        tvObTelegramStatus.text = "Looking for your message…"
        TelegramHelper.detectChatId(token) { chatId -> runOnUiThread {
            btnObTelegramDetect.isEnabled = true
            if (chatId != null) {
                etObTelegramChatId.setText(chatId)
                tvObTelegramStatus.text = "Found chat ID $chatId — tap Test & Save"
            } else {
                tvObTelegramStatus.text = "No messages found — send your bot a message in Telegram, then tap Detect again"
            }
        }}
    }

    private fun testAndSaveTelegram() {
        val token = etObTelegramToken.text.toString().trim()
        val chatId = etObTelegramChatId.text.toString().trim()
        btnObTelegramConnect.isEnabled = false
        tvObTelegramStatus.text = "Sending test…"
        TelegramHelper.sendTest(token, chatId) { ok, msg -> runOnUiThread {
            btnObTelegramConnect.isEnabled = true
            if (ok) {
                FilterSettings.saveTelegramCredentials(this, token, chatId)
                Toast.makeText(this, "✅ Telegram connected!", Toast.LENGTH_LONG).show()
                goToStep(5)
            } else {
                tvObTelegramStatus.text = "❌ $msg"
            }
        }}
    }

    // ── Step: Done ───────────────────────────────────────────────────────────

    private fun launchFilterWizard() {
        filterWizardLauncher.launch(Intent(this, FilterSetupWizardActivity::class.java))
    }

    private fun finishWizard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
