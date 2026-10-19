package com.circlecounter.app.domain

/**
 * Geographic point with optional timestamp (epoch millis) and accuracy meters.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long = 0L,
    val accuracyMeters: Float? = null,
    /** Fused location speed m/s, when the provider reported it. */
    val speedMps: Float? = null,
) {
    fun toLatLngPair(): Pair<Double, Double> = latitude to longitude
}
