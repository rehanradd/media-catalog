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
     * Density (2026-11-15): 132 dp showed ~2.4 tiles per row on a 360 dp phone;
     * 112 dp shows ~2.7 and matches Netflix's ~110–120 dp rail. Mockup
     * (2026-11-15): the target layout's rails are wider — ~150 dp posters,
     * 3:4-ish, two per row with the next peeking — so the rail grows to 150 dp.
     * The poster ratio stays 2:3 (see [POSTER_ASPECT]): one ratio per artwork
     * class, and the skeleton follows this same number. Continue Watching
     * stays its own width — it is full-width rows now, not rail tiles.
     */
    const val RAIL_CARD_WIDTH_DP: Int = 150

    /**
     * Continue Watching rows: the poster's width in the full-width row (mockup,
     * 2026-11-15). At [POSTER_ASPECT] the thumb is 144 dp tall, so the row is
     * the poster's height plus its padding — the title, remaining label and
     * progress bar sit beside it, vertically centred.
     */
    const val CONTINUE_WATCHING_ROW_THUMB_WIDTH_DP: Int = 96

    /** The detail header's poster column: the same ratio, a fixed column instead of a rail cell. */
    const val DETAIL_POSTER_WIDTH_DP: Int = 120

    /** The wide art band above a season's episode list: the work's art cropped wide. */
    const val SEASON_ART_ASPECT: Float = 3.2f

    /** Episode stills and backdrops — the 16:9 class, including the hero. */
    const val WIDE_ART_ASPECT: Float = 16f / 9f
}
