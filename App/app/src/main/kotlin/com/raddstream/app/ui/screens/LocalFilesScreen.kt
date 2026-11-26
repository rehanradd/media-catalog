package com.raddstream.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.data.local.LocalVideoFile
import com.raddstream.app.data.local.LocalVideoFolder
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.ui.components.rememberDelayedVisible
import com.raddstream.app.viewmodel.LocalFilesViewModel
import com.raddstream.app.viewmodel.formatTime
import com.raddstream.app.ui.theme.RaddTokens

/**
 * The phone's own videos as a Local Library — folders first, like MX
 * Player, not one flat list. The first level is every folder on the phone
 * (folder name, video count, a real thumbnail from its newest video), the
 * newest folder on top; tap a folder to see its videos, tap a video to
 * play it in the same local player as a finished download. Nothing here
 * touches the server: no search, no catalog, no upload. The player
 * remembers where it stopped, even after the app restarts.
 *
 * The permission is asked once, on first entry. A denied answer shows the
 * honest state with a retry and a way to the app's settings — the screen
 * never pretends the phone has no videos when it was never allowed to look.
 * Back inside a folder returns to the folder list; back on the list leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesScreen(
    onBackClick: () -> Unit,
    onPlayLocal: (uri: String, title: String) -> Unit = { _, _ -> },
    viewModel: LocalFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val selectedFolder = state.selectedFolder
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.load()
        } else if (!asked) {
            asked = true
            permissionLauncher.launch(permission)
        } else {
            viewModel.onPermissionResult(false)
        }
    }
    BackHandler(enabled = selectedFolder != null) { viewModel.backToFolders() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(selectedFolder?.displayName ?: stringResource(R.string.local_library_title))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedFolder != null) viewModel.backToFolders()
                        else onBackClick()
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_action_arrow_left),
                            contentDescription = stringResource(R.string.local_library_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (state.needsPermission) permissionLauncher.launch(permission)
                        else viewModel.refresh()
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_action_refresh),
                            contentDescription = stringResource(R.string.local_library_refresh),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> {
                // S5: a local scan that lands in under 250ms never flashes
                // a spinner — the screen stays blank until the wait proves
                // long, then the spinner appears.
                val showSpinner = rememberDelayedVisible(true)
                Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showSpinner) CircularProgressIndicator()
                }
            }
            state.needsPermission -> Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.local_library_permission_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.local_library_permission_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(permission) }, shape = RaddTokens.Shape.Pill) {
                    Text(stringResource(R.string.local_library_permission_allow))
                }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }) {
                    Text(stringResource(R.string.local_library_open_settings))
                }
            }
            state.loadFailed -> Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.local_library_load_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.refresh() }, shape = RaddTokens.Shape.Pill) {
                    Text(stringResource(R.string.local_library_retry))
                }
            }
            state.folders.isEmpty() -> Box(Modifier.fillMaxSize().padding(contentPadding)) {
                EmptyState(
                    title = stringResource(R.string.local_library_empty_title),
                    message = stringResource(R.string.local_library_empty_message),
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_action_folder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            selectedFolder != null -> LocalFolderVideosContent(
                folder = selectedFolder,
                onPlay = { file -> onPlayLocal(file.uri, file.title) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
            else -> LocalFolderListContent(
                folders = state.folders,
                thumbnails = state.thumbnails,
                onOpenFolder = { folder -> viewModel.selectFolder(folder.path) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }
}

/**
 * The first level: one row per folder — the folder's name, how many videos
 * it holds, and a thumbnail from its newest video. Folders arrive sorted,
 * newest folder first; the order is the ViewModel's, not this list's.
 */
@Composable
internal fun LocalFolderListContent(
    folders: List<LocalVideoFolder>,
    thumbnails: Map<String, Bitmap>,
    onOpenFolder: (LocalVideoFolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folders, key = { it.path }) { folder ->
            LocalFolderRow(
                folder = folder,
                thumbnail = thumbnails[folder.path],
                onOpen = { onOpenFolder(folder) },
            )
        }
    }
}

/** One folder row: its newest video's keyframe, its name, its count. */
@Composable
private fun LocalFolderRow(
    folder: LocalVideoFolder,
    thumbnail: Bitmap?,
    onOpen: () -> Unit,
) {
    val countText = if (folder.videoCount == 1) {
        stringResource(R.string.local_library_videos_one)
    } else {
        stringResource(R.string.local_library_videos_many, folder.videoCount)
    }
    val subtitle = if (folder.parentHint != null) {
        "$countText · ${folder.parentHint}"
    } else {
        countText
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .testTag("localFolder_${folder.path}")
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A real keyframe when the provider has one; below API 29, or
            // when the media has no thumbnail, the dark tile with a folder
            // mark — never an invented picture.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_action_folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painterResource(R.drawable.ic_action_chevron_right),
                contentDescription = stringResource(
                    R.string.local_library_open_folder,
                    folder.displayName,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The second level: the videos of one folder, newest first. */
@Composable
internal fun LocalFolderVideosContent(
    folder: LocalVideoFolder,
    onPlay: (LocalVideoFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(folder.videos, key = { it.uri }) { file ->
            LocalVideoRow(
                file = file,
                onPlay = { onPlay(file) },
            )
        }
    }
}

@Composable
internal fun LocalVideoRow(file: LocalVideoFile, onPlay: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A local file has no poster: a dark 2:3 tile with a play mark,
            // the same shape as the poster's tile elsewhere.
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_player_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = file.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    file.durationMs.takeIf { it > 0 }?.formatTime(),
                    file.sizeBytes.takeIf { it > 0 }?.let { QualityOption.formatBytes(it) },
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPlay() }
                    .semantics { role = Role.Button }
                    .testTag("localFile_play"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_player_play),
                    contentDescription = stringResource(R.string.local_library_play_video, file.title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
