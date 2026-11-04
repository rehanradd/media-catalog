package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.network.friendlyNetworkError
import com.raddstream.app.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val serverUrl: String = "",
    val isChecking: Boolean = false,
    val isConnected: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /**
     * The address field stays hidden until the viewer asks for it: the
     * shipped address is a value this screen holds, never a line it prints
     * on first sight.
     */
    val showUrlField: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val settingsRepository: ServerSettingsRepository,
) : ViewModel() {
    // Held, not shown: the address the brand file ships is the value the
    // Connect button tests and the field edits once revealed — reaching
    // this screen is a one-tap Connect instead of typing an address, and a
    // self-hoster who opens the field can still see the default they are
    // about to replace.
    private val _uiState = MutableStateFlow(SetupUiState(serverUrl = Brand.defaultServerUrl))
    val uiState = _uiState.asStateFlow()

    /** Open the address field: the viewer's explicit choice to see and change it. */
    fun revealUrlField() {
        _uiState.update { it.copy(showUrlField = true) }
    }

    fun onServerUrlChanged(value: String) {
        _uiState.update {
            it.copy(
                serverUrl = value,
                isConnected = false,
                message = null,
                messageIsError = false,
            )
        }
    }

    fun testConnection() {
        test(_uiState.value.serverUrl)
    }

    /**
     * Test the shipped server again, without ever showing its address: the
     * first-run probe can lose a slow network once, and a retry that prints
     * the address to reach it would be the leak this screen exists to close.
     */
    fun retryDefault() {
        test(Brand.defaultServerUrl)
    }

    private fun test(submittedUrl: String) {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, message = null, isConnected = false) }
            try {
                catalogRepository.testAndSaveServer(submittedUrl)
                _uiState.update {
                    it.copy(
                        serverUrl = com.raddstream.app.network.normalizeServerUrl(submittedUrl),
                        isChecking = false,
                        isConnected = true,
                        // B1: the success line prints no count — the stored
                        // total is not a browsable count, so the count
                        // itself is not the news. The count still lives in
                        // the log, not on the screen.
                        message = "Connected — you're ready to watch.",
                        messageIsError = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // No debug suffix here: the raw error can carry the address
                // this screen exists to hide, and the friendly wording
                // already says what the viewer can do about it.
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        isConnected = false,
                        message = friendlyNetworkError(error, "Could not reach the catalog — check your connection and try again.", debugBuild = false),
                        messageIsError = true,
                    )
                }
            }
        }
    }

    /**
     * The single question Continue must answer before it hands over: has this install already
     * been asked what it watches? A fresh install has not, and the region screen belongs on
     * that viewer's first launch, not on their second — which is what happened while Continue
     * walked straight to Home and left the launch gate in MainActivity to serve Region late.
     *
     * The flag is read at the moment of the tap, not cached at construction: the same tap that
     * opens the next screen must be judged against the store as it is then. A store that cannot
     * answer reads as "already asked", the identical rule MainActivity applies at launch —
     * onboarding is a convenience and must never become the door.
     */
    fun continuePressed(onConnected: (askRegion: Boolean) -> Unit) {
        viewModelScope.launch {
            val askRegion = try {
                !settingsRepository.regionChoiceRecorded.first()
            } catch (_: Exception) {
                false
            }
            onConnected(askRegion)
        }
    }
}
