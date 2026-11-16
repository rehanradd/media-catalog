package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.network.models.AccountEntitlement
import com.raddstream.app.sync.SyncAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the account screens show, in one state. Session 123: the app
 * had no profile and no dashboard. These screens read what the server
 * already publishes (`entitlement`, `account`) and write nothing. (The
 * coin balance, the streak, the ladder, the pass catalog and the ledger
 * history lived here until coins and daily check-in were removed
 * 2026-11-17.)
 */
data class AccountOverviewUiState(
    val isLoading: Boolean = false,
    val signedIn: Boolean = false,
    val loadError: String? = null,
    /** True when the server says this account holds a live membership. */
    val isVip: Boolean = false,
    /** The membership's end, on the server's clock (epoch seconds). */
    val vipUntil: Double = 0.0,
    /** When this account was created, on the server's clock — null when unread. */
    val createdAt: Double? = null,
    val lastSeenAt: Double? = null,
    /**
     * Free launch: the VIP upsell renders only while the operator's
     * quality gate is ON. The gate flag is the free-launch switch — gate
     * OFF means every viewer is premium. Defaults true (gate on) so a
     * build that has not heard from the panel yet behaves exactly as today.
     */
    val paywallVisible: Boolean = true,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val syncAccountRepository: SyncAccountRepository,
    private val settingsRepository: ServerSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountOverviewUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Free launch: the quality-gate flag is the free-launch switch.
        // Gate OFF hides the paywall surfaces on the account screens.
        viewModelScope.launch {
            settingsRepository.qualityGateEnabled.collect { enabled ->
                _uiState.update { it.copy(paywallVisible = enabled) }
            }
        }
        viewModelScope.launch {
            syncAccountRepository.signedIn.collect { signedIn ->
                _uiState.update { it.copy(signedIn = signedIn) }
                // A guest has nothing to read, so the screens must not sit
                // on "loading" forever waiting for a read that never comes.
                if (signedIn) refresh() else _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** One read of everything the account screens show. A failure leaves the last good values standing and says so, never zeroes them. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            try {
                val entitlement: AccountEntitlement? = syncAccountRepository.refreshEntitlement()
                val info = syncAccountRepository.loadAccountInfo()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isVip = entitlement?.vip ?: it.isVip,
                        vipUntil = entitlement?.vipUntil ?: it.vipUntil,
                        createdAt = info?.createdAt ?: it.createdAt,
                        lastSeenAt = info?.lastSeenAt ?: it.lastSeenAt,
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadError = "Could not reach the server — showing what was last read.",
                    )
                }
            }
        }
    }

    /** Sign out on this device: the key is dropped locally and the server is told to revoke it. */
    fun signOut() {
        viewModelScope.launch {
            syncAccountRepository.signOut()
            _uiState.update {
                AccountOverviewUiState(signedIn = false)
            }
        }
    }
}
