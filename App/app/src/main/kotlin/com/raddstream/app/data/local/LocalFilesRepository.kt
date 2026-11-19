package com.raddstream.app.data.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own videos, read through MediaStore — the Local Library.
 * Every folder, only .mp4/.mkv/.webm, newest first. The app's own
 * `Movies/RaddStream/` downloads are in the list on purpose: they are
 * videos on the phone, and the Downloads list beside it is a different view
 * (keeps with states), not a second copy of the same files.
 *
 * The query shape lives in [localLibrarySelection] as pure values; this
 * class only runs the Cursor. A revoked permission mid-query answers as an
 * empty list — the screen checks the permission before it ever asks here,
 * so this is a safety net, not a state.
 */
@Singleton
class LocalFilesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalVideosDataSource {

    override suspend fun loadVideos(): List<LocalVideoFile> = withContext(Dispatchers.IO) {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val (selection, selectionArgs) = localLibrarySelection()
        val projection = if (useRelativePath) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_ADDED,
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATE_ADDED,
            )
        }
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val pathIndex = if (useRelativePath) {
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                }
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ).toString()
                        val relativePath = if (useRelativePath) cursor.getString(pathIndex) else null
                        val dataPath = if (useRelativePath) null else cursor.getString(pathIndex)
                        localVideoFromRow(
                            uri = uri,
                            displayName = cursor.getString(nameIndex),
                            durationMs = cursor.getLong(durationIndex),
                            sizeBytes = cursor.getLong(sizeIndex),
                            relativePath = relativePath,
                            dataPath = dataPath,
                            mimeType = cursor.getString(mimeIndex),
                            // The provider stores seconds since the epoch.
                            dateAddedMs = cursor.getLong(addedIndex) * 1_000L,
                        )?.let { add(it) }
                    }
                }
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    override suspend fun loadThumbnail(videoUri: String): Bitmap? = withContext(Dispatchers.IO) {
        // loadThumbnail is API 29+; older releases keep the dark tile.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        try {
            context.contentResolver.loadThumbnail(
                Uri.parse(videoUri),
                // A folder row paints ~64dp; 320×180 is plenty at any density.
                Size(320, 180),
                null,
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
