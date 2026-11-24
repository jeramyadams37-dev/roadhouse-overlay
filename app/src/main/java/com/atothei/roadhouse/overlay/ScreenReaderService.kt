package com.atothei.roadhouse.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that keeps a live copy of the foreground app's
 * screen text — same node-tree walk as the proven AI Overlay APK shell.
 * It only READS. It never clicks, types, or acts in any app.
 * Latest text + foreground package are exposed on the companion object for
 * the overlay service, voice handler, and JS bridge to read.
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        @Volatile
        var currentScreenText: String = ""
            private set

        @Volatile
        var foregroundPackage: String = ""
            private set

        @Volatile
        var lastUpdatedMs: Long = 0L
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val MAX_CHARS = 20000
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 500
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.toString()?.let { foregroundPackage = it }
        refreshText()
    }

    private fun refreshText() {
        val sb = StringBuilder()
        val seen = LinkedHashSet<String>()
        try {
            val windows = windows ?: return
            for (window in windows) {
                val root = window?.root ?: continue
                try {
                    extractText(root, sb, seen)
                } finally {
                    try {
                        root.recycle()
                    } catch (e: Exception) {
                        // Best effort; a stale node must not kill the service.
                    }
                }
            }
        } catch (e: Exception) {
            // Keep the last good text; never crash the service.
        }
        if (sb.isNotEmpty()) {
            currentScreenText = sb.toString().take(MAX_CHARS)
            lastUpdatedMs = System.currentTimeMillis()
        }
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
        super.onDestroy()
        isRunning = false
    }
}
