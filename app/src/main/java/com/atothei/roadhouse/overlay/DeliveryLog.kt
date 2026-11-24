package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DeliveryEntry(
    val date: String,
    val time: String,
    val platform: String,
    val pay: Double?,
    val miles: Double?,
    val perMile: Double?,
    val address: String?
)

/**
 * Local JSON log of logged deliveries, kept in the app's private files dir.
 * Shape is RoadHouse-compatible (date, platform, pay, miles, address) so
 * RoadHouse's mileage/tax tracking can import it later. Nothing leaves the
 * phone except when the user explicitly exports it from Settings.
 */
object DeliveryLog {
    private const val FILE_NAME = "delivery_log.json"

    fun append(context: Context, entry: DeliveryEntry): Boolean {
        return try {
            val arr = readAll(context)
            val obj = JSONObject().apply {
                put("date", entry.date)
                put("time", entry.time)
                put("platform", entry.platform)
                if (entry.pay != null) put("pay", entry.pay) else put("pay", JSONObject.NULL)
                if (entry.miles != null) put("miles", entry.miles) else put("miles", JSONObject.NULL)
                if (entry.perMile != null) put("per_mile", entry.perMile) else put("per_mile", JSONObject.NULL)
                put("address", entry.address ?: JSONObject.NULL)
                put("source", "roadhouse-overlay-v1")
                put("logged_at", isoNow())
            }
            arr.put(obj)
            File(context.filesDir, FILE_NAME).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readAll(context: Context): JSONArray {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun count(context: Context): Int = readAll(context).length()

    fun clear(context: Context): Boolean {
        return try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun nowParts(): Pair<String, String> {
        val now = Date()
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val t = SimpleDateFormat("HH:mm", Locale.US).format(now)
        return d to t
    }

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date())
    }
}
