package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings store. The Gemini API key (bring-your-own-key) lives in
 * EncryptedSharedPreferences; if the encrypted store ever fails on a device,
 * it falls back to plain private preferences and flags it — the key is never
 * written to code, logs, or the APK.
 */
object Prefs {
    private const val FILE = "roadhouse_overlay_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_FLOOR = "per_mile_floor"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_SIZE = "overlay_size"
    private const val KEY_ONBOARDING = "onboarding_done"

    const val DEFAULT_FLOOR = 1.00

    @Volatile
    private var encryptedFailed = false

    private fun prefs(context: Context): SharedPreferences {
        if (encryptedFailed) {
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            encryptedFailed = true
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    /** True only if the encrypted store failed and we fell back to plain prefs. */
    fun isEncryptedFallback(context: Context): Boolean {
        prefs(context)
        return encryptedFailed
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    fun getFloor(context: Context): Double =
        prefs(context).getFloat(KEY_FLOOR, DEFAULT_FLOOR.toFloat()).toDouble()

    fun setFloor(context: Context, floor: Double) {
        prefs(context).edit().putFloat(KEY_FLOOR, floor.toFloat()).apply()
    }

    fun isVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE, true)

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    /** "small" | "medium" | "large" */
    fun getOverlaySize(context: Context): String =
        prefs(context).getString(KEY_SIZE, "medium") ?: "medium"

    fun setOverlaySize(context: Context, size: String) {
        prefs(context).edit().putString(KEY_SIZE, size).apply()
    }

    fun isOnboardingDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING, false)

    fun setOnboardingDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING, true).apply()
    }
}
