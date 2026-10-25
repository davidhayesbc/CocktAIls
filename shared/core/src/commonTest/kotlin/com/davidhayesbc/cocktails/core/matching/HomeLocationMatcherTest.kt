package com.davidhayesbc.cocktails.core.matching

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLocationMatcherTest {
    @Test
    fun matchesOnlyHomeWithinConfiguredRadius() {
        val areas = listOf(
            HomeLocationArea("home-a", 37.4219999, -122.0840575, 250.0),
            HomeLocationArea("home-b", 47.6062, -122.3321, 250.0),
        )

        assertEquals(HomeLocationMatch.Matched("home-a"), matchHomeLocation(37.4220, -122.0840, areas))
        assertEquals(HomeLocationMatch.NoMatch, matchHomeLocation(37.43, -122.0840, areas))
    }

    @Test
    fun overlappingAreasRequireManualChoice() {
        val areas = listOf(
            HomeLocationArea("home-b", 0.0, 0.0, 500.0),
            HomeLocationArea("home-a", 0.0, 0.001, 500.0),
        )

        assertEquals(HomeLocationMatch.Ambiguous(listOf("home-a", "home-b")), matchHomeLocation(0.0, 0.0005, areas))
    }

    @Test
    fun invalidCoordinatesAndAreasNeverCauseASelection() {
        val areas = listOf(
            HomeLocationArea("invalid", Double.NaN, 0.0, 250.0),
            HomeLocationArea("bad-radius", 0.0, 0.0, Double.NaN),
        )

        assertEquals(HomeLocationMatch.NoMatch, matchHomeLocation(91.0, 0.0, areas))
        assertEquals(HomeLocationMatch.NoMatch, matchHomeLocation(0.0, 0.0, areas))
    }
}
