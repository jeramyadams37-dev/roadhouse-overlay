package com.atothei.roadhouse.overlay

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Gemini REST client — plain HttpURLConnection, no extra dependency.
 * Model is gemini-3.8-flash (verified working on the free tier, Nov 2026).
 * The API key comes only from device storage (Prefs); it is never compiled
 * into the app. Screen text is sent to Google's Gemini API only, and only
 * as part of a question the driver asked — it is never stored.
 */
object GeminiClient {
    const val MODEL = "gemini-3.8-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    const val SYSTEM_PROMPT = "You are RoadHouse, a voice-first driving assistant for a gig delivery " +
        "driver using Walmart Spark, DoorDash, or Instacart. You see the text of the delivery app " +
        "screen currently open on the driver's phone. Answer briefly, in plain spoken language, " +
        "under 40 words unless the driver asks for detail. Give facts from the screen: pay, miles, " +
        "address, customer notes, item counts. The app computes the dollars-per-mile verdict " +
        "separately, so do not recommend accepting or declining an offer — just report what the " +
        "screen says. If the screen does not contain the answer, say so plainly."

    interface Callback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    fun ask(apiKey: String, userPrompt: String, screenText: String, callback: Callback) {
        Thread {
            try {
                val prompt = buildString {
                    append("Screen text from the delivery app:\n")
                    append(screenText.take(8000))
                    append("\n\nDriver question: ")
                    append(userPrompt)
                }
                val body = JSONObject().apply {
                    put(
                        "system_instruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                        )
                    )
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.3)
                        put("maxOutputTokens", 512)
                    })
                }
                val url = URL(
                    String.format(ENDPOINT, MODEL) +
                        "?key=" + URLEncoder.encode(apiKey, "UTF-8")
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) {
                    callback.onError("Gemini error $code: ${extractError(text)}")
                    return@Thread
                }
                val answer = JSONObject(text)
                    .optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
                if (answer.isBlank()) callback.onError("Gemini returned an empty answer.")
                else callback.onResult(answer)
            } catch (e: Exception) {
                callback.onError("Could not reach Gemini: ${e.message ?: "network error"}")
            }
        }.start()
    }

    private fun extractError(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message", body.take(200))
                ?: body.take(200)
        } catch (e: Exception) {
            body.take(200)
        }
    }
}
