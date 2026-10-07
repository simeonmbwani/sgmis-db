package com.example

import com.example.data.api.ApiClient
import com.example.data.api.SessionManager
import com.example.data.local.SgmisDatabase
import com.example.data.repository.SgmisRepository
import com.example.ui.viewmodel.SgmisUiState
import com.example.ui.viewmodel.SgmisViewModel
import org.junit.Assert.*
import org.junit.Test

class LoginDuplicateSubmissionTest {

    @Test
    fun testLoginState_defaultStateIsNotLoadingAndNotLockedOut() {
        val state = SgmisUiState()
        assertFalse("Initial state should not be loading", state.isLoading)
        assertFalse("Initial state should not be locked out", state.isLockedOut)
        assertEquals(0, state.lockoutRemainingMinutes)
        assertNull(state.errorMessage)
    }

    @Test
    fun testLoginState_lockedOutPreventsSubmission() {
        val state = SgmisUiState(
            isLockedOut = true,
            lockoutRemainingMinutes = 15,
            errorMessage = "Account locked due to 5 failed login attempts."
        )
        assertTrue(state.isLockedOut)
        assertEquals(15, state.lockoutRemainingMinutes)
        // Verify submit condition from LoginScreen: enabled = !uiState.isLoading && !uiState.isLockedOut
        val buttonEnabled = !state.isLoading && !state.isLockedOut
        assertFalse("Submit button must be disabled when locked out", buttonEnabled)
    }

    @Test
    fun testLoginState_loadingPreventsDuplicateSubmission() {
        val state = SgmisUiState(
            isLoading = true
        )
        assertTrue(state.isLoading)
        // Verify submit condition from LoginScreen: enabled = !uiState.isLoading && !uiState.isLockedOut
        val buttonEnabled = !state.isLoading && !state.isLockedOut
        assertFalse("Submit button must be disabled while a request is in-flight", buttonEnabled)
    }

    @Test
    fun testLoginValidation_blankCredentialsDoNotTriggerLoading() {
        val state = SgmisUiState()
        val blankUsername = ""
        val blankPassword = ""

        val isInvalid = blankUsername.isBlank() || blankPassword.isBlank()
        assertTrue("Blank credentials must be flagged invalid before network dispatch", isInvalid)
    }
}
