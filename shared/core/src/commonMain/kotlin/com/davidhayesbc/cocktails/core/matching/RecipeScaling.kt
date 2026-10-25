package com.davidhayesbc.cocktails.core.matching

/** Scales a recipe quantity proportionally; null amounts stay unspecified. */
fun scaleRecipeAmount(amount: Double?, originalServings: Double, targetServings: Double): Double? {
    if (amount == null || !amount.isFinite() || !originalServings.isFinite() || !targetServings.isFinite()) return null
    if (originalServings <= 0.0 || targetServings <= 0.0) return null
    val scaled = amount * targetServings / originalServings
    return scaled.takeIf(Double::isFinite)
}
