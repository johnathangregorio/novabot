package com.coralreef

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DasherAccessibilityService : AccessibilityService() {

    // ─── Screen states ────────────────────────────────────────────────────────

    enum class ScreenState {
        HOME,             // Dasher home — "Dash now to check for offers"
        UPCOMING_ORDER,   // Claimed order starting soon — "Catering store pickup at X" + Dash + Unassign
        CLAIM_THANKS,     // "Thanks for claiming an offer!" confirmation — auto-tap Got it
        MENU,             // Side drawer open
        SCHEDULE,         // Schedule screen, Offers tab
        SCHEDULE_MY,      // Schedule screen, My Schedule tab
        DASHING,          // Currently on a live dash
        OFFER_DETAIL,     // Offer card open — "Claim and schedule" / "Guaranteed" visible
        POST_CLAIM,       // Post-claim order detail — "Preview directions" / "Preview route"
        ERROR,            // Connection/error screen — "Try again" / "No internet"
        EARNINGS,         // Earnings or account screen
        UNKNOWN,
        LOADING
    }

    companion object {
        const val DASHER_PACKAGE = "com.doordash.driverapp"
        val WATCHED_PACKAGES = setOf(DASHER_PACKAGE)
        const val TAG = "CateringBot"

        @Volatile var isRunning = false
        @Volatile var currentScreen = ScreenState.UNKNOWN
        @Volatile var pendingNavigate = false  // set by MainActivity; service acts on first Dasher event

        val recentLogs = ArrayDeque<String>()
        var onNewLog: ((String) -> Unit)? = null
        var instance: DasherAccessibilityService? = null
            private set

        data class Timings(
            val contentThrottle: Long,
            val dateTabWait:     Long,
            val detailWait:      Long,
            val loopShort:       Long,
            val loopMedium:      Long,
            val offerTabSwitch:  Long,
            val dismissVerify:   Long,
            val claimConfirm:    Long
        ) {
            companion object {
                // ±pct random wobble around a base wait, re-rolled every call. Used on every
                // timing EXCEPT claimConfirm — that one stays exact because claiming is a race
                // against other dashers for the same slot, not something we want to look human
                // at the cost of losing it.
                private fun jitter(baseMs: Long, pct: Double): Long {
                    val delta = (baseMs * pct).toLong()
                    return if (delta <= 0) baseMs else (baseMs - delta..baseMs + delta).random()
                }

                private const val LOOSE = 0.25  // per-step nav waits, offer/scan cadence
                private const val TIGHT = 0.10  // recovery-path waits — don't let jitter stall error recovery

                fun normal() = Timings(
                    contentThrottle = jitter(500,  LOOSE),
                    dateTabWait     = jitter(1500, LOOSE),
                    detailWait      = jitter(2000, LOOSE),
                    loopShort       = jitter(500,  LOOSE),
                    loopMedium      = jitter(1500, TIGHT),
                    offerTabSwitch  = jitter(800,  LOOSE),
                    dismissVerify   = jitter(1500, LOOSE),
                    claimConfirm    = 1000
                )

                fun speed() = Timings(
                    contentThrottle = jitter(150, LOOSE),
                    dateTabWait     = jitter(750, LOOSE),
                    detailWait      = jitter(1000, LOOSE),
                    loopShort       = jitter(200, LOOSE),
                    loopMedium      = jitter(750, TIGHT),
                    offerTabSwitch  = jitter(400, LOOSE),
                    dismissVerify   = jitter(800, LOOSE),
                    claimConfirm    = 500
                )

                // Same per-step navigation waits as normal() — only the gap between full
                // re-scan passes (loopShort) is randomized within the user's min/max range,
                // since "slow mode" means checking less often (and unpredictably), not
                // clicking through screens more sluggishly. loopMedium (mid-navigation
                // recovery waits, e.g. waiting out an active dash) keeps the tight recovery
                // jitter, not the full min/max spread, so error recovery doesn't occasionally
                // inherit a many-minute wait.
                fun slow(minMs: Long, maxMs: Long) = Timings(
                    contentThrottle = jitter(500,  LOOSE),
                    dateTabWait     = jitter(1500, LOOSE),
                    detailWait      = jitter(2000, LOOSE),
                    loopShort       = if (maxMs > minMs) (minMs..maxMs).random() else minMs,
                    loopMedium      = jitter(5000, TIGHT),
                    offerTabSwitch  = jitter(800,  LOOSE),
                    dismissVerify   = jitter(1500, LOOSE),
                    claimConfirm    = 1000
                )

                fun forFilters(f: FilterSettings) = when {
                    f.slowMode  -> slow(f.slowMinSeconds * 1000L, f.slowMaxSeconds * 1000L)
                    f.speedMode -> speed()
                    else        -> normal()
                }
            }
        }

        private fun addLog(line: String) {
            synchronized(recentLogs) {
                if (recentLogs.size >= 300) recentLogs.removeFirst()
                recentLogs.addLast(line)
            }
            onNewLog?.invoke(line)
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── Persistent log file (48h rolling) ───────────────────────────────────
    private val logIOThread = android.os.HandlerThread("CateringBotLogIO").also { it.start() }
    private val logIOHandler = android.os.Handler(logIOThread.looper)

    private fun appendBotLog(line: String) {
        logIOHandler.post {
            try {
                val file = java.io.File(filesDir, "bot.log")
                file.appendText(line + "\n")
                // Rotate at 20MB — keep last 75% to cover ~48h at normal scan rate
                if (file.length() > 20L * 1024 * 1024) {
                    val lines = file.readLines()
                    file.writeText(lines.takeLast(lines.size * 3 / 4).joinToString("\n") + "\n")
                }
            } catch (_: Exception) {}
        }
    }

    // ─── Overlay ──────────────────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var overlayRoot: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayPanel: View? = null
    private var overlayStatus: TextView? = null
    private var overlayLastAction: TextView? = null
    private var overlayCountdown: TextView? = null
    // Wall-clock deadline of the next loop cycle, and whether that cycle was scheduled under
    // Slow Mode — drives the pill countdown badge so the min/max delay is visible at a glance
    // without opening the panel, since that's the whole point of a "quieter" cadence.
    private var nextCycleAtMs: Long = 0L
    private var slowModeActive: Boolean = false

    // ─── Touch lock (pocket protection) ──────────────────────────────────────
    private var lockRoot: View? = null
    private var lockLongPressRunnable: Runnable? = null

    // ─── Debug overlay box (visual tap-target verification, adb-triggered only) ──────────
    private var debugBoxRoot: View? = null

    // ─── Scan state ───────────────────────────────────────────────────────────
    @Volatile var isScanning = false
    @Volatile var isLoopRunning = false
    private var lastOfferDates = listOf<DateScanResult>()
    private var consecutiveFailures = 0
    private val MAX_FAILURES = 5   // stop after 5 consecutive navigation/detection failures (~15s)
    private val MY_SCHEDULE_PEEK_PCT = 20  // % chance per cycle of the My Schedule humanizing detour (Slow Mode only)

    // LOADING (no root window yet) gets its own patience budget, separate from
    // consecutiveFailures — hamburger-nav against a blank loading screen is pointless and
    // shouldn't burn through MAX_FAILURES. DoorDash's own transition screens while a dash is
    // starting can outlast the normal 15s failure window, so this waits much longer before
    // giving up. 0 = not currently in a loading streak.
    private var loadingSinceMs = 0L
    private val MAX_LOADING_MS = 90_000L

    // How long the bot has been continuously seeing DASHING/UPCOMING_ORDER — logging only,
    // no timeout/alert. Being on a real dash for hours is completely normal.
    private var dashingSinceMs = 0L

    // Separate from dashingSinceMs — tracks whether the one-shot nav attempt has already
    // fired for the current dash. dashingSinceMs resets on ANY single non-DASHING reading,
    // which happens transiently mid-navigation (e.g. while the drawer is genuinely opening,
    // captureAndDetect can briefly see something else). If the one-shot re-arm were tied to
    // that, a real success (drawer opens from tap #1) gets immediately undone by tap #2 on
    // the very next cycle re-treating it as a fresh dash. This flag only resets when we
    // actually reach Schedule, or when the loop (re)starts — never on a transient blip.
    private var dashingNavAttempted = false

    // UNKNOWN gets its own patience budget too, separate from consecutiveFailures — a screen
    // we don't recognize at all (e.g. a real-time incoming-delivery-offer popup, ~30s) isn't
    // safe to guess navigation on, and shouldn't burn through MAX_FAILURES in ~15s either.
    // Longer than a single live-order popup, shorter than LOADING's budget since a genuinely
    // broken/stuck screen should still surface an alert reasonably promptly.
    private var unknownSinceMs = 0L
    private val MAX_UNKNOWN_MS = 60_000L

    // Offer cache: skip offers seen within the configured cooldown (payout+zone key)
    private var loopCycleCount = 0
    private var timings = Timings.normal()
    private val offerCache = HashMap<String, Long>()
    private var offerCooldownMs = 5 * 60_000L   // updated from settings at start of each cycle

    // Daily stats — reset at midnight and sent as a Telegram summary
    private var dailyClaimedCount = 0
    private var dailyClaimedTotal = 0.0
    private var dailySkippedCount = 0
    private var dailyBestOffer    = ""   // "$52.00 CA: Sacramento"

    // Notification banner overlay
    private var notifBannerRoot: View? = null
    private val notifDismissRunnable = Runnable { dismissBanner() }

    private enum class ToneType { CLAIMED, SKIP, ALERT }

    private fun offerCacheKey(payout: Double, zone: String) =
        "${"%.2f".format(payout)}_${zone.lowercase().trim()}"

    private fun isOfferCached(payout: Double, zone: String): Boolean {
        val t = offerCache[offerCacheKey(payout, zone)] ?: return false
        return (System.currentTimeMillis() - t) < offerCooldownMs
    }
    private fun cacheOffer(payout: Double, zone: String) {
        offerCache[offerCacheKey(payout, zone)] = System.currentTimeMillis()
    }

    // Fix 6 — Watchdog: auto-clear isScanning if stuck for 2 minutes
    private val watchdogRunnable = Runnable {
        if (isScanning) {
            log("⚠️ WATCHDOG: isScanning stuck 120s — force clearing")
            isScanning = false
        }
    }

    // Throttle content-change dumps — only re-dump after 500ms of quiet
    private var dumpPending = false
    private val dumpRunnable = Runnable {
        dumpPending = false
        captureAndDetect()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        isRunning = true
        instance = this
        log("Service connected v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — watching ${DASHER_PACKAGE}")
        NotificationHelper.createChannels(this)
        mainHandler.post { createOverlay() }
        scheduleMidnightSummary()
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        mainHandler.removeCallbacks(midnightRunnable)
        mainHandler.removeCallbacks(dumpRunnable)
        mainHandler.removeCallbacks(notifDismissRunnable)
        notifBannerRoot?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        notifBannerRoot = null
        lockLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
        lockRoot?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        lockRoot = null
        hideDebugBox()
        removeOverlay()
        log("Service stopped")
        super.onDestroy()
    }

    override fun onInterrupt() { log("Service interrupted") }

    // ─── Event handling ───────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WATCHED_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // New screen — dump immediately
                mainHandler.removeCallbacks(dumpRunnable)
                captureAndDetect()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Throttle: wait before dumping — shorter in speed mode
                if (!dumpPending) {
                    dumpPending = true
                    mainHandler.postDelayed(dumpRunnable, timings.contentThrottle)
                }
            }
        }
    }

    // ─── Screen detection ─────────────────────────────────────────────────────

    private fun captureAndDetect() {
        val root = rootInActiveWindow ?: run {
            currentScreen = ScreenState.LOADING
            return
        }

        val allText = StringBuilder()
        collectText(root, allText)
        val flat = allText.toString().lowercase()

        var detectionReason = ""
        val state = when {
            // Side menu open: has Home + Schedule + Account items on left side
            hasNodeWithText(root, "Home") && hasNodeWithText(root, "Account") &&
                    hasNodeWithText(root, "Ratings") -> {
                detectionReason = "Home+Account+Ratings nodes"
                ScreenState.MENU
            }

            // Schedule screen — most reliable: date row tabs only exist here
            hasDateTabRow(root) -> {
                val s = if (isMyScheduleTab(flat)) ScreenState.SCHEDULE_MY else ScreenState.SCHEDULE
                detectionReason = "dateTabRow found → ${s.name}"
                s
            }

            // Also catch Schedule by tab labels if date row isn't loaded yet
            hasNodeWithText(root, "My schedule") && hasNodeWithText(root, "Offers") -> {
                val s = if (isMyScheduleTab(flat)) ScreenState.SCHEDULE_MY else ScreenState.SCHEDULE
                detectionReason = "My schedule+Offers nodes → ${s.name}"
                s
            }

            // Claim confirmation — "Thanks for claiming an offer!" screen
            flat.contains("thanks for claiming") || flat.contains("here's what you need to know before you start") -> {
                detectionReason = if (flat.contains("thanks for claiming")) "thanks for claiming" else "here's what you need to know"
                ScreenState.CLAIM_THANKS
            }

            // Upcoming order — claimed order about to start. NEVER tap Dash or Unassign here.
            // Guard "start this dash before" with !claim-and-schedule: the offer DETAIL card also
            // contains "Start this Dash before X to keep this offer" but has "Claim and schedule".
            flat.contains("catering store pickup") ||
                    (flat.contains("start this dash before") && !flat.contains("claim and schedule") && !flat.contains("preview directions")) ||
                    flat.contains("keep the offer") ||
                    (flat.contains("unassign") && flat.contains("total pay")) -> {
                detectionReason = when {
                    flat.contains("catering store pickup") -> "catering store pickup"
                    flat.contains("start this dash before") -> "start this dash before (no claim btn)"
                    flat.contains("keep the offer") -> "keep the offer"
                    else -> "unassign+total pay"
                }
                ScreenState.UPCOMING_ORDER
            }

            // Dashing — "finding offers" used to be excluded here because it flashes briefly
            // after claiming a future catering offer, and DASHING used to mean a hard stop.
            // Now DASHING just triggers a graceful nav-away attempt that fails harmlessly if
            // there's nothing to act on, so a brief false match is no longer risky.
            flat.contains("dashing now") || flat.contains("return to dash") ||
                    flat.contains("current dash") || flat.contains("end dash") ||
                    flat.contains("finding offers") -> {
                detectionReason = when {
                    flat.contains("dashing now") -> "dashing now"
                    flat.contains("return to dash") -> "return to dash"
                    flat.contains("current dash") -> "current dash"
                    flat.contains("end dash") -> "end dash"
                    else -> "finding offers"
                }
                ScreenState.DASHING
            }

            // Home screen — multiple variants depending on Dasher state
            flat.contains("dash now to check for offers") ||
                    flat.contains("stay busy") ||
                    flat.contains("stay busy with") ||
                    hasNodeWithDesc(root, "dx.home_screen.schedule") &&
                    !hasDateTabRow(root) -> {
                detectionReason = when {
                    flat.contains("dash now to check for offers") -> "dash now to check for offers"
                    flat.contains("stay busy with") -> "stay busy with"
                    flat.contains("stay busy") -> "stay busy"
                    else -> "dx.home_screen.schedule desc (no date tabs)"
                }
                ScreenState.HOME
            }

            // Offer detail card — tapped from list, waiting to be claimed or dismissed
            flat.contains("claim and schedule") ||
                    (flat.contains("guaranteed") && flat.contains("pick up by")) -> {
                detectionReason = when {
                    flat.contains("claim and schedule") -> "claim and schedule"
                    else -> "guaranteed+pick up by"
                }
                ScreenState.OFFER_DETAIL
            }

            // Post-claim order detail — shown after Got it on some devices
            flat.contains("preview directions") || flat.contains("preview route") -> {
                detectionReason = if (flat.contains("preview directions")) "preview directions" else "preview route"
                ScreenState.POST_CLAIM
            }

            // Error / no-connection screen
            flat.contains("no internet") || flat.contains("connect to the internet") ||
                    flat.contains("something went wrong") ||
                    (flat.contains("try again") && !flat.contains("check again")) -> {
                detectionReason = when {
                    flat.contains("no internet") -> "no internet"
                    flat.contains("connect to the internet") -> "connect to the internet"
                    flat.contains("something went wrong") -> "something went wrong"
                    else -> "try again"
                }
                ScreenState.ERROR
            }

            // Earnings / account screen
            flat.contains("total earnings") || flat.contains("weekly earnings") ||
                    flat.contains("dasher rewards") ||
                    (flat.contains("earnings") && flat.contains("promotions")) -> {
                detectionReason = "earnings screen"
                ScreenState.EARNINGS
            }

            else -> {
                detectionReason = "no match"
                ScreenState.UNKNOWN
            }
        }

        if (state != currentScreen) {
            log("Screen → $state [$detectionReason]")
            if (state == ScreenState.UNKNOWN) {
                val sample = allText.toString().replace('\n', ' ').take(200)
                log("  [unknown screen text: $sample]")
            }
            currentScreen = state
            updateOverlayStatus()
        }

        // Gap 4 — connection error recovery (only on UNKNOWN state)
        if (state == ScreenState.UNKNOWN) {
            if (flat.contains("something went wrong")) {
                log("Gap4: 'Something went wrong' — pressing Back to dismiss")
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1000)
            } else if (flat.contains("no internet") || flat.contains("connect to the internet") ||
                       (flat.contains("try again") && !flat.contains("check again"))) {
                log("Gap4: connection error — auto-retrying in 1s")
                mainHandler.postDelayed({ tapByText("Try again") || tapByText("Retry") }, 1000)
            }
        }

        // Auto-dismiss "Thanks for claiming" — no manual intervention needed
        if (state == ScreenState.CLAIM_THANKS) {
            log("CLAIM_THANKS detected — auto-tapping Got it")
            mainHandler.postDelayed({
                if (tapByText("Got it")) {
                    log("Auto-dismissed claim confirmation ✓")
                    // Samsung: tapping Got it sometimes opens the side drawer. Press Back to close it.
                    mainHandler.postDelayed({
                        captureAndDetect()
                        if (currentScreen == ScreenState.UNKNOWN) {
                            log("Auto-dismiss: side drawer open after Got it — pressing Back")
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                    }, 1000)
                } else {
                    // "Got it" not found yet — try once more
                    mainHandler.postDelayed({ tapByText("Got it") }, 1500)
                }
            }, 500)
        }

        // Fire pending navigation as soon as Dasher is visible and settled
        if (pendingNavigate && state != ScreenState.LOADING && state != ScreenState.UNKNOWN) {
            pendingNavigate = false
            log("Auto-navigate: Dasher is ready (state=$state), navigating…")
            mainHandler.postDelayed({
                navigateToSchedule { success ->
                    log(if (success) "✅ Schedule/Offers reached!" else "❌ Navigation failed")
                }
            }, 600)
        }

        // Log tree to file for inspection (throttled — already called after quiet period)
        val sb = StringBuilder()
        sb.appendLine("=== UI TREE ${timeFormat.format(Date())} | State: $state ===")
        appendNode(root, 0, sb)
        writeToLogFile(sb.toString())

        @Suppress("DEPRECATION") root.recycle()
    }

    fun detectCurrentScreen(): ScreenState = currentScreen

    // ─── Navigation ───────────────────────────────────────────────────────────

    /**
     * From any screen, navigate to Schedule → Offers tab.
     * Calls [onDone] with true when arrived, false if navigation failed.
     */
    fun navigateToSchedule(onDone: (Boolean) -> Unit) {
        // Always refresh before deciding — currentScreen can be stale if no event fired recently
        captureAndDetect()
        log("navigateToSchedule: current = $currentScreen")
        when (currentScreen) {
            ScreenState.SCHEDULE -> {
                // Already on Schedule — make sure Offers tab is selected
                ensureOffersTab(onDone)
            }
            ScreenState.SCHEDULE_MY -> {
                // Wrong tab — tap Offers
                if (tapByText("Offers")) {
                    log("navigateToSchedule: tapped Offers tab")
                    mainHandler.postDelayed({ onDone(true) }, timings.offerTabSwitch)
                } else {
                    log("navigateToSchedule: Offers tab not found")
                    onDone(false)
                }
            }
            ScreenState.HOME -> {
                // Tap the bottom nav Schedule icon (desc='dx.home_screen.schedule')
                if (tapByDesc("dx.home_screen.schedule")) {
                    log("navigateToSchedule: tapped bottom-nav Schedule icon")
                    waitForScheduleScreen(onDone, attemptsLeft = 6)
                } else {
                    log("navigateToSchedule: bottom-nav icon not found — trying hamburger")
                    navigateViaHamburger(onDone)
                }
            }
            ScreenState.MENU -> {
                // Hamburger already open — tap Schedule
                tapMenuItemSchedule(onDone)
            }
            ScreenState.DASHING, ScreenState.UPCOMING_ORDER -> {
                // Verified against multiple real dash screens (not guessed): there is no
                // bottom nav or hamburger here. The gear icon that opens the "Current dash"
                // settings sheet is the real path — but its content-description varies by
                // screen: "Open Settings" on the live map, "Current dash" on the Dash Paused
                // screen (confirmed via the app's own ui_dump.log, not assumed). Try both.
                // Once the sheet is open: exact text "Go to home screen" → lands on Home,
                // where the normal HOME-branch nav (tap dx.home_screen.schedule) takes over.
                // All exact content-desc/text matches on the live tree, never coordinates,
                // and never touch "Pause orders" / "End now" / "Resume dash" / anything
                // dash-affecting on that sheet.
                if (tapByDesc("Open Settings") || tapByDesc("Current dash")) {
                    log("navigateToSchedule: tapped Settings gear from $currentScreen")
                    mainHandler.postDelayed({
                        if (tapByText("Go to home screen")) {
                            log("navigateToSchedule: tapped 'Go to home screen' — waiting for Home…")
                            waitForHomeThenSchedule(onDone, attemptsLeft = 6)
                        } else {
                            log("navigateToSchedule: 'Go to home screen' not found on settings sheet")
                            onDone(false)
                        }
                    }, 1000)
                } else if (tapByDesc("dx.home_screen.schedule")) {
                    log("navigateToSchedule: tapped bottom-nav Schedule icon from $currentScreen")
                    waitForScheduleScreen(onDone, attemptsLeft = 6)
                } else {
                    // Give the screen a beat to finish settling (e.g. right after the
                    // "Go to home screen" transition) before attempting the hamburger —
                    // firing immediately mid-transition may not be a fair test of it.
                    log("navigateToSchedule: no known path found from $currentScreen — settling before hamburger")
                    mainHandler.postDelayed({ navigateViaHamburger(onDone) }, 1500)
                }
            }
            else -> {
                // Unknown or loading — try hamburger as fallback
                navigateViaHamburger(onDone)
            }
        }
    }

    /** Poll every 600ms until the Schedule screen is detected, then ensure Offers tab is active. */
    private fun waitForScheduleScreen(onDone: (Boolean) -> Unit, attemptsLeft: Int) {
        mainHandler.postDelayed({
            captureAndDetect()  // Force live reading — don't rely on stale cached state
            when (currentScreen) {
                ScreenState.SCHEDULE, ScreenState.SCHEDULE_MY -> ensureOffersTab(onDone)
                else -> {
                    log("navigateToSchedule: waiting for Schedule screen ($attemptsLeft left, current=$currentScreen)")
                    if (attemptsLeft > 0) {
                        waitForScheduleScreen(onDone, attemptsLeft - 1)
                    } else {
                        log("navigateToSchedule: timed out waiting for Schedule screen")
                        onDone(false)
                    }
                }
            }
        }, 600)
    }

    /** Poll every 600ms until Home is detected after leaving the dash-settings sheet, then
     *  hand off to the normal navigateToSchedule HOME path (tap dx.home_screen.schedule). */
    /** Poll every 700ms (up to ~4.2s total) after the edge-swipe for the drawer to finish
     *  its settle/fling animation into MENU. Confirmed live that this genuinely takes
     *  longer than a click would — don't give up on a single fixed-delay check. */
    private fun waitForMenuAfterSwipe(onDone: (Boolean) -> Unit, attemptsLeft: Int) {
        mainHandler.postDelayed({
            captureAndDetect()
            log("navigateViaHamburger: state after swipe ($attemptsLeft left) = $currentScreen")
            when (currentScreen) {
                ScreenState.MENU -> tapMenuItemSchedule(onDone)
                ScreenState.SCHEDULE -> ensureOffersTab(onDone)
                else -> {
                    if (attemptsLeft > 0) {
                        waitForMenuAfterSwipe(onDone, attemptsLeft - 1)
                    } else {
                        log("navigateViaHamburger: menu still didn't open after swipe")
                        tapMenuItemSchedule(onDone)
                    }
                }
            }
        }, 700)
    }

    private fun waitForHomeThenSchedule(onDone: (Boolean) -> Unit, attemptsLeft: Int) {
        mainHandler.postDelayed({
            captureAndDetect()
            // While a dash is active, Home always carries a persistent "dashing now"/"current
            // dash" banner and classifies as DASHING, not a "pure" HOME — that state never
            // occurs mid-dash, so waiting for it exactly would hang until the timeout every
            // time. DASHING here means "we're on Home now, just still dashing" — proceed;
            // navigateToSchedule's DASHING branch tries the hamburger from here next.
            if (currentScreen == ScreenState.HOME || currentScreen == ScreenState.DASHING) {
                navigateToSchedule(onDone)
            } else if (attemptsLeft > 0) {
                log("navigateToSchedule: waiting for Home after 'Go to home screen' ($attemptsLeft left, current=$currentScreen)")
                waitForHomeThenSchedule(onDone, attemptsLeft - 1)
            } else {
                log("navigateToSchedule: timed out waiting for Home after 'Go to home screen'")
                onDone(false)
            }
        }, 600)
    }

    private fun ensureOffersTab(onDone: (Boolean) -> Unit) {
        if (currentScreen == ScreenState.SCHEDULE) {
            onDone(true)
            return
        }
        if (tapByText("Offers")) {
            log("ensureOffersTab: tapped Offers tab")
            mainHandler.postDelayed({ onDone(currentScreen == ScreenState.SCHEDULE) }, timings.offerTabSwitch)
        } else {
            onDone(currentScreen == ScreenState.SCHEDULE)
        }
    }

    /**
     * Like tapByDesc, but dispatches a real touch gesture at the node's bounds instead of
     * calling performAction(ACTION_CLICK). Verified live against the real Dasher app: the
     * "Side Menu" hamburger reports a successful ACTION_CLICK but doesn't actually open the
     * drawer (its gesture handling doesn't respond to the synthetic accessibility click) —
     * a real coordinate tap, matching what a human finger does, does open it.
     */
    private fun tapByDescReal(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByDesc(root, desc)
        @Suppress("DEPRECATION") root.recycle()
        if (node == null) {
            log("tapByDescReal: '$desc' — not found")
            return false
        }
        // Some views only wire up their real click/gesture handler once accessibility focus
        // has landed on them — try that first, it's a no-op if unnecessary.
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        val b = Rect()
        node.getBoundsInScreen(b)
        @Suppress("DEPRECATION") node.recycle()
        val cx = (b.left + b.right) / 2f
        val cy = (b.top + b.bottom) / 2f
        // Small deliberate jitter + a longer press — a real finger never lands perfectly
        // still, and some gesture detectors distinguish a zero-movement synthetic tap from
        // one with natural movement.
        log("tapByDescReal: '$desc' ✓ real tap w/ jitter @ ($cx, $cy)")
        tapWithJitter(cx, cy)
        return true
    }

    private fun findNodeByDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == desc) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByDesc(child, desc)
            if (result != null) {
                if (result !== child) { @Suppress("DEPRECATION") child.recycle() }
                return result
            }
            @Suppress("DEPRECATION") child.recycle()
        }
        return null
    }

    private fun navigateViaHamburger(onDone: (Boolean) -> Unit) {
        // Semantic click (ACTION_CLICK) first — confirmed live on an isolated Compose
        // ModalNavigationDrawer test screen that this DOES work, while gesture dispatch
        // doesn't. Logs the exact bounds of the node being clicked, since DoorDash's real
        // screen has sometimes exposed two different nodes both labeled "Side Menu" — if
        // this fails on the real app, the bounds log tells us whether it hit the real
        // hamburger position or a decoy.
        if (tapByDescSemanticLogged("Side Menu")) {
            log("navigateViaHamburger: semantic-clicked Side Menu, waiting for menu to open…")
            mainHandler.postDelayed({
                captureAndDetect()
                log("navigateViaHamburger: state after semantic click = $currentScreen")
                when (currentScreen) {
                    ScreenState.MENU -> tapMenuItemSchedule(onDone)
                    ScreenState.SCHEDULE -> ensureOffersTab(onDone)
                    else -> {
                        log("navigateViaHamburger: semantic click didn't open menu — trying gesture tap")
                        attemptGestureHamburger(onDone)
                    }
                }
            }, 1500)
        } else {
            log("navigateViaHamburger: Side Menu not found (semantic) — trying gesture tap")
            attemptGestureHamburger(onDone)
        }
    }

    /** Bounds-logged semantic click — ACTION_CLICK, not a gesture. Walks up to the nearest
     *  clickable ancestor if the matched node itself isn't clickable, same as tapByDesc, but
     *  logs the exact screen position of whatever actually got clicked. */
    private fun tapByDescSemanticLogged(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByDesc(root, desc)
        @Suppress("DEPRECATION") root.recycle()
        if (node == null) {
            log("tapByDescSemanticLogged: '$desc' — not found")
            return false
        }
        val b = Rect()
        node.getBoundsInScreen(b)
        if (node.isClickable) {
            log("tapByDescSemanticLogged: '$desc' @ $b (clickable) — ACTION_CLICK")
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            @Suppress("DEPRECATION") node.recycle()
            return result
        }
        var parent = node.parent
        @Suppress("DEPRECATION") node.recycle()
        while (parent != null) {
            if (parent.isClickable) {
                val pb = Rect(); parent.getBoundsInScreen(pb)
                log("tapByDescSemanticLogged: '$desc' @ $b not clickable — clickable parent @ $pb — ACTION_CLICK")
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                @Suppress("DEPRECATION") parent.recycle()
                return result
            }
            val gp = parent.parent
            @Suppress("DEPRECATION") parent.recycle()
            parent = gp
        }
        log("tapByDescSemanticLogged: '$desc' @ $b — no clickable node or ancestor found")
        return false
    }

    /** The gesture-dispatch attempt (tap, then edge-swipe fallback) — split out so it can be
     *  tried after semantic click, instead of before it. */
    private fun attemptGestureHamburger(onDone: (Boolean) -> Unit) {
        if (tapByDescReal("Side Menu")) {
            log("navigateViaHamburger: gesture-tapped Side Menu, waiting for menu to open…")
            mainHandler.postDelayed({
                captureAndDetect()
                log("navigateViaHamburger: state after gesture tap = $currentScreen")
                when (currentScreen) {
                    ScreenState.MENU -> tapMenuItemSchedule(onDone)
                    ScreenState.SCHEDULE -> ensureOffersTab(onDone)
                    else -> {
                        log("navigateViaHamburger: gesture tap didn't open menu — trying edge-swipe")
                        swipeOpenDrawer()
                        waitForMenuAfterSwipe(onDone, attemptsLeft = 15)
                    }
                }
            }, 1500)
        } else {
            log("navigateViaHamburger: Side Menu not found (gesture)")
            onDone(false)
        }
    }

    private fun tapMenuItemSchedule(onDone: (Boolean) -> Unit) {
        val root = rootInActiveWindow
        if (root != null) {
            val nodes = root.findAccessibilityNodeInfosByText("Schedule")
            val menuItem = nodes.firstOrNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.left < 500 && bounds.top > 300
            }
            if (menuItem != null) {
                menuItem.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ?: menuItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                log("navigateViaHamburger: tapped Schedule in menu")
                nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
                @Suppress("DEPRECATION") root.recycle()
                waitForScheduleScreen(onDone, attemptsLeft = 6)
                return
            }
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            @Suppress("DEPRECATION") root.recycle()
        }
        log("navigateViaHamburger: Schedule not found in menu")
        onDone(false)
    }

    // ─── Date scanning ────────────────────────────────────────────────────────

    data class DateScanResult(val label: String, val status: OfferListStatus)

    /**
     * Navigate to Schedule if needed, then tap every date tab and record
     * which ones have catering offers.  Progress strings are emitted via
     * [onProgress] on the main thread; the final list via [onComplete].
     */
    fun scanAllDates(
        onProgress: (String) -> Unit,
        onComplete: (List<DateScanResult>) -> Unit
    ) {
        if (isScanning) { onProgress("Already scanning…"); return }
        isScanning = true
        mainHandler.postDelayed(watchdogRunnable, 120_000) // Fix 6
        setOverlayLastAction("Navigating to Schedule…")

        val doScan = {
            val root = rootInActiveWindow
            if (root == null) {
                isScanning = false
                onProgress("❌ No window — open Dasher first")
                onComplete(emptyList())
            } else {
                val dates = mutableListOf<Pair<String, Rect>>()
                collectDateNodes(root, dates)
                @Suppress("DEPRECATION") root.recycle()

                if (dates.isEmpty()) {
                    isScanning = false
                    onProgress("❌ No date tabs found — go to Schedule/Offers screen")
                    onComplete(emptyList())
                } else {
                    log("Scanning ${dates.size} date tabs…")
                    onProgress("Scanning ${dates.size} dates…")
                    scanNextDate(dates, 0, mutableListOf(), onProgress, onComplete)
                }
            }
        }

        if (currentScreen == ScreenState.SCHEDULE) {
            doScan()
        } else {
            navigateToSchedule { success ->
                if (success) {
                    mainHandler.postDelayed({ doScan() }, 600)
                } else {
                    isScanning = false
                    onProgress("❌ Could not reach Schedule screen")
                    onComplete(emptyList())
                }
            }
        }
    }

    private fun scanNextDate(
        dates: List<Pair<String, Rect>>,
        index: Int,
        results: MutableList<DateScanResult>,
        onProgress: (String) -> Unit,
        onComplete: (List<DateScanResult>) -> Unit
    ) {
        if (index >= dates.size) {
            isScanning = false
            mainHandler.removeCallbacks(watchdogRunnable) // Fix 6
            val summary = buildScanSummary(results)
            log("Scan complete: $summary")
            setOverlayLastAction(summary)
            onProgress(summary)
            // Store dates that have offers and show Claim button if any found
            lastOfferDates = results.filter { it.status == OfferListStatus.HAS_OFFERS }
            onComplete(results)
            return
        }

        val (label, bounds) = dates[index]
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        val shortLabel = shortDateLabel(label)

        log("Tapping date: $label")
        onProgress("Checking $shortLabel…")
        setOverlayLastAction("Checking $shortLabel…")
        if (!tapByDesc(label)) tapAt(cx, cy)

        mainHandler.postDelayed({
            val status = readOffersStatus()
            val emoji = statusEmoji(status)
            log("  $shortLabel → $status")
            onProgress("$shortLabel: $emoji")

            results.add(DateScanResult(label, status))
            scanNextDate(dates, index + 1, results, onProgress, onComplete)
        }, timings.dateTabWait)
    }

    private fun collectDateNodes(node: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>) {
        val desc = node.contentDescription?.toString() ?: ""
        if (looksLikeDateDesc(desc) && out.none { it.first == desc }) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 20) out.add(desc to bounds)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDateNodes(child, out)
            @Suppress("DEPRECATION") child.recycle()
        }
    }

    private fun looksLikeDateDesc(desc: String): Boolean {
        if (desc.isBlank()) return false
        val days = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
        return desc.startsWith("Today") || days.any { desc.startsWith(it) }
    }

    private fun shortDateLabel(desc: String): String {
        // "Today, Wednesday 2026-05-20" → "Today"
        // "Thursday 2026-05-21"         → "Thu 5/21"
        if (desc.startsWith("Today")) return "Today"
        val parts = desc.split(" ")
        val day = parts.getOrNull(0)?.take(3) ?: desc.take(5)
        val date = parts.getOrNull(1)?.let { d ->
            val bits = d.split("-")
            if (bits.size == 3) "${bits[1].trimStart('0')}/${bits[2].trimStart('0')}" else d
        } ?: ""
        return "$day $date".trim()
    }

    private fun statusEmoji(status: OfferListStatus) = when (status) {
        OfferListStatus.HAS_OFFERS -> "✅ offers!"
        OfferListStatus.EMPTY      -> "— empty"
        OfferListStatus.SEE_NEXT   -> "⏭ see next"
        else                       -> "?"
    }

    private fun buildScanSummary(results: List<DateScanResult>): String {
        val hits = results.filter { it.status == OfferListStatus.HAS_OFFERS }
        return if (hits.isNotEmpty())
            "✅ Offers on: ${hits.joinToString { shortDateLabel(it.label) }}"
        else
            "No catering offers across ${results.size} dates"
    }

    // ─── Offer detail parsing & claim flow ───────────────────────────────────

    data class OfferDetails(
        val payout:      Double,
        val miles:       Double,
        val pickupTime:  String,
        val restaurant:  String,
        val zone:        String,
        val deliverTime: String = "",
        val itemCount:   Int    = 0
    ) {
        override fun toString() = buildString {
            append("\$${"%.2f".format(payout)}")
            if (restaurant.isNotEmpty()) append(" | $restaurant")
            if (itemCount > 0) append(" ($itemCount items)")
            append(" | ${miles}mi")
            if (pickupTime.isNotEmpty()) append(" | pickup $pickupTime")
            if (deliverTime.isNotEmpty()) append(" | deliver $deliverTime")
            if (zone.isNotEmpty()) append(" | $zone")
        }
    }

    enum class ClaimResult { SUCCESS, CONFLICT, UNAVAILABLE, NOT_FOUND, UNKNOWN }

    private fun offerBlock(
        payout: Double, miles: Double, pickupTime: String, zone: String, dateLabel: String,
        restaurant: String = "", deliverTime: String = "", itemCount: Int = 0,
        guaranteed: Boolean = false
    ) = buildString {
        if (restaurant.isNotEmpty()) {
            if (itemCount > 0) appendLine("🍽 $restaurant ($itemCount items)")
            else appendLine("🍽 $restaurant")
        }
        val amt = "\$${"%.2f".format(payout)}"
        appendLine("💵 ${if (guaranteed) "$amt Guaranteed" else amt}")
        if (dateLabel.isNotEmpty()) appendLine("📅 ${shortDateLabel(dateLabel)}")
        if (pickupTime.isNotEmpty()) appendLine("⏰ Pick up by $pickupTime")
        if (deliverTime.isNotEmpty()) appendLine("🏁 Deliver by $deliverTime")
        if (miles > 0) appendLine("📏 $miles mi")
        if (zone.isNotEmpty()) append("📍 $zone")
    }

    /**
     * Read the starting point / region label from the offer list screen
     * BEFORE tapping the offer card. e.g. "CA: Sacramento", "Folsom, CA"
     * This text sits above the offer payout on the list card.
     */
    fun readRegionFromListScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        @Suppress("DEPRECATION") root.recycle()
        val flat = sb.toString()
        // Match "XX: City" — DoorDash's standard region format
        return Regex("""([A-Z]{2}:\s*[A-Za-z][A-Za-z /'\-]+)""").find(flat)
            ?.groupValues?.get(1)?.trim()
            // Fallback: "City, ST" format
            ?: Regex("""([A-Z][a-z][A-Za-z ]+,\s*[A-Z]{2})""").find(flat)
                ?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * Read payout / miles / pickup time / restaurant from the currently visible
     * offer detail screen.  Returns null if the mandatory $X.XX Guaranteed line
     * isn't found (i.e. we're not on a detail screen).
     */
    fun readDetailScreen(): OfferDetails? {
        // Collect text from ALL windows — DoorDash may open the offer card as a dialog
        // in a separate window, making rootInActiveWindow return the wrong (empty) window.
        val sb = StringBuilder()
        try {
            windows?.forEach { w ->
                val root = w.root ?: return@forEach
                collectText(root, sb)
                @Suppress("DEPRECATION") root.recycle()
            }
        } catch (_: Exception) {
            val root = rootInActiveWindow ?: return null
            collectText(root, sb)
            @Suppress("DEPRECATION") root.recycle()
        }
        return parseDetailText(sb.toString())
    }

    // DoorDash sometimes runs the pickup-time label straight into the restaurant name with no
    // separator once the accessibility tree's text nodes get flattened into one string (e.g.
    // "AM Pickup TOGO'S Catering" instead of just "TOGO'S Catering") — the restaurant-name regexes
    // below have no way to know where that label ends, since both "AM"/"PM" and the restaurant
    // name itself start with a capital letter, so strip it defensively after the match.
    private fun stripPickupTimeNoise(name: String): String =
        name.replace(Regex("""^\s*(?:\d{1,2}:\d{2}\s*)?(?:AM|PM)\s+Pickup\s+""", RegexOption.IGNORE_CASE), "").trim()

    private fun parseDetailText(flat: String): OfferDetails? {
        // Find all dollar amounts ($X.XX or OCR artifact SX.XX) and take the largest —
        // the guaranteed payout is always the max (it includes tips + setup pay components).
        val payout = Regex("""(?:\$|S)(\d+\.\d+)""").findAll(flat)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .maxOrNull() ?: return null

        val miles = Regex("""([\d.]+)\s*mi(?!n)""").find(flat)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        val pickupTime = Regex(
            """Pick up by\s+(\d{1,2}:\d{2}(?:\s*[AP]M)?)""", RegexOption.IGNORE_CASE
        ).find(flat)?.groupValues?.get(1)?.trim() ?: ""

        val restaurant = stripPickupTimeNoise(
            Regex("""([A-Z][A-Za-z0-9 '&,.\-]+)\s*\(\d+\s*items?\)""").find(flat)
                ?.groupValues?.get(1)?.trim() ?: ""
        )

        val itemCount = Regex("""\((\d+)\s*items?\)""", RegexOption.IGNORE_CASE)
            .find(flat)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val zone = Regex("""([A-Z]{2}:\s*[A-Za-z][A-Za-z /'\-]+)""").find(flat)
            ?.groupValues?.get(1)?.trim()
            ?: Regex("""-\s*([A-Za-z][A-Za-z ,]+)\s*\(""").find(flat)
                ?.groupValues?.get(1)?.trim() ?: ""

        val deliverTime = Regex(
            """Deliver by\s+(\d{1,2}:\d{2}(?:\s*[AP]M)?)""", RegexOption.IGNORE_CASE
        ).find(flat)?.groupValues?.get(1)?.trim() ?: ""

        return OfferDetails(payout, miles, pickupTime, restaurant, zone, deliverTime, itemCount)
    }

    private fun extractBitmapFromResult(result: ScreenshotResult): Bitmap? = try {
        val buf = result.javaClass.getMethod("getHardwareBuffer").invoke(result)
        if (buf != null) {
            val cs = try { result.javaClass.getMethod("getColorSpace").invoke(result)
                as? android.graphics.ColorSpace } catch (_: Exception) { null }
            @Suppress("UNCHECKED_CAST")
            val hwBmp = Bitmap.wrapHardwareBuffer(buf as android.hardware.HardwareBuffer, cs)
            (buf as android.hardware.HardwareBuffer).close()
            hwBmp?.copy(Bitmap.Config.ARGB_8888, false).also { hwBmp?.recycle() }
        } else null
    } catch (_: Exception) {
        try { result.javaClass.getMethod("getHardwareBitmap").invoke(result) as? Bitmap }
        catch (_: Exception) { null }?.let { hw ->
            if (hw.config == Bitmap.Config.HARDWARE) hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } else hw
        }
    }

    private fun bitmapToJpeg(bmp: Bitmap): ByteArray? = try {
        java.io.ByteArrayOutputStream().also {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, it)
        }.toByteArray()
    } catch (_: Exception) { null }

    // Save a screenshot to disk (sent with SEND LOGS, not auto-transmitted).
    // name controls the filename: debug_offer_list.jpg, debug_offer_card.jpg, etc.
    @android.annotation.SuppressLint("NewApi")
    fun saveDebugScreenshot(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        overlayRoot?.visibility = View.INVISIBLE
        notifBannerRoot?.visibility = View.INVISIBLE
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = extractBitmapFromResult(result)
                    overlayRoot?.visibility = View.VISIBLE
                    notifBannerRoot?.visibility = View.VISIBLE
                    val jpegBytes = bmp?.let { bitmapToJpeg(it).also { _ -> bmp.recycle() } } ?: return
                    logIOHandler.post {
                        try { java.io.File(filesDir, "debug_${name}.jpg").writeBytes(jpegBytes) }
                        catch (_: Exception) {}
                    }
                }
                override fun onFailure(code: Int) {
                    overlayRoot?.visibility = View.VISIBLE
                    notifBannerRoot?.visibility = View.VISIBLE
                }
            })
        } catch (_: Exception) {
            overlayRoot?.visibility = View.VISIBLE
            notifBannerRoot?.visibility = View.VISIBLE
        }
    }

    private fun readDetailScreenViaOCR(attemptsLeft: Int = 1, onDone: (OfferDetails?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onDone(null); return }
        overlayRoot?.visibility = View.INVISIBLE
        notifBannerRoot?.visibility = View.INVISIBLE
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = extractBitmapFromResult(result)
                    if (bmp == null) {
                        log("OCR: bitmap extraction failed")
                        overlayRoot?.visibility = View.VISIBLE
                        notifBannerRoot?.visibility = View.VISIBLE
                        onDone(null); return
                    }
                    log("OCR: bitmap extracted (${bmp.width}x${bmp.height})")
                    val image = InputImage.fromBitmap(bmp, 0)
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .process(image)
                        .addOnSuccessListener { visionText ->
                            val flat = visionText.text
                            val parsed = parseDetailText(flat)
                            if (parsed == null && attemptsLeft > 1) {
                                // Card still loading — recycle bitmap, restore overlays, wait and retry
                                log("OCR: card not ready (attempt ${5 - attemptsLeft + 1}) — retrying in 1.5s")
                                bmp.recycle()
                                overlayRoot?.visibility = View.VISIBLE
                                notifBannerRoot?.visibility = View.VISIBLE
                                mainHandler.postDelayed({
                                    readDetailScreenViaOCR(attemptsLeft - 1, onDone)
                                }, 1500)
                            } else {
                                // Success or final attempt — save screenshot and return result
                                log("OCR: ${flat.replace('\n', ' ').take(200)}")
                                val jpegBytes = bitmapToJpeg(bmp)
                                bmp.recycle()
                                overlayRoot?.visibility = View.VISIBLE
                                notifBannerRoot?.visibility = View.VISIBLE
                                if (jpegBytes != null) logIOHandler.post {
                                    try { java.io.File(filesDir, "debug_offer_card.jpg").writeBytes(jpegBytes) }
                                    catch (_: Exception) {}
                                }
                                onDone(parsed)
                            }
                        }
                        .addOnFailureListener { e ->
                            bmp.recycle()
                            overlayRoot?.visibility = View.VISIBLE
                            notifBannerRoot?.visibility = View.VISIBLE
                            log("OCR recognition failed: ${e.message}")
                            onDone(null)
                        }
                }
                override fun onFailure(errorCode: Int) {
                    overlayRoot?.visibility = View.VISIBLE
                    notifBannerRoot?.visibility = View.VISIBLE
                    log("OCR screenshot failed: error $errorCode")
                    onDone(null)
                }
            })
        } catch (e: Exception) {
            overlayRoot?.visibility = View.VISIBLE
            notifBannerRoot?.visibility = View.VISIBLE
            log("OCR exception: ${e.message}")
            onDone(null)
        }
    }

    // Fix 2+3 — multi-offer support + full pre-filter from list screen
    data class ListOffer(val payout: Double, val x: Float, val y: Float, val zone: String, val label: String,
                         val pickupTime: String = "", val miles: Double = 0.0,
                         val restaurant: String = "", val itemCount: Int = 0)

    /**
     * Find ALL offer cards on the current Schedule/Offers screen.
     * Each card contains "$X.XX offer" text. Sorted highest payout first.
     * Pre-filters by min payout so we never open cards below the threshold.
     */
    private fun findOffersOnListScreen(minPayout: Double): List<ListOffer> {
        val root = rootInActiveWindow ?: return emptyList()
        val offers = mutableListOf<ListOffer>()
        log("findOffers: scanning for offer cards (minPayout=\$${"%.2f".format(minPayout)})")
        collectOfferNodes(root, offers)
        @Suppress("DEPRECATION") root.recycle()
        val filtered = offers
            .filter { it.payout >= minPayout }  // Fix 3: pre-filter payout
            .sortedByDescending { it.payout }    // Fix 2: highest first
        log("findOffers: ${offers.size} card(s) found, ${filtered.size} pass payout filter")
        return filtered
    }

    /**
     * Collapse all text+contentDescription in a subtree WITHOUT inserting spaces.
     * Dasher (Compose) splits "$45.00 offer" into individual character TextViews,
     * so collectText produces "$ 4 5 . 0 0 o f f e r" — this produces "$45.00offer".
     */
    private fun collectCollapsed(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it) }
        node.contentDescription?.let { sb.append(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCollapsed(child, sb)
            @Suppress("DEPRECATION") child.recycle()
        }
    }

    private fun collectOfferNodes(node: AccessibilityNodeInfo, out: MutableList<ListOffer>) {
        // Pre-collect all zone label positions so multi-zone screens assign correctly.
        // findZoneAboveNode used Regex.find() on all sibling text, which always returned
        // the first zone on screen — wrong for cards in a later zone group.
        val zonePositions = mutableListOf<Pair<String, Int>>() // (zone, screenY)
        collectZonePositions(node, zonePositions)
        collectOfferNodesInner(node, zonePositions, out)
    }

    /** Walk the full tree and record each "XX: City" zone label and its screen Y position. */
    private fun collectZonePositions(node: AccessibilityNodeInfo, out: MutableList<Pair<String, Int>>) {
        val text = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
        Regex("""([A-Z]{2}:\s*[A-Za-z][A-Za-z /'\-]+)""").find(text)?.let { m ->
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.width() > 0) out.add(m.groupValues[1].trim() to b.top)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectZonePositions(child, out)
            @Suppress("DEPRECATION") child.recycle()
        }
    }

    private fun collectOfferNodesInner(
        node: AccessibilityNodeInfo,
        zonePositions: List<Pair<String, Int>>,
        out: MutableList<ListOffer>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Match offer card containers: clickable, below tab row (y>650), card-sized
        if (node.isClickable && bounds.top > 650 && bounds.height() > 80 && bounds.width() > 400) {
            val sb = StringBuilder()
            collectCollapsed(node, sb)
            val collapsed = sb.toString()
            // Matches "$45.00offer" (collapsed) or "$45.00 offer" (spaced)
            val m = Regex("""\$(\d+\.?\d*)\s*offer""", RegexOption.IGNORE_CASE).find(collapsed)
            if (m != null) {
                val payout = m.groupValues[1].toDoubleOrNull() ?: 0.0
                if (payout > 0) {
                    val cx = (bounds.left + bounds.right) / 2f
                    val cy = (bounds.top + bounds.bottom) / 2f
                    // Closest zone label whose Y is at or above this card's top edge
                    val zone = zonePositions
                        .filter { (_, y) -> y <= bounds.top }
                        .maxByOrNull { (_, y) -> y }
                        ?.first
                        ?: readRegionFromListScreen()
                    // Also read spaced text for pickup time and miles (collectCollapsed destroys spaces)
                    val spaced = StringBuilder()
                    collectText(node, spaced)
                    val spacedText = spaced.toString()
                    val pickupTime = Regex("""Pick up by\s+(\d{1,2}:\d{2}(?:\s*[AP]M)?)""", RegexOption.IGNORE_CASE)
                        .find(spacedText)?.groupValues?.get(1)?.trim() ?: ""
                    val miles = Regex("""([\d.]+)\s*mi(?!n)""").find(spacedText)
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    // List screen format: "Chipotle - Sacramento • Catering" — extract name before " - City"
                    val restaurant = stripPickupTimeNoise(
                        Regex("""([A-Z][A-Za-z0-9 '&]+?)(?:\s*-\s*[A-Za-z][A-Za-z ,]+)?\s*[•·]\s*[Cc]atering""")
                            .find(spacedText)?.groupValues?.get(1)?.trim() ?: ""
                    )
                    log("  card: \$${"%.2f".format(payout)} @ (${cx.toInt()},${cy.toInt()}) zone='$zone' pickup='$pickupTime' ${miles}mi rest='$restaurant'")
                    out.add(ListOffer(payout, cx, cy, zone, collapsed, pickupTime, miles, restaurant))
                    return  // don't recurse into children of a matched card
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectOfferNodesInner(child, zonePositions, out)
            @Suppress("DEPRECATION") child.recycle()
        }
    }

    /** Tap a specific offer card by its screen coordinates */
    private fun tapOfferAtCoords(x: Float, y: Float) = tapAt(x, y)

    /**
     * Find and tap the first offer card by its position in the content area.
     * Used when text search fails (real Dasher Compose renders text as contentDescription).
     * Offer cards are large clickable containers below the tab row (y > 650, height > 100).
     */
    private fun tapOfferCardInContentArea(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findOfferCardNode(root)
        @Suppress("DEPRECATION") root.recycle()
        if (node != null) {
            val b = Rect()
            node.getBoundsInScreen(b)
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            log("Offer card found at ($cx, $cy) h=${b.height()}")
            tapAt(cx, cy)
            return true
        }
        return false
    }

    private fun findOfferCardNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val b = Rect()
        node.getBoundsInScreen(b)
        // Match: clickable, below tab row (y>650), large enough to be a card (h>80, w>400)
        if (node.isClickable && b.top > 650 && b.height() > 80 && b.width() > 400) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findOfferCardNode(child)
            if (result != null) { @Suppress("DEPRECATION") child.recycle(); return result }
            @Suppress("DEPRECATION") child.recycle()
        }
        return null
    }

    /**
     * Mirrors Lua's isDangerousTapTarget — never tap buttons that could affect an active dash.
     */
    private fun isDangerousTap(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "dash" || t == "dash now" || t == "unassign" || t == "end now" ||
               t == "pause orders" || t == "resume dash" ||
               t.contains("start dash") || t.contains("dash now") || t.contains("return to dash") ||
               t.contains("end dash") || t.contains("end now") || t.contains("pause orders") ||
               t.contains("resume dash") || t.contains("preview directions") || t.contains("preview route")
    }

    /**
     * Tap "Claim and schedule", then wait for the confirmation screen and dismiss it.
     * Mirrors Lua's findAndTapAccept + tryAccept + handleClaimSuccess flow.
     */
    fun claimCurrentOffer(onDone: (ClaimResult) -> Unit) {
        // Try text search first, then positional fallback for real Dasher (Compose)
        val tapped = tapByText("Claim and schedule") || tapClaimButtonByPosition()
        if (!tapped) {
            log("Claim: 'Claim and schedule' button not found")
            onDone(ClaimResult.NOT_FOUND)
            return
        }
        log("Claim: tapped 'Claim and schedule' — waiting for confirmation…")

        // Phase 1 (4.5s): check for conflict / unavailable / success confirmation
        // Mirrors Lua: isConflict = "already scheduled" OR ("got it" AND "cancel")
        //              isThanks   = "thanks for claiming" OR "here's what you need to know"
        //                           OR ("got it" AND "when to start")
        mainHandler.postDelayed({
            saveDebugScreenshot("post_claim")
            val root = rootInActiveWindow
            val sb = StringBuilder()
            if (root != null) { collectText(root, sb); @Suppress("DEPRECATION") root.recycle() }
            val flat = sb.toString().lowercase()

            val isThanks = flat.contains("thanks for claiming") ||
                           flat.contains("here's what you need to know before you start") ||
                           (flat.contains("got it") && flat.contains("when to start"))
            val isConflict = flat.contains("already scheduled") ||
                             (flat.contains("got it") && flat.contains("cancel"))
            val isUnavailable = flat.contains("unable to schedule") || flat.contains("no longer available")

            when {
                isThanks -> {
                    log("Claim: ✅ SUCCESS — tapping Got it")
                    tapByText("Got it")
                    mainHandler.postDelayed({ onDone(ClaimResult.SUCCESS) }, timings.claimConfirm)
                }
                isConflict -> {
                    log("Claim: ⚠️ scheduling conflict — tapping Got it")
                    tapByText("Got it")
                    mainHandler.postDelayed({ onDone(ClaimResult.CONFLICT) }, 800)
                }
                isUnavailable -> {
                    log("Claim: ⚠️ offer no longer available — tapping Got it")
                    tapByText("Got it")
                    mainHandler.postDelayed({ onDone(ClaimResult.UNAVAILABLE) }, 800)
                }
                isMyScheduleTab(flat) -> {
                    // Auto-dismiss already tapped Got it; My Schedule is showing = claim succeeded
                    log("Claim: ✅ SUCCESS (Got it already handled — My Schedule visible)")
                    onDone(ClaimResult.SUCCESS)
                }
                else -> {
                    // Screen not yet showing — log what we see and retry once (mirrors Lua's maxAttempts=2)
                    val sample = flat.replace('\n', ' ').take(120)
                    log("Claim: confirmation not detected yet — retrying in 3s [saw: $sample]")
                    mainHandler.postDelayed({
                        val root2 = rootInActiveWindow
                        val sb2 = StringBuilder()
                        if (root2 != null) { collectText(root2, sb2); @Suppress("DEPRECATION") root2.recycle() }
                        val flat2 = sb2.toString().lowercase()

                        val isThanks2 = flat2.contains("thanks for claiming") ||
                                        flat2.contains("here's what you need to know before you start") ||
                                        (flat2.contains("got it") && flat2.contains("when to start"))
                        val isConflict2 = flat2.contains("already scheduled") ||
                                          (flat2.contains("got it") && flat2.contains("cancel"))
                        val isUnavailable2 = flat2.contains("unable to schedule") || flat2.contains("no longer available")

                        when {
                            isThanks2 -> {
                                log("Claim: ✅ SUCCESS (retry) — tapping Got it")
                                tapByText("Got it")
                                mainHandler.postDelayed({ onDone(ClaimResult.SUCCESS) }, timings.claimConfirm)
                            }
                            isConflict2 -> {
                                log("Claim: ⚠️ conflict (retry) — tapping Got it")
                                tapByText("Got it")
                                mainHandler.postDelayed({ onDone(ClaimResult.CONFLICT) }, 800)
                            }
                            isUnavailable2 -> {
                                log("Claim: ⚠️ unavailable (retry) — tapping Got it")
                                tapByText("Got it")
                                mainHandler.postDelayed({ onDone(ClaimResult.UNAVAILABLE) }, 800)
                            }
                            else -> {
                                val sample2 = flat2.replace('\n', ' ').take(120)
                                captureAndDetect()
                                if (currentScreen == ScreenState.SCHEDULE || currentScreen == ScreenState.SCHEDULE_MY) {
                                    log("Claim: still unknown after retry — assuming success [saw: $sample2]")
                                    onDone(ClaimResult.SUCCESS)
                                } else {
                                    log("Claim: no confirmation after retry — result unknown [saw: $sample2]")
                                    onDone(ClaimResult.UNKNOWN)
                                }
                            }
                        }
                    }, 3000)
                }
            }
        }, 4500)  // 4.5s — real Dasher takes longer to show confirmation screen
    }

    /**
     * Full flow for one offer date: tap card → read details → claim.
     * Call this after the scan has landed on a date with HAS_OFFERS status.
     */
    /**
     * Positional fallback for "Claim and schedule" button.
     * Real Dasher (Compose) often doesn't expose button text via findAccessibilityNodeInfosByText.
     * The claim button is always a large clickable node in the bottom quarter of the screen.
     */
    private fun tapClaimButtonByPosition(): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomQuarterStart = screenHeight * 0.70f  // bottom 30% of screen

        val node = findClaimButtonNode(root, bottomQuarterStart)
        @Suppress("DEPRECATION") root.recycle()

        if (node != null) {
            // Safety: read full subtree text — Compose puts text in child nodes, parent is often empty
            val sb = StringBuilder()
            collectCollapsed(node, sb)
            val nodeText = sb.toString().trim()
            if (isDangerousTap(nodeText)) {
                log("Claim: BLOCKED positional fallback — dangerous target '$nodeText'")
                @Suppress("DEPRECATION") node.recycle()
                return false
            }
            // Only tap if it looks like a claim button (empty = Compose default, or claim/schedule text)
            val t = nodeText.lowercase()
            if (nodeText.isNotEmpty() && !t.contains("claim") && !t.contains("schedule")) {
                log("Claim: BLOCKED positional fallback — unexpected button text '$nodeText'")
                @Suppress("DEPRECATION") node.recycle()
                return false
            }
            val b = Rect()
            node.getBoundsInScreen(b)
            val cx = (b.left + b.right) / 2f
            val cy = (b.top + b.bottom) / 2f
            log("Claim button found at ($cx, $cy) via position fallback [text='$nodeText']")
            tapAt(cx, cy)
            @Suppress("DEPRECATION") node.recycle()
            return true
        }
        return false
    }

    private fun findClaimButtonNode(node: AccessibilityNodeInfo, minY: Float): AccessibilityNodeInfo? {
        val b = Rect()
        node.getBoundsInScreen(b)
        // Claim button: clickable, wide (>60% screen width), in bottom portion, reasonable height
        val screenWidth = resources.displayMetrics.widthPixels
        if (node.isClickable && b.top >= minY && b.width() > screenWidth * 0.6f
            && b.height() in 40..200) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClaimButtonNode(child, minY)
            if (result != null) { @Suppress("DEPRECATION") child.recycle(); return result }
            @Suppress("DEPRECATION") child.recycle()
        }
        return null
    }

    /**
     * Close the offer detail card.
     * The close button has resource-id "close_action_button" but no text/contentDescription
     * (confirmed via uiautomator dump) — tapById finds it by resource ID.
     * Falls back to GLOBAL_ACTION_BACK if the resource ID tap fails.
     */
    private fun dismissOfferCard(onDone: () -> Unit) {
        val closedById = tapById("close_action_button")
        if (!closedById) performGlobalAction(GLOBAL_ACTION_BACK)
        log("dismissOfferCard: ${if (closedById) "tapped close_action_button ✓" else "close_action_button not found — GLOBAL_ACTION_BACK"}")
        mainHandler.postDelayed({
            val root = rootInActiveWindow
            val sb = StringBuilder()
            if (root != null) { collectText(root, sb); @Suppress("DEPRECATION") root.recycle() }
            val flat = sb.toString().lowercase()
            if (flat.contains("guaranteed") || flat.contains("claim and schedule")) {
                log("dismissOfferCard: card still open — retrying GLOBAL_ACTION_BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
                mainHandler.postDelayed({ onDone() }, 800)
            } else {
                onDone()
            }
        }, timings.dismissVerify)
    }

    /**
     * Process ALL offers on the current date screen — highest payout first.
     * Implements: Fix 1 (cache), Fix 2 (multi-offer), Fix 3 (payout pre-filter),
     *             Fix 4 (zone pre-filter), Fix 5 (dismiss verify).
     */
    fun processOfferOnCurrentDate(isToday: Boolean = false, isWeekend: Boolean = false, dateLabel: String = "", onDone: (String) -> Unit) {
        val filters = FilterSettings.load(this)

        // Fix 2+3: find all offers pre-filtered by min payout, sorted highest first
        val listOffers = findOffersOnListScreen(filters.minPayout)

        if (listOffers.isEmpty()) {
            // collectOfferNodes returned 0 — Compose card nodes didn't match size/position criteria.
            // Read the full text tree (same as OCR — collectText sees all visible text including
            // "Pick up by 7:48 AM", miles, zone) and run ALL filters before opening the card.
            val root = rootInActiveWindow
            val fullText = StringBuilder()
            val collapsedText = StringBuilder()
            if (root != null) {
                collectText(root, fullText)
                collectCollapsed(root, collapsedText)
                @Suppress("DEPRECATION") root.recycle()
            }
            // Strip Android system overlay noise before parsing
            val flat = fullText.toString()
                .replace(Regex("(?i)catering bot is displaying over other apps"), "")
                .replace(Regex("(?i)\\bexpand\\b"), "")
            val collapsed = collapsedText.toString()
                .replace(Regex("(?i)catering bot is displaying over other apps"), "")
                .replace(Regex("(?i)\\bexpand\\b"), "")

            // Extract every field visible on the list screen card
            val payoutOnScreen = Regex("""\$(\d+\.?\d*)\s*offer""", RegexOption.IGNORE_CASE)
                .find(collapsed)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("""\$(\d+\.?\d*)\s+(?:offer|Guaranteed)""", RegexOption.IGNORE_CASE)
                    .find(flat)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: 0.0
            val milesOnScreen = Regex("""([\d.]+)\s*mi(?!n)""").find(flat)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val pickupTimeOnScreen = Regex(
                """Pick up by\s+(\d{1,2}:\d{2}(?:\s*[AP]M)?)""", RegexOption.IGNORE_CASE
            ).find(flat)?.groupValues?.get(1)?.trim() ?: ""
            val zoneOnScreen = Regex("""([A-Z]{2}:\s*[A-Za-z][A-Za-z /'\-]+)""").find(flat)
                ?.groupValues?.get(1)?.trim() ?: ""
            val restaurantOnScreen = stripPickupTimeNoise(
                Regex("""([A-Z][A-Za-z0-9 '&]+?)(?:\s*-\s*[A-Za-z][A-Za-z ,]+)?\s*[•·]\s*[Cc]atering""")
                    .find(flat)?.groupValues?.get(1)?.trim() ?: ""
            )

            log("List screen: payout=\$${"%.2f".format(payoutOnScreen)} miles=${milesOnScreen}mi pickup='$pickupTimeOnScreen' zone='$zoneOnScreen' rest='$restaurantOnScreen'")

            // Run full filter check on list-screen data.
            // Payout defaults to minPayout (no false failure) when not found.
            // Miles/pickupTime/zone default to safe values when not found.
            // Check cache before doing anything — prevents repeat Telegram spam each cycle
            if (payoutOnScreen > 0 && isOfferCached(payoutOnScreen, zoneOnScreen)) {
                log("Cache: \$${"%.2f".format(payoutOnScreen)} $zoneOnScreen seen recently — skipping (fallback)")
                onDone("⏭ Cached"); return
            }

            val hasAnyData = payoutOnScreen > 0 || pickupTimeOnScreen.isNotEmpty() || zoneOnScreen.isNotEmpty()
            if (hasAnyData) {
                val checkPayout = if (payoutOnScreen > 0) payoutOnScreen else filters.minPayout
                val partial = OfferDetails(checkPayout, milesOnScreen, pickupTimeOnScreen, "", zoneOnScreen)
                val (passes, reason) = filters.check(partial, isToday, isWeekend, dateLabel)
                if (!passes) {
                    cacheOffer(payoutOnScreen, zoneOnScreen)
                    if (reason.contains("same-day")) {
                        TelegramHelper.send(this,
                            "🔔 TODAY'S OFFER — NOT AUTO-CLAIMED\n\n" +
                            offerBlock(payoutOnScreen, milesOnScreen, pickupTimeOnScreen, zoneOnScreen, dateLabel,
                                restaurantOnScreen)
                        )
                    } else {
                        TelegramHelper.send(this,
                            "⏭ OFFER SKIPPED\nReason: $reason\n\n" +
                            offerBlock(payoutOnScreen, milesOnScreen, pickupTimeOnScreen, zoneOnScreen, dateLabel,
                                restaurantOnScreen)
                        )
                    }
                    val msg = "⏭ Skipped (list screen): $reason"
                    showBanner("⏭ OFFER SKIPPED", "\$${"%.2f".format(payoutOnScreen)} — $reason", 0xFFE65100.toInt())
                    playTone(ToneType.SKIP)
                    setOverlayLastAction(msg); onDone(msg); return
                }
                log("All list-screen filters passed — opening card")
            }

            // Fallback 1: text search (mock / some Dasher versions)
            if (tapByText("Catering") || tapByText("Pick up by")) {
                processSingleOffer(filters, isToday, isWeekend, null, dateLabel, onDone)
                return
            }
            // Fallback 2: find offer card by position (real Dasher Compose)
            if (tapOfferCardInContentArea()) {
                log("processOffer: tapped offer card via position fallback")
                processSingleOffer(filters, isToday, isWeekend, null, dateLabel, onDone)
                return
            }
            val msg = "❌ No offer cards found on screen"
            log(msg); onDone(msg); return
        }

        log("Found ${listOffers.size} offer(s) — processing highest payout first")
        processOfferAtIndex(listOffers, 0, filters, isToday, isWeekend, dateLabel, onDone)
    }

    private fun processOfferAtIndex(
        offers: List<ListOffer>, index: Int,
        filters: FilterSettings, isToday: Boolean, isWeekend: Boolean,
        dateLabel: String,
        onDone: (String) -> Unit
    ) {
        if (index >= offers.size) {
            // All visible offers exhausted — scroll down once to reveal any below-fold cards
            if (scrollOfferListDown()) {
                mainHandler.postDelayed({
                    val more = findOffersOnListScreen(filters.minPayout)
                        .filter { n -> offers.none { o -> o.payout == n.payout && o.zone == n.zone } }
                    if (more.isNotEmpty()) {
                        log("Scroll: ${more.size} more offer(s) revealed below fold")
                        processOfferAtIndex(more, 0, filters, isToday, isWeekend, dateLabel, onDone)
                    } else {
                        onDone("⏭ All ${offers.size} offer(s) skipped/processed")
                    }
                }, 500)
            } else {
                onDone("⏭ All ${offers.size} offer(s) skipped/processed")
            }
            return
        }

        // Log full offer list on first call — mirrors Lua's autoClaimFlow dump
        if (index == 0) {
            log("processOffers: ${offers.size} offer(s) — trying highest payout first")
            offers.forEachIndexed { i, o ->
                log("  [${i + 1}] \$${"%.2f".format(o.payout)} — ${o.zone.ifEmpty { "unknown zone" }}")
            }
        }

        val offer = offers[index]
        log("processOffers: trying [${index + 1}/${offers.size}] \$${"%.2f".format(offer.payout)} @ (${offer.x.toInt()},${offer.y.toInt()})")

        // Fix 1: skip if seen within 5 min
        if (isOfferCached(offer.payout, offer.zone)) {
            log("Cache: \$${offer.payout} ${offer.zone} seen recently — skipping")
            processOfferAtIndex(offers, index + 1, filters, isToday, isWeekend, dateLabel, onDone)
            return
        }

        // Full pre-filter on list-screen data — run ALL filters before opening the card.
        // pickup time and miles are now captured in collectOfferNodes from the card's own subtree.
        val partial = OfferDetails(offer.payout, offer.miles, offer.pickupTime, "", offer.zone)
        val (passes, preReason) = filters.check(partial, isToday, isWeekend, dateLabel)
        if (!passes) {
            cacheOffer(offer.payout, offer.zone)
            dailySkippedCount++
            if (preReason.contains("same-day")) {
                TelegramHelper.send(this,
                    "🔔 TODAY'S OFFER — NOT AUTO-CLAIMED\n\n" +
                    offerBlock(offer.payout, offer.miles, offer.pickupTime, offer.zone, dateLabel,
                        offer.restaurant, itemCount = offer.itemCount)
                )
            } else {
                TelegramHelper.send(this,
                    "⏭ OFFER SKIPPED\nReason: $preReason\n\n" +
                    offerBlock(offer.payout, offer.miles, offer.pickupTime, offer.zone, dateLabel,
                        offer.restaurant, itemCount = offer.itemCount)
                )
            }
            val msg = "⏭ Skipped: $preReason"
            showBanner("⏭ OFFER SKIPPED", "\$${"%.2f".format(offer.payout)} — $preReason", 0xFFE65100.toInt())
            playTone(ToneType.SKIP)
            setOverlayLastAction(msg)
            processOfferAtIndex(offers, index + 1, filters, isToday, isWeekend, dateLabel, onDone); return
        }

        log("Tapping offer \$${offer.payout} @ (${offer.x.toInt()}, ${offer.y.toInt()})")
        setOverlayLastAction("Opening \$${offer.payout} offer…")
        saveDebugScreenshot("offer_list")
        tapOfferAtCoords(offer.x, offer.y)

        processSingleOffer(filters, isToday, isWeekend, offer, dateLabel) { result ->
            if (result.startsWith("⏭") || result.startsWith("⚠️")) {
                mainHandler.postDelayed({
                    processOfferAtIndex(offers, index + 1, filters, isToday, isWeekend, dateLabel, onDone)
                }, 500)
            } else {
                onDone(result)
            }
        }
    }

    private fun processSingleOffer(
        filters: FilterSettings, isToday: Boolean, isWeekend: Boolean,
        listOffer: ListOffer?, dateLabel: String, onDone: (String) -> Unit
    ) {
        log("processSingleOffer: waiting ${timings.detailWait}ms for detail screen…")
        mainHandler.postDelayed({
            fun claimWithDetails(rawDetails: OfferDetails) {
                // Zone label ("CA: Sacramento") only appears on the list card, not the detail card.
                // parseDetailText's fallback zone regex can match restaurant location names instead.
                // Always prefer the zone captured from the list screen when available.
                val details = if (listOffer != null && listOffer.zone.isNotEmpty())
                    rawDetails.copy(zone = listOffer.zone) else rawDetails
                log("Detail: $details")
                setOverlayLastAction("\$${details.payout} | ${details.miles}mi | ${details.pickupTime}")

                val (passes, reason) = filters.check(details, isToday, isWeekend, dateLabel)
                if (!passes) {
                    listOffer?.let { cacheOffer(it.payout, it.zone) }
                    dailySkippedCount++
                    if (reason.contains("same-day")) {
                        TelegramHelper.send(this,
                            "🔔 TODAY'S OFFER — NOT AUTO-CLAIMED\n\n" +
                            offerBlock(details.payout, details.miles, details.pickupTime, details.zone, dateLabel,
                                details.restaurant, details.deliverTime, details.itemCount, guaranteed = true)
                        )
                    } else {
                        TelegramHelper.send(this,
                            "⏭ OFFER SKIPPED\nReason: $reason\n\n" +
                            offerBlock(details.payout, details.miles, details.pickupTime, details.zone, dateLabel,
                                details.restaurant, details.deliverTime, details.itemCount)
                        )
                    }
                    val msg = "⏭ Skipped: $reason"
                    showBanner("⏭ OFFER SKIPPED", "\$${details.payout} — $reason", 0xFFE65100.toInt())
                    playTone(ToneType.SKIP)
                    setOverlayLastAction(msg)
                    dismissOfferCard { onDone(msg) }
                    return
                }

                log("Claiming \$${details.payout}")
                claimCurrentOffer { result ->
                    when (result) {
                        ClaimResult.SUCCESS -> {
                            listOffer?.let { cacheOffer(it.payout, it.zone) }
                            dailyClaimedCount++
                            dailyClaimedTotal += details.payout
                            val offerDesc = "\$${String.format("%.2f", details.payout)} ${details.zone}"
                            if (dailyBestOffer.isEmpty() || details.payout >
                                (dailyBestOffer.substringAfter("$").substringBefore(" ").toDoubleOrNull() ?: 0.0))
                                dailyBestOffer = offerDesc
                            ClaimHistoryStore.record(this, details.payout, details.restaurant, details.zone)
                            TelegramHelper.send(this, "✅ ORDER CLAIMED\n\n" +
                                offerBlock(details.payout, details.miles, details.pickupTime, details.zone, dateLabel,
                                    details.restaurant, details.deliverTime, details.itemCount, guaranteed = true))
                            mainHandler.postDelayed({
                                captureAndDetect()
                                if (currentScreen == ScreenState.POST_CLAIM) {
                                    log("Claim: card still open after Got it (POST_CLAIM) — pressing Back")
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                } else {
                                    log("Claim: post-claim screen = $currentScreen — no Back needed")
                                }
                            }, 1200)
                        }
                        ClaimResult.CONFLICT -> {
                            listOffer?.let { cacheOffer(it.payout, it.zone) }
                            TelegramHelper.send(this,
                                "⚠️ SCHEDULING CONFLICT\nAlready scheduled at this time.\n\n" +
                                offerBlock(details.payout, details.miles, details.pickupTime, details.zone, dateLabel,
                                    details.restaurant, details.deliverTime, details.itemCount)
                            )
                        }
                        ClaimResult.UNAVAILABLE -> {
                            listOffer?.let { cacheOffer(it.payout, it.zone) }
                            TelegramHelper.send(this,
                                "⚠️ OFFER GONE\nClaimed by someone else.\n\n" +
                                offerBlock(details.payout, details.miles, details.pickupTime, details.zone, dateLabel,
                                    details.restaurant, details.deliverTime, details.itemCount)
                            )
                        }
                        else -> {}
                    }
                    val msg = when (result) {
                        ClaimResult.SUCCESS     -> "✅ CLAIMED: \$${details.payout} | ${details.pickupTime} | ${details.restaurant}"
                        ClaimResult.CONFLICT    -> "⚠️ Scheduling conflict — skipped"
                        ClaimResult.UNAVAILABLE -> "⚠️ Offer no longer available"
                        ClaimResult.NOT_FOUND   -> "❌ Claim button not found"
                        ClaimResult.UNKNOWN     -> "❓ Unknown claim result"
                    }
                    when (result) {
                        ClaimResult.SUCCESS -> {
                            showBanner("✅ ORDER CLAIMED", "\$${details.payout} · ${details.restaurant}", 0xFF1B5E20.toInt())
                            playTone(ToneType.CLAIMED)
                        }
                        ClaimResult.CONFLICT -> {
                            showBanner("⚠️ SCHEDULING CONFLICT", "\$${details.payout} — already scheduled at this time", 0xFFE65100.toInt())
                            playTone(ToneType.ALERT)
                        }
                        ClaimResult.UNAVAILABLE -> {
                            showBanner("⚠️ OFFER GONE", "\$${details.payout} at ${details.restaurant} was taken", 0xFFE65100.toInt())
                            playTone(ToneType.ALERT)
                        }
                        else -> {}
                    }
                    setOverlayLastAction(msg); onDone(msg)
                }
            }

            val details = readDetailScreen()
            if (details != null) {
                claimWithDetails(details)
            } else {
                log("processSingleOffer: detail screen text empty — trying OCR (up to 4 attempts)")
                readDetailScreenViaOCR(attemptsLeft = 4) { ocrDetails ->
                    if (ocrDetails != null) {
                        claimWithDetails(ocrDetails)
                    } else {
                        dismissOfferCard { onDone("❌ Could not parse offer detail screen") }
                    }
                }
            }
        }, timings.detailWait)
    }

    // ─── Read offer list ──────────────────────────────────────────────────────

    /**
     * Call this when on the Schedule/Offers screen.
     * Returns the current status of the offer list for today's selected date.
     */
    fun readOffersStatus(): OfferListStatus {
        val root = rootInActiveWindow ?: return OfferListStatus.LOADING
        val allText = StringBuilder()
        collectText(root, allText)
        val flat = allText.toString().lowercase()
        @Suppress("DEPRECATION") root.recycle()

        // My Schedule tab shows already-claimed orders — never treat as new offers
        if (isMyScheduleTab(flat)) {
            log("readOffersStatus: EMPTY (My Schedule tab — skipping)")
            return OfferListStatus.EMPTY
        }

        return when {
            flat.contains("nothing available to schedule") ||
                    flat.contains("nothing available") ||
                    flat.contains("check again later") ||
                    flat.contains("check again") -> {
                val trigger = when {
                    flat.contains("nothing available to schedule") -> "nothing available to schedule"
                    flat.contains("nothing available") -> "nothing available"
                    flat.contains("check again later") -> "check again later"
                    else -> "check again"
                }
                log("readOffersStatus: EMPTY (\"$trigger\")")
                OfferListStatus.EMPTY
            }

            flat.contains("see next available") -> {
                log("readOffersStatus: SEE_NEXT")
                OfferListStatus.SEE_NEXT
            }

            flat.contains("catering") || flat.contains("pick up by") ||
                    flat.contains("guaranteed") -> {
                val trigger = when {
                    flat.contains("catering") -> "catering"
                    flat.contains("pick up by") -> "pick up by"
                    else -> "guaranteed"
                }
                log("readOffersStatus: HAS_OFFERS (\"$trigger\")")
                OfferListStatus.HAS_OFFERS
            }

            else -> {
                log("readOffersStatus: UNKNOWN (no matching text)")
                OfferListStatus.UNKNOWN
            }
        }
    }

    enum class OfferListStatus { HAS_OFFERS, EMPTY, SEE_NEXT, LOADING, UNKNOWN }

    // ─── Tap helpers ──────────────────────────────────────────────────────────

    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // Primary: system API search (works for View-based layouts)
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val hit = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()?.let { node ->
            var n: AccessibilityNodeInfo? = node.parent
            while (n != null && !n.isClickable) { n = n.parent }
            n
        }
        if (hit != null) {
            hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            log("tapByText: '$text' ✓")
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            @Suppress("DEPRECATION") root.recycle()
            return true
        }
        nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
        // Fallback: manual traversal — Compose exposes node.text but findAccessibilityNodeInfosByText misses it
        if (tapByTextManual(root, text)) {
            log("tapByText: '$text' ✓ (manual)")
            @Suppress("DEPRECATION") root.recycle()
            return true
        }
        log("tapByText: '$text' — not found")
        @Suppress("DEPRECATION") root.recycle()
        return false
    }

    private fun tapByTextManual(node: AccessibilityNodeInfo, text: String): Boolean {
        val t = node.text?.toString() ?: ""
        if (t.equals(text, ignoreCase = true) || (t.isNotEmpty() && t.contains(text, ignoreCase = true))) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            // Walk up to find clickable parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    @Suppress("DEPRECATION") parent.recycle()
                    return true
                }
                val gp = parent.parent
                @Suppress("DEPRECATION") parent.recycle()
                parent = gp
            }
            // No clickable parent — tap by coordinates as last resort
            val b = Rect()
            node.getBoundsInScreen(b)
            if (b.width() > 0) tapAt((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = tapByTextManual(child, text)
            @Suppress("DEPRECATION") child.recycle()
            if (found) return true
        }
        return false
    }

    fun tapByDesc(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, desc, byDesc = true).also {
            @Suppress("DEPRECATION") root.recycle()
            if (it) log("tapByDesc: '$desc' ✓") else log("tapByDesc: '$desc' — not found")
        }
    }

    private fun findAndClick(node: AccessibilityNodeInfo, target: String, byDesc: Boolean): Boolean {
        val value = if (byDesc) node.contentDescription?.toString() else node.text?.toString()
        if (value == target) {
            when {
                node.isClickable -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                else -> {
                    // Matching node isn't clickable — walk up to find clickable parent
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            @Suppress("DEPRECATION") parent.recycle()
                            return true
                        }
                        val grandparent = parent.parent
                        @Suppress("DEPRECATION") parent.recycle()
                        parent = grandparent
                    }
                    // No clickable parent found — try clicking the node directly anyway
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClick(child, target, byDesc)) {
                @Suppress("DEPRECATION") child.recycle()
                return true
            }
            @Suppress("DEPRECATION") child.recycle()
        }
        return false
    }

    fun tapById(idFragment: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId("$DASHER_PACKAGE:id/$idFragment")
        return if (nodes.isNotEmpty()) {
            nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            log("tapById: '$idFragment' ✓")
            nodes.forEach { @Suppress("DEPRECATION") it.recycle() }
            @Suppress("DEPRECATION") root.recycle()
            true
        } else {
            log("tapById: '$idFragment' — not found")
            @Suppress("DEPRECATION") root.recycle()
            false
        }
    }

    fun tapAt(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        // Briefly make lock overlay non-touchable so the gesture reaches Dasher.
        // The gap is only as long as the gesture (50ms) — too fast for human reaction.
        // updateViewLayout doesn't reach the input dispatcher until the next vsync (~16ms).
        // Delay the gesture 50ms so the FLAG_NOT_TOUCHABLE passthrough is live before it fires.
        setLockPassthrough(true)
        mainHandler.postDelayed({
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { mainHandler.post { setLockPassthrough(false) } }
                override fun onCancelled(g: GestureDescription) { mainHandler.post { setLockPassthrough(false) } }
            }, null)
            if (!dispatched) setLockPassthrough(false)
        }, 50)
        log("tapAt ($x, $y)")
    }

    /** Like tapAt, but with a small deliberate movement and a longer press — mimics the
     *  natural jitter of a real finger tap instead of a perfectly still synthetic one. */
    private fun tapWithJitter(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 3f, y + 2f)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        setLockPassthrough(true)
        mainHandler.postDelayed({
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { mainHandler.post { setLockPassthrough(false) } }
                override fun onCancelled(g: GestureDescription) { mainHandler.post { setLockPassthrough(false) } }
            }, null)
            if (!dispatched) setLockPassthrough(false)
        }, 50)
        log("tapWithJitter ($x, $y)")
    }

    /** Real edge-swipe drag gesture — distinct from a tap, goes through Android's drag/pan
     *  gesture recognition rather than click recognition. Many navigation drawers (including
     *  Compose's ModalNavigationDrawer) open via an edge-swipe pan gesture, which is a
     *  fundamentally different code path than the hamburger icon's own click handler. */
    private fun swipeOpenDrawer() {
        val path = Path().apply {
            moveTo(4f, 900f)
            lineTo(150f, 900f)
            lineTo(350f, 900f)
            lineTo(500f, 900f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        setLockPassthrough(true)
        mainHandler.postDelayed({
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { mainHandler.post { setLockPassthrough(false) } }
                override fun onCancelled(g: GestureDescription) { mainHandler.post { setLockPassthrough(false) } }
            }, null)
            if (!dispatched) setLockPassthrough(false)
        }, 50)
        log("swipeOpenDrawer: edge-swipe dispatched")
    }

    private fun setLockPassthrough(passthrough: Boolean) {
        val root = lockRoot ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val current = params.flags and flag != 0
        if (current == passthrough) return
        params.flags = if (passthrough) params.flags or flag else params.flags and flag.inv()
        try { windowManager?.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    // ─── Node helpers ─────────────────────────────────────────────────────────

    /** True when the My Schedule tab content is visible — already-claimed orders with standalone View Details buttons. */
    private fun isMyScheduleTab(flat: String): Boolean {
        // "view details" confirmed in uiautomator dump — claimed orders always show this button on My Schedule.
        // "start dash by" appears for near-term orders. "preview directions" / "unassign" for other states.
        return flat.contains("start dash by") ||
               flat.contains("you scheduled this offer") ||
               flat.contains("view details") ||
               flat.contains("preview directions") ||
               flat.contains("unassign")
    }

    private fun hasNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean =
        root.findAccessibilityNodeInfosByText(text).isNotEmpty()

    /** True when the date-picker row is visible — unique to the Schedule screen. */
    private fun hasDateTabRow(root: AccessibilityNodeInfo): Boolean {
        return hasDescStartingWith(root, "Today") ||
               listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")
                   .any { hasDescStartingWith(root, it) }
    }

    private fun hasDescStartingWith(node: AccessibilityNodeInfo, prefix: String): Boolean {
        if (node.contentDescription?.toString()?.startsWith(prefix) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = hasDescStartingWith(child, prefix)
            @Suppress("DEPRECATION") child.recycle()
            if (found) return true
        }
        return false
    }

    private fun hasNodeWithDesc(root: AccessibilityNodeInfo, desc: String): Boolean {
        if (root.contentDescription?.toString() == desc) return true
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (hasNodeWithDesc(child, desc)) {
                @Suppress("DEPRECATION") child.recycle()
                return true
            }
            @Suppress("DEPRECATION") child.recycle()
        }
        return false
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            @Suppress("DEPRECATION") child.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun appendNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val text = node.text?.toString()?.take(80) ?: ""
        val desc = node.contentDescription?.toString()?.take(80) ?: ""
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val flags = buildString {
            if (node.isClickable) append("clickable ")
            if (node.isScrollable) append("scrollable ")
            if (node.isEnabled) append("enabled ")
        }.trim()
        sb.appendLine("$indent[$cls] id='$id' text='$text' desc='$desc' $flags @ $bounds")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { appendNode(it, depth + 1, sb); it.recycle() }
        }
    }

    // ─── Auto-loop ────────────────────────────────────────────────────────────

    fun clearOfferCache() {
        offerCache.clear()
        log("Offer cache cleared")
    }

    // ─── Daily summary ────────────────────────────────────────────────────────

    private val midnightRunnable = Runnable {
        sendDailySummary()
        dailyClaimedCount = 0; dailyClaimedTotal = 0.0
        dailySkippedCount = 0; dailyBestOffer = ""
        scheduleMidnightSummary()
    }

    private fun scheduleMidnightSummary() {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        mainHandler.postDelayed(midnightRunnable, cal.timeInMillis - System.currentTimeMillis())
    }

    private fun sendDailySummary() {
        val f = FilterSettings.load(this)
        if (!f.isTelegramConfigured()) return
        val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date())
        val msg = buildString {
            appendLine("📊 DAILY SUMMARY — $date")
            appendLine("──────────────────────")
            appendLine("✅ Claimed: $dailyClaimedCount (\$${String.format("%.2f", dailyClaimedTotal)})")
            appendLine("⏭ Skipped: $dailySkippedCount")
            appendLine("🔄 Cycles run: $loopCycleCount")
            if (dailyBestOffer.isNotEmpty()) append("💰 Best: $dailyBestOffer")
            else append("No offers claimed today")
        }
        TelegramHelper.send(this, msg, silent = true)
        log("📊 Daily summary sent")
    }

    // ─── Scroll helpers ───────────────────────────────────────────────────────

    private fun findScrollableNode(node: AccessibilityNodeInfo, minY: Int): AccessibilityNodeInfo? {
        val b = Rect(); node.getBoundsInScreen(b)
        if (node.isScrollable && b.top >= minY) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child, minY)
            if (found != null) {
                if (found !== child) @Suppress("DEPRECATION") child.recycle()
                return found
            }
            @Suppress("DEPRECATION") child.recycle()
        }
        return null
    }

    private fun scrollOfferListDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findScrollableNode(root, minY = 500)
        @Suppress("DEPRECATION") root.recycle()
        if (node == null) return false
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        @Suppress("DEPRECATION") node.recycle()
        return result
    }

    fun startAutoLoop() {
        if (isLoopRunning) { log("Loop already running"); return }
        val f = FilterSettings.load(this)
        isLoopRunning = true
        consecutiveFailures = 0
        dashingNavAttempted = false
        loopCycleCount = 0
        timings = Timings.forFilters(f)
        slowModeActive = f.slowMode
        log("▶ Auto-loop started [${if (f.slowMode) "🐢 SLOW MODE" else if (f.speedMode) "⚡ SPEED MODE" else "normal"}]")
        updateOverlayLoopState(true)
        mainHandler.postDelayed({ showTouchLock() }, 800)
        runLoopCycle()
    }

    fun stopAutoLoop() {
        isLoopRunning = false
        mainHandler.removeCallbacks(loopCycleRunnable)
        stopCountdownTicker()
        log("⏹ Auto-loop stopped")
        setOverlayLastAction("Bot stopped")
        updateOverlayLoopState(false)
        hideTouchLock()
    }

    private val loopCycleRunnable = Runnable { runLoopCycle() }

    private fun runLoopCycle() {
        if (!isLoopRunning) return

        loopCycleCount++
        log("── Cycle #$loopCycleCount ──")

        // If another app (e.g. Google Maps) is in the foreground, press Back to return to Dasher
        val activePkg = rootInActiveWindow?.let { r ->
            val p = r.packageName?.toString()
            @Suppress("DEPRECATION") r.recycle()
            p
        }
        if (activePkg != null && activePkg !in WATCHED_PACKAGES) {
            log("Loop: not in Dasher (package=$activePkg) — pressing Back to return")
            performGlobalAction(GLOBAL_ACTION_BACK)
            scheduleNext(2000)
            return
        }

        captureAndDetect()
        if (currentScreen != ScreenState.LOADING) loadingSinceMs = 0L
        if (currentScreen != ScreenState.UNKNOWN) unknownSinceMs = 0L

        // Safety: DASHING or UPCOMING_ORDER → never tap anything but the known-safe nav
        // anchors on these screens (isDangerousTap guards Dash/Unassign/Start Dash/Preview
        // route/End now/Pause orders/Resume dash regardless). The hamburger drawer's
        // open-gesture doesn't respond to AccessibilityService-dispatched touches on any
        // variant tried so far (plain, focus+duration, jitter) — a raw adb input-tap at the
        // identical coordinates opens it instantly, dispatchGesture does not — so that part
        // may never succeed. But the Settings-gear → "Go to home screen" step DOES work, and
        // which content-description the gear uses varies per screen ("Open Settings" on the
        // live map, "Current dash" on the Dash Paused screen — confirmed via the app's own
        // ui_dump.log, not guessed). So: try once per fresh entry into this state — whatever
        // anchors actually exist on THIS particular screen get used, whatever doesn't exist
        // is skipped — then idle quietly until the state changes, regardless of whether the
        // attempt succeeded. Never repeats or spams retries within the same streak. A live
        // incoming-delivery-offer popup mid-dash is inherently safe under this too — nothing
        // gets tapped beyond the one attempt, and only ever exact-named safe anchors.
        if (currentScreen == ScreenState.DASHING || currentScreen == ScreenState.UPCOMING_ORDER) {
            if (dashingSinceMs == 0L) dashingSinceMs = System.currentTimeMillis()
            if (!dashingNavAttempted) {
                dashingNavAttempted = true
                log("Loop: $currentScreen — one-shot Schedule-nav attempt on entry")
                navigateToSchedule { success -> log("Loop: one-shot nav attempt result = $success") }
            }
            val dashingMs = System.currentTimeMillis() - dashingSinceMs
            log("Loop: $currentScreen — waiting it out (${dashingMs / 1000}s), not touching anything further")
            setOverlayLastAction("Dash active — waiting…")
            scheduleNext(timings.loopMedium)
            return
        } else {
            dashingSinceMs = 0L
        }

        // Error dialogs — two kinds handled differently
        run {
            val r = rootInActiveWindow
            val sb = StringBuilder()
            if (r != null) { collectText(r, sb); @Suppress("DEPRECATION") r.recycle() }
            val flat = sb.toString().lowercase()
            if (flat.contains("something went wrong")) {
                // DoorDash bottom-sheet glitch (often post-claim) — Back dismisses it cleanly
                log("Loop: 'Something went wrong' — pressing Back to dismiss, will return to Offers")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleNext(2000)
                return
            }
            if (flat.contains("no internet") || flat.contains("connect to the internet") ||
                (flat.contains("try again") && !flat.contains("check again"))) {
                log("Loop: connection error — tapping Try again / Retry")
                mainHandler.postDelayed({ tapByText("Try again") || tapByText("Retry") }, 500)
                scheduleNext(3000)
                return
            }
        }

        val filters = FilterSettings.load(this)
        offerCooldownMs = filters.offerCooldownMs()
        timings = Timings.forFilters(filters)
        slowModeActive = filters.slowMode

        if (!filters.isWithinActiveHours()) {
            log("Loop: outside active hours — snoozing 60s")
            setOverlayLastAction("⏸ Outside active hours")
            scheduleNext(60_000L)
            return
        }

        // Mirror Lua's handleOcrResult — react to what is on screen right now
        when (currentScreen) {

            // My Schedule tab — mirrors Lua: "My Schedule tab detected — switching to Offers tab"
            ScreenState.SCHEDULE_MY -> {
                consecutiveFailures = 0
                dashingNavAttempted = false
                log("Loop: My Schedule tab — tapping Offers")
                tapByText("Offers")
                scheduleNext(timings.loopMedium)
            }

            // Claim confirmation — mirrors Lua: tap Got it and reschedule
            ScreenState.CLAIM_THANKS -> {
                consecutiveFailures = 0
                log("Loop: claim confirmation — tapping Got it")
                tapByText("Got it")
                // Close side drawer if it appears on this device after Got it
                mainHandler.postDelayed({
                    captureAndDetect()
                    if (currentScreen == ScreenState.UNKNOWN) {
                        log("Loop: CLAIM_THANKS — side drawer open, pressing Back")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    scheduleNext(800)
                }, 1000)
            }

            // On Schedule/Offers tab — the main work
            ScreenState.SCHEDULE -> {
                consecutiveFailures = 0
                dashingNavAttempted = false

                // Humanizing detour: occasionally glance at My Schedule before scanning, instead
                // of only ever sitting on Offers. My Schedule is a known, inspected read-only
                // screen (already-claimed orders) — no Dash-Now-style live action lives there, so
                // this is safe to tap into automatically, unlike Home/Dash which are not.
                if (filters.slowMode && filters.slowRandomizeTabs && (0 until 100).random() < MY_SCHEDULE_PEEK_PCT) {
                    log("Loop: humanizing detour — peeking at My Schedule before scanning")
                    setOverlayLastAction("Checking My Schedule…")
                    if (tapByText("My schedule")) {
                        val dwellMs = (1200L..3000L).random()
                        mainHandler.postDelayed({
                            if (!isLoopRunning) return@postDelayed
                            tapByText("Offers")
                            mainHandler.postDelayed({
                                if (!isLoopRunning) return@postDelayed
                                val status = readOffersStatus()
                                log("Loop: on Schedule — $status")
                                setOverlayLastAction("Schedule: $status…")
                                scanAllDatesFromFirst(filters) { scheduleNext(timings.loopShort) }
                            }, timings.offerTabSwitch)
                        }, dwellMs)
                        return
                    }
                    log("Loop: My Schedule tab not found — skipping detour this cycle")
                }

                val status = readOffersStatus()
                log("Loop: on Schedule — $status")
                setOverlayLastAction("Schedule: $status…")

                // HAS_OFFERS, EMPTY, or SEE_NEXT — scan all 7 dates, then loop back immediately
                scanAllDatesFromFirst(filters) { scheduleNext(timings.loopShort) }
            }

            // Post-claim order detail card — press Back, not a navigation failure
            ScreenState.POST_CLAIM -> {
                log("Loop: POST_CLAIM card — pressing Back to return to Schedule")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleNext(timings.loopMedium)
            }

            // Offer detail card open unexpectedly — dismiss and let loop retry
            ScreenState.OFFER_DETAIL -> {
                log("Loop: OFFER_DETAIL open unexpectedly — pressing Back to dismiss")
                performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleNext(1200)
            }

            // Error / no-connection — two kinds handled differently
            ScreenState.ERROR -> {
                val r2 = rootInActiveWindow
                val sb2 = StringBuilder()
                if (r2 != null) { collectText(r2, sb2); @Suppress("DEPRECATION") r2.recycle() }
                val errFlat = sb2.toString().lowercase()
                if (errFlat.contains("something went wrong")) {
                    log("Loop: ERROR 'Something went wrong' — pressing Back to dismiss")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    scheduleNext(2000)
                } else {
                    log("Loop: ERROR screen — tapping Try again / Retry")
                    tapByText("Try again") || tapByText("Retry")
                    scheduleNext(3000)
                }
            }

            // No root window yet — DoorDash's own transition/loading screens (network lag, or
            // the sequence it shows while a dash is starting up) have nothing to tap or
            // navigate from. Wait it out on its own budget instead of burning through
            // MAX_FAILURES trying (and failing) to find a hamburger menu that isn't there.
            ScreenState.LOADING -> {
                if (loadingSinceMs == 0L) loadingSinceMs = System.currentTimeMillis()
                val loadingMs = System.currentTimeMillis() - loadingSinceMs
                if (loadingMs > MAX_LOADING_MS) {
                    loadingSinceMs = 0L
                    stopAutoLoop()
                    val msg = "Bot paused — stuck on a loading screen for ${MAX_LOADING_MS / 1000}s. Open Dasher and tap ▶ Start Bot."
                    TelegramHelper.send(this, "⏸ BOT PAUSED\n\n$msg")
                    NotificationHelper.notifyBotPaused(this, msg)
                    showBanner("⏸ BOT PAUSED", "Tap ▶ Start Bot to resume", 0xFFE65100.toInt())
                    playTone(ToneType.ALERT)
                    setOverlayLastAction("⏸ $msg")
                    log("Loop: auto-stopped — LOADING persisted ${loadingMs / 1000}s")
                    return
                }
                log("Loop: LOADING — waiting (${loadingMs / 1000}s)")
                setOverlayLastAction("Loading…")
                scheduleNext(timings.loopMedium)
            }

            // A screen that matches none of our known signatures at all — could be a
            // real-time incoming-delivery-offer popup (~30s), a permissions dialog, a rating
            // prompt, anything. We don't know what's on it, so guessing at navigation (or
            // counting it as a fast failure) isn't safe. Wait it out like LOADING, just with
            // a shorter budget since a genuinely stuck/broken screen should still alert
            // reasonably promptly. The instant it resolves to a recognized state, normal
            // handling picks up on the very next cycle automatically.
            ScreenState.UNKNOWN -> {
                if (unknownSinceMs == 0L) unknownSinceMs = System.currentTimeMillis()
                val unknownMs = System.currentTimeMillis() - unknownSinceMs
                if (unknownMs > MAX_UNKNOWN_MS) {
                    unknownSinceMs = 0L
                    stopAutoLoop()
                    val msg = "Bot paused — stuck on an unrecognized screen for ${MAX_UNKNOWN_MS / 1000}s. Open Dasher and tap ▶ Start Bot."
                    TelegramHelper.send(this, "⏸ BOT PAUSED\n\n$msg")
                    NotificationHelper.notifyBotPaused(this, msg)
                    showBanner("⏸ BOT PAUSED", "Tap ▶ Start Bot to resume", 0xFFE65100.toInt())
                    playTone(ToneType.ALERT)
                    setOverlayLastAction("⏸ $msg")
                    log("Loop: auto-stopped — UNKNOWN persisted ${unknownMs / 1000}s")
                    return
                }
                log("Loop: UNKNOWN — waiting (${unknownMs / 1000}s), not guessing at navigation")
                setOverlayLastAction("Unrecognized screen — waiting…")
                scheduleNext(timings.loopMedium)
            }

            // Any other state (HOME, MENU, EARNINGS) —
            // mirrors Lua: return "needs_refresh" → navigate to Schedule with failure counting
            else -> {
                consecutiveFailures++
                log("Loop: not on Schedule ($currentScreen) — failure $consecutiveFailures/$MAX_FAILURES")
                setOverlayLastAction("Navigating to Schedule ($consecutiveFailures/$MAX_FAILURES)…")

                if (consecutiveFailures >= MAX_FAILURES) {
                    consecutiveFailures = 0
                    stopAutoLoop()
                    val msg = "Bot paused — couldn't navigate to Schedule. Open Dasher and tap ▶ Start Bot."
                    TelegramHelper.send(this, "⏸ BOT PAUSED\n\n$msg")
                    NotificationHelper.notifyBotPaused(this, msg)
                    showBanner("⏸ BOT PAUSED", "Tap ▶ Start Bot to resume", 0xFFE65100.toInt())
                    playTone(ToneType.ALERT)
                    setOverlayLastAction("⏸ $msg")
                    log("Loop: auto-stopped after $MAX_FAILURES failures")
                    return
                }

                navigateToSchedule { success ->
                    if (success) { consecutiveFailures = 0 }
                    scheduleNext(if (success) timings.loopShort else 3000L)
                }
            }
        }
    }

    private fun scheduleNext(delayMs: Long = 500) {
        if (!isLoopRunning) return
        mainHandler.postDelayed(loopCycleRunnable, delayMs)
        nextCycleAtMs = System.currentTimeMillis() + delayMs
        if (slowModeActive) startCountdownTicker() else stopCountdownTicker()
    }

    // ─── Slow Mode countdown badge ───────────────────────────────────────────
    private val countdownRunnable = Runnable { tickCountdown() }

    private fun startCountdownTicker() {
        mainHandler.removeCallbacks(countdownRunnable)
        tickCountdown()
    }

    private fun stopCountdownTicker() {
        mainHandler.removeCallbacks(countdownRunnable)
        overlayCountdown?.visibility = View.GONE
    }

    private fun tickCountdown() {
        if (!isLoopRunning || !slowModeActive) {
            overlayCountdown?.visibility = View.GONE
            return
        }
        val remainingSec = ((nextCycleAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val mins = remainingSec / 60
        val secs = remainingSec % 60
        overlayCountdown?.apply {
            visibility = View.VISIBLE
            text = "🐢 $mins:${secs.toString().padStart(2, '0')}"
        }
        // Re-tick every second until the cycle actually fires — scheduleNext() will overwrite
        // nextCycleAtMs and restart this loop for the next delay once it does.
        mainHandler.postDelayed(countdownRunnable, 1000)
    }

    // Scan all 7 date tabs starting from the leftmost — mirrors Lua's checkCurrentDate.
    // scanAndProcessDate taps dates[0] first, mirroring Lua: "navigate to leftmost date first".
    private fun scanAllDatesFromFirst(filters: FilterSettings, onDone: () -> Unit) {
        if (!isLoopRunning) { onDone(); return }

        // Always ensure the Offers sub-tab is active before scanning.
        // DoorDash switches to My Schedule after a claim, and the user can also switch manually.
        captureAndDetect()
        if (currentScreen == ScreenState.SCHEDULE_MY) {
            log("scanAllDates: My Schedule tab detected — switching to Offers first")
            if (tapByText("Offers")) {
                mainHandler.postDelayed({
                    if (!isLoopRunning) { onDone(); return@postDelayed }
                    scanAllDatesFromFirst(filters, onDone)
                }, timings.offerTabSwitch)
            } else {
                log("scanAllDates: Offers tab not found — aborting scan")
                onDone()
            }
            return
        }

        val root = rootInActiveWindow
        val dates = mutableListOf<Pair<String, Rect>>()
        if (root != null) { collectDateNodes(root, dates); @Suppress("DEPRECATION") root.recycle() }

        if (dates.isEmpty()) {
            log("Loop: no date tabs found — skipping scan")
            onDone()
            return
        }

        // Randomizing which date gets tapped first/next doesn't affect coverage — every date in
        // the list still gets tapped and checked by content-description each cycle regardless of
        // order — so this is safe to shuffle purely to vary the visible tap pattern cycle to cycle.
        val scanOrder = if (filters.slowMode && filters.slowRandomizeOrder) dates.shuffled() else dates
        val orderNote = if (scanOrder !== dates) " (shuffled)" else " from leftmost"
        log("Loop: scanning ${dates.size} dates$orderNote (${shortDateLabel(scanOrder[0].first)})")

        // Safety: if the first date (Today) should be skipped, immediately navigate to the first
        // valid date tab before the scan loop starts. Dasher defaults to Today on every navigation,
        // so without this tap the screen is showing Today's offers while the scan loop decides what
        // to do — creating a window where an accidental claim could fire on cycle 1. This check is
        // always against the natural leftmost tab (dates[0], the one actually showing on screen
        // right now) regardless of the randomized processing order above.
        val firstSkipped = dates.isNotEmpty() && !filters.shouldScanDay(dates[0].first)
        if (firstSkipped) {
            val firstValid = dates.drop(1).firstOrNull { (label, _) -> filters.shouldScanDay(label) }
            if (firstValid != null) {
                val (vLabel, vBounds) = firstValid
                val vCx = (vBounds.left + vBounds.right) / 2f
                val vCy = (vBounds.top + vBounds.bottom) / 2f
                log("Pre-scan: Today is active but skipped — immediately tapping ${shortDateLabel(vLabel)} to move off Today")
                if (!tapByDesc(vLabel)) tapAt(vCx, vCy)
                mainHandler.postDelayed({
                    if (!isLoopRunning) { onDone(); return@postDelayed }
                    scanAndProcessDate(scanOrder, 0, filters, false) { _ -> onDone() }
                }, 800)
                return
            }
        }

        scanAndProcessDate(scanOrder, 0, filters, false) { _ -> onDone() }
    }

    /**
     * Refresh offer data after exhausting all dates.
     * Presses Back to leave Schedule (forces server reload on next visit),
     * then taps the Schedule bottom-nav icon directly — avoids the hamburger
     * path entirely since the Schedule header's ⚙️/❓ buttons share the
     * 'Side Menu' content description with the hamburger.
     */
    private fun doRefresh(onDone: () -> Unit) {
        if (!isLoopRunning) { onDone(); return }
        log("Refresh: Back → Schedule bottom-nav tap")
        setOverlayLastAction("Refreshing…")

        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            if (!isLoopRunning) { onDone(); return@postDelayed }
            captureAndDetect()
            log("Refresh: after Back, state = $currentScreen")

            when (currentScreen) {
                ScreenState.HOME -> {
                    // Best case — tap the calendar icon directly
                    if (tapByDesc("dx.home_screen.schedule")) {
                        log("Refresh: tapped Schedule bottom-nav icon")
                        waitForScheduleScreen({ _ -> onDone() }, attemptsLeft = 6)
                    } else {
                        navigateToSchedule { _ -> onDone() }
                    }
                }
                ScreenState.SCHEDULE, ScreenState.SCHEDULE_MY -> {
                    // Back didn't leave Schedule — just continue
                    ensureOffersTab { _ -> onDone() }
                }
                else -> {
                    navigateToSchedule { _ -> onDone() }
                }
            }
        }, 2000) // 2s — let the home screen fully render before tapping
    }

    /**
     * Single-pass scan+process: tap each date tab, check for offers, process inline.
     * Never goes back — matches the Mac Hammerspoon approach exactly.
     */
    private fun scanAndProcessDate(
        dates: List<Pair<String, Rect>>,
        index: Int,
        filters: FilterSettings,
        claimedSoFar: Boolean,
        onAllDone: (claimedAny: Boolean) -> Unit
    ) {
        if (index >= dates.size || !isLoopRunning) { onAllDone(claimedSoFar); return }

        val (label, bounds) = dates[index]
        val shortLabel = shortDateLabel(label)

        // Skip dates not in the user's selected scan days
        if (!filters.shouldScanDay(label)) {
            log("  $shortLabel → skipped (not in scan days)")
            scanAndProcessDate(dates, index + 1, filters, claimedSoFar, onAllDone)
            return
        }

        // Always verify we're on the Offers tab BEFORE tapping the date.
        // DoorDash can switch to My Schedule at any time; other screen states mean we've
        // drifted off Schedule entirely and the main loop should re-navigate.
        captureAndDetect()
        when (currentScreen) {
            ScreenState.SCHEDULE_MY -> {
                log("$shortLabel: on My Schedule before tap — switching to Offers")
                if (tapByText("Offers")) {
                    mainHandler.postDelayed({
                        if (!isLoopRunning) { onAllDone(claimedSoFar); return@postDelayed }
                        scanAndProcessDate(dates, index, filters, claimedSoFar, onAllDone) // retry same date
                    }, 800)
                } else {
                    log("$shortLabel: Offers tap failed — aborting scan")
                    onAllDone(claimedSoFar)
                }
                return
            }
            ScreenState.SCHEDULE -> { /* correct tab — proceed */ }
            ScreenState.OFFER_DETAIL, ScreenState.POST_CLAIM -> {
                // A card is open — press Back to close it, then retry this date
                log("$shortLabel: card open ($currentScreen) — pressing Back to dismiss")
                performGlobalAction(GLOBAL_ACTION_BACK)
                mainHandler.postDelayed({
                    if (!isLoopRunning) { onAllDone(claimedSoFar); return@postDelayed }
                    scanAndProcessDate(dates, index, filters, claimedSoFar, onAllDone)
                }, 800)
                return
            }
            else -> {
                // Drifted off Schedule (HOME, ERROR, UNKNOWN, UPCOMING_ORDER, etc.)
                // Abort scan; main loop will detect state and re-navigate.
                log("$shortLabel: not on Schedule ($currentScreen) — aborting scan, main loop will re-navigate")
                onAllDone(claimedSoFar)
                return
            }
        }

        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f

        log("Scanning [${index + 1}/${dates.size}] $shortLabel")
        setOverlayLastAction("$shortLabel…")
        if (!tapByDesc(label)) tapAt(cx, cy)

        mainHandler.postDelayed({
            if (!isLoopRunning) { onAllDone(claimedSoFar); return@postDelayed }

            // After tapping a date, DoorDash can switch to My Schedule (e.g. a scheduled order
            // exists on that date). Detect and correct before reading offer status.
            captureAndDetect()
            if (currentScreen == ScreenState.SCHEDULE_MY) {
                log("  $shortLabel: date tap switched to My Schedule — switching back to Offers")
                if (tapByText("Offers")) {
                    mainHandler.postDelayed({
                        if (!isLoopRunning) { onAllDone(claimedSoFar); return@postDelayed }
                        if (!tapByDesc(label)) tapAt(cx, cy) // re-tap the date now on Offers tab
                        mainHandler.postDelayed({
                            if (!isLoopRunning) { onAllDone(claimedSoFar); return@postDelayed }
                            processDateContent(label, shortLabel, cx, cy, dates, index, filters, claimedSoFar, onAllDone)
                        }, 1200)
                    }, 700)
                } else {
                    log("  $shortLabel: Offers tap failed — skipping date")
                    scanAndProcessDate(dates, index + 1, filters, claimedSoFar, onAllDone)
                }
                return@postDelayed
            }

            processDateContent(label, shortLabel, cx, cy, dates, index, filters, claimedSoFar, onAllDone)
        }, timings.dateTabWait)
    }

    private fun processDateContent(
        label: String, shortLabel: String, cx: Float, cy: Float,
        dates: List<Pair<String, Rect>>, index: Int,
        filters: FilterSettings, claimedSoFar: Boolean,
        onAllDone: (claimedAny: Boolean) -> Unit
    ) {
        val status = readOffersStatus()
        log("  $shortLabel → $status")

        when (status) {
            OfferListStatus.HAS_OFFERS -> {
                setOverlayLastAction("$shortLabel: offers found!")
                val isToday   = label.startsWith("Today")
                val dow       = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                val isWeekend = dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY
                processOfferOnCurrentDate(isToday, isWeekend, label) { result ->
                    log("$shortLabel: $result")
                    if (result.startsWith("✅")) {
                        // Claim succeeded — stop scan; main loop handles My Schedule → Offers recovery
                        mainHandler.postDelayed({ onAllDone(true) }, 800)
                    } else {
                        mainHandler.postDelayed({
                            scanAndProcessDate(dates, index + 1, filters, claimedSoFar, onAllDone)
                        }, 800)
                    }
                }
            }
            else -> {
                scanAndProcessDate(dates, index + 1, filters, claimedSoFar, onAllDone)
            }
        }
    }

    private fun updateOverlayLoopState(running: Boolean) {
        mainHandler.post {
            overlayRoot?.apply {
                findViewById<android.widget.Button>(R.id.overlay_btn_start)?.apply {
                    text = if (running) "⏹ Stop Bot" else "▶ Start Bot"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (running) 0xFFB71C1C.toInt() else 0xFF1B5E20.toInt()
                    )
                }
                // Collapsed pill itself — green while running, default otherwise, so the
                // run state is visible at a glance without opening the panel.
                findViewById<View>(R.id.overlay_pill)?.setBackgroundResource(
                    if (running) R.drawable.overlay_pill_bg_running else R.drawable.overlay_pill_bg
                )
            }
            // Keep the screen awake for as long as the bot is "running" — including idle
            // ticks outside Active Hours. Once the phone actually locks, nothing (including
            // this accessibility service) can unlock it again, so a scheduled window starting
            // against a locked screen would silently never claim anything until a human
            // physically unlocks the phone. Simpler/safer to just never let it lock while the
            // bot is on, at the cost of battery during long gaps between scheduled windows.
            overlayParams?.let { p ->
                p.flags = if (running)
                    p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                else
                    p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                try { windowManager?.updateViewLayout(overlayRoot, p) } catch (_: Exception) {}
            }
        }
    }

    // ─── Overlay ──────────────────────────────────────────────────────────────

    fun createOverlay() {
        if (overlayRoot != null) { log("Overlay already exists"); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            log("Overlay permission not granted — skipping")
            return
        }
        log("Creating overlay…")
        try {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val root = LayoutInflater.from(this).inflate(R.layout.overlay_bot, null)
        overlayRoot = root
        overlayPanel = root.findViewById(R.id.overlay_panel)
        overlayStatus = root.findViewById(R.id.overlay_status)
        overlayLastAction = root.findViewById(R.id.overlay_last_action)
        overlayCountdown = root.findViewById(R.id.overlay_countdown)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0; y = 8
        }
        overlayParams = params

        // Drag the pill to reposition
        val pill = root.findViewById<View>(R.id.overlay_pill)
        var dragX = 0; var dragY = 0; var dragStartX = 0f; var dragStartY = 0f
        pill.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = params.x; dragY = params.y
                    dragStartX = event.rawX; dragStartY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartX).toInt()
                    val dy = (event.rawY - dragStartY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        params.x = dragX + dx
                        params.y = dragY + dy
                        wm.updateViewLayout(root, params)
                        true
                    } else false
                }
                else -> false
            }
        }

        // Tap pill: stop bot if running, otherwise toggle panel
        pill.setOnClickListener {
            if (isLoopRunning) {
                stopAutoLoop()
            } else {
                val panel = overlayPanel ?: return@setOnClickListener
                if (panel.visibility == View.GONE) {
                    panel.visibility = View.VISIBLE
                    updateOverlayStatus()
                    // If the pill's been dragged low, the panel opening downward can run off
                    // the bottom of the screen — nudge the whole overlay window back up just
                    // enough to fit once the panel's real height is known post-layout.
                    root.post {
                        val loc = IntArray(2)
                        root.getLocationOnScreen(loc)
                        val screenHeight = resources.displayMetrics.heightPixels
                        val overflow = (loc[1] + root.height) - screenHeight
                        if (overflow > 0) {
                            params.y = (params.y - overflow - 12).coerceAtLeast(0)
                            wm.updateViewLayout(root, params)
                        }
                    }
                } else {
                    panel.visibility = View.GONE
                }
            }
        }

        // Start / Stop bot button
        root.findViewById<Button>(R.id.overlay_btn_start).setOnClickListener {
            overlayPanel?.visibility = View.GONE
            if (isLoopRunning) stopAutoLoop() else startAutoLoop()
        }

        // Settings button
        root.findViewById<Button>(R.id.overlay_btn_settings).setOnClickListener {
            overlayPanel?.visibility = View.GONE
            val intent = android.content.Intent(this, SettingsActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // Close panel button
        root.findViewById<Button>(R.id.overlay_btn_close).setOnClickListener {
            overlayPanel?.visibility = View.GONE
        }

        wm.addView(root, params)
        log("Overlay created ✅")
        } catch (e: Exception) {
            log("Overlay creation failed: ${e.message}")
        }
    }

    /** Debug-only: draws a red-bordered, non-touchable box at absolute screen coordinates,
     *  on top of whatever app is currently in the foreground — lets a computed tap target's
     *  bounds be checked by eye against the real, live-rendered element underneath. */
    fun showDebugBox(x: Int, y: Int, w: Int, h: Int, label: String) {
        val wm = (getSystemService(WINDOW_SERVICE) as? WindowManager) ?: return
        hideDebugBox()
        try {
            val box = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(6, 0xFFFF0000.toInt())
                    setColor(0x00000000)
                }
            }
            if (label.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = label
                    setTextColor(0xFFFF0000.toInt())
                    textSize = 10f
                    setBackgroundColor(0xCCFFFFFF.toInt())
                })
            }
            val params = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            wm.addView(box, params)
            debugBoxRoot = box
            log("Debug box shown @ ($x,$y) ${w}x${h} '$label'")
        } catch (e: Exception) {
            log("Debug box failed: ${e.message}")
        }
    }

    fun hideDebugBox() {
        debugBoxRoot?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        debugBoxRoot = null
    }

    private fun showTouchLock() {
        val f = FilterSettings.load(this)
        if (!f.screenLock && !f.screenDim) return
        mainHandler.post {
            if (lockRoot != null) return@post
            if (!android.provider.Settings.canDrawOverlays(this)) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            val label = android.widget.TextView(this).apply {
                text = when {
                    f.screenLock -> "🔒 Bot running"
                    f.screenDim  -> "Hold 2s to un-dim"
                    else         -> ""
                }
                textSize = 16f
                setTextColor(0xBBFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                val pad = (resources.displayMetrics.heightPixels * 0.15f).toInt()
                setPadding(0, 0, 0, pad)
            }
            val root = android.widget.FrameLayout(this)
            root.setBackgroundColor(0x22000000)
            root.addView(label, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val dm = resources.displayMetrics
            val params = WindowManager.LayoutParams(
                dm.widthPixels,
                dm.heightPixels,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
                if (f.screenDim) screenBrightness = 0.03f
            }

            if (f.screenLock) {
                // Long-press (2s) anywhere = stop the bot
                root.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            label.text = "🔒 Hold 2s to stop bot"
                            lockLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            val r = Runnable {
                                lockLongPressRunnable = null
                                stopAutoLoop()
                            }
                            lockLongPressRunnable = r
                            mainHandler.postDelayed(r, 2000)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            lockLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            lockLongPressRunnable = null
                            mainHandler.postDelayed({ label.text = "🔒 Bot running" }, 150)
                        }
                    }
                    true
                }
            } else if (f.screenDim) {
                // Dim-only: long-press (2s) to un-dim — bot keeps running
                root.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            label.text = "Hold 2s to un-dim..."
                            lockLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            val r = Runnable {
                                lockLongPressRunnable = null
                                hideTouchLock()
                            }
                            lockLongPressRunnable = r
                            mainHandler.postDelayed(r, 2000)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            lockLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            lockLongPressRunnable = null
                            mainHandler.postDelayed({ label.text = "Hold 2s to un-dim" }, 150)
                        }
                    }
                    true
                }
            }

            wm.addView(root, params)
            lockRoot = root
            log("Touch lock: enabled (lock=${f.screenLock}, dim=${f.screenDim})")
        }
    }

    private fun hideTouchLock() {
        mainHandler.post {
            lockLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
            lockLongPressRunnable = null
            lockRoot?.let {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
            }
            lockRoot = null
        }
    }

    // ─── Notification banner ──────────────────────────────────────────────────

    private fun showBanner(title: String, subtitle: String, bgColor: Int) {
        mainHandler.post {
            try {
                mainHandler.removeCallbacks(notifDismissRunnable)
                notifBannerRoot?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
                notifBannerRoot = null

                if (!android.provider.Settings.canDrawOverlays(this)) return@post
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val root = LayoutInflater.from(this).inflate(R.layout.overlay_notification, null)

                val bg = android.graphics.drawable.GradientDrawable()
                bg.setColor(bgColor)
                bg.cornerRadius = 14 * resources.displayMetrics.density
                root.background = bg

                root.findViewById<TextView>(R.id.notif_title).text = title
                root.findViewById<TextView>(R.id.notif_subtitle).text = subtitle

                val dm = resources.displayMetrics
                val margin = (16 * dm.density).toInt()
                val params = WindowManager.LayoutParams(
                    dm.widthPixels - margin * 2,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (24 * dm.density).toInt()
                }

                wm.addView(root, params)
                notifBannerRoot = root

                root.translationY = 180f
                root.animate().translationY(0f).setDuration(280).start()
                mainHandler.postDelayed(notifDismissRunnable, 6000)
            } catch (e: Exception) {
                log("Banner error: ${e.message}")
            }
        }
    }

    private fun dismissBanner() {
        val root = notifBannerRoot ?: return
        root.animate().translationY(180f).setDuration(250).withEndAction {
            try { windowManager?.removeView(root) } catch (_: Exception) {}
            if (notifBannerRoot === root) notifBannerRoot = null
        }.start()
    }

    private fun playTone(type: ToneType) {
        if (!FilterSettings.load(this).soundEnabled) return
        when (type) {
            ToneType.CLAIMED, ToneType.SKIP -> {
                val resId = if (type == ToneType.CLAIMED) R.raw.sound_claimed else R.raw.sound_skip
                mainHandler.post {
                    try {
                        val mp = android.media.MediaPlayer.create(this, resId) ?: return@post
                        mp.setOnCompletionListener { it.release() }
                        mp.start()
                    } catch (_: Exception) {}
                }
            }
            ToneType.ALERT -> Thread {
                try {
                    val gen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 75)
                    gen.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 300)
                    Thread.sleep(400)
                    gen.release()
                } catch (_: Exception) {}
            }.start()
        }
    }

    private fun removeOverlay() {
        overlayRoot?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayRoot = null
        overlayPanel = null
        overlayStatus = null
        overlayLastAction = null
        overlayCountdown = null
    }

    fun updateOverlayStatus() {
        mainHandler.post {
            overlayStatus?.text = "Screen: $currentScreen"
        }
    }

    private fun setOverlayLastAction(msg: String) {
        log(msg)
        mainHandler.post { overlayLastAction?.text = msg }
    }

    // ─── Logging ──────────────────────────────────────────────────────────────

    fun log(msg: String) {
        val line = "[${timeFormat.format(Date())}] $msg"
        Log.d(TAG, line)
        addLog(line)
        appendBotLog(line)
    }

    private fun writeToLogFile(content: String) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val file = File(dir, "ui_dump.log")
            file.appendText(content)
            if (file.length() > 2 * 1024 * 1024) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(lines.size / 2).joinToString("\n"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Log write failed: ${e.message}")
        }
    }

    fun getLogFilePath(): String? =
        getExternalFilesDir(null)?.absolutePath?.let { "$it/ui_dump.log" }
}
