package com.atothei.roadhouse.overlay

import java.util.Locale

/** Verdict from the in-code $/mile math. The model never decides this. */
enum class Verdict { TAKE, SKIP, UNKNOWN }

data class OfferResult(
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val address: String?,
    val verdict: Verdict,
    val floor: Double,
    val note: String
)

/**
 * Parses pay, miles and a delivery address out of raw screen text.
 * All math happens here, in code — never by the model — so the verdict is
 * exact and auditable. If the screen is ambiguous, the result says so
 * instead of guessing.
 */
object OfferParser {

    private val moneyRegex =
        Regex("""\$(\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")
    private val milesRegex =
        Regex("""(\d+(?:\.\d+)?)\s*(?:mi|miles)\b""", RegexOption.IGNORE_CASE)
    private val payLabelRegex =
        Regex("""(total|pay|earn|payout|guaranteed|estimated)""", RegexOption.IGNORE_CASE)
    private val milesLabelRegex =
        Regex("""(total|distance|trip|drive)""", RegexOption.IGNORE_CASE)

    private val streetSuffix =
        """(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Way|Place|Pl|Highway|Hwy|Circle|Cir|Terrace|Ter|Parkway|Pkwy|Trail|Trl|Loop|Square|Sq)"""
    // House number must be followed by a capitalised word (street names are
    // capitalised; "32 min" or "5 miles" are not addresses). City is capped
    // at 3 words so the match cannot swallow the rest of the screen.
    private val addressRegex = Regex(
        """\b\d{1,6}\s+(?=[A-Z0-9])(?:[A-Za-z0-9.'-]+\s+){0,4}?$streetSuffix\b(?:\s*,?\s*[A-Za-z][A-Za-z.'-]*(?:\s+[A-Za-z][A-Za-z.'-]*){0,2})?(?:\s*,?\s*[A-Z]{2}\s*\d{5}(?:-\d{4})?)?"""
    )

    fun check(screenText: String, floor: Double): OfferResult {
        val pay = parsePay(screenText)
        val miles = parseMiles(screenText)
        val perMile = if (pay != null && miles != null && miles > 0) pay / miles else null
        val address = parseAddress(screenText.replace('\n', ' '))
        val verdict = when {
            perMile == null -> Verdict.UNKNOWN
            perMile >= floor -> Verdict.TAKE
            else -> Verdict.SKIP
        }
        val note = when {
            pay == null && miles == null ->
                "I can't see a pay amount or miles on this screen. Open the offer first, then check again."
            pay == null -> "I can see the miles but not the pay on this screen."
            miles == null -> "I can see the pay but not the miles on this screen."
            else -> ""
        }
        return OfferResult(pay, miles, perMile, address, verdict, floor, note)
    }

    /** Spoken verdict — one short sentence, safe to read aloud while driving. */
    fun speakText(result: OfferResult, platform: String): String {
        if (result.perMile == null) return result.note
        val payStr = "$%.2f".format(Locale.US, result.pay)
        val milesStr = "%.1f".format(Locale.US, result.miles)
        val perMileStr = "$%.2f".format(Locale.US, result.perMile)
        val floorStr = "$%.2f".format(Locale.US, result.floor)
        return when (result.verdict) {
            Verdict.TAKE ->
                "$platform offer: $payStr for $milesStr miles, $perMileStr a mile. Above your $floorStr floor. Take it."
            Verdict.SKIP ->
                "$platform offer: $payStr for $milesStr miles, $perMileStr a mile. Below your $floorStr floor. Skip it."
            Verdict.UNKNOWN -> result.note
        }
    }

    /**
     * Pay = the largest labelled amount on a line mentioning total/pay/earn/
     * payout/guaranteed/estimated, else the largest dollar amount on screen.
     * Lines like "Earn up to $100" are promos, not the offer — skipped.
     */
    fun parsePay(text: String): Double? {
        val labeled = mutableListOf<Double>()
        val all = mutableListOf<Double>()
        for (line in text.lines()) {
            if (line.contains("up to", ignoreCase = true)) continue
            if (line.contains("was", ignoreCase = true)) continue
            for (m in moneyRegex.findAll(line)) {
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
                if (v <= 0.0 || v > 500.0) continue
                all.add(v)
                if (payLabelRegex.containsMatchIn(line)) labeled.add(v)
            }
        }
        return labeled.maxOrNull() ?: all.maxOrNull()
    }

    /** Miles = the amount on a line mentioning total/distance/trip/drive, else the first miles figure. */
    fun parseMiles(text: String): Double? {
        var first: Double? = null
        for (line in text.lines()) {
            val m = milesRegex.find(line) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > 500.0) continue
            if (first == null) first = v
            if (milesLabelRegex.containsMatchIn(line)) return v
        }
        return first
    }

    /** First US street address found in the text, or null. */
    fun parseAddress(text: String): String? {
        val m = addressRegex.find(text) ?: return null
        return m.value.trim().trimEnd(',', ' ').replace(Regex("\\s+"), " ")
    }
}
