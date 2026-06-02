package com.example.blindassist.gemini

data class SceneMetadata(
    val obstacleCount: Int,
    val obstacles: List<ObstacleInfo>,
    val tiltAngleDeg: Double?,
    val fps: Float
)

data class ObstacleInfo(
    val id: Int,
    val zone: String,          // "trái", "giữa", "phải"
    val distanceMeters: Double?,
    val hasReliableDistance: Boolean
)
