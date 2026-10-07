package com.circlecounter.app.domain

/**
 * Geographic point with optional timestamp (epoch millis) and accuracy meters.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long = 0L,
    val accuracyMeters: Float? = null,
) {
    fun toLatLngPair(): Pair<Double, Double> = latitude to longitude
}
