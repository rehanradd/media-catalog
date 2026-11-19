# Local Library — research (2026-11-19)

Folder-first local video browsing, before writing code. Sources: Android
developers docs, MX Player / VLC write-ups, MediaStore guides (5 searches,
several phrasings).

## What the incumbents do

- **MX Player**: folders are the front door. It scans storage, groups clips by
  folder (users organise videos in directories), shows folder name + video
  count + a thumbnail decoded from a keyframe, sorts by date or size, and
  opens a folder to its videos. A flat "all videos" list is the secondary
  view, not the default.
- **VLC**: a "Directories" option browses folders, but it is a secondary
  view next to the flat video list — the complaint in every roundup is that
  folder view is not the default.
- **Our existing screen** (LocalFilesScreen, "Media Vault") is the opposite of
  both: one scrolling list with collapsible folder sections, Movies/ and
  Download/ only, and dark placeholder tiles instead of thumbnails. The
  library upgrades it in place (same `local_files` route, same entry points):
  folders first as their own level, every folder on the phone, real
  thumbnails, tap a folder to see its videos.

## Grouping: relative_path / _data, not BUCKET columns

- `MediaStore.Video` carries `BUCKET_ID` / `BUCKET_DISPLAY_NAME`, and most
  gallery tutorials group by bucket. But `BUCKET_DISPLAY_NAME` is only the
  last segment ("Camera") — two different parents with the same leaf name
  collapse into one row.
- The existing code already reads `RELATIVE_PATH` (API 29+, e.g.
  `Movies/RaddStream/`) and `_data` (older, full filesystem path). Grouping
  by the full parent path keeps identity exact, and the display name is the
  last segment. Two folders that share a display name get the parent segment
  as a disambiguating hint. No new projection columns, no new query shape
  beyond dropping the old Movies/Download-only filter.
- Sort: folders by their newest video first (the brief), videos inside a
  folder newest first — the rows already arrive `DATE_ADDED DESC` from the
  provider; the pure grouping function re-sorts defensively so the order is
  pinned by tests, not by provider behaviour.

## Permissions: API 33+ granular, older releases legacy

- API 33+ (Android 13): `READ_EXTERNAL_STORAGE` has no effect and is never
  granted. Video access is `READ_MEDIA_VIDEO` only.
- API 32 and lower: `READ_EXTERNAL_STORAGE`, declared with
  `android:maxSdkVersion="32"`.
- Both are already declared in the manifest exactly this way; the screen
  already picks the right one by SDK level. Nothing new to declare.
- Android 14's `READ_MEDIA_VISUAL_USER_SELECTED` (partial access) needs no
  handling: a partial grant just returns fewer rows from the same query, and
  the screen already treats an empty result as an honest empty state.
- Never `MANAGE_EXTERNAL_STORAGE` — that is for file managers, and Play
  rejects it for a player.

## Thumbnails: loadThumbnail, with a dark-tile fallback

- `ContentResolver.loadThumbnail(uri, Size, null)` (API 29+) is the official
  framework method for content-URI video thumbnails; it throws
  `IOException`/`FileNotFoundException` when the media has no thumbnail.
  One call per folder, on the folder's newest video, loaded in the
  ViewModel after the list lands.
- Below API 29 there is no `loadThumbnail`; the fallback is the dark tile
  with a folder icon the old screen already paints — a real keyframe or
  nothing invented, never a stock picture.
- No new dependency: Coil 2.6.0 is in the app but `coil-video` is not, and
  one thumbnail per folder does not justify a new decoder artifact.

## What is custom

- The grouping/sorting/collision-hint rules (pure, in
  `data/local/LocalLibrary.kt`, JVM-tested).
- The two-level navigation inside the one screen (folders → folder videos),
  with back returning to the folder list.
- The `LocalVideosDataSource` interface, so the ViewModel's state tests can
  stand in a fake (the `CatalogPreloadModule` pattern).
