<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

It acts as a client-side playback interface that can integrate with the Stremio addon ecosystem for content discovery and source resolution through user-installed extensions.

Built with Kotlin and optimized for a TV-first viewing experience.

## Ecosystem

This `custom` fork is the on-screen client for a self-hosted stack built to run together:

| Project | Role |
| --- | --- |
| **NuvioTV** *(this repo, `custom`)* | Android TV client. Cinemeta `#DUPE#` reconstruction, episode/season/series scope search, a live-ticking EPG panel, and player fixes (DV/MKV, CEA-608 captions, live-HLS recovery). |
| **[AIOStreams](https://github.com/ConfidentlyIncorrect/AIOStreams/tree/custom)** | On-demand movie/series aggregator — torrents (Torrentio/Comet) + a self-hosted Prowlarr → NZBGeek usenet pipeline, all through TorBox. Its fork patch resolves `#DUPE#` server-side (the search-side complement to this app's display-side reconstruction). |
| **[usa-tv-next](https://github.com/ConfidentlyIncorrect/usa-tv-next)** | Live US TV addon (~293 channels, merged multi-source EPG) that emits the `epgSchedule` field this app's guide panel renders. |
| **[Comet (fork)](https://github.com/ConfidentlyIncorrect/comet/tree/tvdb-dupe-fix)** | Torrent-scraper addon (currently paused). |

Install AIOStreams + usa-tv-next as addons in this client to get the full stack (on-demand + live TV). Both `#DUPE#` layers are intentionally symmetric: this app fixes the *displayed* title/art/episodes, AIOStreams fixes the *search* query. Supporting infra (Prowlarr + NZBGeek, TorBox, Caddy) runs on a single VPS.

## This fork

This repository is a fork of [tapframe/NuvioTV](https://github.com/tapframe/NuvioTV), maintained
to pair with the [**usa-tv-next**](https://github.com/ConfidentlyIncorrect/usa-tv-next) live-TV
Stremio addon (293 US channels; a merged multi-source EPG — Schedules Direct → epg.pw →
epgshare01/i.mjh.nz).

### Branches

| Branch | Purpose |
| --- | --- |
| `custom` | All our modifications, on top of upstream `tapframe/NuvioTV` `dev`. **Build releases from here.** |

**Syncing upstream (merge, not rebase — `custom` is pushed):**

One command does the whole dance below — fetch upstream, merge onto a throwaway
`sync/upstream-<date>` branch, list any conflicts, and compile-check a clean merge (it never touches
`custom` until you fast-forward it yourself):
```bash
scripts/sync-upstream.sh            # JAVA_HOME must point at a JDK 17–21 for the compile check
# then, once green:
git switch custom && git merge --ff-only sync/upstream-<date> && git push origin custom
```
A **`Upstream sync check`** GitHub Action (`.github/workflows/upstream-sync.yml`) also opens/updates a
single tracking issue whenever `upstream/dev` gets ahead, with the new-commit list and a clean/conflict
probe — so a sync never gets missed. (GitHub only fires `schedule` from the **default branch**; set
`custom` as default, or trigger the workflow manually.)

Or do it by hand:
```bash
git remote add upstream https://github.com/tapframe/NuvioTV.git   # one-time
git fetch upstream
git switch custom && git switch -c sync-dev      # throwaway test branch
git merge upstream/dev                           # resolve any conflicts, then build/test
git switch custom && git merge --ff-only sync-dev && git push origin custom
```
Use merge (not rebase) so the pushed `custom` history isn't rewritten. The conflict surface is
small — our changes are localized to the files listed below (the last sync of 52 upstream commits
merged with **zero** conflicts).

### `custom` modifications

- **Focus-reactive, live EPG guide panel on the stream-selection screen.** As focus moves between
  a channel's feeds, the left panel shows that stream's program guide (defaults to the first
  stream). It recomputes **NOW/NEXT on a 30s clock** from a new `epgSchedule` field (absolute-time
  window emitted by usa-tv-next) so it stays live and self-corrects even from a cached response;
  it falls back to the `epg` now/next string, then `description`. Focus reads are deferred into the
  panel composable so hovering recomposes only the panel.
- **Live channel detail screen.** `HeroSection` recomputes NOW PLAYING / UP NEXT / today's schedule
  from `meta.epgSchedule` on the same 30s clock, formatted **identically regardless of EPG source**.
  Both screens share one formatter — `core/util/EpgGuide.kt` (the single source of truth).
- **CEA-608/708 closed captions for HLS.** Re-streamed live channels carry captions muxed in-band
  with no declared rendition; `PlayerMediaSourceFactory` routes HLS through `HlsMediaSource.Factory`
  with `DefaultHlsExtractorFactory(exposeCea608WhenMissingDeclarations=true)`, and
  `PlayerRuntimeControllerTracks` labels them "Closed Captions [CCn]" so they're selectable.
- **Email/password sign-in re-enabled on TV** (`AuthSignInScreen` rebuilt via `AccountViewModel`),
  with first launch defaulting to the email screen + a QR toggle (`MainActivity`, `NuvioNavHost`,
  Settings→Sign-in route).
- **Keyless TheTVDB metadata layer + Cinemeta `#DUPE#` reconstruction.** Cinemeta de-dupes shows that
  have multiple regional IMDb entries (e.g. *Mayday* / *Air Crash Investigation* / *Air Disasters*) by
  renaming the duplicate to the literal `#DUPE#` (slug `<type>/dupe-<id>`) and leaving a half-broken
  entry whose own IMDb id is dead at TMDB/IMDb — so it appears nameless with the canonical sibling's
  data leaking in (wrong title "Mayday", year 2003, country "Canada", 404'd metahub episode
  thumbnails). `core/tvdb/TvdbMetadataService` recovers the rightful entry **with no API key** by
  scraping `thetvdb.com` (the dupe's Cinemeta meta still carries a `tvdb_id`): name + year +
  current poster/background + country/language/status/genres/runtime from the series page, and the full
  season/episode tree (with screenshots) from the *all-seasons* page. On both the catalog tile
  (`CatalogRepositoryImpl`) and the detail screen (`MetaDetailsViewModel.enrichMeta`) the entry is
  rebuilt from TheTVDB — re-fetching from Cinemeta when another meta addon masks the dupe — and the
  TMDB enrichment is guarded so it can't re-clobber the regional facts; episode thumbnails fall back to
  the series backdrop where TheTVDB has none. A metahub clearlogo fallback (by IMDb id) also restores
  stylized hero title-treatments when an addon serves no logo. Threaded through a new `tvdb_id` field
  on `MetaResponseDto` → `Meta` → `MetaMapper`.
- **Full episode-description overlay.** Episode-card descriptions truncate with `…`; the episode
  long-press menu now has a **View full description** action that opens the untruncated text in a
  D-pad-scrollable overlay. Extracted the hero's scrollable-synopsis pattern into a reusable
  `ui/components/ScrollableDescriptionDialog`.
- **"Exit behavior" settings (fully close the app).** Settings → Layout → *Exit behavior* adds two
  toggles (both off by default): **Fully close on Back** and **Close when Home is pressed**. When on,
  the app removes its task and kills its process (`MainActivity.fullyExit()`) instead of staying
  cached — Home is hooked via `onUserLeaveHint()`, guarded against external-player handoffs. Backed by
  `LayoutPreferenceDataStore` (`exitAppOnBack` / `exitAppOnHome`).
- **Files touched:** `StreamScreen.kt`, `HeroSection.kt`, `Stream.kt`/`Meta.kt`, the `*Dto`s +
  mappers, `core/util/EpgGuide.kt`, `PlayerMediaSourceFactory.kt`,
  `PlayerRuntimeControllerTracks.kt`, `AuthSignInScreen.kt`, `MainActivity.kt`, `NuvioNavHost.kt`,
  `core/tvdb/TvdbMetadataService.kt`, `core/tmdb/DupeTitleResolver.kt`,
  `data/remote/api/TvdbWebApi.kt`, `CatalogRepositoryImpl.kt`, `MetaDetailsViewModel.kt`,
  `EpisodesSection.kt`, `ui/components/ScrollableDescriptionDialog.kt`, `core/di/NetworkModule.kt`.

## Installation

### Android TV

Download the latest APK from [GitHub Releases](https://github.com/tapframe/NuvioTV/releases/latest) and install on your Android TV device.

## Development

### Prerequisites

- Android Studio (latest version)
- **JDK 17–21** (the bundled Android Studio JBR 21 at `…/Android Studio/jbr` works; **JDK 26 is not yet supported** by this Gradle/AGP and will crash the build). Point `JAVA_HOME`/Gradle JDK at a 17–21 JDK.
- Android SDK (API 29+)
- Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.0 (via the wrapper)

### Setup

```bash
# This fork (build from the `custom` branch)
git clone https://github.com/ConfidentlyIncorrect/NuvioTV.git
cd NuvioTV
git checkout custom

# Optional: track upstream for syncing
git remote add upstream https://github.com/tapframe/NuvioTV.git
```

### Local config (gitignored — never commit)

| File | Keys | Notes |
| --- | --- | --- |
| `local.properties` | `sdk.dir`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` | Trakt creds bake into `BuildConfig` at build time. Create a Trakt API app at https://trakt.tv/oauth/applications/new with Redirect URI `urn:ietf:wg:oauth:2.0:oob` (leave JavaScript/CORS origins blank — device-code flow). Empty = "missing client id/secret". |
| `local.dev.properties` | `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `TV_LOGIN_WEB_BASE_URL` | Supabase auth config used by the email/browser sign-in path. |
| `nuviotv.jks` | — | Signing keystore (the debug build uses the `release` signing config). Generate a self-signed key matching the baked-in alias/passwords if missing. |

### Full Debug Build

```bash
./gradlew :app:compileFullDebugKotlin
./gradlew :app:assembleFullDebug --no-watch-fs   # produces app/build/outputs/apk/full/debug/*.apk
```

> **Build note:** packaging occasionally fails with a flaky `IncrementalSplitterRunnable` error. The Kotlin/codegen still compiled — just re-run with filesystem watching disabled: `./gradlew :app:packageFullDebug --no-watch-fs`. APK splits are produced per-ABI (`app-full-arm64-v8a-debug.apk` for most Android TV boxes) plus a `universal` APK.

### Running on Emulator or Device

## License

[GNU General Public License v3.0](./LICENSE)
