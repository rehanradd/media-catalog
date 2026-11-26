package com.raddstream.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.network.models.Person
import com.raddstream.app.network.models.Post
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the Actor screen knows about the person lookup itself, apart
 * from the works list. A 404 from `/api/v1/person` is [NotFound] — the
 * server knows nobody by that name, so the screen shows an initials
 * circle and the works for the raw name, never a wrong photo. Any other
 * failure (503 outage, no key server-side, network) is [Unavailable]:
 * the same header, plus the "details unavailable" line.
 */
enum class PersonState { Loading, Found, NotFound, Unavailable }

/**
 * Actor screen state. [rawName] is the scraped cast string from the
 * detail row; [canonicalName] is TMDb's name for the same person once
 * the lookup lands, and it is what the works search uses — a short raw
 * name is noisy server-side (`q=Dina` matches 601 works), while the
 * canonical name is the person, not the string.
 */
data class ActorUiState(
    val rawName: String = "",
    val canonicalName: String? = null,
    val person: Person? = null,
    val personState: PersonState = PersonState.Loading,
    val works: List<Post> = emptyList(),
    val worksLoading: Boolean = true,
    val worksError: Boolean = false,
)

/**
 * One actor: their TMDb photo + bio from `GET /api/v1/person`, then
 * their titles on this server from the existing posts search. The
 * person lookup runs first because the works query wants the canonical
 * name; the works load runs even when the lookup fails, so a tap on a
 * name TMDb never heard of still lands on that name's titles instead
 * of an error screen.
 */
@HiltViewModel
class ActorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    /**
     * The nav argument arrives through [Screen.Actor]'s codec. The
     * navigation component decodes percent-escapes on the way in, so a
     * second decode would turn a literal `+` in a name into a space —
     * decode only when an escape is still visible. (Parsed cast names
     * hold no `%` or `+` anyway — see `parseCastNames` — so this is a
     * guard, not a path the app relies on.)
     */
    private val actorName: String =
        ((savedStateHandle[Screen.Actor.ARG_NAME] as? String) ?: "")
            .let { raw -> if ('%' in raw) Screen.Actor.decode(raw) else raw }

    private val _uiState = MutableStateFlow(ActorUiState(rawName = actorName))
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    /** Re-runs the whole load (the works error state's Retry button). */
    fun retry() {
        load()
    }

    private fun load() {
        if (actorName.isBlank()) {
            _uiState.update {
                it.copy(
                    personState = PersonState.NotFound,
                    worksLoading = false,
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                personState = PersonState.Loading,
                worksLoading = true,
                worksError = false,
            )
        }
        viewModelScope.launch {
            val person = try {
                catalogRepository.getPerson(actorName)
            } catch (ex: CancellationException) {
                // A cancelled load is not a failed load: rethrow so the
                // coroutine ends cancelled instead of painting an error
                // over a screen the viewer already left.
                throw ex
            } catch (_: Exception) {
                // 503 / outage / network: the header still renders, with
                // the "details unavailable" line — the works load below
                // decides the page, not this failure.
                _uiState.update { it.copy(personState = PersonState.Unavailable) }
                null
            }
            if (person != null) {
                _uiState.update {
                    it.copy(
                        person = person,
                        canonicalName = person.name,
                        personState = PersonState.Found,
                    )
                }
            } else if (_uiState.value.personState == PersonState.Loading) {
                // Null with no exception is the repository's 404: no such
                // person. (The Unavailable branch above already set its
                // own state, so this only fires on the true 404.)
                _uiState.update { it.copy(personState = PersonState.NotFound) }
            }
            // Works by the canonical name when the lookup found one, else
            // the raw scraped name — the honest fallback, noisy or not.
            val worksQuery = person?.name?.takeIf { it.isNotBlank() } ?: actorName
            try {
                val page = catalogRepository.getPosts(
                    query = worksQuery,
                    page = 1,
                    limit = 30,
                )
                _uiState.update {
                    it.copy(works = page.results, worksLoading = false)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                _uiState.update { it.copy(worksLoading = false, worksError = true) }
            }
        }
    }
}
