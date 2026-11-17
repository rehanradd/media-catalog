package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Help screen's "Report a problem" row is doing: sending, sent,
 * or the reason it could not send.
 */
data class HelpReportState(
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * The two server facts the FAQ answers depend on. The quality cap and the
 * VIP prices are the operator's, not the client's: the gate flag is the
 * free-launch switch (gate OFF means every viewer plays up to 4K), and the
 * prices are the panel's published ones (0 means "not published", and the
 * FAQ drops the numbers rather than printing a zero). Defaults match the
 * build that has not heard from the panel yet — gate ON, no prices — so a
 * cold Help open reads exactly as today until the first answers land.
 */
data class HelpUiState(
    val gateOn: Boolean = true,
    val monthlyPkr: Int = 0,
    val yearlyPkr: Int = 0,
)

/**
 * The Help screen's one write: a general problem report. It uses the same
 * sink the Detail screen's broken-stream report uses
 * ([CatalogRepository.submitContact], category "bug") — a report from Help
 * names no title, so there is no link to mark dead, only the viewer's own
 * words for the operator.
 */
@HiltViewModel
class HelpViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val settingsRepository: ServerSettingsRepository,
) : ViewModel() {

    private val _reportState = MutableStateFlow(HelpReportState())
    val reportState = _reportState.asStateFlow()

    private val _uiState = MutableStateFlow(HelpUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Settings review (Nov 2026, D1/D8): the FAQ's Watch cap and VIP
        // prices are read from the server when the screen opens, the same
        // way Settings reads them — the gate flag is the same flow the VIP
        // card's paywallVisible collects, and the prices are the same
        // remote-config fetch observePlan runs. A failed fetch leaves the
        // defaults, so Help degrades to today's copy, never to a crash.
        viewModelScope.launch {
            settingsRepository.qualityGateEnabled.collect { enabled ->
                _uiState.update { it.copy(gateOn = enabled) }
            }
        }
        viewModelScope.launch {
            runCatching { catalogRepository.getRemoteConfig() }.onSuccess { config ->
                _uiState.update {
                    it.copy(
                        monthlyPkr = config.vip.monthlyPkr.takeIf { price -> price > 0 } ?: 0,
                        yearlyPkr = config.vip.yearlyPkr.takeIf { price -> price > 0 } ?: 0,
                    )
                }
            }
        }
    }

    fun submitReport(note: String) {
        if (_reportState.value.isSubmitting) return
        _reportState.update { it.copy(isSubmitting = true, message = null) }
        viewModelScope.launch {
            // Settings review (Nov 2026, D14): the same no-server guard
            // every other server write on Settings has. A report is a row
            // on a server, and with no address there is nowhere to send it
            // — so the failure names that, instead of the generic "try
            // again" every other failure gets.
            val serverUrl = settingsRepository.serverUrl.first().orEmpty()
            if (serverUrl.isBlank()) {
                _reportState.update {
                    it.copy(
                        isSubmitting = false,
                        message = "No server is connected yet, so there is nowhere to send the report.",
                        messageIsError = true,
                    )
                }
                return@launch
            }
            val sent = catalogRepository.submitContact(
                category = "bug",
                subject = "Problem report (Help)",
                body = note.trim().ifBlank { "Reported a problem from the Help screen." },
                accountHint = null,
            )
            _reportState.update {
                it.copy(
                    isSubmitting = false,
                    message = if (sent) "Thanks — we've sent your report."
                    else "Couldn't send the report right now. Please try again.",
                    messageIsError = !sent,
                )
            }
        }
    }
}
