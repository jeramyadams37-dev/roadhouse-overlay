package com.atothei.roadhouse.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Weather for the pre-shift check — free Open-Meteo, no key, fail-soft.
 * Same pattern as the Carpentry Jobs weather check: the city name is
 * geocoded once with Nominatim (and cached in Prefs so it is not looked up
 * again), then Open-Meteo returns the current conditions. Any failure —
 * no network, bad city, bad response — returns null and the pre-shift
 * summary says the weather was unavailable instead of guessing.
 *
 * Called only when the driver runs a pre-shift check. No polling, no
 * background refresh.
 */
object Weather {
    private const val NOMINATIM_URL =
        "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
    private const val OPEN_METEO_URL =
        "https://api.open-meteo.com/v1/forecast?latitude=%f&longitude=%f" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph"
    private const val USER_AGENT = "RoadHouseOverlay/1.0 (driver assistant)"

    /** One-shot fetch on a background thread. Callback gets a short summary or null. */
    fun fetch(context: Context, callback: (String?) -> Unit) {
        Thread {
            callback.invoke(fetchSync(context))
        }.start()
    }

    private fun fetchSync(context: Context): String? {
        return try {
            val city = Prefs.getWeatherCity(context)
            val latLon = Prefs.getCachedLatLon(context, city) ?: geocode(city)?.also {
                Prefs.setCachedLatLon(context, city, it.first, it.second)
            } ?: return null
            val url = URL(
                String.format(
                    java.util.Locale.US,
                    OPEN_METEO_URL,
                    latLon.first,
                    latLon.second
                )
            )
            val body = httpGet(url) ?: return null
            val current = JSONObject(body).optJSONObject("current") ?: return null
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val wind = current.optDouble("wind_speed_10m", Double.NaN)
            if (temp.isNaN() || code < 0) return null
            val desc = weatherWords(code)
            val windPart = if (!wind.isNaN()) ", wind %.0f mph".format(wind) else ""
            "%.0f°F and %s in %s%s".format(temp, desc, city, windPart)
        } catch (e: Exception) {
            null
        }
    }

    /** Nominatim geocode: city name -> (latitude, longitude), or null. */
    private fun geocode(city: String): Pair<Double, Double>? {
        return try {
            val url = URL(NOMINATIM_URL + URLEncoder.encode(city, "UTF-8"))
            val body = httpGet(url) ?: return null
            val first = JSONArray(body).optJSONObject(0) ?: return null
            Pair(first.optDouble("lat", Double.NaN), first.optDouble("lon", Double.NaN)).let {
                if (it.first.isNaN() || it.second.isNaN()) null else it
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: URL): String? {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10000
                readTimeout = 10000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            null
        }
    }

    /** WMO weather codes -> plain words. */
    private fun weatherWords(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80 -> "light showers"
        81 -> "showers"
        82 -> "heavy showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "uncertain conditions"
    }
}
