package com.raddstream.app.data.local

import android.graphics.Bitmap

/**
 * The phone's own videos, behind an interface so the ViewModel's state
 * tests can stand in a fake (the `CatalogPreloadModule` pattern). The
 * production implementation is [LocalFilesRepository], which reads MediaStore.
 */
interface LocalVideosDataSource {

    /**
     * Every video on the phone (.mp4/.mkv/.webm, every folder), newest
     * first. A revoked permission mid-query answers as an empty list, never
     * as a crash — the screen checks the permission before it ever asks.
     */
    suspend fun loadVideos(): List<LocalVideoFile>

    /**
     * The video's own keyframe, for a folder row's thumbnail. Null below
     * API 29 (no `loadThumbnail` there), null when the media has no
     * thumbnail, null when the provider refuses — the row then paints the
     * dark tile, never an invented picture.
     */
    suspend fun loadThumbnail(videoUri: String): Bitmap?
}
