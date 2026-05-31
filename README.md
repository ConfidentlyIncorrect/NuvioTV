<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="NuvioTV" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A modern Android TV media player powered by the Stremio addon ecosystem.
    <br />
    Stremio Addon ecosystem • Android TV optimized • Playback-focused experience
  </p>

</div>

## About

NuvioTV is a modern media player designed specifically for Android TV.

It acts as a client-side playback interface that can integrate with the Stremio addon ecosystem for content discovery and source resolution through user-installed extensions.

Built with Kotlin and optimized for a TV-first viewing experience.

## This fork

This repository is a fork of [tapframe/NuvioTV](https://github.com/tapframe/NuvioTV), maintained
to pair with the [**usa-tv-next**](https://github.com/ConfidentlyIncorrect/usa-tv-next) live-TV
Stremio addon (293 US channels, EPG via Schedules Direct with an epg.pw XMLTV fallback).

### Branches

| Branch | Purpose |
| --- | --- |
| `dev` | Tracks upstream `tapframe/NuvioTV` `dev`. Keep clean for clean rebases/merges. |
| `custom` | Our modifications on top of `dev`. **Build releases from here.** |

Sync upstream into the fork periodically with `git fetch upstream && git rebase upstream/dev`
on `dev`, then `git rebase dev` (or merge) on `custom`.

### `custom` modifications

- **Focus-reactive EPG guide panel on the stream-selection screen.** As focus moves between a
  channel's streams (feeds/qualities), the left panel updates to show that stream's program
  guide; with nothing hovered yet it shows the first stream's. The guide text comes from a new
  optional `epg` field on each stream (a non-standard Stremio extension emitted by usa-tv-next;
  it falls back to the stream `description`, and is simply absent/ignored for other addons).
  Implemented in `StreamScreen.kt` (`LeftContentSection` + `StreamCard` focus wiring) with the
  field threaded through `StreamResponseDto` → `Stream` → `StreamMapper`. Reads are deferred
  into the panel composable so hovering recomposes only the panel, never the stream list.

## Installation

### Android TV

Download the latest APK from [GitHub Releases](https://github.com/tapframe/NuvioTV/releases/latest) and install on your Android TV device.

## Development

### Prerequisites

- Android Studio (latest version)
- JDK 11+
- Android SDK (API 29+)
- Gradle 8.0+

### Setup

```bash
# This fork (build from the `custom` branch)
git clone https://github.com/ConfidentlyIncorrect/NuvioTV.git
cd NuvioTV
git checkout custom

# Optional: track upstream for syncing
git remote add upstream https://github.com/tapframe/NuvioTV.git
```

### Full Debug Build

```bash
./gradlew :app:compileFullDebugKotlin
./gradlew :app:assembleFullDebug
```

### Running on Emulator or Device

```bash
# Full debug build
./gradlew :app:assembleFullDebug

# Run on connected device
adb shell am start -n com.nuviodebug.com/com.nuvio.tv.MainActivity
```

## Legal & DMCA

NuvioTV functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

NuvioTV is not affiliated with any third-party extensions or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our **[Legal & Disclaimer Page](https://nuvioapp.space/legal)**.

## Built With

* Kotlin
* Jetpack Compose & TV Material3
* ExoPlayer / Media3
* Hilt (Dependency Injection)
* Retrofit (Networking)
* Gradle

## Star History

<a href="https://www.star-history.com/#tapframe/NuvioTV&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tapframe/NuvioTV&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tapframe/NuvioTV&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=tapframe/NuvioTV&type=date&legend=top-left" />
 </picture>
</a>

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/tapframe/NuvioTV.svg?style=for-the-badge
[contributors-url]: https://github.com/tapframe/NuvioTV/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/tapframe/NuvioTV.svg?style=for-the-badge
[forks-url]: https://github.com/tapframe/NuvioTV/network/members
[stars-shield]: https://img.shields.io/github/stars/tapframe/NuvioTV.svg?style=for-the-badge
[stars-url]: https://github.com/tapframe/NuvioTV/stargazers
[issues-shield]: https://img.shields.io/github/issues/tapframe/NuvioTV.svg?style=for-the-badge
[issues-url]: https://github.com/tapframe/NuvioTV/issues
[license-shield]: https://img.shields.io/github/license/tapframe/NuvioTV.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
