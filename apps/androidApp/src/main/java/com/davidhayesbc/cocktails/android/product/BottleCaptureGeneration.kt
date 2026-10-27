package com.davidhayesbc.cocktails.android.product

/** Token used to discard late camera, decode, and crop completions after the user changes bottles. */
internal data class BottleCaptureToken(val generation: Long, val draftId: String?)

internal fun bottleCaptureTempFileName(token: BottleCaptureToken): String =
    "capture-${token.generation}-${token.draftId?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,100}")) } ?: "manual"}.jpg"

internal fun isCurrentBottleCapture(
    token: BottleCaptureToken,
    generation: Long,
    activeDraftId: String?,
): Boolean = token.generation == generation && token.draftId == activeDraftId

internal fun restoreBottleCaptureToken(
    launchPending: Boolean,
    generation: Long,
    draftId: String?,
    manualCapture: Boolean,
    fileName: String?,
): BottleCaptureToken? {
    if (!launchPending || generation <= 0) return null
    val token = if (manualCapture) BottleCaptureToken(generation, null) else {
        val safeDraftId = draftId?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,100}")) } ?: return null
        BottleCaptureToken(generation, safeDraftId)
    }
    return token.takeIf { fileName == bottleCaptureTempFileName(it) }
}

internal fun bottleCaptureDraftIsStillOpen(token: BottleCaptureToken, openDraftIds: Set<String>): Boolean =
    token.draftId == null || token.draftId in openDraftIds
