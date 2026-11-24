package com.atothei.roadhouse.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Accessibility service that reads the foreground app's screen text
 * ON DEMAND — only when the driver asks (tap Ask, voice command,
 * long-press offer check). It never polls, never runs a timer, and never
 * walks the node tree on accessibility events: the service stays passive
 * until asked, so it costs no battery, data, or Gemini calls in use.
 *
 * One read per request. A read taken within the last few seconds is reused
 * for an immediate follow-up instead of walking the tree again (and a
 * follow-up Gemini call still only happens because the driver asked).
 * Anything older is re-read on demand; a failed read returns a plain
 * failure instead of stale text.
 *
 * SystemUI (notification shade / status bar) and the overlay's own windows
 * are always skipped — a read only ever sees the foreground app's window.
 * It only READS. It never clicks, types, or acts in any app.
 */
class ScreenReaderService : AccessibilityService() {

    /** Outcome of an on-demand read. */
    enum class ReadStatus {
        OK,
        SERVICE_NOT_RUNNING,
        SHADE_DOWN,
        NO_APP_WINDOW,
        NO_TEXT
    }

    data class ScreenRead(
        val status: ReadStatus,
        val text: String,
        val packageName: String,
        val readAtMs: Long
    ) {
        val isOk: Boolean get() = status == ReadStatus.OK

        /** Honest age label for the answer card: "read just now" / "read 4s ago". */
        fun ageLabel(): String {
            val ageSec = ((System.currentTimeMillis() - readAtMs) / 1000).toInt()
            return if (ageSec < 2) "read just now" else "read ${ageSec}s ago"
        }

        /** Plain-language message for a failed read. Never answers from old text. */
        fun failureMessage(): String = when (status) {
            ReadStatus.SERVICE_NOT_RUNNING ->
                "The screen reader isn't running. Open RoadHouse Overlay and turn on the accessibility service."
            ReadStatus.SHADE_DOWN ->
                "I can't see the app right now — close the notification shade and ask again."
            ReadStatus.NO_APP_WINDOW ->
                "I can't see any app screen right now. Open your delivery app first, then ask again."
            ReadStatus.NO_TEXT ->
                "I can see the app but there's no readable text on this screen right now."
            ReadStatus.OK -> ""
        }
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val OWN_PACKAGE = "com.atothei.roadhouse.overlay"
        private const val MAX_CHARS = 20000

        /**
         * A read this young may be reused for an immediate follow-up
         * instead of walking the tree again. One read per request;
         * anything older is re-read on demand.
         */
        private const val FRESH_WINDOW_MS = 5000L

        @Volatile
        private var instance: ScreenReaderService? = null

        /** Last on-demand read, if any. Updated only when a read is taken. */
        @Volatile
        var currentScreenText: String = ""
            private set

        /** Package the last on-demand read came from. */
        @Volatile
        var foregroundPackage: String = ""
            private set

        @Volatile
        var lastUpdatedMs: Long = 0L
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * The read for a user request. Reuses a read taken within the last
         * few seconds (immediate follow-up); otherwise walks the tree now.
         * Never returns stale text: a read older than FRESH_WINDOW_MS is
         * always replaced by a fresh walk, and a failed walk returns a
         * failure status instead of old text.
         */
        fun readFresh(): ScreenRead {
            val now = System.currentTimeMillis()
            if (currentScreenText.isNotEmpty() && now - lastUpdatedMs < FRESH_WINDOW_MS) {
                return ScreenRead(ReadStatus.OK, currentScreenText, foregroundPackage, lastUpdatedMs)
            }
            return instance?.readNow()
                ?: ScreenRead(ReadStatus.SERVICE_NOT_RUNNING, "", foregroundPackage, now)
        }

        private fun isSkippedPackage(pkg: String?): Boolean =
            pkg == null || pkg == SYSTEM_UI_PACKAGE || pkg == OWN_PACKAGE
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        val info = serviceInfo ?: AccessibilityServiceInfo()
        // Only window-state changes: enough to know which app is in front.
        // Content/scroll/text events are deliberately NOT subscribed — the
        // service must not wake up (or walk the tree) for every screen
        // change. It reads only when the driver asks.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 500
        serviceInfo = info
    }

    /**
     * Event handler: tracks the foreground package only. No node-tree walk
     * happens here — reading is strictly on demand (see readFresh()).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!isSkippedPackage(pkg)) foregroundPackage = pkg
    }

    /**
     * Walks the foreground app's window right now. Called only from
     * readFresh(), i.e. only for a user request. getWindows() is a binder
     * call, safe from any thread; node reads below are IPC-backed too.
     */
    private fun readNow(): ScreenRead {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        val seen = LinkedHashSet<String>()
        var appPackage = ""
        var systemUiActive = false
        var focusedAppRoot: AccessibilityNodeInfo? = null
        var firstAppRoot: AccessibilityNodeInfo? = null
        try {
            val windows = windows
                ?: return ScreenRead(ReadStatus.NO_APP_WINDOW, "", foregroundPackage, now)
            for (window in windows) {
                if (window == null) continue
                val root = try {
                    window.root
                } catch (e: Exception) {
                    null
                }
                val pkg = root?.packageName?.toString()
                // Shade / status bar pulled down or being touched: say so
                // plainly instead of reading the app underneath it.
                if (pkg == SYSTEM_UI_PACKAGE && (window.isFocused || window.isActive)) {
                    systemUiActive = true
                }
                // Only the foreground app's own window: no SystemUI, no
                // input method, no overlay — and never our own card/button.
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    isSkippedPackage(pkg)
                ) {
                    try {
                        root?.recycle()
                    } catch (e: Exception) {
                    }
                    continue
                }
                if (firstAppRoot == null) {
                    firstAppRoot = root
                    appPackage = pkg ?: ""
                }
                if (window.isFocused && focusedAppRoot == null) {
                    focusedAppRoot = root
                    appPackage = pkg ?: ""
                }
                if (focusedAppRoot != null && firstAppRoot != null) break
                // An extra app window we do not need (split screen / PiP):
                // recycle its root now instead of leaking it.
                if (root !== firstAppRoot && root !== focusedAppRoot) {
                    try {
                        root?.recycle()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            return ScreenRead(ReadStatus.NO_APP_WINDOW, "", foregroundPackage, now)
        }
        if (systemUiActive) {
            try {
                focusedAppRoot?.recycle()
            } catch (e: Exception) {
            }
            if (focusedAppRoot !== firstAppRoot) {
                try {
                    firstAppRoot?.recycle()
                } catch (e: Exception) {
                }
            }
            return ScreenRead(ReadStatus.SHADE_DOWN, "", foregroundPackage, now)
        }
        // Prefer the focused app window; fall back to the topmost app window.
        val root = focusedAppRoot ?: firstAppRoot
        if (root == null || appPackage.isEmpty()) {
            return ScreenRead(ReadStatus.NO_APP_WINDOW, "", foregroundPackage, now)
        }
        try {
            extractText(root, sb, seen)
        } finally {
            try {
                root.recycle()
            } catch (e: Exception) {
                // Best effort; a stale node must not kill the service.
            }
            // Only when both roots exist and differ is there a second node
            // to recycle; otherwise root above was already the first one.
            if (focusedAppRoot != null && focusedAppRoot !== firstAppRoot &&
                firstAppRoot != null
            ) {
                try {
                    firstAppRoot.recycle()
                } catch (e: Exception) {
                }
            }
        }
        val text = sb.toString().take(MAX_CHARS)
        if (text.isBlank()) {
            return ScreenRead(ReadStatus.NO_TEXT, "", appPackage, now)
        }
        currentScreenText = text
        foregroundPackage = appPackage
        lastUpdatedMs = now
        return ScreenRead(ReadStatus.OK, text, appPackage, now)
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder, seen: LinkedHashSet<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val line = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }
        if (line.isNotEmpty() && seen.add(line)) {
            sb.append(line).append('\n')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                extractText(child, sb, seen)
            } finally {
                try {
                    child?.recycle()
                } catch (e: Exception) {
                    // Best effort.
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRunning = false
    }
}
