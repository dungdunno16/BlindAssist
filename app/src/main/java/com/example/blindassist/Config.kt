package com.example.blindassist

object Config {

    // ── MiDaS ──
    const val MIDAS_INPUT_SIZE        = 256

    // ── Detection ──
    const val FOREGROUND_RATIO        = 0.35f
    const val MIN_CONTOUR_AREA        = 800
    const val MAX_CONTOUR_RATIO       = 0.7f
    const val MERGE_IOU_THRESHOLD     = 0.1f
    const val MERGE_GAP_PX            = 25

    // ── Tracking ──
    const val MAX_MISSED_FRAMES       = 8
    const val IOU_MATCH_THRESHOLD     = 0.25f

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
}
