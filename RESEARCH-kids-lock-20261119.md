# MX #7 — Kids Lock: research + plan

Date: 2026-11-19. Branch: `muse/mx-kids-lock-20261119`, base main `6e23c97b`.
Question: what does a kids mode look like when the catalog has no maturity
rating on the wire, and how do MX Player, Netflix Kids and YouTube Kids do it?

## What the best apps actually do

### MX Player — Kids Lock is a PLAYER lock, not a content filter
- Kids Lock (a plugin) locks the screen during playback so a child cannot
  make calls, open other apps, or tap playback away. Unlock = touch the
  4 corners of the screen clockwise, from any corner. Optional touch
  effects: colourful shapes appear where the child taps.
- Settings path: Settings > Player > Touch controls > Lock mode >
  Kids lock (+touch effects).
- Separately, newer MX Player builds have parental controls: a PIN-gated
  settings area with content restrictions by age/category.
- Lesson for us: the MX name means "the child cannot leave or disturb
  playback". A geometric unlock a child can stumble on is NOT a lock —
  our unlock must be a PIN, no back-door sequence.

### Netflix Kids — a profile + a PIN on the exit
- A Kids profile is a simplified experience showing only titles rated for
  ages 12 and under; unrated/over-age titles vanish from search entirely.
- Profile Lock: a 4-digit PIN per adult profile. The Kids profile is
  "lock-in": leaving it requires the PIN of the destination profile.
- The recurring failure in every guide: a kids profile WITHOUT a PIN on
  the exit is a swinging door — one tap to the adult profile.
- PIN changes happen on the web account page (password-verified). We have
  no account, so our PIN lives on-device (see below).

### YouTube Kids — allowlisted content, parent PIN for settings
- Content is allowlisted by age band (Preschool / Younger / Older); the
  parent PIN gates settings and exit. Same shape: allowlist + PIN.

### Common pattern (all three)
1. A 4-digit PIN. Setup = enter twice. Exit = enter once.
2. Content shown is an allowlist, not a denylist.
3. The exit is PIN-gated; there is no tap-around.
4. Locked surfaces: settings, store/purchase, downloads management,
   profile switching.

## What our wire can and cannot tell us (measured, not assumed)

- The public port NEVER carries `is_18_plus` (`AdultPolicy.kt` documents
  the measurement: detail 404s flagged ids, `redact.project` whitelists
  only identity/title/releases). The server already hides flagged rows
  from every fresh catalog read — that is layer 0, and it holds in kids
  mode exactly as in adult mode.
- The app DOES know a flag for rows it keeps: favourites (`favorite_posts`),
  resume points (`watch_history`), kept-file tasks. `AdultPolicy`
  (`listingFor` / `listableRows`) is the one rule for those, and the
  cached-row hole it closes is exactly the hole kids mode would otherwise
  reopen (a title watched before it was flagged resurfacing in
  Continue Watching, or in the local FTS echo).
- A `Post` carries: `genres` (CSV/JSON-array string, junk tokens included
  — "Hindi Dub", "NETFLIX", "COMPLETED" sit in the column), `contentType`
  (movie | series | complete_season | ongoing_season), `isAnimated`,
  `year`, `imdbRating` (a score, not a maturity rating), `industry`,
  `country`, `language`. There is NO maturity/certification field on the
  wire, and no `kid_safe` projection. Inventing one server-side is out
  of scope (client-only build).
- The server's own genre universe (`fast_fetch.py` COMMON_GENRES):
  Action, Adventure, Animation, Biography, Comedy, Crime, Documentary,
  Drama, Family, Fantasy, History, Horror, Mystery, Romance, Sci-Fi,
  Sport, Thriller, War.

## The kids-appropriate rule (two layers, both test-pinned)

Layer 1 — hard, never negotiable: `AdultPolicy.mayList(flag, refused)`.
A positively flagged row, or a row the server refused, never lists in
kids mode — even if its genres look friendly. This is the guarantee.

Layer 2 — heuristic, documented as one: genre allowlist.
`KIDS_GENRES = Animation, Family, Kids, Children, Cartoon, Comedy,
Adventure, Fantasy, Musical, Documentary, Educational, Sport`.
A post is kid-appropriate when at least one of its known genres is in
the set (intersection, not "all genres in the set" — a cartoon tagged
`Animation, Hindi Dub` counts). A post with NO known genre is NOT
kid-appropriate (fail closed: an unclassified title does not appear).
`isAnimated` alone is NOT a pass — anime spans the whole maturity
range, and the flag layer is what catches its adult end.

Honest limit, stated on the setup screen and in code: the genre rule
shapes browsing; it is not a maturity rating. The guarantee is layer 1
plus the server's own hiding of flagged rows. A mainstream R-rated
comedy in the Comedy genre is the known residual — the follow-up that
actually fixes it is a server-side maturity signal, not a longer
client list.

## The plan (D1–D8) — built 2026-11-19, all eight steps landed

- **D1 — Pure rules** (`viewmodel/KidsLock.kt`, JVM-testable, no Android
  imports): `isValidPinFormat` (exactly 4 digits); `hashPin(pin, salt)`
  (SHA-256 of `salt:pin`); `verifyPin(pin, salt, hash)`; `isKidAppropriate`
  (layers 1+2 over genres + flag + refused); `filterPosts` /
  `filterSections`-style helpers; PIN-attempt throttle (5 failed
  attempts → 30 s cooldown, pure function of attempts + timestamps).
  ✅ Done, with `KidsLockTest` (plain JUnit).
- **D2 — Storage** (`ServerSettingsRepository`, same DataStore pattern as
  every other preference): `kidsLockPinHash` + `kidsLockSalt` (random UUID
  minted at setup), `kidsModeActive`; `saveKidsLockPin`,
  `verifyKidsPin`, `setKidsModeActive`. The PIN is NEVER stored in
  plaintext — only the salted hash. No new endpoint, client-only.
  ✅ Done.
- **D3 — Controller** (`KidsLockViewModel`, Hilt): `pinSet`,
  `modeActive`, `setupPin`, `changePin`, `enterKidsMode`,
  `exitKidsMode`, `removeKidsLock`, attempt throttle state. One place
  owns PIN logic; Settings, Home banner and the player all call it
  (the player VM reads the repository directly — VMs do not depend on
  other VMs).
  ✅ Done.
- **D4 — Settings card**: "Kids Lock" card in SettingsScreen with a
  `PinPadDialog` (own numeric pad, dots, no keyboard). States: not set
  up → set up (enter twice); set up, mode off → Enter Kids Mode,
  Change PIN, Remove; mode on → Exit Kids Mode, Change PIN. Every
  transition is PIN-gated. Copy says the PIN has no reset without
  clearing app data (no account exists to recover it — device-credential
  fallback is the follow-up, not v1).
  ✅ Done.
- **D5 — The mode shell** (MainActivity collects `kidsModeActive` →
  NavGraph): bottom bar in kids mode = Home, Search, Settings only.
  My List (unfiltered adult saves) and Live TV (unrated live channels)
  are hidden. Settings opens behind a PIN challenge (session-scoped
  adult verification in the graph). Downloads / VIP / Dashboard /
  Profile / Watch History / Languages / Help are reachable only through
  Settings, so the gate covers them; the Home + Detail "Open Downloads"
  entry points are hidden in kids mode. Home wears a slim "Kids Mode
  is on — Exit" banner (PIN to exit).
  ✅ Done. As built: entering kids mode also pops everything above Home
  off the back stack, so My List / Settings / Downloads cannot linger
  for Back to reach; the Settings gate renders over an empty screen,
  never the Settings content behind the dialog.
- **D6 — Content filtering**: Home sections + Continue Watching +
  hero candidates + Pick 3 (hidden in kids mode) filter through the
  rule; Search results (PagingData filter), local echo, suggestions and
  recents (hidden in kids mode — they carry the parent's queries) do
  the same; Browse likewise. Detail refuses a non-appropriate title
  with a plain "Not available in Kids Mode" state — the last line of
  defence for deep links and shared links.
  ✅ Done. As built: the filter rides the VMs' state as `visible*`
  read-through views (raw lists stay whole underneath, so turning the
  mode off restores the full screen with no re-fetch); the Pager
  rebuilds on the flag flip; Detail also gates the optimistic header
  and the keep/download actions, and filters similar titles; a kid's
  searches never join the shared recents store. `KidsModeStateTest`
  pins the state views. Known cosmetic gap: Search's count line still
  reads the server's unfiltered total next to the filtered loaded
  count — the line names the loaded count truthfully, and the file's
  own comment already says the two numbers never agree.
- **D7 — Player composition** (the MX heritage): in kids mode the
  player starts locked (`state.locked = true` on load); unlocking
  requires the PIN (the lock button opens the pad instead of toggling).
  Ghost mode is untouched — it is a chrome choice, not a lock.
  ✅ Done. As built: the lock engages on a new episode/file (rung
  switches never re-lock mid-play), the PIN grant is per-title, and
  turning the mode on mid-play locks a running player at once; the
  unlock FAB is ungated by Ghost, so the pad is always reachable.
- **D8 — Adult mode unchanged**: every filter, tab and gate is
  conditional on `kidsModeActive == true`. A build that never turns
  it on behaves byte-for-byte as before.
  ✅ Done by construction — every gate reads the flag; the default is
  off and the DataStore read seeds off on failure.

## Out of scope / owed
- Device-credential (fingerprint / device PIN) fallback for a forgotten
  PIN. A forgotten PIN today means clearing app data.
- Server-side maturity signal (`kid_safe` projection) — the real fix
  for the Comedy-genre residual.
- Per-title blocks, screen-time limits, age bands.
- Device verification (PIN flow + banner + player lock on a real phone).
