package com.raddstream.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.data.local.LocalVideoFile
import com.raddstream.app.player.QualityOption
import com.raddstream.app.ui.components.EmptyState
import com.raddstream.app.viewmodel.LocalFilesViewModel
import com.raddstream.app.viewmodel.formatTime

/**
 * The phone's own videos — Movies/ and Download/, newest first — listed from
 * MediaStore and played through the same local player as a finished download.
 * Nothing here touches the server: no search, no catalog, no upload. A tap
 * plays the file; the player remembers where it stopped, even after the app
 * restarts.
 *
 * The permission is asked once, on first entry. A denied answer shows the
 * honest state with a retry and a way to the app's settings — the screen
 * never pretends the phone has no videos when it was never allowed to look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesScreen(
    onBackClick: () -> Unit,
    onPlayLocal: (uri: String, title: String) -> Unit = { _, _ -> },
    viewModel: LocalFilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Local files") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (state.needsPermission) permissionLauncher.launch(permission)
                        else viewModel.refresh()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.needsPermission -> Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Allow access to your videos",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Radd lists videos in Movies and Downloads so you can play them here. " +
                        "Nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(permission) }) {
                    Text("Allow access")
                }
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }) {
                    Text("Open settings")
                }
            }
            state.loadFailed -> Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Couldn't list your videos.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.refresh() }) {
                    Text("Try again")
                }
            }
            state.files.isEmpty() -> Box(Modifier.fillMaxSize().padding(contentPadding)) {
                EmptyState(
                    title = "No videos on this phone",
                    message = "Videos you save in Movies or Downloads will show up here.",
                    icon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> LocalFilesListContent(
                files = state.files,
                onPlay = { file -> onPlayLocal(file.uri, file.title) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }
}

/**
 * The list itself, grouped by the folder each file came from: one folder
 * row ("Movies — N files") with its files under it, newest first inside
 * each folder. The mockup shows a single folder because Stitch imagined one;
 * the phone has two (Movies and Downloads), so a folder earns its own row
 * rather than the list pretending they are one.
 *
 * Stitch sub-screens mockup (Nov 2026): each file is a card with a small
 * thumb, the title, the size line and a jade play button. A local file has
 * no poster art, so the thumb is a dark tile with a play mark — never an
 * invented picture — and the size line is the file's own duration and size:
 * there is no quality field on a local file, so no "1080p" is claimed.
 */
@Composable
internal fun LocalFilesListContent(
    files: List<LocalVideoFile>,
    onPlay: (LocalVideoFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which folders are folded shut; a list survives a rotation.
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    // groupBy keeps the folders in first-encounter order, and the files
    // arrive newest first, so each folder's rows stay newest first too.
    val groups = files.groupBy { it.folder }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (folder, folderFiles) ->
            val isCollapsed = folder in collapsed
            item(key = "folder:$folder") {
                FolderHeaderRow(
                    folder = folder,
                    count = folderFiles.size,
                    expanded = !isCollapsed,
                    onToggle = {
                        collapsed = if (isCollapsed) collapsed - folder
                        else collapsed + folder
                    },
                )
            }
            if (!isCollapsed) {
                items(folderFiles, key = { it.uri }) { file ->
                    LocalVideoRow(
                        file = file,
                        onPlay = { onPlay(file) },
                    )
                }
            }
        }
    }
}

/** One folder row: the folder, how many files it holds, fold it shut. */
@Composable
private fun FolderHeaderRow(
    folder: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .testTag("localFolder_$folder")
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = folder,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (count == 1) "1 file" else "$count files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse $folder" else "Expand $folder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Icons.Default.PlayArrow,
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
                    Icons.Default.PlayArrow,
                    contentDescription = "Play ${file.title}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
