package com.raddstream.app.work

import com.raddstream.app.network.models.Post
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.V2BrowsePage
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.industryWireValues
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Feel-instant S8: what the preload workers ask the catalog for. The
 * workers take the interface, so their tests can stand in a fake — the
 * same split [com.raddstream.app.data.follow.FollowSeriesCatalog] makes
 * for the follow check. Every call upserts its titles into the FTS index
 * through the repository's own `cachePosts`, so paging here IS the index
 * sync: no separate write step, no second copy of the dedupe.
 */
interface CatalogPreloadCatalog {

    /** One rail's first page — the same v2-first chain Home's loadSection runs. */
    suspend fun railFirstPage(section: HomeSection): List<Post>

    /** One v2 trending page by cursor — null when the server cannot serve v2. */
    suspend fun trendingPage(cursor: String?): V2BrowsePage?

    /** One v1 trending page by number — the sync's fallback when v2 is unavailable. */
    suspend fun trendingPostsPage(page: Int, limit: Int): List<Post>
}

/**
 * One rail's first page — the fetch chain `HomeViewModel.loadSection`
 * runs, as a pure function of its four calls so a test can pin it without
 * a server: v2 first; an empty v2 page falls back; a rail ordered by a
 * server sort ("Most copies", `sort=trending`) tries the dedicated
 * trending route, then the v1 list; every other rail goes straight to the
 * v1 list. A related rail asks its own route. Cancellation is rethrown,
 * never swallowed into a fallback — a cancelled warm must stop, not fetch
 * the same rail twice.
 */
internal suspend fun fetchRailFirstPage(
    section: HomeSection,
    browseV2: suspend () -> V2BrowsePage?,
    trending: suspend () -> List<Post>,
    list: suspend () -> List<Post>,
    related: suspend () -> List<Post>,
): List<Post> {
    if (section.relatedWorkId != null) return related()
    // Home's loadSection treats a v2 failure exactly like a null page:
    // fall back, never abort the rail. Cancellation is the one exception —
    // a cancelled warm must stop, not fetch the same rail twice.
    val page = try {
        browseV2()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (page != null && page.posts.isNotEmpty()) return page.posts
    if (section.sort == "trending") {
        try {
            return trending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fall through to the v1 list below.
        }
    }
    return list()
}

/** The production catalog: the existing repository, wired as the interface. */
class ApiCatalogPreloadCatalog @Inject constructor(
    private val catalog: CatalogRepository,
) : CatalogPreloadCatalog {

    override suspend fun railFirstPage(section: HomeSection): List<Post> = fetchRailFirstPage(
        section = section,
        browseV2 = {
            catalog.browseWorksV2(
                type = section.type,
                sort = section.sort ?: "newest",
                limit = WARM_RAIL_PAGE_LIMIT,
                industries = industryWireValues(section.industry),
            )
        },
        trending = {
            catalog.getTrending(type = section.type, page = 1, limit = WARM_RAIL_PAGE_LIMIT).results
        },
        list = {
            catalog.getPosts(
                type = section.type,
                industry = section.industry,
                sort = section.sort,
                page = 1,
                limit = WARM_RAIL_PAGE_LIMIT,
            ).results
        },
        related = {
            catalog.getRelated(
                workId = requireNotNull(section.relatedWorkId),
                page = 1,
                limit = WARM_RAIL_PAGE_LIMIT,
            ).results
        },
    )

    override suspend fun trendingPage(cursor: String?): V2BrowsePage? =
        catalog.browseWorksV2(sort = "trending", limit = SYNC_PAGE_LIMIT, cursor = cursor)

    override suspend fun trendingPostsPage(page: Int, limit: Int): List<Post> =
        catalog.getPosts(sort = "trending", page = page, limit = limit).results
}
