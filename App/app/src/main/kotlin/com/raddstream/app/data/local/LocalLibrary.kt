package com.raddstream.app.data.local

/**
 * One folder of the phone's own videos, for the Local Library's folder
 * level: the folder, how many videos it holds, and the newest video first.
 * The folder's thumbnail is the newest video's keyframe, loaded separately —
 * a [LocalVideoFile] carries no bitmap, so this stays pure and JVM-tested.
 *
 * [path] is the full parent path (`Movies/RaddStream`, or an absolute
 * `/storage/…/Movies` on older releases) — the grouping identity. Two
 * folders can share a [displayName] from different parents; then each one
 * carries its parent segment in [parentHint] so the row can tell them apart.
 * [videos] is newest first; [newestVideoUri] is the thumbnail's source.
 */
data class LocalVideoFolder(
    val path: String,
    val displayName: String,
    val videos: List<LocalVideoFile>,
    val parentHint: String?,
) {
    val videoCount: Int get() = videos.size
    val newestVideoUri: String? get() = videos.firstOrNull()?.uri
    val newestAddedMs: Long get() = videos.firstOrNull()?.dateAddedMs ?: 0L
}

/**
 * The parent folder a cursor row came from, as a grouping identity. A
 * `relative_path` already is the folder (`Movies/RaddStream/`); a `_data`
 * path carries the file after its folder, so the folder is everything
 * before the last `/`. Empty when the provider stored neither — the row
 * still belongs to the library, under "On this phone".
 */
fun localVideoFolderPath(relativePath: String?, dataPath: String?): String {
    relativePath?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { return it }
    val data = dataPath?.trim().orEmpty()
    if (data.isEmpty()) return ""
    return data.substringBeforeLast('/', "")
}

/**
 * The name a folder row paints: the last segment of its path
 * (`Movies/RaddStream` → `RaddStream`). A path with no segment is the
 * honest fallback, never blank.
 */
fun localFolderDisplayName(folderPath: String): String {
    // The one-argument substringAfterLast returns the whole string when
    // there is no '/' — a top-level folder ("Movies") is its own name.
    // The two-argument form with "" would answer "" there and this would
    // fall back to "On this phone" for every top-level folder.
    val name = folderPath.trim().trimEnd('/').substringAfterLast('/')
    return name.ifEmpty { "On this phone" }
}

/**
 * The MediaStore query for the whole library: every folder on the phone,
 * only .mp4/.mkv/.webm, by MIME or extension — the same format clause the
 * old Movies/Downloads-only query used, with the folder clause dropped.
 * The old `localVideoSelection` is gone with the scope it encoded: a
 * library that only showed two folders was not a library.
 */
fun localLibrarySelection(): Pair<String, Array<String>> {
    val selection = "(mime_type IN (?, ?, ?) OR " +
        "_display_name LIKE ? OR _display_name LIKE ? OR _display_name LIKE ?)"
    val args = arrayOf(
        "video/mp4", "video/x-matroska", "video/webm",
        "%.mp4", "%.mkv", "%.webm",
    )
    return selection to args
}

/**
 * Videos into folders, for the library's first level. Folders sort by their
 * newest video first (the folder you just saved to is on top), then by name
 * so the order is stable when two folders share a newest timestamp; videos
 * inside a folder are newest first, then by title. A folder whose display
 * name is not unique gets its parent segment as [LocalVideoFolder.parentHint].
 */
fun groupVideosIntoFolders(videos: List<LocalVideoFile>): List<LocalVideoFolder> {
    val byPath = videos.groupBy { it.folderPath }
    val folders = byPath.map { (path, folderVideos) ->
        val sorted = folderVideos.sortedWith(
            compareByDescending<LocalVideoFile> { it.dateAddedMs }
                .thenBy { it.title.lowercase() }
                .thenBy { it.uri },
        )
        LocalVideoFolder(
            path = path,
            displayName = localFolderDisplayName(path),
            videos = sorted,
            parentHint = null,
        )
    }
    val collidingNames = folders.groupBy { it.displayName }
        .filterValues { it.size > 1 }.keys
    return folders.map { folder ->
        if (folder.displayName !in collidingNames) {
            folder
        } else {
            folder.copy(parentHint = parentSegment(folder.path))
        }
    }.sortedWith(
        compareByDescending<LocalVideoFolder> { it.newestAddedMs }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.path },
    )
}

/** The segment above the leaf (`Movies/Action` → `Movies`); null at the top. */
private fun parentSegment(folderPath: String): String? {
    val trimmed = folderPath.trim().trimEnd('/')
    val parent = trimmed.substringBeforeLast('/', "")
    if (parent.isEmpty()) return null
    // One-argument substringAfterLast: a top-level parent ("Movies") is
    // its own last segment; the two-argument form with "" answers "" there.
    return parent.substringAfterLast('/')
}
