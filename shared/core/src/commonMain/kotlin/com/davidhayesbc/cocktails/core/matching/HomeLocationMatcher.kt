package com.davidhayesbc.cocktails.core.matching

/** A device-local circular area configured for one household home. */
data class HomeLocationArea(
    val locationId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

sealed interface HomeLocationMatch {
    data class Matched(val locationId: String) : HomeLocationMatch
    data class Ambiguous(val locationIds: List<String>) : HomeLocationMatch
    data object NoMatch : HomeLocationMatch
}

/** Match only a single home. Overlapping areas require a user decision. */
fun matchHomeLocation(
    latitude: Double,
    longitude: Double,
    areas: List<HomeLocationArea>,
): HomeLocationMatch {
    if (!latitude.isValidLatitude() || !longitude.isValidLongitude()) return HomeLocationMatch.NoMatch

    val matches = areas.asSequence()
        .filter { it.latitude.isValidLatitude() && it.longitude.isValidLongitude() }
        .filter { it.radiusMeters.isFinite() && it.radiusMeters > 0.0 }
        .filter { distanceMeters(latitude, longitude, it.latitude, it.longitude) <= it.radiusMeters }
        .map(HomeLocationArea::locationId)
        .distinct()
        .sorted()
        .toList()

    return when (matches.size) {
        0 -> HomeLocationMatch.NoMatch
        1 -> HomeLocationMatch.Matched(matches.single())
        else -> HomeLocationMatch.Ambiguous(matches)
    }
}

private fun distanceMeters(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDelta = degreesToRadians(latitudeB - latitudeA)
    val longitudeDelta = degreesToRadians(longitudeB - longitudeA)
    val latitudeARadians = degreesToRadians(latitudeA)
    val latitudeBRadians = degreesToRadians(latitudeB)
    val haversine =
        sinSquared(latitudeDelta / 2.0) +
            kotlin.math.cos(latitudeARadians) * kotlin.math.cos(latitudeBRadians) * sinSquared(longitudeDelta / 2.0)
    val centralAngle = 2.0 * kotlin.math.asin(kotlin.math.sqrt(haversine.coerceIn(0.0, 1.0)))
    return earthRadiusMeters * centralAngle
}

private fun sinSquared(value: Double): Double {
    val sine = kotlin.math.sin(value)
    return sine * sine
}

private fun degreesToRadians(degrees: Double): Double = degrees * (kotlin.math.PI / 180.0)

private fun Double.isValidLatitude(): Boolean = isFinite() && this in -90.0..90.0

private fun Double.isValidLongitude(): Boolean = isFinite() && this in -180.0..180.0
