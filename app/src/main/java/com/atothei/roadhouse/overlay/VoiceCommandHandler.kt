package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Routes a heard voice command to the right action.
 * Offer verdicts come from OfferParser (code math vs the $/mile floor);
 * earnings tallies come from Earnings (code math on the delivery log);
 * open questions go to Gemini with the current screen text. Nothing here
 * ever taps, accepts, or declines anything in a delivery app — the overlay
 * advises, the driver decides.
 *
 * Routing order matters: outcome recording, earnings, and pre-shift need
 * NO screen read, so they are routed before any readFresh() call. Only
 * navigate / log / offer check / notes / Gemini questions read the screen,
 * one fresh read per request, on demand.
 */
class VoiceCommandHandler(private val context: Context) {

    interface Output {
        fun speak(text: String)
        /** Speak a question, then start listening when the speech finishes. */
        fun speakAndListen(text: String)
        fun showCard(title: String, body: String, actions: List<String>)
        fun hideCard()
        fun navigateTo(address: String)
    }

    var output: Output? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The most recent offer check (voice or card) — for "took it" / "skipped it". */
    @Volatile
    private var lastChecked: OfferMemoryRecord? = null

    /** Pre-shift conversation state; null when no check is running. */
    private var preShift: PreShiftFlow? = null

    private data class PreShiftFlow(
        var step: Int, // 0 vehicle, 1 fuel, 2 apps
        var vehicleOk: Boolean?,
        var fuel: String?,
        var apps: List<String>,
        var retries: Int
    )

    fun handle(command: String) {
        val lower = command.lowercase()

        // A pre-shift check in progress owns every answer until it ends.
        if (preShift != null) {
            handlePreShiftAnswer(command, lower)
            return
        }
        // These need no screen read — route them before readFresh().
        if (isOutcome(lower)) {
            recordOutcome(if (isTook(lower)) OfferOutcome.TOOK else OfferOutcome.SKIPPED)
            return
        }
        if (isEarnings(lower)) {
            handleEarnings(lower)
            return
        }
        if (isPreShiftStart(lower)) {
            startPreShift()
            return
        }

        // Everything below reads the screen, one fresh read per request.
        val read = ScreenReaderService.readFresh()
        if (!read.isOk) {
            val msg = read.failureMessage()
            output?.speak(msg)
            output?.showCard("Can't see the screen", msg, listOf("hide"))
            return
        }
        val screenText = read.text
        val platform = Platforms.displayName(read.packageName)

        when {
            isNoteAdd(lower) -> handleAddNote(command, screenText, platform)
            isNoteRead(lower) -> handleReadNotes(screenText, platform, read)
            isNoteForget(lower) -> handleForgetNote(screenText, platform, read)
            isNavigate(lower) -> handleNavigate(screenText)
            isLog(lower) -> handleLog(screenText, platform, read)
            isOfferCheck(lower) -> handleOfferCheck(screenText, platform, read)
            else -> handleAsk(command, screenText, platform, read)
        }
    }

    /** "Read just now · Spark" — honest about how fresh the screen was. */
    private fun readFooter(read: ScreenReaderService.ScreenRead, platform: String): String =
        "\n\n${read.ageLabel().replaceFirstChar { it.uppercaseChar() }} · $platform"

    // ---- command matchers ----

    private fun isOutcome(lower: String) = isTook(lower) || isSkipped(lower)

    private fun isTook(lower: String) =
        lower.contains("took it") || lower.contains("i took") ||
            lower.contains("took this")

    private fun isSkipped(lower: String) =
        lower.contains("skipped it") || lower.contains("i skipped") ||
            lower.contains("skipped this") || lower.contains("skip it")

    private fun isEarnings(lower: String) =
        lower.contains("earning") || lower.contains("how much") &&
            (lower.contains("today") || lower.contains("this week") ||
                lower.contains("this month") || lower.contains("made") ||
                lower.contains("make")) ||
            lower.contains("how much did i make") ||
            lower.contains("how much have i made")

    private fun isPreShiftStart(lower: String) =
        lower.contains("pre-shift") || lower.contains("pre shift") ||
            lower.contains("preshift") || lower.contains("start my shift") ||
            lower.contains("start shift")

    private fun isNoteAdd(lower: String) =
        lower.contains("remember") || lower.contains("add a note") ||
            lower.contains("save a note") || lower.contains("note this")

    private fun isNoteRead(lower: String) =
        lower.contains("what do i know") || lower.contains("any notes") ||
            lower.contains("read my notes") || lower.contains("store notes") ||
            lower.contains("notes for this store") || lower.contains("what do you know")

    private fun isNoteForget(lower: String) =
        lower.contains("forget that note") || lower.contains("forget the note") ||
            lower.contains("delete note") || lower.contains("delete that note") ||
            lower.contains("forget my notes") || lower.contains("forget this note")

    private fun isNavigate(lower: String) =
        lower.contains("navigate") || lower.contains("directions") ||
            lower.contains("take me") || lower.contains("drive to")

    private fun isLog(lower: String) =
        lower.contains("log") || lower.contains("save this") ||
            lower.contains("record this") || lower.contains("record delivery")

    private fun isOfferCheck(lower: String) =
        lower.contains("check") || lower.contains("offer") || lower.contains("mile") ||
            lower.contains("worth") || lower.contains("how much") || lower.contains("pay")

    // ---- offer outcome recording ----

    /** Voice path: record the outcome, speak it, show it. */
    fun recordOutcome(outcome: OfferOutcome) {
        val msg = recordOutcomeMessage(outcome)
        output?.speak(msg)
        output?.showCard("Offer recorded", msg, listOf("hide"))
    }

    /** Card-button path (via AgentBridge): just the message; the bridge speaks it. */
    fun recordOutcomeMessage(outcome: OfferOutcome): String {
        val checked = lastChecked
        if (checked == null) {
            return "I don't have a recent offer check to record. Check an offer first, then say took it or skipped it."
        }
        val ok = OfferMemory.markOutcome(context, checked.id, outcome)
        lastChecked = checked.copy(outcome = outcome)
        val label = if (checked.storeLabel.isNotBlank()) checked.storeLabel else checked.platform
        return if (ok) {
            if (outcome == OfferOutcome.TOOK) "Recorded: took it — $label."
            else "Recorded: skipped it — $label."
        } else {
            "I couldn't save that. Try again."
        }
    }

    /** Remember a check the card ran (AgentBridge.checkOffer) so outcome recording works. */
    fun noteCheckedOffer(record: OfferMemoryRecord) {
        lastChecked = record
    }

    // ---- earnings tally (code math, no screen read, no Gemini) ----

    private fun handleEarnings(lower: String) {
        val range = when {
            lower.contains("this week") -> EarningsRange.WEEK
            lower.contains("this month") -> EarningsRange.MONTH
            else -> EarningsRange.TODAY
        }
        val tally = Earnings.tally(context, range)
        val text = Earnings.text(tally)
        output?.speak(text)
        output?.showCard("Earnings — ${tally.label}", text, listOf("hide"))
    }

    /** JSON tally for the card buttons. */
    fun earningsSummary(rangeText: String): String {
        val range = when (rangeText.lowercase()) {
            "week" -> EarningsRange.WEEK
            "month" -> EarningsRange.MONTH
            else -> EarningsRange.TODAY
        }
        val tally = Earnings.tally(context, range)
        return org.json.JSONObject().apply {
            put("ok", true)
            put("label", tally.label)
            put("count", tally.count)
            put("totalPay", tally.totalPay)
            put("totalMiles", tally.totalMiles)
            if (tally.avgPerMile != null) put("perMile", tally.avgPerMile) else put("perMile", org.json.JSONObject.NULL)
            put("text", Earnings.text(tally))
        }.toString()
    }

    // ---- pre-shift check (guided voice conversation, no screen read) ----

    fun startPreShift() {
        preShift = PreShiftFlow(step = 0, vehicleOk = null, fuel = null, apps = emptyList(), retries = 0)
        askPreShiftStep()
    }

    private fun askPreShiftStep() {
        val flow = preShift ?: return
        val question = when (flow.step) {
            0 -> "Pre-shift check, step 1 of 3. Is the vehicle OK? Say yes or no."
            1 -> "Step 2 of 3. What is the fuel level? Say full, half, quarter, or low."
            else -> "Step 3 of 3. Which apps are you running today? Say Spark, DoorDash, Instacart, or all."
        }
        output?.showCard(
            "Pre-shift check",
            question + "\n\nSay \"cancel\" to stop.",
            listOf("hide")
        )
        output?.speakAndListen(question)
    }

    private fun handlePreShiftAnswer(command: String, lower: String) {
        val flow = preShift ?: return
        if (lower.contains("cancel") || lower == "stop" || lower.contains("never mind") ||
            lower.contains("quit") || lower.contains("forget it")
        ) {
            preShift = null
            val msg = "Pre-shift check cancelled."
            output?.speak(msg)
            output?.showCard("Pre-shift check", msg, listOf("hide"))
            return
        }
        when (flow.step) {
            0 -> {
                val yes = lower.contains("yes") || lower.contains("yeah") || lower.contains("yep") ||
                    lower.contains("all good") || lower.contains("good") || lower == "ok" ||
                    lower.contains("okay") || lower.contains("fine")
                val no = lower.contains("no") || lower.contains("nope") || lower.contains("not ok") ||
                    lower.contains("needs work") || lower.contains("broken")
                if (yes && !no) {
                    flow.vehicleOk = true
                    flow.step = 1
                    flow.retries = 0
                    askPreShiftStep()
                } else if (no) {
                    flow.vehicleOk = false
                    flow.step = 1
                    flow.retries = 0
                    askPreShiftStep()
                } else if (flow.retries < 1) {
                    flow.retries++
                    output?.speakAndListen("I didn't catch that — is the vehicle OK, yes or no?")
                } else {
                    flow.vehicleOk = null
                    flow.step = 1
                    flow.retries = 0
                    askPreShiftStep()
                }
            }
            1 -> {
                flow.fuel = parseFuel(lower)
                flow.step = 2
                flow.retries = 0
                askPreShiftStep()
            }
            else -> {
                val apps = parseApps(lower)
                if (apps.isEmpty() && flow.retries < 1) {
                    flow.retries++
                    output?.speakAndListen("Which apps — Spark, DoorDash, Instacart, or all?")
                } else {
                    flow.apps = apps
                    finishPreShift(flow)
                }
            }
        }
    }

    private fun parseFuel(lower: String): String {
        return when {
            lower.contains("full") -> "Full"
            lower.contains("three quarter") || lower.contains("3/4") || lower.contains("3 quarters") -> "Three quarters"
            lower.contains("half") || lower.contains("1/2") -> "Half"
            lower.contains("quarter") || lower.contains("1/4") -> "Quarter"
            lower.contains("low") || lower.contains("empty") || lower.contains("almost out") -> "Low"
            else -> lower.trim().take(40).ifBlank { "Not recorded" }
        }
    }

    private fun parseApps(lower: String): List<String> {
        if (lower.contains("all") || lower.contains("everything") || lower.contains("all three")) {
            return listOf("Spark", "DoorDash", "Instacart")
        }
        val apps = mutableListOf<String>()
        if (lower.contains("spark")) apps.add("Spark")
        if (lower.contains("doordash") || lower.contains("dash")) apps.add("DoorDash")
        if (lower.contains("instacart")) apps.add("Instacart")
        if (lower.contains("both") && apps.size < 2) {
            // "Both" on its own: the two he drives most.
            if (!apps.contains("Spark")) apps.add("Spark")
            if (!apps.contains("DoorDash")) apps.add("DoorDash")
        }
        return apps
    }

    private fun finishPreShift(flow: PreShiftFlow) {
        preShift = null
        output?.showCard("Pre-shift check", "Checking the weather…", listOf("hide"))
        Weather.fetch(context) { weather ->
            mainHandler.post {
                val vehiclePart = when (flow.vehicleOk) {
                    true -> "Vehicle OK."
                    false -> "Vehicle NOT OK — check it before you drive."
                    null -> "Vehicle not confirmed."
                }
                val appsPart = if (flow.apps.isEmpty()) "No apps named." else "Apps: ${flow.apps.joinToString(", ")}."
                val weatherPart = if (weather != null) "Weather: $weather." else "Weather unavailable."
                val summary = "$vehiclePart Fuel: ${flow.fuel ?: "Not recorded"}. $appsPart $weatherPart"
                val (date, time) = DeliveryLog.nowParts()
                PreShiftLog.append(
                    context,
                    PreShiftRecord(date, time, flow.vehicleOk, flow.fuel ?: "Not recorded", flow.apps, weather, summary)
                )
                val done = "Pre-shift done. $summary"
                output?.speak(done)
                output?.showCard("Pre-shift check", done, listOf("hide"))
            }
        }
    }

    // ---- store notes (need the current store from the screen) ----

    private fun handleAddNote(command: String, screenText: String, platform: String) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first, then save the note."
            output?.speak(msg)
            output?.showCard("Store note", msg, listOf("hide"))
            return
        }
        val lower = command.lowercase()
        var text = command
        for (prefix in listOf("remember that", "remember this", "remember", "add a note", "save a note", "note this")) {
            if (lower.startsWith(prefix)) {
                text = command.substring(prefix.length)
                break
            }
        }
        text = text.trim().trimStart(':', '-', ' ').trim()
        if (text.isBlank()) {
            val msg = "What should I remember about $label? Say it after \"remember\"."
            output?.speak(msg)
            output?.showCard("Store note", msg, listOf("hide"))
            return
        }
        val note = StoreNotes.add(context, key, label, text)
        val msg = if (note != null) "Saved for $label: $text" else "I couldn't save that note. Try again."
        output?.speak(msg)
        output?.showCard("Store note", msg, listOf("hide"))
    }

    private fun handleReadNotes(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first."
            output?.speak(msg)
            output?.showCard("Store notes", msg, listOf("hide"))
            return
        }
        val notes = StoreNotes.notesFor(context, key)
        val msg = if (notes.isEmpty()) {
            "No notes for $label yet. Say \"remember\" followed by the note to save one."
        } else {
            val joined = notes.joinToString("\n• ") { it.text }
            "Notes for $label:\n• $joined"
        }
        output?.speak(StoreNotes.spokenLine(notes) ?: msg)
        output?.showCard("Store notes — $label", msg + readFooter(read, platform), listOf("hide"))
    }

    private fun handleForgetNote(
        screenText: String,
        platform: String,
        read: ScreenReaderService.ScreenRead
    ) {
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)
        if (key.isBlank()) {
            val msg = "I can't tell which store this is. Open the store or offer screen first."
            output?.speak(msg)
            output?.showCard("Store notes", msg, listOf("hide"))
            return
        }
        val deleted = StoreNotes.deleteAllFor(context, key)
        val msg = if (deleted > 0) {
            "Forgot $deleted note${if (deleted == 1) "" else "s"} for $label."
        } else {
            "There are no notes saved for $label."
        }
        output?.speak(msg)
        output?.showCard("Store notes", msg + readFooter(read, platform), listOf("hide"))
    }

    // ---- existing routes (navigate / log / offer / ask) ----

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
        val floor = Prefs.getFloor(context)
        val result = OfferParser.checkBatch(screenText, floor)
        val key = OfferParser.storeKey(screenText, platform)
        val label = OfferParser.storeLabel(screenText, platform)

        // Look up memory BEFORE recording this check, or it would match itself.
        val match = OfferMemory.findMatch(context, key, result.pay, result.miles)
        val storeOnly = if (match == null) OfferMemory.findStore(context, key) else null
        val memoryLine = OfferMemory.memoryLine(match, storeOnly)

        // Remember this check; the driver fills in took/skipped afterwards.
        val (date, time) = DeliveryLog.nowParts()
        val record = OfferMemoryRecord(
            id = OfferMemory.newId(),
            date = date,
            time = time,
            platform = platform,
            storeKey = key,
            storeLabel = label,
            pay = result.pay,
            miles = result.miles,
            perMile = result.perMile,
            verdict = result.verdict,
            outcome = OfferOutcome.UNKNOWN,
            stops = result.stops.map { it.address }
        )
        OfferMemory.append(context, record)
        lastChecked = record
        val notes = StoreNotes.notesFor(context, key)
        val noteLine = StoreNotes.spokenLine(notes)

        val spoken = buildString {
            append(OfferParser.speakBatchText(result, platform))
            if (memoryLine != null) append(" $memoryLine")
            if (noteLine != null) append(" $noteLine")
        }.toString()
        output?.speak(spoken)

        val body = buildString {
            if (result.isBatch) append("Batch: ${result.stops.size} stops\n")
            if (result.pay != null) append("Pay: $%.2f\n".format(result.pay))
            if (result.miles != null) append("Miles: %.1f\n".format(result.miles))
            if (result.perMile != null) append("Per mile: $%.2f\n".format(result.perMile))
            append("Floor: $%.2f / mile\n".format(result.floor))
            if (result.isBatch) {
                result.stops.forEachIndexed { i, stop ->
                    val milesPart = if (stop.miles != null) " — %.1f mi".format(stop.miles) else ""
                    val payPart = if (stop.pay != null) " — $%.2f".format(stop.pay) else ""
                    append("Stop ${i + 1}: ${stop.address}$milesPart$payPart\n")
                }
            } else if (result.address != null) {
                append("Address: ${result.address}\n")
            }
            if (memoryLine != null) append("\n$memoryLine\n")
            if (notes.isNotEmpty()) {
                append("\nNotes for this store:\n")
                notes.forEach { append("• ${it.text}\n") }
            }
            append("\n$spoken")
            append(readFooter(read, platform))
        }
        output?.showCard(
            "Offer check — $platform",
            body.trim(),
            listOf("navigate", "log", "took", "skipped", "hide")
        )
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
