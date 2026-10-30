package com.fcitx5sensevoice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRequestTest {
    private val request = DraftRequest(1, 2, 3, 4)
    @Test fun unchangedActiveDraftIsAccepted() {
        assertTrue(request.canApply(request.copy(), true, false))
    }
    @Test fun editedOrClearedDraftRejectsOldResult() {
        assertFalse(request.canApply(request.copy(revision = 5), true, false))
    }
    @Test fun newRequestRejectsOldResult() {
        assertFalse(request.canApply(request.copy(request = 5), true, false))
    }
    @Test fun newInputFieldRejectsOldResult() {
        assertFalse(request.canApply(request.copy(session = 5), true, false))
    }
    @Test fun newRecordingRejectsOldResult() {
        assertFalse(request.canApply(request.copy(recording = 5), true, false))
        assertFalse(request.canApply(request, true, true))
    }
    @Test fun hiddenKeyboardRejectsOldResult() {
        assertFalse(request.canApply(request, false, false))
    }
}
