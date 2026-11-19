package com.raddstream.app.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.local.LocalVideoFolder
import com.raddstream.app.data.local.LocalVideosDataSource
import com.raddstream.app.data.local.groupVideosIntoFolders
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocalFilesUiState(
    val isLoading: Boolean = true,
    val needsPermission: Boolean = false,
    val folders: List<LocalVideoFolder> = emptyList(),
    val selectedFolderPath: String? = null,
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val loadFailed: Boolean = false,
) {
    /** The folder the viewer is inside, if any — null on the folder list. */
    val selectedFolder: LocalVideoFolder?
        get() = folders.firstOrNull { it.path == selectedFolderPath }
}

/**
 * The phone's own videos, as the Local Library: folders first, newest
 * folder on top, one real thumbnail per folder from its newest video. The
 * screen owns the permission question — this class only answers it: granted
 * loads the folders, denied shows the honest state with a way back to
 * settings. A revoked permission mid-read answers as an empty list from the
 * repository, never as a crash. A refresh keeps the viewer inside the folder
 * they were in, when that folder still exists.
 */
@HiltViewModel
class LocalFilesViewModel @Inject constructor(
    private val dataSource: LocalVideosDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalFilesUiState())
    val uiState: StateFlow<LocalFilesUiState> = _uiState.asStateFlow()

    fun onPermissionResult(granted: Boolean) {
        if (granted) load() else {
            _uiState.value = LocalFilesUiState(isLoading = false, needsPermission = true)
        }
    }

    fun load() {
        viewModelScope.launch {
            val previousSelection = _uiState.value.selectedFolderPath
            _uiState.value = LocalFilesUiState(isLoading = true)
            runCatching { dataSource.loadVideos() }
                .onSuccess { videos ->
                    val folders = groupVideosIntoFolders(videos)
                    val thumbnails = mutableMapOf<String, Bitmap>()
                    for (folder in folders) {
                        val uri = folder.newestVideoUri ?: continue
                        runCatching { dataSource.loadThumbnail(uri) }
                            .getOrNull()
                            ?.let { thumbnails[folder.path] = it }
                    }
                    val keptSelection = previousSelection
                        ?.takeIf { path -> folders.any { it.path == path } }
                    _uiState.value = LocalFilesUiState(
                        isLoading = false,
                        folders = folders,
                        selectedFolderPath = keptSelection,
                        thumbnails = thumbnails,
                    )
                }
                .onFailure {
                    _uiState.value = LocalFilesUiState(isLoading = false, loadFailed = true)
                }
        }
    }

    fun refresh() = load()

    /** Open a folder; an unknown path is ignored, never a crash. */
    fun selectFolder(path: String) {
        if (_uiState.value.folders.none { it.path == path }) return
        _uiState.value = _uiState.value.copy(selectedFolderPath = path)
    }

    fun backToFolders() {
        _uiState.value = _uiState.value.copy(selectedFolderPath = null)
    }

    /**
     * True when the back press was consumed — the viewer was inside a
     * folder and is now back on the folder list. False means the screen
     * itself should go back.
     */
    fun onBackPressed(): Boolean {
        if (_uiState.value.selectedFolderPath == null) return false
        backToFolders()
        return true
    }
}
