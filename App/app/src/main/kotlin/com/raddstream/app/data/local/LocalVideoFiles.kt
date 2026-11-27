package com.raddstream.app.data.local

/**
 * A video that already lives on this phone, found through MediaStore — the
 * Media Vault's "on this phone" list. The URI is the provider's own
 * `content://media/…` address for the row, so the player opens it exactly like
 * a finished download; the title is the file's display name, never a path.
 *
 * Everything in this file is pure — plain values in, plain values out — so the
 * query shape, the title rule and the folder rule are all pinned by JVM tests
 * without a provider. The [LocalFilesRepository] owns the Cursor.
 */
data class LocalVideoFile(
    val uri: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val dateAddedMs: Long,
    /**
     * The full parent path the row came from (`Movies/RaddStream`, or an
     * absolute `/storage/…/Movies` on older releases) — the library groups
     * by this, not by [folder], because two parents can share a leaf name.
     * Empty when the provider stored no path; the file still belongs to the
     * library, under "On this phone".
     */
    val folderPath: String = "",
)

/** The file name a row with no usable name is shown under. Never blank. */
const val LOCAL_VIDEO_UNTITLED = "Local video"

/** Containers the plan promises: .mp4, .mkv, .webm. */
val LOCAL_VIDEO_MIMES = setOf("video/mp4", "video/x-matroska", "video/webm")

private val LOCAL_VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm")

/**
 * A display name out of whatever the provider stored. The extension comes
 * off, underscores read as spaces, and a name that is blank or only digits
 * (a bare row id wearing a name) falls back to [LOCAL_VIDEO_UNTITLED] — the
 * same honesty rule [LocalVideoOpen] applies to an outside intent.
 * Video-audit #35 (27 Nov 2026): a trailing source-site tag baked into
 * the file name ("… Episode 15 kisskh") is cut before display — see
 * [stripSourceTag]. The file itself is never renamed.
 */
fun localVideoTitle(displayName: String?): String {
    val raw = displayName?.takeIf { it.isNotBlank() } ?: return LOCAL_VIDEO_UNTITLED
    val name = raw.substringAfterLast('/').substringBeforeLast('.')
    val cleaned = stripSourceTag(name).replace('_', ' ').filterNot { it.isISOControl() }.trim().take(80)
    return cleaned.takeIf { it.isNotEmpty() && !cleaned.all { ch -> ch.isDigit() } }
        ?: LOCAL_VIDEO_UNTITLED
}

/**
 * Video-audit #35 (27 Nov 2026): downloaders bake the source site's name
 * into the file name — "River of No Return (2026) Episode 15 kisskh" —
 * and the file name is the displayed title, so the site tag reads as part
 * of the title. A trailing token that IS a known source-site slug is cut
 * at display time only: the file itself is never renamed, and the stored
 * rows keep the name they were filed under.
 *
 * Two guards keep a real title word from being cut: the token must be a
 * whole trailing token (space, underscore or dash before it, optional
 * [brackets] or (parens) around it), and the title in front of it must
 * already look complete — a year "(2026)" or an episode marker — so a
 * bare "The Castle" keeps its name while "Castle (2009) S01E01 castle"
 * loses only the redundant tag. A tag after a title with no year and no
 * episode is left alone: a conservative miss beats a wrong cut.
 */
fun stripSourceTag(title: String): String {
    val trimmed = title.trim()
    val match = TRAILING_SOURCE_TAG.find(trimmed) ?: return title
    if (match.groupValues[1].lowercase() !in SOURCE_SITE_TAGS) return title
    val head = trimmed.substring(0, match.range.first).trimEnd()
        .trimEnd('[', '(', '-', '_').trimEnd()
    if (head.isEmpty() || !TITLE_COMPLETE.containsMatchIn(head)) return title
    return head
}

/** A trailing site tag: separator, optional bracket, one token, end of title. */
private val TRAILING_SOURCE_TAG = Regex("""[\s_\-]+[\[(]?([A-Za-z0-9]{3,})[\])]?\s*$""")

/** A title that is already complete without its last token: a year or an episode marker. */
private val TITLE_COMPLETE = Regex("""\(\s*(19|20)\d{2}\s*\)|[Ee]pisode[\s_]*\d+|\bS\d{1,2}E\d{1,2}\b|\bEP[\s_]*\d+""")

/**
 * The source-site slugs this catalog carries (the importer modules under
 * Fast_Fetch/ — read-only vocabulary for this display rule, not a backend
 * dependency: a slug added to the catalog without a line here is simply
 * not stripped until this set is extended).
 */
private val SOURCE_SITE_TAGS = setOf(
    "5movierulz", "animahd", "archive", "aryplus", "castle", "desiserials",
    "dramacool", "dramadog", "filmiyin", "filmyhit", "filmywap", "fzmovies",
    "humtv", "iptv_org", "javmost", "kisskh", "kuttymovies", "levidia",
    "megacartoons", "missav", "moviecok", "moviesflix", "moviesmela",
    "mp4moviez", "pagalmovies", "themoviebox", "wp_family", "10xflix",
    "7starhd", "9xmovies", "bolly4u", "desicinemas", "filmyzilla",
    "harpalgeo", "hdhub4u", "hdtvb", "hindilinks4u", "katmoviehd",
    "movienestbd", "moviesdrive", "moviezguru", "multimovies", "rareanimes",
    "rogmovies", "ssrmovies", "toonworld4all", "vegamovies", "world4ufree",
    "xprimehub",
)

/**
 * Which of the two folders a row came from, for the row's subtitle. A
 * `relative_path` reads `Movies/…` / `Download/…`; a `_data` path carries the
 * same folders after a `/`. Anything else is honest about being neither.
 */
fun localVideoFolder(relativePath: String?, dataPath: String?): String {
    val path = relativePath ?: dataPath ?: return "On this phone"
    return when {
        path.startsWith("Movies/") || path.contains("/Movies/") -> "Movies"
        path.startsWith("Download/") || path.contains("/Download/") -> "Downloads"
        else -> "On this phone"
    }
}

/** The defensive re-check on a row the SQL already filtered: a known video MIME or a known extension. */
fun isLocalVideoFile(mimeType: String?, displayName: String?): Boolean {
    if (mimeType?.lowercase() in LOCAL_VIDEO_MIMES) return true
    val extension = displayName?.substringAfterLast('.', "").orEmpty().lowercase()
    return extension in LOCAL_VIDEO_EXTENSIONS
}

/**
 * One cursor row as a [LocalVideoFile], or null when the row is not one of the
 * promised containers after all. [uri] is the provider address the repository
 * already built for the row id; [dateAddedMs] is epoch milliseconds (the
 * provider stores seconds).
 */
fun localVideoFromRow(
    uri: String,
    displayName: String?,
    durationMs: Long,
    sizeBytes: Long,
    relativePath: String?,
    dataPath: String?,
    mimeType: String?,
    dateAddedMs: Long,
): LocalVideoFile? {
    if (!isLocalVideoFile(mimeType, displayName)) return null
    return LocalVideoFile(
        uri = uri,
        title = localVideoTitle(displayName),
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        folder = localVideoFolder(relativePath, dataPath),
        dateAddedMs = dateAddedMs,
        folderPath = localVideoFolderPath(relativePath, dataPath),
    )
}
