package com.example.blindassist

object Config {

    // ── MiDaS ──
    const val MIDAS_INPUT_SIZE        = 256

    // ── Detection ──
    const val MIN_CONTOUR_AREA        = 800
    const val MAX_CONTOUR_RATIO       = 0.7f
    const val MERGE_IOU_THRESHOLD     = 0.1f
    const val MERGE_GAP_PX            = 15

    // ── Tracking ──
    const val TRACK_INTERVAL          = 5
    const val MAX_MISSED_FRAMES       = 15
    const val IOU_MATCH_THRESHOLD     = 0.3f
    const val MAX_CORNERS             = 20
    const val QUALITY_LEVEL           = 0.01
    const val MIN_DISTANCE            = 5.0
    const val OF_WIN_SIZE             = 15

    // ── Kalman noise ──
    const val PROCESS_NOISE           = 1e-2
    const val MEASUREMENT_NOISE       = 1e-1

    // ── Distance ──
    const val MIN_RELIABLE_Z          = 0.2
    const val MAX_RELIABLE_Z          = 4.0
    const val MIN_TOTAL_ANGLE_DEG     = 3.0
    const val Z_EMA_BETA              = 0.35f
    const val CAMERA_HEIGHT_RATIO     = 0.6

    // ── Tilt ──
    const val TILT_DEADBAND_DEG       = 1.0

    // ── Alert ──
    const val THRESHOLD_CRITICAL_M    = 0.8
    const val THRESHOLD_NEAR_M        = 1.5
    const val THRESHOLD_MID_M         = 3.0
    const val COOLDOWN_CRITICAL_MS    = 1500L
    const val COOLDOWN_NEAR_MS        = 2500L
    const val COOLDOWN_MID_MS         = 4000L

    // ── Scene change ──
    const val SCENE_CHANGE_MIN_RATIO  = 0.35
    const val SCENE_CHANGE_MAX_RATIO  = 2.8

    // ── User input ──
    const val MIN_USER_HEIGHT_CM      = 100
    const val MAX_USER_HEIGHT_CM      = 220
    const val PREF_KEY_HEIGHT         = "user_height_cm"

    // ── Gemini Describe ──
    const val DESCRIBE_COOLDOWN_MS       = 5000L
    const val DESCRIBE_TIMEOUT_MS        = 10000L
    const val DESCRIBE_SUPPRESS_ALERT_MS = 10000L
    const val DESCRIBE_IMAGE_MAX_SIDE    = 640
    const val GEMINI_MODEL_NAME          = "gemini-2.5-flash"
}
