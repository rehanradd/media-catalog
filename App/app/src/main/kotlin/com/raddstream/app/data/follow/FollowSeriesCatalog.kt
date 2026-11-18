package com.raddstream.app.data.follow

import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import javax.inject.Inject

/**
 * The catalog surface the follow check needs, narrowed to two calls so
 * the worker test can stand in a fake without implementing the whole
 * [com.raddstream.app.network.RaddStreamApi].
 */
interface FollowSeriesCatalog {
    /** The posts that name this work, newest first — empty when the work is gone. */
    suspend fun postsForWork(workRef: String, cleanTitle: String?): List<Post>

    /** One post, whole, with its releases — null when the server cannot serve it. */
    suspend fun detailFor(postId: Int): PostDetail?
}

/**
 * The production catalog: the existing `GET /api/v1/posts`, asked by the
 * minted handle first. A handle names one work on the server (it resolves
 * to the work's newest post), so that is the precise query; when a handle
 * resolves to nothing — an older server, a key the server cannot unmint —
 * the title search is the fallback the audit specced, capped so one
 * followed title cannot turn into a catalog walk.
 */
class ApiFollowSeriesCatalog @Inject constructor(
    private val apiProvider: ApiProvider,
) : FollowSeriesCatalog {

    override suspend fun postsForWork(workRef: String, cleanTitle: String?): List<Post> {
        val api = apiProvider.current()
        val byRef = runCatching {
            api.getPosts(workRef = workRef, limit = 20).results
        }.getOrNull().orEmpty()
        if (byRef.isNotEmpty()) return byRef
        val title = cleanTitle?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            api.getPosts(query = title, limit = 20).results
        }.getOrNull().orEmpty()
    }

    override suspend fun detailFor(postId: Int): PostDetail? {
        val api = apiProvider.current()
        return runCatching { api.getPostDetail(postId) }.getOrNull()?.takeIf { it.success }?.data
    }
}
