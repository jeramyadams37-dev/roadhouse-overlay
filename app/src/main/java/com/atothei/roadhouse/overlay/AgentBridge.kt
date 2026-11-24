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

    @JavascriptInterface
    fun getScreenContext(): String = ScreenReaderService.currentScreenText

    @JavascriptInterface
    fun getForegroundApp(): String =
        Platforms.displayName(ScreenReaderService.foregroundPackage)

    @JavascriptInterface
    fun isDeliveryApp(): Boolean =
        Platforms.isDeliveryApp(ScreenReaderService.foregroundPackage)

    @JavascriptInterface
    fun getFloor(): Double = Prefs.getFloor(service)

    @JavascriptInterface
    fun isVoiceEnabled(): Boolean = Prefs.isVoiceEnabled(service)

    /** Offer check with the $/mile math done in code. Returns JSON. */
    @JavascriptInterface
    fun checkOffer(): String {
        val screenText = ScreenReaderService.currentScreenText
        val platform = Platforms.displayName(ScreenReaderService.foregroundPackage)
        val result = OfferParser.check(screenText, Prefs.getFloor(service))
        return JSONObject().apply {
            put("platform", platform)
            put("pay", result.pay ?: JSONObject.NULL)
            put("miles", result.miles ?: JSONObject.NULL)
            put("perMile", result.perMile ?: JSONObject.NULL)
            put("floor", result.floor)
            put("address", result.address ?: JSONObject.NULL)
            put("verdict", result.verdict.name)
            put("spoken", OfferParser.speakText(result, platform))
            put("note", result.note)
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
            OfferParser.parseAddress(ScreenReaderService.currentScreenText.replace('\n', ' '))
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
        val screenText = ScreenReaderService.currentScreenText
        val platform = Platforms.displayName(ScreenReaderService.foregroundPackage)
        val result = OfferParser.check(screenText, Prefs.getFloor(service))
        val (date, time) = DeliveryLog.nowParts()
        val ok = DeliveryLog.append(
            service,
            DeliveryEntry(date, time, platform, result.pay, result.miles, result.perMile, result.address)
        )
        return JSONObject().apply {
            put("ok", ok)
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
