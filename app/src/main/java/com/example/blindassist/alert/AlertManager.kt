package com.example.blindassist.alert

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.blindassist.Config
import com.example.blindassist.tracking.TrackerEntry
import java.util.Locale

class AlertManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var voiceAlertsEnabled = true
    private var vibrationEnabled = true
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    enum class Zone(val text: String) {
        LEFT("bên trái"),
        CENTER("phía trước"),
        RIGHT("bên phải")
    }

    enum class Tier {
        CRITICAL,
        NEAR,
        MID,
        FAR
    }

    private val lastAlertTier = mutableMapOf<Int, Tier>()
    
    private var suppressAlertsUntilMs: Long = 0L
    @Volatile private var isSuppressedIndefinitely: Boolean = false

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
        if (isSuppressedIndefinitely || System.currentTimeMillis() < suppressAlertsUntilMs) return

        val now = System.currentTimeMillis()
        val currentTrackIds = tracks.map { it.id }.toSet()
        lastAlertTier.keys.retainAll(currentTrackIds)

        // 1. Collect all tier changes this frame
        val pendingAlerts = mutableListOf<PendingAlert>()

        for (track in tracks) {
            val z = track.distanceM ?: continue
            val zone = classifyZone(track.smoothedBox, frameWidth)

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

        // 3. Sort by danger level (most dangerous first)
        val sorted = pendingAlerts.sortedBy { it.tier.ordinal }
        val highestTier = sorted.first().tier

        // Vibrate based on the most dangerous tier
        vibrate(highestTier)

        // 4. Combine all alerts into a single sentence
        val combinedText = sorted.joinToString(", ") { it.text }

        // 5. CRITICAL always speaks immediately (flush everything)
        if (highestTier == Tier.CRITICAL) {
            if (voiceAlertsEnabled && isReady && tts != null) {
                tts?.speak(combinedText, TextToSpeech.QUEUE_FLUSH, null, "alert_combined_CRITICAL")
                lastAlertSpokenMs = now
            }
            Log.d("AlertManager", "CRITICAL combined alert: $combinedText")
            return
        }

        // 6. Non-critical: skip if TTS is still speaking or cooldown hasn't passed
        if (!voiceAlertsEnabled || !isReady || tts == null) return
        if (tts?.isSpeaking == true) return
        if (now - lastAlertSpokenMs < ALERT_MIN_INTERVAL_MS) return

        tts?.speak(combinedText, TextToSpeech.QUEUE_ADD, null, "alert_combined_${highestTier.name}")
        lastAlertSpokenMs = now
        Log.d("AlertManager", "Combined alert fired: $combinedText")
    }

    private fun vibrate(tier: Tier) {
        if (!vibrationEnabled || !vibrator.hasVibrator()) return

        val (timings, amplitudes) = when (tier) {
            Tier.CRITICAL -> longArrayOf(0, 250, 100, 250, 100, 350) to intArrayOf(0, 255, 0, 255, 0, 255)
            Tier.NEAR -> longArrayOf(0, 200, 120, 200) to intArrayOf(0, 220, 0, 220)
            Tier.MID -> longArrayOf(0, 180) to intArrayOf(0, 160)
            Tier.FAR -> return
        }

        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    fun setVoiceAlertsEnabled(enabled: Boolean) {
        voiceAlertsEnabled = enabled
        lastAlertTier.clear()
        if (!enabled) tts?.stop()
    }

    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
        lastAlertTier.clear()
        if (!enabled) vibrator.cancel()
    }

    fun suppressAlerts(durationMs: Long) {
        suppressAlertsUntilMs = System.currentTimeMillis() + durationMs
    }

    fun suppressIndefinitely() {
        isSuppressedIndefinitely = true
        vibrator.cancel()
    }

    fun resumeAlerts() {
        isSuppressedIndefinitely = false
        lastAlertTier.clear()
    }

    fun speakImmediate(text: String, onDone: (() -> Unit)? = null) {
        if (!voiceAlertsEnabled || !isReady || tts == null) {
            onDone?.invoke()
            return
        }
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "gemini_describe") {
                        onDone.invoke()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == "gemini_describe") {
                        onDone.invoke()
                    }
                }
            })
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gemini_describe")
    }

    fun stop() {
        vibrator.cancel()
        if (isReady) {
            tts?.stop()
        }
    }

    fun shutdown() {
        vibrator.cancel()
        tts?.stop()
        tts?.shutdown()
        isReady = false
        tts = null
    }

    companion object {
        // Zone boundary ratios
        private const val LEFT_END = 0.30
        private const val RIGHT_START = 0.70
        // If CENTER coverage >= this fraction of box width, classify as CENTER
        private const val CENTER_PRIORITY_THRESHOLD = 0.40

        /**
         * Coverage-based zone classification.
         * Instead of using only the box center point, this calculates how much
         * of the box overlaps each zone (LEFT 0-30%, CENTER 30-70%, RIGHT 70-100%).
         * CENTER is prioritized: if >= 40% of the box covers CENTER, it's "phía trước".
         * Otherwise the zone with the most coverage wins.
         */
        fun classifyZone(box: org.opencv.core.Rect, frameWidth: Int): Zone {
            if (frameWidth <= 0) return Zone.CENTER

            val boxLeft = box.x.toDouble()
            val boxRight = (box.x + box.width).toDouble()
            val boxW = box.width.toDouble()
            if (boxW <= 0) return Zone.CENTER

            val leftBoundary = frameWidth * LEFT_END
            val rightBoundary = frameWidth * RIGHT_START

            // Calculate overlap with each zone
            val overlapLeft = (minOf(boxRight, leftBoundary) - maxOf(boxLeft, 0.0)).coerceAtLeast(0.0)
            val overlapCenter = (minOf(boxRight, rightBoundary) - maxOf(boxLeft, leftBoundary)).coerceAtLeast(0.0)
            val overlapRight = (minOf(boxRight, frameWidth.toDouble()) - maxOf(boxLeft, rightBoundary)).coerceAtLeast(0.0)

            // Fraction of box width in each zone
            val fracCenter = overlapCenter / boxW

            // Prioritize CENTER if significant coverage (most important for safety)
            if (fracCenter >= CENTER_PRIORITY_THRESHOLD) return Zone.CENTER

            // Otherwise, pick zone with most coverage
            return when {
                overlapLeft >= overlapRight -> Zone.LEFT
                else -> Zone.RIGHT
            }
        }
    }
}
