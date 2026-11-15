package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The Help screen's one write: a general problem report. It uses the same
 * sink the Detail screen's broken-stream report uses
 * ([CatalogRepository.submitContact], category "bug") — a report from Help
 * names no title, so there is no link to mark dead, only the viewer's own
 * words for the operator.
 */
@HiltViewModel
class HelpViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val _reportState = MutableStateFlow(HelpReportState())
    val reportState = _reportState.asStateFlow()

    fun submitReport(note: String) {
        if (_reportState.value.isSubmitting) return
        _reportState.update { it.copy(isSubmitting = true, message = null) }
        viewModelScope.launch {
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
