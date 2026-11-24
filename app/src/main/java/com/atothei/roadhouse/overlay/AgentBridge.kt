package com.atothei.roadhouse.overlay

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS bridge for the overlay card WebView (window.RoadHouse).
 * Same shape as the proven AI Overlay APK bridge (getScreenContext,
 * launchApp-style helpers), extended for the driver use case: offer checks
 * computed in code, Gemini questions, navigation, delivery logging.
 */
class AgentBridge(private val service: OverlayService) {

    /** Fresh read for this request (a few-second-old read is reused). */
    private fun freshPackage(): String {
        val read = ScreenReaderService.readFresh()
        return if (read.isOk) read.packageName else ScreenReaderService.foregroundPackage
    }

    @JavascriptInterface
    fun getScreenContext(): String {
        val read = ScreenReaderService.readFresh()
        return if (read.isOk) read.text else ""
    }

    @JavascriptInterface
    fun getForegroundApp(): String = Platforms.displayName(freshPackage())

    @JavascriptInterface
    fun isDeliveryApp(): Boolean = Platforms.isDeliveryApp(freshPackage())

    @JavascriptInterface
    fun getFloor(): Double = Prefs.getFloor(service)

    @JavascriptInterface
    fun isVoiceEnabled(): Boolean = Prefs.isVoiceEnabled(service)

    /**
     * Offer check with the $/mile math done in code. Returns JSON with
     * "ok": true on success, or "ok": false + "error" when the screen
     * could not be read right now — never a verdict from stale text.
     */
    @JavascriptInterface
    fun checkOffer(): String {
        val read = ScreenReaderService.readFresh()
        val floor = Prefs.getFloor(service)
        if (!read.isOk) {
            return JSONObject().apply {
                put("ok", false)
                put("error", read.failureMessage())
                put("platform", Platforms.displayName(ScreenReaderService.foregroundPackage))
                put("pay", JSONObject.NULL)
                put("miles", JSONObject.NULL)
                put("perMile", JSONObject.NULL)
                put("floor", floor)
                put("address", JSONObject.NULL)
                put("verdict", Verdict.UNKNOWN.name)
                put("spoken", read.failureMessage())
                put("note", read.failureMessage())
                put("readAge", read.ageLabel())
            }.toString()
        }
        val platform = Platforms.displayName(read.packageName)
        val result = OfferParser.check(read.text, floor)
        return JSONObject().apply {
            put("ok", true)
            put("platform", platform)
            put("pay", result.pay ?: JSONObject.NULL)
            put("miles", result.miles ?: JSONObject.NULL)
            put("perMile", result.perMile ?: JSONObject.NULL)
            put("floor", result.floor)
            put("address", result.address ?: JSONObject.NULL)
            put("verdict", result.verdict.name)
            put("spoken", OfferParser.speakText(result, platform))
            put("note", result.note)
            put("readAge", read.ageLabel())
        }.toString()
    }

    @JavascriptInterface
    fun askGemini(question: String) {
        service.askGeminiFromCard(question)
    }

    @JavascriptInterface
    fun startListening() {
        service.startListening()
    }

    @JavascriptInterface
    fun speak(text: String) {
        service.speak(text)
    }

    @JavascriptInterface
    fun navigate(address: String) {
        val target = address.ifBlank {
            val read = ScreenReaderService.readFresh()
            if (!read.isOk) {
                service.showCard(
                    "Can't see the screen",
                    read.failureMessage(),
                    listOf("hide")
                )
                return
            }
            OfferParser.parseAddress(read.text.replace('\n', ' '))
        } ?: ""
        if (target.isBlank()) {
            service.showCard(
                "Navigate",
                "I can't find an address on this screen.",
                listOf("hide")
            )
        } else {
            service.navigateTo(target)
        }
    }

    /** Logs the current screen's delivery. Returns {"ok":bool,"count":n}. */
    @JavascriptInterface
    fun logDelivery(): String {
        val read = ScreenReaderService.readFresh()
        if (!read.isOk) {
            return JSONObject().apply {
                put("ok", false)
                put("error", read.failureMessage())
                put("count", DeliveryLog.count(service))
            }.toString()
        }
        val platform = Platforms.displayName(read.packageName)
        val result = OfferParser.check(read.text, Prefs.getFloor(service))
        val (date, time) = DeliveryLog.nowParts()
        val ok = DeliveryLog.append(
            service,
            DeliveryEntry(date, time, platform, result.pay, result.miles, result.perMile, result.address)
        )
        return JSONObject().apply {
            put("ok", ok)
            put("error", if (ok) JSONObject.NULL else "Could not save that delivery. Try again.")
            put("count", DeliveryLog.count(service))
        }.toString()
    }

    @JavascriptInterface
    fun hideCard() {
        service.hideCard()
    }

    @JavascriptInterface
    fun openSettings() {
        service.openSettings()
    }
}
