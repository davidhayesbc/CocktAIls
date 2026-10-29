package com.davidhayesbc.cocktails.android.ui

/** Fences slow diagnostics reads and the single outstanding document-picker request. */
internal class DiagnosticsActionGate {
    data class Scope(val generation: Long, val draftId: String?)

    private var generation = 0L
    private var currentScope: Scope? = null
    private var exportInFlight: Scope? = null

    @Synchronized
    fun open(draftId: String?): Scope {
        generation += 1
        return Scope(generation, draftId).also { currentScope = it }
    }

    @Synchronized
    fun dismiss() {
        generation += 1
        currentScope = null
    }

    @Synchronized
    fun scopeFor(draftId: String?): Scope? = currentScope?.takeIf { it.draftId == draftId }

    @Synchronized
    fun isCurrent(scope: Scope): Boolean = currentScope == scope

    @Synchronized
    fun beginExport(scope: Scope): Boolean {
        if (currentScope != scope || exportInFlight != null) return false
        exportInFlight = scope
        return true
    }

    /** Returns false for a dismissed/replaced dialog, while always releasing its picker slot. */
    @Synchronized
    fun finishExport(scope: Scope): Boolean {
        if (exportInFlight != scope) return false
        exportInFlight = null
        return currentScope == scope
    }

    @Synchronized
    fun cancelExport(scope: Scope) {
        if (exportInFlight == scope) exportInFlight = null
    }
}
