# Icon system — Home scope (2027 fix)

One icon language for Home, not two. The Home scope paints **no
`Icons.Default.*` glyph** — `HomeIconSystemTest` fails the build if one
comes back. Every icon is a house drawable, loaded with
`painterResource` (or `ImageVector.vectorResource` where the call site
takes an `ImageVector`).

## House style

- 24dp viewport (`android:width/height="24dp"`, `viewportWidth/Height="24"`).
- Outline glyphs: `fillColor="@android:color/transparent"`,
  `strokeWidth="2"`, `strokeColor="#FF000000"`, round caps and joins.
  The caller's `tint` recolours the stroke.
- Filled glyphs: a Material filled path with `fillColor="#FF000000"`,
  no stroke. The caller's `tint` recolours the fill.
- Paths are verbatim Feather (outline) or Material (filled) path data —
  proven shapes, never re-drawn by hand. The one hand-converted shape
  is the lock body (Feather's `<rect>` has no vector-drawable
  equivalent; it is a rounded-rect path).

## Inventory

| Glyph | File | Used where | Replaces |
|---|---|---|---|
| Close (×) | `ic_action_close` | PosterCard remove, CW remove, trial + announcement dismiss | `Icons.Default.Close` |
| Refresh (rotate-cw) | `ic_action_refresh` | ErrorState Retry, InlineLoadFailure Retry | `Icons.Default.Refresh` |
| Info (i) | `ic_action_info` | InlineLoadFailure | `Icons.Default.Info` |
| History (clock) | `ic_action_history` | Home "No active shows" row | `Icons.Default.History` |
| Lock | `ic_action_lock` | Home Kids Mode row | `Icons.Default.Lock` |
| Cloud-off | `ic_action_cloud_off` | ErrorState (network failure, brand-tinted, never red) | `Icons.Default.CloudOff` |
| Update (download arrow) | `ic_action_update` | Home update dialog | `Icons.Default.AutoAwesome` (sparkles said nothing about an update) |
| Heart, filled | `ic_action_favorite_filled` | Hero + poster saved heart; My List tab selected icon | `Icons.Default.Favorite` |
| Home, filled | `ic_tab_home_filled` | Home tab selected icon | — (outline `ic_tab_home` is unselected) |
| Search, filled | `ic_tab_search_filled` | Search tab selected icon | — |
| Settings, filled | `ic_tab_settings_filled` | Settings tab selected icon | — |
| Live TV, filled | `ic_tab_live_tv_filled` | Live TV tab selected icon | — |

Reused, not redrawn: `ic_player_play` / `ic_player_pause` (play + pause),
`ic_tab_my_list` (the outline heart — the unsaved state and the My List
tab's unselected icon; the same Material heart path as a stroke).

## Rules

1. **One glyph, one meaning.** The lock is the Kids Mode lock only —
   do not reuse `ic_player_lock` for it (one glyph, two meanings was
   the old failure). A new meaning gets a new file.
2. **Fill-on-select.** The bottom nav paints the outline glyph
   unselected and the filled variant selected (`BottomNavItem.selectedIcon`,
   `BottomNavTab`). A tab with no filled variant keeps its outline.
3. **Never `Icons.Default` in the Home scope.** The gate test scans
   HomeScreen, HeroCarousel, PosterCard, ScrollingChipRow, NavGraph and
   CommonUi. Another screen's Material icons are that screen's debt, not
   this file's — do not "fix" them here.
4. **A new icon starts from Feather or Material path data**, in the
   house style above, with a one-line comment in the XML saying what it
   is and where it is used. Add it to the table here.

## Open question

Two brand-mark representations exist: the Canvas `RaddStreamMark`
(used: hero fallback, empty state, skeleton — the skeleton's copy was
removed in the 2027 fix, H4) and the unused `ic_logo_mark.xml`.
Which one is canonical is unconfirmed — do not assume; the mark is not
an icon in this system until that is decided.
