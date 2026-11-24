package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Routes a heard voice command to the right action.
 * Offer verdicts come from OfferParser (code math vs the $/mile floor);
 * open questions go to Gemini with the current screen text. Nothing here
 * ever taps, accepts, or declines anything in a delivery app — the overlay
 * advises, the driver decides.
 */
class VoiceCommandHandler(private val context: Context) {

    interface Output {
        fun speak(text: String)
        fun showCard(title: String, body: String, actions: List<String>)
        fun hideCard()
        fun navigateTo(address: String)
    }

    var output: Output? = null

    fun handle(command: String) {
        // One fresh read per request. A read taken within the last few
        // seconds is reused for an immediate follow-up; anything older is
        // re-read now. A failed read answers plainly — never from old text.
        val read = ScreenReaderService.readFresh()
        if (!read.isOk) {
            val msg = read.failureMessage()
            output?.speak(msg)
            output?.showCard("Can't see the screen", msg, listOf("hide"))
            return
        }
        val screenText = read.text
        val platform = Platforms.displayName(read.packageName)
        val lower = command.lowercase()

        when {
            isNavigate(lower) -> handleNavigate(screenText)
            isLog(lower) -> handleLog(screenText, platform, read)
            isOfferCheck(lower) -> handleOfferCheck(screenText, platform, read)
            else -> handleAsk(command, screenText, platform, read)
        }
    }

    /** "Read just now · Spark" — honest about how fresh the screen was. */
    private fun readFooter(read: ScreenReaderService.ScreenRead, platform: String): String =
        "\n\n${read.ageLabel().replaceFirstChar { it.uppercaseChar() }} · $platform"

    private fun isNavigate(lower: String) =
        lower.contains("navigate") || lower.contains("directions") ||
            lower.contains("take me") || lower.contains("drive to")

    private fun isLog(lower: String) =
        lower.contains("log") || lower.contains("save this") ||
            lower.contains("record this") || lower.contains("record delivery")

    private fun isOfferCheck(lower: String) =
        lower.contains("check") || lower.contains("offer") || lower.contains("mile") ||
            lower.contains("worth") || lower.contains("how much") || lower.contains("pay")

    private fun handleNavigate(screenText: String) {
        val address = OfferParser.parseAddress(screenText.replace('\n', ' '))
        if (address.isNullOrBlank()) {
            val msg = "I can't find an address on this screen. Open the order details first."
            output?.speak(msg)
            output?.showCard("Navigate", msg, listOf("hide"))
            return
        }
        output?.speak("Navigating to $address.")
        output?.navigateTo(address)
    }

    private fun handleLog(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val result = OfferParser.check(screenText, Prefs.getFloor(context))
        val (date, time) = DeliveryLog.nowParts()
        val ok = DeliveryLog.append(
            context,
            DeliveryEntry(date, time, platform, result.pay, result.miles, result.perMile, result.address)
        )
        val msg = if (ok) {
            val payPart = if (result.pay != null) "$%.2f".format(result.pay) else "unknown pay"
            val milesPart = if (result.miles != null) "%.1f miles".format(result.miles) else "unknown miles"
            "Logged. $platform, $payPart, $milesPart."
        } else {
            "I couldn't save that delivery. Try again."
        }
        output?.speak(msg)
        output?.showCard("Delivery logged", msg + readFooter(read, platform), listOf("hide"))
    }

    private fun handleOfferCheck(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val result = OfferParser.check(screenText, Prefs.getFloor(context))
        val spoken = OfferParser.speakText(result, platform)
        output?.speak(spoken)
        val body = buildString {
            if (result.pay != null) append("Pay: $%.2f\n".format(result.pay))
            if (result.miles != null) append("Miles: %.1f\n".format(result.miles))
            if (result.perMile != null) append("Per mile: $%.2f\n".format(result.perMile))
            append("Floor: $%.2f / mile\n".format(result.floor))
            if (result.address != null) append("Address: ${result.address}\n")
            append("\n$spoken")
            append(readFooter(read, platform))
        }
        output?.showCard("Offer check — $platform", body.trim(), listOf("navigate", "log", "hide"))
    }

    private fun handleAsk(
        question: String,
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val key = Prefs.getApiKey(context)
        if (key.isBlank()) {
            val msg = "No Gemini key set yet. Open Settings in the RoadHouse Overlay app and paste your key."
            output?.speak(msg)
            output?.showCard("Gemini key needed", msg, listOf("hide"))
            return
        }
        output?.showCard("Thinking…", "Asking Gemini about this screen…", listOf("hide"))
        GeminiClient.ask(key, question, screenText, object : GeminiClient.Callback {
            override fun onResult(text: String) {
                output?.speak(text)
                output?.showCard("RoadHouse", text + readFooter(read, platform), listOf("hide"))
            }

            override fun onError(message: String) {
                output?.speak(message)
                output?.showCard("Gemini error", message, listOf("hide"))
            }
        })
    }

    /** Opens Google Maps navigation; falls back to the Maps website. */
    fun openMaps(address: String) {
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(address))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.apps.maps")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(address))
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(web)
        }
    }
}
