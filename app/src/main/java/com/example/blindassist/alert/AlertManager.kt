package com.example.blindassist.alert

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.blindassist.Config
import com.example.blindassist.tracking.TrackerEntry
import java.util.Locale

class AlertManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false

    enum class Zone(val text: String) {
        LEFT("bên trái"),
        CENTER("phía trước"),
        RIGHT("bên phải")
    }

    enum class Tier(val cooldownMs: Long) {
        CRITICAL(Config.COOLDOWN_CRITICAL_MS),
        NEAR(Config.COOLDOWN_NEAR_MS),
        MID(Config.COOLDOWN_MID_MS),
        FAR(0L)
    }

    private val lastAlertTier = mutableMapOf<Int, Tier>()
    
    private var suppressAlertsUntilMs: Long = 0L

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeVi = Locale.Builder().setLanguage("vi").setRegion("VN").build()
            val result = tts?.setLanguage(localeVi)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("AlertManager", "Vietnamese language is not supported or missing data. Falling back to default.")
                tts?.setLanguage(Locale.getDefault())
            }
            isReady = true
            Log.d("AlertManager", "TTS is ready")
        } else {
            Log.e("AlertManager", "TTS initialization failed")
        }
    }

    // Minimum time between any two alert speeches
    private val ALERT_MIN_INTERVAL_MS = 2000L
    private var lastAlertSpokenMs: Long = 0L

    private data class PendingAlert(
        val trackId: Int,
        val tier: Tier,
        val zone: Zone,
        val distance: Double,
        val text: String
    )

    fun update(tracks: List<TrackerEntry>, frameWidth: Int) {
        if (!isReady || tts == null) return
        if (System.currentTimeMillis() < suppressAlertsUntilMs) return

        val now = System.currentTimeMillis()
        val currentTrackIds = tracks.map { it.id }.toSet()
        lastAlertTier.keys.retainAll(currentTrackIds)

        // 1. Collect all tier changes this frame
        val pendingAlerts = mutableListOf<PendingAlert>()

        for (track in tracks) {
            val z = track.distanceM ?: continue
            val centerX = track.smoothedBox.x + track.smoothedBox.width / 2.0
            
            val zone = when {
                centerX < frameWidth * 0.35 -> Zone.LEFT
                centerX > frameWidth * 0.65 -> Zone.RIGHT
                else -> Zone.CENTER
            }

            val previousTier = lastAlertTier[track.id] ?: Tier.FAR
            
            // Hysteresis (0.2m) to prevent tier oscillation
            val tier = when (previousTier) {
                Tier.CRITICAL -> when {
                    z < Config.THRESHOLD_CRITICAL_M + 0.2 -> Tier.CRITICAL
                    z < Config.THRESHOLD_NEAR_M + 0.2 -> Tier.NEAR
                    z < Config.THRESHOLD_MID_M + 0.2 -> Tier.MID
                    else -> Tier.FAR
                }
                Tier.NEAR -> when {
                    z < Config.THRESHOLD_CRITICAL_M -> Tier.CRITICAL
                    z < Config.THRESHOLD_NEAR_M + 0.2 -> Tier.NEAR
                    z < Config.THRESHOLD_MID_M + 0.2 -> Tier.MID
                    else -> Tier.FAR
                }
                Tier.MID -> when {
                    z < Config.THRESHOLD_CRITICAL_M -> Tier.CRITICAL
                    z < Config.THRESHOLD_NEAR_M -> Tier.NEAR
                    z < Config.THRESHOLD_MID_M + 0.2 -> Tier.MID
                    else -> Tier.FAR
                }
                Tier.FAR -> when {
                    z < Config.THRESHOLD_CRITICAL_M -> Tier.CRITICAL
                    z < Config.THRESHOLD_NEAR_M -> Tier.NEAR
                    z < Config.THRESHOLD_MID_M -> Tier.MID
                    else -> Tier.FAR
                }
            }

            // Always update the stored tier
            if (tier != previousTier) {
                lastAlertTier[track.id] = tier
                
                if (tier == Tier.FAR) continue

                val alertText = when (tier) {
                    Tier.CRITICAL -> "Cảnh báo! Vật cản ${zone.text}, ${String.format("%.1f", z)} mét"
                    Tier.NEAR -> "Vật cản ${zone.text}, ${String.format("%.1f", z)} mét"
                    Tier.MID -> "Chú ý ${zone.text}"
                    else -> ""
                }
                pendingAlerts.add(PendingAlert(track.id, tier, zone, z, alertText))
            }
        }

        // 2. Nothing to say
        if (pendingAlerts.isEmpty()) return

        // 3. Pick the most dangerous alert only
        val chosen = pendingAlerts.minByOrNull { it.tier.ordinal } ?: return

        // 4. CRITICAL always speaks immediately (flush everything)
        if (chosen.tier == Tier.CRITICAL) {
            tts?.speak(chosen.text, TextToSpeech.QUEUE_FLUSH, null, "alert_${chosen.trackId}_CRITICAL")
            lastAlertSpokenMs = now
            Log.d("AlertManager", "CRITICAL alert for ID ${chosen.trackId}: ${chosen.text}")
            return
        }

        // 5. Non-critical: skip if TTS is still speaking or cooldown hasn't passed
        if (tts?.isSpeaking == true) return
        if (now - lastAlertSpokenMs < ALERT_MIN_INTERVAL_MS) return

        tts?.speak(chosen.text, TextToSpeech.QUEUE_ADD, null, "alert_${chosen.trackId}_${chosen.tier.name}")
        lastAlertSpokenMs = now
        Log.d("AlertManager", "Alert fired for ID ${chosen.trackId}: ${chosen.text}")
    }

    fun suppressAlerts(durationMs: Long) {
        suppressAlertsUntilMs = System.currentTimeMillis() + durationMs
    }

    fun speakImmediate(text: String) {
        if (!isReady || tts == null) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gemini_describe")
    }

    fun stop() {
        if (isReady) {
            tts?.stop()
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isReady = false
        tts = null
    }
}
