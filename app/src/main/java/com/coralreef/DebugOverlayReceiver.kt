package com.coralreef

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only: triggered via `adb shell am broadcast` to draw or clear a live overlay box
 *  on screen, for visually verifying that a computed tap target's bounds actually line up
 *  with the real, currently-rendered element underneath. */
class DebugOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("CateringBot", "DebugOverlayReceiver.onReceive action=${intent.action} instance=${DasherAccessibilityService.instance}")
        val service = DasherAccessibilityService.instance ?: return
        when (intent.action) {
            "com.coralreef.SHOW_DEBUG_BOX" -> {
                val x = intent.getIntExtra("x", 0)
                val y = intent.getIntExtra("y", 0)
                val w = intent.getIntExtra("w", 0)
                val h = intent.getIntExtra("h", 0)
                val label = intent.getStringExtra("label") ?: ""
                service.showDebugBox(x, y, w, h, label)
            }
            "com.coralreef.HIDE_DEBUG_BOX" -> service.hideDebugBox()
        }
    }
}
