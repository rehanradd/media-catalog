package com.raddstream.app.ui.layout

/**
 * The one place catalog geometry is decided.
 *
 * A row scans cleanly only when every tile in it — and the placeholder that stood there a
 * second earlier — is the same shape. Before this object existed the poster ratio was written
 * out three times (a card, a detail header, a skeleton), the rail tile width twice, and
 * the design spec asked for `2f/3f` while the code drew `0.68f`. Nothing reported the drift,
 * because each copy was individually correct-looking.
 *
 * [CardGeometryTest] keeps it that way: it walks the UI sources and refuses an `aspectRatio`
 * literal that is not one of the values defined here.
 *
 * Shapes are named by ARTWORK CLASS, not by screen: a poster, a season art band and an episode
 * still are three different crops of three different assets, and forcing them to share one
 * ratio would crop faces out of episode thumbs. The rule this file enforces is that each class
 * has exactly one ratio for every screen that draws it.
 */
object CardGeometry {

    /**
     * Poster tiles: home rails, the browse and my-list grids, search results, the detail
     * header and every skeleton that stands in for one.
     *
     * The design spec (`design/DESIGN_SYSTEM.md` §8) asks for 2:3 (0.667), and
     * Batch B adopts it: 0.68f cropped a sliver off the top and bottom of
     * every 2:3 source poster (the round-2 "cropped/poorly framed" complaint).
     * The value is still one line here — the answer to the old owner question
     * is the spec, and a future change is a one-line edit, not three.
     */
    const val POSTER_ASPECT: Float = 2f / 3f

    /**
     * Rail tile width. A rail cell and its placeholder both draw from this one number.
     * Density (video audit, Nov 2026): ONE fixed poster-card size in every
     * row and screen — no card bigger or smaller. The 100 dp baseline shows
     * ~3 tiles per row on a 360 dp phone (Netflix/Prime run ~105–120 dp) and
     * matches the 3-up grids' ~101 dp cells, so rails and grids finally
     * agree; the old 120 dp rail and the 150 dp spotlight tier are both
     * gone. Smaller text (12sp titles, see [PosterCard]) rides the same
     * change, so more content fits per screen. Continue Watching stays its
     * own width — it is full-width rows now, not rail tiles.
     */
    const val RAIL_CARD_WIDTH_DP: Int = 100

    /** Continue Watching rows: the poster's width in the full-width row (mockup,
     * 2026-11-15). At [POSTER_ASPECT] the thumb is 144 dp tall, so the row is
     * the poster's height plus its padding — the title, remaining label and
     * progress bar sit beside it, vertically centred.
     */
    const val CONTINUE_WATCHING_ROW_THUMB_WIDTH_DP: Int = 96

    /**
     * Search result rows (refined direction, Nov 2026): the rich row's thumb
     * width — bigger than a Continue Watching thumb and a home rail card on
     * the same phone, the YouTube-style lead the row is built around. At
     * [POSTER_ASPECT] the thumb is 168 dp tall, so the row is the poster's
     * height plus its padding — the title, rating and more info sit beside
     * it, top-aligned.
     */
    const val SEARCH_RESULT_ROW_THUMB_WIDTH_DP: Int = 112

    /** The detail header's poster column: the same ratio, a fixed column instead of a rail cell. */
    const val DETAIL_POSTER_WIDTH_DP: Int = 100

    /** The wide art band above a season's episode list: the work's art cropped wide. */
    const val SEASON_ART_ASPECT: Float = 3.2f

    /** Episode stills and backdrops — the 16:9 class, including the hero. */
    const val WIDE_ART_ASPECT: Float = 16f / 9f

    /**
     * Season tiles: plain numbered squares — one number per tile, no
     * artwork. A square reads as a number, not a poster, and six of
     * them fit a 360 dp phone at 48 dp each.
     */
    const val SEASON_TILE_ASPECT: Float = 1f
}
