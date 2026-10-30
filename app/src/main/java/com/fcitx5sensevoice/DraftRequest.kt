package com.fcitx5sensevoice

internal data class DraftRequest(
    val request: Long,
    val session: Long,
    val revision: Long,
    val recording: Long,
) {
    fun canApply(current: DraftRequest, active: Boolean, recordingActive: Boolean): Boolean =
        this == current && active && !recordingActive
}
