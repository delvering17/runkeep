package com.runkeep.core

enum class DiagnosticTransferPhase { IDLE, PREPARING, SAVE_READY, SAVE_PICKER, WRITING, SHARE_READY, SHARE_PICKER }

data class DiagnosticTransferState(val phase: DiagnosticTransferPhase = DiagnosticTransferPhase.IDLE) {
    val busy get() = phase != DiagnosticTransferPhase.IDLE
    fun start() = if (busy) this else copy(phase = DiagnosticTransferPhase.PREPARING)
    fun prepared(share: Boolean) = if (phase != DiagnosticTransferPhase.PREPARING) this
        else copy(phase = if (share) DiagnosticTransferPhase.SHARE_READY else DiagnosticTransferPhase.SAVE_READY)
    fun launched() = when (phase) {
        DiagnosticTransferPhase.SAVE_READY -> copy(phase = DiagnosticTransferPhase.SAVE_PICKER)
        DiagnosticTransferPhase.SHARE_READY -> copy(phase = DiagnosticTransferPhase.SHARE_PICKER)
        else -> this
    }
    fun documentResult(hasUri: Boolean, hasContent: Boolean) = if (phase != DiagnosticTransferPhase.SAVE_PICKER) this
        else if (hasUri && hasContent) copy(phase = DiagnosticTransferPhase.WRITING) else DiagnosticTransferState()
    fun finish() = DiagnosticTransferState()
}
