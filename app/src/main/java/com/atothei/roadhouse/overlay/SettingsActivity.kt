package com.atothei.roadhouse.overlay

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings: Gemini API key (BYOK), $/mile floor, voice on/off, overlay
 * button size, weather city, delivery-log / offer-history / store-notes
 * export/clear. The key is stored encrypted and is never shown in full
 * after saving — the field is a password field.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var floorInput: EditText
    private lateinit var weatherCityInput: EditText
    private lateinit var voiceSwitch: Switch
    private lateinit var sizeGroup: RadioGroup
    private lateinit var logCount: TextView
    private lateinit var offerCount: TextView
    private lateinit var noteCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        keyInput = findViewById(R.id.input_api_key)
        floorInput = findViewById(R.id.input_floor)
        weatherCityInput = findViewById(R.id.input_weather_city)
        voiceSwitch = findViewById(R.id.switch_voice)
        sizeGroup = findViewById(R.id.group_size)
        logCount = findViewById(R.id.text_log_count)
        offerCount = findViewById(R.id.text_offer_count)
        noteCount = findViewById(R.id.text_note_count)

        keyInput.setText(Prefs.getApiKey(this))
        floorInput.setText("%.2f".format(Prefs.getFloor(this)))
        weatherCityInput.setText(Prefs.getWeatherCity(this))
        voiceSwitch.isChecked = Prefs.isVoiceEnabled(this)
        when (Prefs.getOverlaySize(this)) {
            "small" -> sizeGroup.check(R.id.radio_small)
            "large" -> sizeGroup.check(R.id.radio_large)
            else -> sizeGroup.check(R.id.radio_medium)
        }
        refreshCounts()

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_test_key).setOnClickListener { testKey() }
        findViewById<Button>(R.id.btn_export_log).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            DeliveryLog.clear(this)
            refreshCounts()
            Toast.makeText(this, "Delivery log cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export_offers).setOnClickListener { exportOffers() }
        findViewById<Button>(R.id.btn_clear_offers).setOnClickListener {
            OfferMemory.clear(this)
            refreshCounts()
            Toast.makeText(this, "Offer history cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export_notes).setOnClickListener { exportNotes() }
        findViewById<Button>(R.id.btn_clear_notes).setOnClickListener {
            StoreNotes.clear(this)
            refreshCounts()
            Toast.makeText(this, "Store notes cleared.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun refreshCounts() {
        logCount.text = getString(R.string.log_count, DeliveryLog.count(this))
        offerCount.text = getString(R.string.offer_count, OfferMemory.count(this))
        noteCount.text = getString(R.string.note_count, StoreNotes.count(this))
    }

    private fun save() {
        Prefs.setApiKey(this, keyInput.text.toString())
        val floor = floorInput.text.toString().toDoubleOrNull()
        if (floor == null || floor <= 0.0 || floor > 10.0) {
            Toast.makeText(
                this,
                "Floor must be between 0.01 and 10.00 dollars per mile.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Prefs.setFloor(this, floor)
        Prefs.setWeatherCity(this, weatherCityInput.text.toString())
        Prefs.setVoiceEnabled(this, voiceSwitch.isChecked)
        val size = when (sizeGroup.checkedRadioButtonId) {
            R.id.radio_small -> "small"
            R.id.radio_large -> "large"
            else -> "medium"
        }
        Prefs.setOverlaySize(this, size)
        Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testKey() {
        val key = keyInput.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Paste your Gemini key first.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Testing key…", Toast.LENGTH_SHORT).show()
        GeminiClient.ask(key, "Reply with exactly: OK", "", object : GeminiClient.Callback {
            override fun onResult(text: String) {
                runOnUiThread {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Key works. Gemini said: $text",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun exportLog() {
        val json = DeliveryLog.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "RoadHouse Overlay delivery log")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share delivery log"))
    }

    private fun exportOffers() {
        val json = OfferMemory.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "RoadHouse Overlay offer history")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share offer history"))
    }

    private fun exportNotes() {
        val json = StoreNotes.readAll(this).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "RoadHouse Overlay store notes")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, "Share store notes"))
    }
}
