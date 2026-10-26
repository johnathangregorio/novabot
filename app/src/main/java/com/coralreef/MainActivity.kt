package com.coralreef

import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

// Pure router, no visible UI of its own — OnboardingWizardActivity covers first-run setup
// (permissions/Telegram/filters) end to end and smart-skips whatever's already done, so ANY
// incomplete setup routes there. A fully set-up install routes straight to SettingsActivity,
// the app's real home.
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
    }

    override fun onResume() {
        super.onResume()
        // Trigger overlay creation if everything is ready
        if (Settings.canDrawOverlays(this) && DasherAccessibilityService.instance != null) {
            DasherAccessibilityService.instance?.createOverlay()
        }
        val setupComplete = isServiceEnabled() && Settings.canDrawOverlays(this) &&
            isNotifPermissionGranted() && FilterSettings.isFiltersConfigured(this)
        val target = if (setupComplete) SettingsActivity::class.java else OnboardingWizardActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }

    private fun isNotifPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isServiceEnabled(): Boolean {
        val name = "$packageName/${DasherAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(":").any { it.equals(name, ignoreCase = true) }
    }
}
