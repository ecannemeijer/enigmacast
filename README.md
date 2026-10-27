# EnigmaCast

EnigmaCast is an Android TV app for Enigma2 receivers. It plays the receiver's live TV on the TV (one stream per TV, so several TVs can watch different channels), adds its own guide, recordings, search and OSD, and brings IPTV (Xtream / M3U), a TMDB film page, a soccer page and optional add-on streaming (your own Stremio-compatible add-on) into the same D-pad-driven interface. A [hotel mode](#hotel-mode) turns a TV into a locked guest TV for a B&B or holiday home.

The first target is an Octagon SF8008 running OpenATV 7.6. Nothing is hardcoded to that model: any Enigma2 receiver (Vu+, Dreambox, Zgemma, Mutant, Gigablue, Edision, …) with OpenWebif and the standard stream port works the same way.

```text
Enigma2 receiver (OpenWebif)
    live MPEG-TS :8001 / transcoded :8002 ---> Media3 / ExoPlayer --------+
    /api/* (bouquets, EPG, timers, tuners) --> Room cache + WorkManager ---+--> Android TV (Compose for TV)
    /grab?mode=osd (follow-receiver mode) ---> OSD overlay ---------------+
    /api/remotecontrol <-------------------- D-pad / remote keys
IPTV (Xtream Codes / M3U) -----------------> same player, row EPG, VOD grids
Stremio addon (your own URL) ------------> jlibtorrent engine -> 127.0.0.1 range server -> player
```

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Install](#install)
- [Build](#build)
- [Configuration](#configuration)
- [Architecture](#architecture)
- [Torrent engine](#torrent-engine)
- [Feature reference](#feature-reference) (menu, viewing modes, guide, recordings, IPTV, torrents, settings, …)
- [Receiver setup](#receiver-setup) · [OpenWebif](#openwebif) · [Streaming and OSD](#streaming-and-osd)
- [Debugging](#debugging) · [Known limitations](#known-limitations) · [Troubleshooting](#troubleshooting)
- [Attribution and legal](#attribution-and-legal)
- [Changelog](#changelog)

## Features

**Enigma2 / OpenWebif live TV**
- Two [viewing modes](#viewing-modes): *Own tuner* (default; each TV streams its own channel from the receiver's stream server, with a free-tuner check and failover to another saved receiver) and *Follow receiver* (the TV shows what the receiver shows, with the receiver's own OSD grabbed from OpenWebif).
- App-drawn channel banner and OSD in seven [GUI skins](#gui-skins), dense channel list with picons and now/next, bouquet column, number entry, timeshift pause, [quick options](#quick-options) (equivalent channels, record, subtitles, audio, audio boost, picture, audio delay, quality, guide, recent channels, sleep, stream info, restart).
- Zapping: CH+/CH−, Up/Down, number entry, and Right on the full-screen picture jumps back to the previous channel. A recent-channels strip (last eight, with logos, paging and a clear tile) and an equivalent-channel picker (another version of the same channel, satellite or IPTV).
- Per-channel audio boost (+3 to +15 dB in five steps, PCM audio only, remembered per channel and synced), live subtitle choice remembered per channel, teletext with a long press on Blue.
- Back ladder on live TV: dialogs, search, options bar and number entry close first; in the channel list overlay Back closes it (full screen again); only Back on the full-screen picture opens the [TV guide](#tv-guide) on the playing channel.
- Stream recovery: limited automatic retries, recovery after a network change, and streams are released when the TV goes to standby and restored on wake. Optional *Quick start* (off by default) and an `EcZap` timing log per zap (see [Troubleshooting](#troubleshooting)).
- [Stream type](#stream-type): Normal, Transcoded (receiver hardware encoder) or Automatic (steps quality down/up on stalls when away from home).
- Picture-in-picture (satellite or IPTV source) and a fallback that plays the same channel from the other source (satellite ↔ IPTV) when a channel drops out.
- Several receivers, power controls (standby, wake, reboot, GUI restart, sleep timer), signal meter and an Oscam page.
- Phone remote (Companion bridge): the EnigmaCast Companion app on your phone controls this TV over the local network; Settings › Phone & remote pairs it with a six-digit code. HTTPS + WebSocket on port 8750, TLS pinned, only while EnigmaCast is on screen. See [Phone remote](#phone-remote-companion-bridge).

**EPG, bouquets and cache**
- [TV guide](#tv-guide) grid (seven days ahead), per-channel programme panel, bouquet selection per page.
- Bouquets, channels, guide and TMDB data are cached per receiver in Room (`enigmacast-cache.db`) and refreshed in the background by WorkManager (checked every 60 minutes; per-item schedules under Settings › Guide & Home › Data & sync). See [Room cache and background refresh](#room-cache-and-background-refresh).

**Recordings, timers and AutoSwitch**
- [Record](#record) on the receiver (OpenWebif timers, AutoTimer for "Record all upcoming") or on this TV (MPEG-TS capture service), with app series rules.
- My Items lists box and device recordings, timers, resume positions and progress bars.
- [AutoSwitch](#autoswitch): a reminder that switches to a programme when it starts (foreground only).
- Recording vs tuners: when a recording needs the tuner this TV's stream holds, the stream moves to the recording channel shortly before the start.

**IPTV / Xtream**
- Source: the receiver's XStreamity playlists, or own M3U / Xtream Codes playlists (Settings › IPTV), loaded into Room off the main thread. Several providers can be added; their categories are grouped per provider, empty categories are skipped, the provider list can be exported and imported (clipboard), and the setup wizard lets you pick which providers to use.
- Playlist access per profile (which profiles may use the shared playlist) and a clear message with automatic retry when the provider's maximum number of connections is reached.
- Live TV with categories, a channel list with now/next per row ([row EPG](#iptv-row-epg)), a programme column, a full IPTV TV guide grid, catch-up for channels with an archive, and the satellite guide matched by name for channels without their own EPG.
- VOD Films and Series: categories, poster grids with TMDB artwork, info page, episodes, resume, watched marks and progress on info pages, Continue watching, favourites, next episode card and Android TV *Watch Next*.
- Category hiding per source and section.

**Home and film pages (TMDB)**
- Home: tuner cards (what uses each tuner), TV series cards, Scheduled today / Upcoming, Continue watching rows.
- Movies and TV Series: today's films and series on TV matched with TMDB (hero, rows per genre, info page with cast, Watch / Schedule / Record).

**Soccer (Sport)**
- football-data.org fixtures, standings and live minutes, plus PitchAPI for Eredivisie and Eerste Divisie details (events, lineups, statistics, club and player pages). TV channels are found in the cached guide. See [Soccer](#soccer).

**Add-on streaming (optional)**
- Torrent page with Movies and Series from TMDB (popular or discover), search, compact **Genre** and **Sort** (Popular, Year, TMDB score) dropdowns, and a TMDB info page.
- Streams come from your own Stremio addon: Settings › Torrent takes its manifest URL (`https://` or `stremio://`). With a URL set the Torrent feature is on; without one it is off. EnigmaCast ships no addon and no preset. The manifest is validated when saved.
- Streams dialog per title or episode (torrents and direct links), a Stremio-style loading page, and streaming with the built-in [jlibtorrent engine](#torrent-engine).
- Favourites and Continue watching (per profile, synced with the sync server); a resume prompt with *Resume from …*, *Start from the beginning* and *Choose another source* (the last torrent stream is remembered; direct links are never stored).

**Players and subtitles**
- One shared Stremio-style VOD OSD for IPTV films/series, recordings and torrents: title, Play/Pause, rewind, next, seek bar with a buffered bar, and icons for subtitles, audio, video, settings and sources.
- SubSource subtitles for IPTV films/series and torrents (release matching on the file name), with subtitle delay. The file is drawn by the app next to the playing media, so picking one keeps the position, progress and seeking.
- Audio: MediaCodec, HDMI passthrough (AC3, E-AC3, DTS) or the bundled FFmpeg decoder (nextlib); per-output audio sync.

**Profiles, sync and security**
- Up to 6 profiles ("Who's watching?") with avatars or a photo uploaded by QR code, an optional PIN per profile; per-profile viewing data, settings and library.
- Sync server: a small service on the Enigma2 receiver (installed from Settings › Sync server over SSH from the public GitHub release; found on the LAN on port 8740; HTTPS with a pinned certificate; a device key in the Android Keystore). Running servers are found and paired with; outdated servers are updated from the app (pairings and data kept). It syncs profiles, per-profile settings, IPTV providers, favourites, Continue watching, resume positions, watched marks, live subtitle choices and audio boost, and can hand a shared IPTV login to a new TV. See [Sync server](#sync-server).
- Optional account (the account server decides whether the app runs in Free mode or needs a login; see [Account and Premium](#account-and-premium)), Settings lock (4-digit PIN), encrypted secrets (AES-256-GCM key in the Android Keystore), app backup off, anonymous daily usage counter.

**Hotel / B&B mode**
- [Hotel mode](#hotel-mode): guests get live TV (satellite or IPTV) on a fixed profile, with a bouquet/category whitelist, start channel and volume, a welcome screen with Wi-Fi QR code, a daily next-guest cleanup, a separate hotel PIN with lock-out, and EnigmaCast as the TV home screen.

**Remote access**
- Remote profile per receiver (DynDNS name, ports, HTTPS) and an experimental built-in WireGuard tunnel (official `com.wireguard.android:tunnel` library) that comes up only when home does not answer. See [Remote access](#remote-access).

**TV-first UI**
- Compose for TV with full D-pad navigation: focus never leaves a dialog, lists wrap or stop at their ends as documented, focusing a visible row never scrolls the list, and poster grids scroll only as far as needed (no bounce).
- Five app languages: English, Nederlands, Deutsch, Italiano, Français ([Language](#language)).
- Animated navigation icons (the classic set stays available); the satellite group is called SAT-TV.
- In-app updates: update check with release notes, one-click download and install.
- Android TV launcher icon, round icon and TV banner (generated from the vector mark by `art/make_icons.py`), Assistant search ("Search for … on EnigmaCast"), first-run setup wizard.
- Main-thread safety: network, Room, JSON parsing, image decoding and file IO run off the main thread; debug builds enable `StrictMode` (disk reads/writes and network on the main thread, log only).

## Requirements

- An Android TV device (or Android TV emulator) with **Android 9 (API 28) or newer**: `minSdk` 28, `targetSdk` and `compileSdk` 37 (`gradle/libs.versions.toml`).
- An Enigma2 receiver on the LAN with OpenWebif enabled and the stream server on port 8001 (8002 or the encoder port for transcoding).
- For building: Android Studio with AGP 9.4 support, JDK 17 or newer (the bundled JDK 21 is used for development), Android SDK platform 37 and build tools.
- Optional: a TMDB v3 API key (film pages, torrent browsing), football-data.org and PitchAPI keys (Soccer), a SubSource key (subtitles), a Stremio addon (torrents).

`compileSdk` and `targetSdk` are 37 because Compose 1.12 and AndroidX Hilt 1.4 require compile SDK 37. The activity requests landscape and draws a fullscreen 16:9 surface, which is how Android TV presents the app.

### Dependency versions

Pinned in `gradle/libs.versions.toml`:

| Component | Version |
| --- | --- |
| Kotlin / KSP | 2.4.20 / 2.3.11 |
| Android Gradle Plugin / Gradle | 9.4.1 / 9.7.0 |
| Compose BOM / Compose for TV (tv-material) | 2026.09.00 / 1.1.0 |
| Media3 / nextlib (FFmpeg audio) | 1.11.1 / 1.11.1-0.16.0 |
| Dagger Hilt / AndroidX Hilt | 2.60.1 / 1.4.0 |
| Room / WorkManager / DataStore | 2.8.5 / 2.12.0 / 1.2.1 |
| OkHttp / Coil | 5.5.0 / 3.6.0 |
| kotlinx-coroutines / serialization | 1.11.0 / 1.11.0 |
| jlibtorrent (FrostWire) / NanoHTTPD | 2.0.12.9 / 2.3.1 |
| WireGuard tunnel | 1.0.20260102 |
| applicationId | com.enigmacast.app |

## Install

On an Android TV device (Android 9 or newer), install the free **Downloader** app (by AFTVnews) from the Play Store, allow it to install unknown apps (Android settings › Apps › Special app access › Install unknown apps), open Downloader and enter the code **1451275**. Downloader fetches the current EnigmaCast APK; confirm the install and open EnigmaCast. The first start runs the setup wizard (receiver, profiles, optional IPTV and sync server). Later versions are offered in the app itself (update check with release notes).

## Build

From the project root, with `JAVA_HOME` pointing at JDK 17+:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

On Linux or macOS: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (add `--offline` once the dependencies are cached), with `sdk.dir` in `local.properties`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Unit tests are plain JVM tests under `app/src/test` (domain logic, parsers, focus and scroll rules; database migrations against `sqlite-jdbc`).

The APK contains the native jlibtorrent library for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, so it runs on TV boxes and on the x86 Android TV emulator images.

## Configuration

API keys are read from the git-ignored `local.properties` at build time and copied into `BuildConfig`. Never commit them; only the key **names** are listed here:

| Key in `local.properties` | `BuildConfig` field | Used for |
| --- | --- | --- |
| `TMDB_API_KEY` | `TMDB_API_KEY` | Movies / TV Series pages, IPTV VOD artwork, Torrent page (TMDB v3 key) |
| `FOOTBALL_DATA_API_KEY` | `FOOTBALL_DATA_API_KEY` | Soccer: fixtures, standings, live minutes (football-data.org) |
| `PITCH_API_KEY` | `PITCH_API_KEY` | Soccer details for Eredivisie / Eerste Divisie (PitchAPI) |
| `SUBSOURCE_API_KEY` | `SUBSOURCE_API_KEY` | SubSource subtitles (kept in BuildConfig, never put in a URL) |

A blank key switches that feature off with a hint on its page. Own TMDB and football-data.org keys can also be entered in Settings (Guide & Home, Soccer) and replace the built-in ones. `BuildConfig.ACCOUNT_API_URL` is the account server; update manifests are in `dist/`.

Things configured in the app, not at build time: receivers and their logins, remote profile and WireGuard config, IPTV playlist, Stremio addon URL (it can contain a debrid key, so it is stored encrypted and only shown masked), sync server, profiles and the Settings PIN. Secrets live in the encrypted `SecretStore` and are never logged.

## Architecture

Single Gradle module (`app`), package `com.enigmacast`:

| Package | Contents |
| --- | --- |
| `domain/*` | Pure Kotlin models and rules (EPG, films, xtream, torrent, soccer, tuner, recording, remote keys, sync policy, …), unit-tested without Android |
| `data/*` | Repositories and clients: `openwebif`, `db` (Room), `xtream`, `tmdb`, `soccer`, `subsource`, `torrent` (scraper, library), `enigmasync` (sync server client and installer), `settings` (DataStore), `security` (Keystore secrets), `profile`, `recording`, `reminder`, `discovery`, `account` |
| `live`, `playback`, `osd`, `remote` | Live session and tuner logic, one app-wide player owner, video output, OSD grabbing, OpenWebif remote control |
| `sync` | Room guide cache and the WorkManager sync (`SyncEngine`, `SyncWork`) |
| `torrent` | jlibtorrent engine, native loader, local HTTP range server, foreground streaming service |
| `ui/*` | Compose for TV screens: dashboard and menu, home, tuners, xtream, torrent, soccer, player (VOD OSD), remote (live player, guide), settings, setup wizard, theme and skins |
| `di` | Hilt modules |

- **UI:** Jetpack Compose with Compose for TV (`androidx.tv:tv-material`), one activity, ViewModels per page (`@HiltViewModel`).
- **DI:** Dagger Hilt (`@HiltAndroidApp`, KSP).
- **Playback:** Media3 ExoPlayer (MPEG-TS, HLS, DASH, progressive), OkHttp data source, nextlib FFmpeg audio decoders; one player at a time across Enigma2, IPTV and torrents.
- **Storage:** Room cache database (bouquets, channels, EPG, TMDB, soccer, IPTV playlist and row EPG) that may be dropped and refilled; a separate user database (resume positions, library, series rules, sync outbox); DataStore for settings; Android Keystore for secrets.
- **Background:** WorkManager for the guide/TMDB refresh and settings import updates; the sync-server sync runs in the app's own coroutine scheduler; foreground services for device recordings (`RecordingCaptureService`) and torrent streaming.
- **Networking:** OkHttp (OpenWebif with at most 4 calls per receiver), Coil for images, commons-net (FTP), JSch (SSH install of the sync server), WireGuard userspace tunnel.

## Torrent engine

The engine follows the torrent-stream (Stremio) approach on libtorrent (FrostWire jlibtorrent 2.0.12.9):

1. The Stremio addon returns streams for the title's IMDb id (`/stream/movie/…` or `/stream/series/…:season:episode`). A torrent stream has an info hash and file index; public trackers are added.
2. `TorrentEngine` fetches the metadata and streams **one file**. At start only the first pieces from the playback position are wanted (about 4 MB gate), at top priority with tight deadlines, so all peers work on the same pieces; the player starts as soon as the piece at the start offset is verified.
3. `TorrentHttpServer` (NanoHTTPD) serves the file on `127.0.0.1` with a random port and path token and full Range support. ExoPlayer reads from it like any HTTP file.
4. While playing, every open reader gets a read-ahead window (32–160 MB, about 60 s at the measured bitrate) at top priority with paced deadlines; the rest of the file follows sequentially at low priority. A read of a missing piece (MKV cues or MP4 `moov` at the end, a seek) gets deadline 0 at once. Piece state is tracked locally, so reads never make synchronous libtorrent calls.
5. `TorrentStreamingService` keeps the app in the foreground with a Wi-Fi lock and a partial wake lock while streaming. Downloads go to `cache/torrents` and are deleted when playback stops.
6. The VOD OSD shows the buffered range in the seek bar.

**Native libraries:** `TorrentNative` loads `libjlibtorrent` for the process ABI before any jlibtorrent class is touched. The APK ships the `arm`, `arm64`, `x86_64` and `x86` binaries (x86 is needed because the APK already has `lib/x86` from other libraries, and API 30 Android TV emulator images are x86). A device without a usable binary gets "The torrent engine is not available on this device (ABI …)" instead of a crash.

Direct (HTTP) links from an addon, for example debrid links, are played directly by ExoPlayer without the engine.

## Feature reference

### Menu and navigation

The left menu is **Home**, **SAT-TV** (flyout: Live TV, TV Guide, Movies, TV Series, Radio), **IPTV** (when a playlist is available; flyout: Live TV, TV Guide, Films, Series), **Torrent** (only when a valid add-on URL is set), **Sport** (opens on a region picker, then Soccer), **Search**, **My Items** and **Settings**, with Exit at the bottom; the last tab per group is remembered. The active profile's avatar and name sit at the top (OK: switch profile, Manage profiles, receiver standby / on). Right enters the open page. D-pad Left moves within the page first (rows scroll back to their first item); Left on the page's leftmost item returns to the menu on the item of the open page, and Right or OK there goes back to where you were. A held Left stops at the start of a row and never opens the menu (press again). Back also returns to the menu. The player, its OSD and dialogs keep their own Left.

- Up and Down only move the highlight; Up on the first and Down on the last item stay in the menu. Right from any item enters the open page and restores its last focus (else the page's first item). Opening a page or loading data never sends focus back to the menu.
- Back from a page returns to the menu; Back again asks whether to stop EnigmaCast (Cancel stays, Stop closes and stops playback). While a stream runs, holding Back (or pressing Play/Pause) on any menu returns to the full-screen player. In [hotel mode](#hotel-mode) the guest menu replaces this menu.
- Lists: focusing a visible row never scrolls the list; channel and bouquet lists wrap at their ends (not while a key is held), grids stop at their top and bottom edges. Poster grids scroll only as far as needed and keep the whole enlarged card (including its year and rating line) on screen.
- Dialogs open in their own window: focus cannot leave them and Back closes them.
- Text fields only highlight on focus ("Press OK to edit"); OK opens the matching keyboard, Done / OK / Back ends editing on the same field.

### Home

One card per tuner of the box shows what uses it (the box's own picture, a recording, or a stream with the client's name), with picon, bouquet and current programme, beside the mini player. Below come TV series cards (recordings of one series grouped), Continue watching (IPTV and torrents), **Scheduled today** and **Upcoming** (7 days) as poster rows. Each card has the TMDB poster, title, channel logo, time, a badge (Record box / Record TV / AutoSwitch) and a Now tag. OK opens Cancel recording / Cancel AutoSwitch / Close with a confirmation; a box timer is removed with `/api/timerdelete`.

### Movies and TV Series (TMDB)

Movies shows the films on TV today, with artwork and data from TMDB. At the top is a hero with the best film still to come (or airing now): backdrop, title or logo, rating, year, genres, plot, channel and time, a LIVE badge when it is on, and Watch / Info / Schedule. Below are rows of poster cards: Now on TV, Tonight (from 20:00), Top rated today, one row per genre with at least three films (Action, Comedy, Drama, Family…), and Later today. Each card shows the channel and start time ("RTL 7 · 20:30"), the rating, the picon, a progress bar when airing, and small marks for AutoSwitch (⏰) and a recording (●). Moving over a card changes the backdrop.

OK on a card opens a full-screen info page: backdrop, poster, title or logo, year, runtime, age rating for your country, genres, rating, tagline, plot, director, cast (Down, then Left/Right) and the channel with its time. Buttons: Watch (starts the channel; for a later film it starts the channel now), Schedule (AutoSwitch, the same reminder as in the guide), Record (the same recorder as the guide: on the TV or as a receiver timer, per Settings › Playback), and Close. Focus starts on Watch and Back closes.

Cards show the title, the channel on its own line, and the time on its own line: "20:30 – 22:05" for a later film (with the start time also as a badge at the bottom left of the poster), or "LIVE · 32 min left" while it is on. The info page fits on one screen without scrolling; More shows the whole plot.

**TV Series** is the same page for series, matched against TMDB TV. Series are recognised from the DVB genre, episode labels (S1E4, "Afl. 12") and titles that recur in the guide.

How films are found: every TV bouquet of the receiver (up to 12) is read with `/api/epgmulti` from four hours ago until midnight (until 03:00 late in the evening); a bouquet without an answer falls back to `/api/epgservice` for its first 40 channels. A programme counts as a film when it is 70 minutes to 4 hours long, is not news/sport/series-like, and has enough clues: the DVB genre Movie/Drama (`genreid` 0x10–0x1F), a prefix such as "Film:", a year, film words in the description, a typical film length. The title is cleaned ("Film: The Matrix (1999) (HD)" → "The Matrix") and searched on TMDB with the year, the language (nl-NL for Dutch) and the region. A result is accepted when the title (or the original title) is similar enough, the year is within one, and the film fits in the slot. The best film is ranked by a vote-weighted rating plus popularity, with a small bonus for films starting soon.

**Home bouquets** (Settings › Guide & Home › Home bouquets): the row shows "X of Y selected"; OK opens a list of all television bouquets with a checkbox each (OK toggles), Select all and Deselect all at the top. The choice is stored per receiver; by default every TV bouquet is selected (also bouquets added later). Movies and TV Series only use channels of the selected bouquets, each channel once. A change recomputes the films from the cache in the background without a new guide download. With no bouquet selected the page shows a hint to pick one.

The country is Settings › Guide & Home › Country, or Auto: the network country, then a lookup of your internet address (ipapi.co, 3 s, cached a week), then the TV's region. TMDB answers are cached on the TV for 24 hours; at most 4 requests run at once and 20 per second.

### Room cache and background refresh

Movies, TV Series and Home read only from a local Room database (`enigmacast-cache.db`), so they open at once from what is cached. The cache is filled per receiver (bouquets, channels, guide, TMDB matches and details, and the matched films), so a second box keeps its own data. On first use, or when you switch to a box with no data, a full sync starts right away in the background; the page shows "Preparing your guide… 37%" meanwhile. After that WorkManager checks every hour (with a network connection) and refreshes what is due. Settings › Guide & Home › Data & sync shows each option as a compact row with its current value; OK opens a list with the current value checked and focused: Channels & bouquets refresh and Guide (EPG) refresh (Off, 1×–7× per day), Home films (TMDB) (Same as guide by default), Guide days to keep (today, +tomorrow, 3 days), the last update of each, the cache size, Refresh now and Clear cache. "N× per day" means a refresh when the last good one is older than 24 h / N; Off means only Refresh now (and the first fill of an empty cache). Guide data older than yesterday is deleted. The rows are recomputed from the cache every 30 seconds against the TV clock, so Now on TV, Tonight and Later today move on by themselves and switch to the new day at midnight (films that started late yesterday stay in Now on TV until they end). All television bouquets are synced (no cap), so a large bouquet list makes the first sync and each guide refresh take longer.

The TV guide grid, the programme list of a channel, the television bouquet list (bouquet picker) and the channels of a bouquet (channel list, zapping) read from Room first, per receiver. When the cache has nothing for the requested window or bouquet, the app asks the receiver as before; when that window lies within the synced days a background sync is started as well (at most once a minute). Beyond "Guide days to keep" the guide comes from the receiver, or stays empty when the receiver has nothing. Now/next in the channel list, radio bouquets and the receiver's current bouquet still come from the receiver directly. AutoSwitch and Record marks work as before, as they are matched on the event, not on its source.

After a box is connected, nothing is played until that box's channel list is in. A dialog blocks the screen while the list loads (no Cancel; Back does not close it). It names the step in progress — bouquets, channels, or programme information — and shows how far those steps are, as a percentage. The receiver does not report a finer figure, so the percentage moves when a step finishes. A missing programme guide is shown as no programme information; an HTTP 500 from an EPG route is an empty guide, not an error. If `/api/getservices` with `picon=1` returns HTTP 500, the same bouquet is requested again without picons. A bouquet that still fails is skipped and another bouquet is used. The dialog then closes and the stream starts.

Bouquet rows from OpenWebif are accepted as `[reference, name]` or `[name, reference]`. The current bouquet is `info.bqref` / `info.bqname`. TV bouquets are service type 7, and playable channels are type 0. Empty bouquets stay in the list.

### Receiver power

Settings › Receiver holds the power controls of the box in use (see [Several receivers](#several-receivers)):

- Standby or Turn on (wake), depending on the current state
- Restart GUI (restart Enigma2 only), Restart (reboot), and Deep standby
- Sleep timer: off or 15 to 120 minutes, followed by standby or shutdown
- An info card with model, image, Enigma2 and OpenWebif versions, kernel, chipset, uptime, and tuners

Reboot, GUI restart, and deep standby ask for confirmation, with Cancel focused. Every command ends with a short message at the bottom of the screen. After standby or wake the app polls `/api/powerstate` until Enigma2 reports the new state, because the reply to `newstate` still carries the state from before the command. The power state is polled every 10 s while the dashboard is open (5 s during a restart).

In "Follow receiver" mode, when the receiver goes to standby (from the app, the remote, or its sleep timer), the live stream stops so the tuner can sleep. When it is on again the guide and mini player come back. In "Own tuner" mode standby changes nothing: the stream keeps playing, because the receiver's stream server works in standby. Deep standby stops the stream server in both modes, and cannot be undone from the app; use the remote or the power button on the receiver.

### Viewing modes

Settings → Playback → Viewing mode chooses how this device watches. Each device has its own choice.

**Own tuner (several viewers)** is the default for new installs, and installs that saved settings before this option existed switch to it too (the setting was missing there, so the default applies). Choose "Follow receiver" once to keep the old behaviour; that choice is saved.

- The device asks the stream server for `http://HOST:8001/<service reference>` itself. The receiver is not zapped and not woken. When this mode is already on at startup, or when you switch to it, the receiver is put in standby so its own screen is off (skipped if it is already in standby). Waking it later from the Receiver page is left as you left it; the stream on this TV keeps playing.
- With two tuners, two devices can each watch a different channel. A third device is told that all tuners are busy, unless its channel is on a transponder that is already tuned (Enigma2 then serves it from the same tuner). When more than one box is saved and this box has no free tuner, the next box in the list is asked for the same service reference (see [Several receivers](#several-receivers)).
- Choosing the channel that is already playing does not stop and reopen the stream. That covers the channel list, CH+/CH−, digits and search, in both viewing modes. A retry, an automatic restart, or a stream that is not actually playing still rebuilds it. OK on that channel a second time still opens the full-screen player.
- The app draws its own OSD, because the receiver's OSD exists only on its own HDMI output. OK shows the channel banner (number, name, picon, now and next with times and progress, audio and subtitle tracks, and receiver-info pills to the left of the resolution: signal strength and quality, satellite position such as 23.5°E, 19.2°E or 13.0°E, and whether the channel is free or encrypted). The pill data is separate from the skin that draws it. Signal values are shown only when they belong to this stream's tuner. Own-tuner mode puts the box in standby, and OpenWebif `/api/tunersignal` then only describes the HDMI service, which is empty. The strength and quality pills then come from that stream's frontend (`/proc/stb/frontend/<n>/snr` and `signal`, and `snr_db` when the image has it), downloaded through OpenWebif. The tuner index is the stream client in `/api/about`. Without an index, the pills are shown only when a single frontend is locked. A reading from another tuner stays hidden. Images that refuse the file download (HTTP 403 or 404) keep the pills empty. IPTV has no signal. OK again pauses or resumes with timeshift (playback continues from that moment until the next channel; a full buffer returns to live). Left opens the channel list. Info or Guide shows the programme text. Up/Down and CH+/CH− zap this device, digits pick a channel number from the current bouquet, and Down on the banner opens the quick options bar. Back first closes an open programme dialog, search or options bar. Then: in the channel list overlay (opened with Left), Back closes the overlay, from the channel list and from the bouquet column alike, and the picture is full screen again on the channel that plays (nothing is zapped; Left inside the overlay still moves from the list to the bouquets). Only Back on the full-screen picture (with or without the banner) opens the TV guide (the full grid with the side menu, see TV guide) with the playing channel and its programme on air highlighted, in that channel's bouquet; the stream plays on in the guide's mini player and OK on a programme on air zaps there full screen, Back in the guide moves to the side menu. Without Premium (guide locked) Back on the picture returns to the dashboard with the side menu's TV item focused (in hotel mode the hotel menu, never the launcher). Back from the programme panel returns to the channel list. In follow-receiver mode Back is still sent to the receiver as EXIT and a long press leaves to the dashboard. Colour and menu keys go nowhere; nothing is sent to the receiver.
- The full-screen channel list is dense, with about 15 rows on a 1080p screen. Each row shows the number, picon, name and the programme on air (start time, title, thin progress bar). The programmes come from one `/api/epgnow?bRef=…` request per bouquet. That request is cached and repeated after 5 minutes, or sooner once a programme has ended. Left in the list opens the bouquets with the current one focused. OK or Right shows that bouquet's channels and returns to the list, and Back from the channel list or the bouquet column closes the list (full-screen picture, same channel). Down on the last row wraps to the first, Up on the first wraps to the last. The chosen bouquet is remembered and also shown on the dashboard. Choosing a bouquet does not zap; OK on a channel does. Right on a channel opens its programme panel (see TV guide). The bouquet column lists television bouquets, or radio bouquets when the station that is playing is radio. A programme is shown on a channel when the EPG reference differs only by service type (SD, HD, H.264, HEVC) or is the parent of a linked subservice. If the receiver has no current channel, the first television bouquet that has channels is loaded.
- Right on the full-screen picture zaps back to the previous channel.
- The dashboard's channel list zaps this device's stream the same way. The last channel is remembered per receiver and starts again the next time that box is in use.

**Follow receiver** is the original behaviour: zapping changes the receiver's channel, the stream shows what the receiver shows, the receiver's OSD is drawn on top, and the remote controls the receiver. Use it when one TV is the only viewer and you want the receiver's menus.

Audio sync, subtitles, and HDMI passthrough work the same in both modes; they belong to the player, not to the mode.

#### How the free tuner is found

Before a stream starts, the app reads:

- `/api/about`: the tuner list (`live`, `rec`, and on oe-alliance images `stream` per tuner) and the stream clients (`streams`: service, client IP, and on oe-alliance images `tuner_number`)
- `/api/statusinfo`: whether the receiver is in standby and which service it shows
- `/api/timerlist`, only when a tuner shows a recording, to learn which transponder the recording holds

A tuner is busy when it shows the receiver's own picture (only while the receiver is on), a recording, or another device's stream. Streams from this device are not counted, because the app stops its old stream before it starts the new one. Tuners that cannot receive the channel (a DVB-C tuner for a satellite channel) are left out. Every distinct transponder in use needs a tuner, so the estimate is the larger of the busy tuners and the transponders in use. A channel on a transponder that is already in use is always allowed.

The check can be wrong, so the stream start is watched as well. Enigma2 answers "200 OK" first and only then looks for a tuner; when none is free it closes the connection without sending video. A stream that ends with almost no data, or sends nothing for 10 seconds, is treated as a full receiver. With more than one saved box the app then tries the next box, the same way as when the estimate already said the tuners were full. When every saved box is full, the box you had selected stays the one in use and the player says that all tuners are busy (for example 2/2) and suggests a channel on the same transponder, with the recordings and stream clients that hold the tuners. OK tries again. A refused stream is not retried by itself. A login failure, an unreachable stream port, or a stream with no picture does not move to another box. Internet streams skip the tuner check.

Other start problems get their own message: a 401 means stream authentication is on and the credentials are missing or wrong; a refused connection means the receiver is off, in deep standby, or the stream port is wrong; data without a picture after 12 seconds usually means a scrambled channel that the CI module or card cannot descramble for this stream.

### Quick options

Down on the channel banner (own tuner), or holding Info while following the receiver, opens a row of tiles at the bottom of the player. Left/Right pick a tile, OK opens its choices, Back closes the bar. Search is not a tile: the remote's Search key opens it. Hotel mode hides record, stream info, equivalent channels, sleep and PiP.

- Equivalent: another version of the playing channel (HD/SD, satellite or IPTV)
- Record: records the programme on air (see Record)
- Subtitles and Audio: the stream's tracks by language, plus the stereo/HDMI output; the live subtitle choice is remembered per channel
- Boost (live TV, always the fifth tile): +3 to +15 dB in five steps for quiet channels, PCM audio only, remembered per channel and synced
- Picture: Auto (fit), 16:9, 4:3, Zoom (fill, crop edges), Stretch
- Audio delay: the audio sync value of the output in use, saved as in Settings
- Quality: As in Settings, Normal, or a transcoded level (720p/576p/360p) for this channel (see Stream type)
- Guide, Recent and PiP (own tuner and IPTV live only): back to the TV guide on the dashboard, the last eight channels watched (logos, paging, a tile to clear the list), and picture-in-picture
- Sleep: closes EnigmaCast after 15–120 minutes, with a warning a minute before
- Stream info: video, audio, bitrate, buffer, dropped frames, stalls, source, route and quality
- Restart stream

### TV guide

The guide is the dashboard's TV Guide page, below the mini player: channels of the current bouquet on the left, programmes on a time line on the right in half-hour columns, two hours at a time, with a line at the current time. The channel that plays is marked. Next to the mini player are the details of the highlighted programme. It works in both viewing modes.

- Up/Down move a channel (wrap at both ends); CH+/CH− (or page keys) six channels without wrapping; Left/Right move a programme and scroll the time line, up to seven days ahead
- OK on a programme on air starts that channel in the mini player, OK again opens the full-screen player; OK on a coming programme opens a dialog with AutoSwitch, Record and Cancel (see AutoSwitch), and a small clock marks programmes with AutoSwitch set
- Left on the programme on air opens the bouquet column to switch bouquets
- Back goes to the menu; Back from the full-screen player comes back here with the highlight on the channel that was playing

Programmes are loaded per bouquet with `/api/epgmulti` in three-hour blocks, only for the visible time window, and kept for 30 minutes; rows are drawn lazily. In the channel list, Right opens a programme panel for the focused channel (`/api/epgservice`, kept 10 minutes) with the same AutoSwitch / Record dialog and clock marks.

The TV Guide page has the mini player at the top with the details of the highlighted programme next to it, and below it the guide of the current bouquet (see TV guide). Right from TV Guide enters the grid. Radio uses the live browser for radio bouquets. While a radio station plays, the mini player and the full-screen player show a now-playing card (picon, station, programme, artist when the stream or the EPG has one) instead of a black picture. The card drifts slowly so it does not burn in. OK on a programme on air starts that channel in the mini player, and OK again on the same channel opens the full-screen player. Back returns to the menu.

Left on the programme on air opens the bouquet list and focuses it. Resting on a bouquet for half a second loads its channels into the guide. Right closes the bouquet list and returns to the guide. Down on the last channel or bouquet goes to the first, and Up on the first goes to the last; page and CH jumps do not wrap.

### Stream type

Settings → Playback → Stream type (per receiver; the connection screen has the same choice under Stream quality): **Normal**, **Transcoded** or **Automatic**. The port used is logged at INFO with every stream start (`stream target route=… profile=… transcoding=… level=… url=…`, credentials removed).

- **Normal** streams the channel unchanged from the stream port (8001): full quality, the receiver's encoder is not used.
- **Transcoded** always asks the receiver's hardware encoder for a smaller stream, also at home: 720p/2.5 Mbit/s, 576p/1.2 Mbit/s or 360p/0.6 Mbit/s, as chosen in the Size row. The URL is `http://<receiver>:<transcoding port>/<service reference>?bitrate=<bit/s>&width=<w>&height=<h>`, with one question mark.
- **Automatic** streams Normal on the home address (also on a metered network) and does not ask the receiver about transcoding there. Only on the remote profile or the tunnel, and only when `/api/about` reports transcoding, does it start transcoded at 576p (or the last level that played well). Two stalls within a minute step one level down; ten minutes without a stall step one level up again. Live TV cannot show spare bandwidth, so going up is time-based, not measured.

The transcoding port (default 8002) is editable on the same card. "Ask the receiver" (also done automatically when you choose Transcoded or Automatic) reads OpenWebif's phone playlist (`/web/stream.m3u?ref=…&device=phone`) and takes the port from it: OpenWebif builds that playlist from the TranscodingSetup plugin on Broadcom boxes (8001 or 8002) and from the normal stream port 8001 on boxes with another encoder (for example HiSilicon models such as the SF8008, where Enigma2's own stream server transcodes when the URL has `bitrate`). When the playlist has no transcoding parameters, the box has no encoder and the card says so.

Transcoded viewing therefore needs a receiver with a hardware encoder, and on Broadcom boxes the TranscodingSetup plugin with its port open. When a transcoded stream fails before its first picture, the app says "Transcoded stream unavailable; check the TranscodingSetup plugin and port. Switched to normal." and plays Normal for the rest of the session on that receiver; it does not try again by itself. Changing the stream type, size or port in Settings, or picking a transcoded level in the Quality tile, tries the encoder again once.

Automatic stream restarts (after a failed start, a receiver that was away, or a quality change) are limited to three tries, 1, 2 and 4 seconds apart. After that the error stays on screen until you press OK; a stream that plays resets the count. The Quality tile in the player's quick options overrides the stream type until the next channel start, and Stream info shows the level and why it was chosen.

### Internet streams in bouquets

Bouquets can mix normal Enigma2 (DVB) channels with internet / IPTV entries. Their service reference has ten identity fields, then the stream URL (its own `:` written as `%3a`, sometimes double-encoded as `%253a`, and sometimes stored with the `%` missing as `https3a//`), then a plain `:` and the channel name, usually with type 4097, 5001, 5002 or 8193:

`4097:0:1:0:0:0:0:0:0:0:https%3a//m1b2.worldcast.tv/dancetelevisionone/2/dancetelevisionone.m3u8:Dance TV - Best of Dance TV`

EnigmaCast restores a missing `%` in `https3a//` and `http3a//`, splits the reference on its plain `:` first and decodes only the URL field, so the channel name never ends up in the address and other escapes in the URL (`%2F` in a token) stay as they are. Credentials in the URL (`user:pass@host`) are kept; Enigma2 player options (`#User-Agent=…`) are dropped. The log line is `stream target kind=internet …`.

These URLs are played directly with ExoPlayer: no tuner check, no ports 8001/8002, no Normal / Transcoded / Automatic. `.m3u8` (or `type=m3u8`) plays as HLS from the live edge, `.mpd` as DASH, `.ts` as MPEG-TS; anything else is detected by the player, and a stream it cannot read is tried once more as HLS. A failing stream is retried at most three times (1, 2 and 4 s apart); then the player stops and says "This internet stream format is not supported" or "could not be played after 3 attempts". OK tries again. In "Follow receiver" mode the app starts the URL at once and also asks the receiver to zap (best effort), so full screen, which follows the receiver, shows the same channel. The guide (`/api/epgservice`) is asked for these channels too: receivers with imported EPG for IPTV return programmes; others simply return none. Back, OSD and AutoSwitch keep working when switching between an internet channel and a DVB channel.

### IPTV (Xtream / M3U)

Settings › IPTV chooses the source: the **XStreamity** playlists of the receiver (read from `/etc/enigma2/xstreamity` over OpenWebif `/file`, FTP as fallback) or an **own playlist** (M3U URL or Xtream Codes login). An own playlist is stored in the encrypted `SecretStore` and loaded into Room in the background: everything is written under a staging source and swapped in at the end, so a failed or cancelled load leaves the old list. Categories can be hidden per source and section. Several own providers can be added (their categories are grouped per provider and empty categories are skipped); the list can be exported to and imported from the clipboard, the setup wizard lets you choose providers, and access to the shared playlist can be limited per profile. When a provider refuses a stream because its maximum number of connections is in use, the player says so and retries.

- **Live TV:** categories, then the channel list (number, logo, name, catch-up badge, programme on now with progress), then Right for the programme column of that channel. OK plays in the app's player with the same skin OSD, channel banner and options row as Enigma2 channels; catch-up plays past programmes on channels with an archive. A setting can start full screen on the last satellite or IPTV channel. Back in the full-screen player opens the IPTV TV guide on the playing channel (below); when the guide is locked without Premium it returns to the dashboard with the side menu's IPTV item focused (the hotel menu in hotel mode). Left opens the channel list overlay (Left again the categories); Back there, from the channel list or the categories, closes the overlay and the channel plays on full screen (a category only browsed is dropped; the programme column first returns to the list). CH+/CH− also work right after that autostart: the remembered channel's category is read from the cache (or loaded) and the channel is matched to its row, also for playlists with several providers.
- **IPTV TV guide:** a full grid (side menu entry TV Guide in the IPTV group, and Back on IPTV live TV) of the channels of one live category: ◀ at the start of the grid opens the categories column, where moving through the categories previews their channels. The highlight opens on the playing channel at the current time (otherwise the last watched IPTV channel, otherwise the first channel), in that channel's category. Programmes come from the provider's EPG per channel (the same Xtream EPG requests as the programme column; M3U playlists use the programmes already known for the channel), cached in Room like the programme column and loaded for the rows around the highlight, refreshed every minute in the background; satellite programmes with the same channel name fill in while it loads. Channels without programmes show "No guide information". A now line, programme details above the grid; OK on a programme on air plays that channel live full screen. No catch-up from this grid. In hotel mode the guest menu has a TV Guide item (satellite or IPTV, whitelist only); Back on live TV opens it, Back in it goes to the menu and Back on the menu returns to live TV.
- **Films and Series:** categories and a poster grid (TMDB artwork per visible card, item count and focused position in the header), an info page, seasons and episodes, resume ("Resume from …" / "Start from the beginning"), progress on the cards, Continue watching, favourites, a next-episode corner card, and Android TV *Watch Next*.
- **Picture-in-picture** with a satellite or IPTV source, and **fallback**: after the usual retries, a channel that drops out can play from the other source (satellite → IPTV or IPTV → satellite).
- **Satellite EPG for IPTV:** an IPTV channel without its own guide uses the guide of the satellite channel with the same name (compared without accents, HD/FHD/UHD/4K/SD, brackets and region suffixes; the HD channel is preferred).

#### IPTV row EPG

The channel list shows now/next on every row without slowing down scrolling:

1. Nothing is fetched while Up/Down is held: only after the list has rested about 450 ms, and only for the visible rows.
2. The bulk guide in Room is asked first (the receiver's satellite guide, matched by name, one query).
3. Then the listings stored per stream (memory and the Room table `iptv_row_epg`; good for 20 minutes, an empty answer is asked again after 10).
4. The rest comes from Xtream, the same listings the programme column uses (`get_short_epg`, and `get_simple_data_table` when that has nothing on now), at most 4 requests at a time, off the main thread. Each answer updates only its own row. A failed or cancelled request is never remembered as "no guide"; it is retried after a minute.

Channel logos start loading after a row has been on screen for 150 ms, 4 at a time, decoded subsampled to at most 192 px wide into a 16 MB cache.

### Torrents

The **Torrent** page browses TMDB: Movies and Series tabs, TMDB's popular list or `/discover` for a genre or another order, a search icon that opens the keyboard at once, and two compact dropdowns under their chips: **Genre** (capped height, scrolling) and **Sort** (Popular, Year (newest), TMDB score). Cards show poster, title, year and TMDB rating.

- OK on a card opens the TMDB info page (backdrop, rating, plot, seasons and episodes for series, favourite toggle); Play asks the addon for streams and lists them in the **Streams** dialog (torrents with their release name, and direct links). A Stremio-style loading page shows the progress until the first frame.
- **Addon:** Settings › Torrent has one field for your own Stremio addon manifest URL; there is no on/off switch and no default addon. A saved URL turns Torrent on, removing it turns it off. Settings from older versions keep their saved URL (also one saved while the old switch was off, which a URL always overrode); the switch and its preset are gone, so an install that only used the preset shows the hint until a URL is added. Saving checks the manifest (valid JSON, offers `stream` for movies or series). Manifest URLs can contain a debrid key: they are kept in the encrypted store and only shown and logged masked.
- **Library:** favourites and Continue watching per profile, shown on Home and in My Items and synced through the sync server. Removals sync too: each device keeps the removal time, so an older copy from another device cannot bring the item back; removing a series from Continue watching removes all of its saved episodes. A series stays in Continue watching until its last episode of the last known season is watched: after each finished episode the card moves to the next one (or the next season's first), marked *Next episode* until it is started, also on the Android TV Watch Next row. The last torrent stream of a title or episode (info hash, file index, public trackers) is remembered, so **Resume** starts it again without asking the addon; the resume prompt offers *Resume from …*, *Start from the beginning* and *Choose another source*. Direct links are never stored.
- **Player:** the shared VOD OSD (below) with the buffered range in the seek bar, SubSource subtitles, audio and video track choice. See [Torrent engine](#torrent-engine) for how streaming works.

You are responsible for what you stream; see [Attribution and legal](#attribution-and-legal).

### VOD player OSD

IPTV films and series, recordings and torrents share one OSD after the Stremio Android TV player: the title, Play/Pause · Rewind (· Next), a thin seek bar with a round thumb and a buffered range, and a row of small outline icons (subtitles, audio, video, settings, sources) with elapsed / total time on the right. Left/Right move the position at once (held: faster) and the file plays from there half a second after release; OK pauses. Subtitles come from the stream or from **SubSource** (matched on the release / file name, with a subtitle delay).

### My Items

Recordings of the box in use and of this TV, timers, IPTV and torrent Continue watching and favourites. Recordings show posters, a resume position with a progress bar and seeking; rows show Scheduled, Recording, Ready or Failed. OK plays a finished or in-progress item, Stop ends a capture, deleting asks for confirmation. Right from the menu enters the list.

### Profiles

The "Who's watching?" picker opens on the first start and whenever there is more than one profile. Each profile has a name and one of 16 avatars (DiceBear, CC0, see `NOTICE.md`); up to 6 profiles can be added, edited or deleted under Manage profiles (the active profile and the last one cannot be deleted).

**Profile photo via QR code.** In the profile editor, *Add profile photo via QR code* starts a one-off HTTP server on the TV's LAN address (port 8765–8775, else any free port) and shows a QR code plus the URL. The phone must be on the same network; nothing goes through the internet. The page (EN/NL/DE/IT/FR from the phone's Accept-Language, no outside resources) lets you pick a photo, drag and zoom it in a square frame and save. The TV checks the single-use token (128 random bits, valid 10 minutes, never logged), the size (10 MB) and that the file really decodes, applies the EXIF rotation, crops the square, scales it to 512 px and stores it as WebP in app storage (`files/avatars/<profile uuid>.webp`). The avatar changes at once everywhere; the server stops on success, when the dialog closes or when the code expires. Picking a bundled avatar again and saving removes the photo. The photo stays on this TV: sync only carries the bundled avatar number. QR codes come from a small built-in encoder (`QrCode.kt`), so no library was added.

- **Per profile:** last channel and bouquet per receiver, recent channels, picture mode per channel, OSD skin and banner fields, navigation sounds, motion quality, app language, Home bouquet selection, resume positions, IPTV and torrent library.
- **Shared:** receivers, viewing mode, stream and playback settings, recordings, timers, series rules, the guide/TMDB cache and soccer settings.

### Sync server

Settings › Sync server installs and connects a small sync service that runs on the Enigma2 receiver itself (a Python service with an Enigma2 menu entry, bundled in `app/src/main/assets/enigmacast-sync`).

- **Find or install:** the page first looks for a server on the receiver (port 8740, no login). When one runs, *Pair with existing server* joins it and imports its profiles (nothing is pushed); *Reinstall* is only behind a warning. When none runs, the install form appears: over SSH (port 22) with the receiver's user and password (used for that call only, never stored), from the latest public GitHub release IPK. Afterwards the web dashboard address and a one-time dashboard password are shown. The server also has an Enigma2 plugin menu entry.
- **Update:** when the server is older than the app expects, *Update sync server* runs over SSH: backup first, then `opkg` install, verify and restart; pairings, data, certificate and dashboard password are kept, and the update stops if the data folder would change. On start the owner gets a one-time prompt (Later / Update; Later remembers that release). In [hotel mode](#hotel-mode) it is only a notice. From 0.3.1 the server reports its version in `/api/v1/health`; older servers are shown as "< 0.3.1".
- **First sync:** pairing finishes within 30 seconds; the first full sync then runs in the background ("Synchronising… N items"). Servers from 0.3.1 send pulls in pages of 500.
- **Connect:** TVs find the server on the home network (port 8740). Traffic is HTTPS with a pinned certificate; each TV has an ECDSA P-256 key in the Android Keystore (the server stores only the public key). The first TV is the admin; a recovery code allows *Take over admin*.
- **What is synced:** profiles and their names, per-profile settings, IPTV providers, IPTV and torrent favourites, Continue watching, resume positions, watched marks, live subtitle choices and audio boost (an outbox in the user database is pushed, newer rows are pulled). An M3U or Xtream login stored on the server is offered once to a new TV in the setup wizard. Without a server Room stays as it is.

### Account and Premium

An account (e-mail + password) is optional and can be created under Settings › Account or in the setup wizard, on the account server in `BuildConfig.ACCOUNT_API_URL` (`register`, `login`, `me`, `logout` under `api/enigmacast/`).

- **Access mode** comes from the account server (`api/enigmacast/config`, see `domain/access/AccessConfig.kt` and `live/PremiumGate.kt`). In **Free** mode every feature is unlocked without an account. In login-required mode a signed-in account unlocks everything; there is no paid entitlement check. Locked items show a lock and open the sign-in dialog. GUI skins and all five languages are always available.
- **Usage counter:** one random install id and one anonymous heartbeat per day (no account, e-mail, content or IP address is sent), used to count active installs.
- Settings › Lock can ask for a 4-digit PIN before Settings opens (PBKDF2-SHA256 in the secret store; 5 tries, then a 30 s wait).

**Update manifest.** The app reads `dist/enigmacast-v2.json` (published with the release); older builds read `dist/enigmacast.json`, which may mark a release that needs a reinstall (`reinstallRequired`, `reinstallMessage`, `reinstallMessageNl`). The update dialog then only offers OK. The same manifest can carry a `companion` object with the phone app's version and APK; the TV ignores unknown keys, so the combined file stays backwards-compatible.

### AutoSwitch

In the TV guide on the dashboard and in the programme panel of the channel list, OK on a programme that has not started yet opens a dialog: **AutoSwitch**, **Record**, **Cancel**. The first button has focus.

- **AutoSwitch** remembers the programme (channel, event id, start time, title) and marks it with a small clock in both guides. OK on a marked programme offers **Cancel AutoSwitch** instead.
- Ten seconds before the programme begins, a notice appears at the top right of whatever screen is open (dashboard, full-screen player, settings): "<title> starts on <channel> in 10 s", counting down, with **OK: Switch now** and **Back: Cancel**. The notice never takes the focus: arrows and every other key keep working on the screen behind it; only OK and Back go to the notice while it shows.
- When the countdown ends, the app switches to the channel the way you watch: with an own tuner its own stream zaps (the usual tuner check applies, so a full receiver shows the normal "tuners busy" message in the player); when following the receiver, the receiver zaps. The full-screen player opens if it was not open already.
- Two programmes beginning within a minute of each other: the earliest (or the one set first) gets the notice, which names the other. Cancel shows the other one's countdown; switching drops the other, since only one channel can play.
- Reminders are saved (their own DataStore file) and survive a restart; programmes that began while the app was closed are dropped when it starts.
- AutoSwitch only works while EnigmaCast is open in the foreground. There is no alarm or background service: in the background or when the app is closed nothing appears. Back in the app within two minutes after the start, the notice still comes, with at least five seconds to cancel; later than that the reminder is dropped. It does not switch while the update dialog or the receiver setup screen is open.

### Search

One field searches live programmes, upcoming EPG, recordings, bouquet names and channel names. TMDb is reserved and unused. From two characters, after a short pause (~300 ms), results are grouped: **Now** (on air), **Upcoming** (by start time), **Recordings** (My Items, matching title or channel name), **Channels** (bouquet names and channel names). Empty query shows a hint; no hits one muted line. A spinner shows while a search is running.

Rows use the same dense style as the channel list. A programme row is `channel · title` (date and time on the second line for upcoming and recordings). A channel row is `bouquet · channel`. Choosing Now, Upcoming or a channel plays that service and switches the bouquet to the one that contains it (own tuner starts with that bouquet's list so CH+/CH− work; follow mode zaps as usual). A future programme tunes the channel that will air it. A bouquet-only row only switches the guide. A recording plays from My Items.

The same panel is on the dashboard (Search in the menu) and in the player (the remote's Search key). On the dashboard, Right from the menu enters the field; Back returns to Search. In the player, Back closes the overlay.

**Android TV Assistant.** "Search for Formula 1 on EnigmaCast" (or BBC One, Rai 1, yesterday's recordings) opens this panel with that query, so you need not type on the remote. "Play … on EnigmaCast" (`MEDIA_PLAY_FROM_SEARCH`) does the same and then plays the first strong hit: an exact channel name, else a live programme, else the first row. In the search field a microphone (and the remote's Search key) starts on-device speech recognition. Spoken or typed "yesterday's recordings" / "opnames van gisteren" lists recordings that started the previous local calendar day.

Search runs on the Room cache (ignoring accents) and falls back to the box; it also lists IPTV Live / Films / Series rows from the playlist.

### Record

OK on a coming programme, or the Record tile while watching, asks where to store the capture: **this TV** or **the receiver**. Settings → Playback holds the same choice (Recording destination) and Recording size: Original, or a transcoded level on this TV (the encoder port is the one from Stream type; what you watch stays the same).

- **This TV** writes the live MPEG-TS on the device while you keep watching. Playback is from My Items as a file (not a live tuner stream), with pause, rewind and a progress bar.
- **The receiver** uses OpenWebif `/api/timeraddbyeventid` (and related timer calls). The box records independently; My Items lists it when the file is known.

My Items is the dashboard section under Search. Right from the menu enters the list. The list shows recordings and timers of the box in use (Settings → Receivers). Rows show Scheduled, Recording, Ready or Failed. OK plays a finished or in-progress item; Stop ends a capture; deleting asks for confirmation.

- **Record dialog:** the choice dialog is compact and puts the box first, named after the receiver ("Record on SF8008", focused by default), then "Record on this device", then Cancel. **Record all upcoming** records every coming episode on the same channel: on the box through an AutoTimer (`/autotimer/edit`), or with an app rule that keeps scheduling new episodes. If an episode is already set to record, the dialog still offers Record all upcoming. Upcoming episodes are taken from the cache and from the box's guide, up to 8 days ahead.
- **Recording vs tuners:** when a recording (a box timer or the app's own capture) is about to start and no tuner is left, this device's stream moves to the recording channel about 15 s before the start, with the notice "Recording starts: switching to <channel>". This only happens when this device's own stream is what blocks the recording. While a recording uses the last tuner, zaps to other transponders are refused with "Not possible during the recording on <channel>; only channels on the same transponder". Channels on the same transponder (the TSID:ONID:namespace parts of the service reference are equal) stay allowed. In multi-user mode, other devices' streams count as busy tuners; only this device's own stream is ever moved. The check is based on `/api/about` (tuners and stream clients), `/api/statusinfo` and `/api/timerlist`.

### Remote access

Settings → Remote access holds these per receiver (the connection screen has them too). Each receiver can have a remote profile: a DynDNS name or public address, the forwarded web, stream and transcoding ports, and HTTPS. An empty port means "same as at home". Access mode:

- **Automatic** (default): uses the home address when it answers a 1.2 s check, else the WireGuard tunnel when set up, else the remote profile. It checks again after a network change, after a failed request, and every five minutes.
- **Home only** / **Remote only**: no check.

The connection screen warns when a web address on the internet uses plain HTTP, and the player shows a one-time notice when streams go over the internet unencrypted. Enigma2 streams (8001/8002) are always plain HTTP; over the internet only a VPN encrypts them.

**WireGuard (experimental).** In Settings → Remote access, turn on "Use the built-in WireGuard tunnel", then paste the client config (`[Interface]` and `[Peer]`) or import a `.conf` file, and press Allow VPN once. The screen checks the config, shows the endpoint and AllowedIPs, and warns about a full tunnel (`0.0.0.0/0`) or a home address outside AllowedIPs. Test tunnel brings the tunnel up and asks the receiver through it. In use, the tunnel comes up only when home does not answer, carries only EnigmaCast's traffic, goes down after a network change (then home is checked again) and when EnigmaCast exits. With the switch off nothing of WireGuard runs: no VpnService, no socket binding, no route changes, and the VPN permission is not even checked. It uses the official WireGuard library (`com.wireguard.android:tunnel`, userspace backend on Android's VpnService), which adds about 3.5 MB of native code per CPU type to the APK.

The robust alternative is the official WireGuard app on the TV with the tunnel always on: leave the built-in tunnel off and use the home address (Home only).

Router and receiver:

1. WireGuard server on the router (Fritz!Box, OpenWrt, UniFi, …) with a peer for the TV; AllowedIPs on the TV side = your home subnet. Forward or open UDP 51820 (or the router's port).
2. Without a VPN: forward the OpenWebif HTTPS port (turn on HTTPS in OpenWebif) and 8001, and 8002 for transcoding, to the receiver; use a DynDNS name. Turn on stream authentication on the receiver. This exposes the receiver to the internet; the VPN is the safer setup.
3. For transcoding: a box with a hardware encoder; on Broadcom boxes also the TranscodingSetup plugin, whose port must be forwarded too. "Ask the receiver" in Settings → Playback → Stream type finds the port.

### Language

EnigmaCast is in English by default, also on a TV set to another language and after updating from an older version. Settings → Language offers English, Nederlands, Deutsch, Italiano and Français. The screen redraws in the new language right away and focus stays on the language choice. On Android 13 and later the choice also shows in Android's per-app language settings (`locales_config.xml`), and a language chosen there is picked up by the app. Channel names, programme titles and other receiver data are shown as the receiver sends them.

All texts are Android string resources: English in `values/strings.xml`, and the translations in `values-nl`, `values-de`, `values-it` and `values-fr`. Skin names are not translated. Logs, the debug overlay, OSD diagnostics values and raw error texts from Android or the network stay in English.

### Soccer

**Soccer** is under **Sport** in the menu. The page works like Home: a hero with the most relevant match (a live match first, otherwise the next kickoff in the first competition of the followed country), then rows: Live now, Today, Upcoming (one row per matchday, up to three) and Recent results, then the standings. Cards show both crests, short names, the score or kickoff time, the date, a status badge (LIVE 67', HT, FT, postponed) and the TV channel when one was found. OK on a card opens the match details: crests, score, half-time score, status, kickoff, venue, referee (when sent), the channel, and the last five head-to-head meetings. Buttons: Watch, Schedule (AutoSwitch with the existing reminders, at the programme's start) and Close. The hero also has Info and Standings (jumps to the table). The table shows #, crest, team, P, W, D, L, GD, points and form (when the plan sends it); a thin bar marks the title/qualification places, European places and relegation (approximate per league). Countries with two competitions (England: PL and Championship; Brazil: Série A and Libertadores; Europe: Champions League and European Championship) get a button per table.

Data comes from football-data.org v4 (free plan, 10 requests per minute), with the key from `FOOTBALL_DATA_API_KEY=` in the git-ignored `local.properties` (built into BuildConfig) or from Settings. Answers are cached in Room per competition (fixtures from yesterday to 14 days ahead, standings, head-to-head for a day), so the page opens from the cache at once. A shared limiter keeps every request within 10 per minute; HTTP 429 or a counter at 0 pauses all requests until the server's reset time. While the page is visible, competitions with a match in play or about to start are re-read every 60 s; nothing polls when the page is closed.

The TV channel is looked up in the cached guide of the active receiver (so only within "Guide days to keep"): a programme that starts within 30 minutes of kickoff and whose title names both teams (or, second best, title plus short description). Names are compared without accents, case and club words (FC, AFC, SC…), as full name, short name and a few TV spellings (Man Utd, BVB, PSG…).

Settings › Soccer: Country to follow (exactly one; only countries whose competitions the key can read; default from the TV's country, otherwise the Netherlands), Fixtures & standings refresh (15 min – 6 h, or only on first open), Live scores (on/off) and an optional own API key. Error states: no key, invalid key, too many requests, competition not in the plan, offline. "Data: football-data.org" is shown on the page and in the details.

#### Soccer details (PitchAPI)

- pitchapi.dev (free, no daily limit, fair-use burst limit) adds the Eredivisie and Eerste Divisie season rows (results, upcoming, live), a table calculated from results, and full match screens: events (goals, cards, substitutions), lineups on a pitch with formation and substitutes, match statistics, player ratings and head-to-head.
- Club page (OK on a team or table row): points, W/D/L, goals for/against, clean sheets, home/away record, form, next and recent matches, top scorers from match events.
- Player page: profile (shirt, position, country) and the stats of that match.
- Football-data.org stays for official standings and live minutes; its match dialog gets a Details button when PitchAPI has the same match.
- Key: add `PITCH_API_KEY=pk_…` to `local.properties` (read into BuildConfig, never committed). Answers are cached in Room (`soccer_fetches`).

### Several receivers

Settings → Receivers holds every saved Enigma2 box (for example living room, bedroom, vacation home). OK on a row makes that box the one in use and marks it "In use". The app does not switch to another box when the chosen one is off or does not answer. It does switch when that box has no free tuner for the channel you just chose and another saved box does: the boxes after the one in use are tried in list order, then the ones before it. The first box that can take the same service reference becomes the one in use and streams it. Tuner status, bouquets, the TV guide, the last channel, recent channels, My Items and the Receiver power page follow that box. When every box is full, the original box stays selected.

Add receiver opens the same address form as the first-launch connection screen (AutoDetect or a typed address). Save stores a new box and selects it. Edit opens that form for the box on the row. Remove asks for confirmation. Removing the box in use selects the next one in the list, or none when it was the last. Each box keeps its own login, remote profile, WireGuard config, stream type and last channel. A password or tunnel is dropped when that box is removed.

AutoDetect leaves out a host that is already in the list, so the same box cannot be saved twice. When every receiver the scan finds is already stored, the dialog says those receivers are already added.

### Hotel mode

Settings › Hotel mode turns the TV into a guest TV for a B&B, holiday home or hotel room. All hotel settings are device-local: they are never synced or exported.

- **Hotel PIN:** 4–6 digits, separate from the Settings lock, stored as a salted PBKDF2 hash. Hotel mode can only be turned on once a PIN is set. Five wrong tries lock entry for 1 minute, doubling each time (1, 2, 4, 8, 16, at most 30 minutes); the lock survives a restart.
- **Source:** Satellite TV or IPTV, optionally *With films and series* (only the IPTV provider's films and series). With IPTV the app warns that most providers allow one connection.
- **Fixed profile:** guests always use the hotel profile; the profile picker never shows.
- **Whitelist:** choose the visible bouquets (satellite) or IPTV categories (per provider and section). Lists, zapping, search and the guide only show these. An empty or no longer matching whitelist shows everything (and logs it), so guests never get an empty list.
- **Start channel and volume:** the last watched channel or a fixed one, and a start volume (percent, or Unchanged), applied at every start and after a cleanup.
- **Welcome screen:** shown at start for 5–60 seconds, with the B&B name, a welcome text, the Wi-Fi name and password (the password is kept in the encrypted secret store) and a Wi-Fi QR code; any key starts watching.
- **Next-guest cleanup:** *Clean up now*, or *Clean up daily* at a set time (default 11:00, quarter-hour steps; a missed cleanup runs when the TV comes on). It clears the hotel profile's watch history, Continue watching, film and episode history, recent channels and the live subtitle choice, and resets the start channel and volume. Audio boost and favourites stay; other profiles are not touched.
- **TV home screen:** while hotel mode is on with a PIN, EnigmaCast can be chosen as the TV's home app, so the Home key and switching the TV on open live TV.
- **Guest menu:** Live TV (satellite or IPTV), TV Guide (whitelist only, when unlocked), Films and Series when allowed, and Settings last. Settings asks for the hotel PIN; leaving hotel mode is Settings › hotel PIN › turn off. Back on live TV opens the TV guide, Back in the guide goes to the menu, Back on the menu returns to live TV.
- **Hidden for guests:** recording, switching receiver, stream info, equivalent channels, sleep timer and PiP (and search for IPTV). Sync-server updates are shown as a notice only; guests are never asked.

### Settings

Right from Settings opens a submenu, in this order. Each page groups its options on cards, and every switch row toggles with OK.

- Account: sign in, plan and devices (see [Account and Premium](#account-and-premium)).
- Profiles: who is watching (switch) and Manage profiles (add, rename, avatar or photo, PIN, delete).
- Phone & remote: pair the Companion app (see [Phone remote](#phone-remote-companion-bridge)).
- Receivers: the list of boxes. OK selects one, Edit changes its address and login, Remove deletes it, Refresh channels & bouquets. See [Several receivers](#several-receivers).
- Receiver: power (standby, wake, restart, sleep timer) and details.
- Playback & streaming: Video decoder and Compatibility mode, Stable or Fast buffer, stream type (Normal, Transcoded, Automatic; size and transcoding port), Stereo or HDMI, DVB subtitles, auto-reconnect, audio sync per output, satellite/IPTV fallback.
- Viewing mode: own tuner (multi-user) or follow the receiver.
- Remote control keys: see below.
- Guide & Home: Home country, Home bouquets, own TMDB key, data & sync (guide days, sync frequency, Refresh now, Clear cache).
- Sync server: find and pair, install, update, status, recovery code (see [Sync server](#sync-server)).
- IPTV: source (XStreamity or own M3U / Xtream providers), load, provider import/export, playlist access per profile, categories to hide.
- Torrent: your own add-on manifest URL (see [Torrents](#torrents)).
- Lock: 4-digit PIN for Settings.
- Hotel mode: guest TV setup (see [Hotel mode](#hotel-mode)).
- Recordings: recording size of captures on this device (where to record is asked per recording).
- Appearance: language, navigation sounds, motion quality, OSD (transparency, banner fields, receiver OSD) and GUI skins.
- Remote access: access mode, remote profile (DynDNS name, ports, HTTPS, Test remote) and the WireGuard tunnel.
- Soccer: country and API keys.
- Signal meter, Oscam and Settings import (tools, open full screen).
- About: Run setup wizard again, version and build, debug overlay, verbose logging, OSD border, and the TMDB attribution.

The old RC Info page is gone; the keys are now listed (and changeable) under Remote control keys.

### Phone remote (Companion bridge)

Settings › Phone & remote turns this TV into a Companion host. The EnigmaCast Companion app
on an Android phone pairs once with a six-digit code and then controls the TV: D-pad, OK,
Back, Home, Menu, media keys, zapping, volume, profiles and power. Pairing also stores the
phone's public key; every later request is signed (ECDSA P-256) and the connection is pinned
to the TV's certificate.

- One port (8750) serves HTTPS and the WebSocket; TLS 1.2/1.3 with a key in the Android Keystore.
- The bridge runs only while EnigmaCast is on screen: it starts in `onStart` and stops 30 s
  after `onStop`. Nothing goes through the internet.
- Settings shows the pairing code (valid for two minutes), the certificate pin and the paired
  phones; a phone can be revoked per device.
- Text input: press OK on any text field (search, receiver login, settings) and type on the
  phone; the TV updates live. Passwords stay masked and are never echoed back. A Test field
  under Settings › Phone demonstrates it without a receiver.
- Settings: the phone shows the full user-settings catalog (24 entries in Display,
  Playback, Startup, Remote and Debug; switches, choices and sliders) and writes it through
  the same settings store as this UI. Dangerous entries ask for confirmation; with a
  Settings PIN they are hidden and rejected. Profile management: create (max. six), rename,
  choose an avatar, delete (with cleanup) and clear a photo; profile PINs are enforced, and
  photo upload is blocked for PIN profiles.
- Live and IPTV: the phone also plays Enigma2 channels and IPTV directly (OpenWebif +
  Media3), without this TV app running. See the companion repository's
  `docs/playback.md`.
- Code: `app/src/main/java/com/enigmacast/companionbridge/`. The feature is behind the
  `COMPANION_BRIDGE` BuildConfig flag; see the Companion repository's `docs/tv-bridge.md` for
  the protocol and the test.

**Remote control keys.** One card per viewing mode (the mode in use first). Each action shows its keys: channel up/down, channel list, programme info, channel banner, options bar, TV guide, search, record, pause/resume, next audio track, next subtitles, next picture mode (own tuner); options bar, guide, search, record, audio, subtitles and picture mode (follow receiver). OK on an action offers Assign a key, Remove keys, Default keys. Assign waits for a key press; holding the key past the long-press time assigns a long press (so Info and held Info can do different things). A key already used by another action asks before it moves. Reset to default keys restores a mode. OK, Back and the number keys are fixed. Following the receiver, keys without an app action still go to the receiver. Stored in `remote.keyBindings`; defaults equal the previous behaviour (plus Play/Pause, Rec, Audio, Captions, Search and Blue = options bar on the own tuner).

**First-run setup wizard.** A fresh install (no receiver) starts a wizard in plain language, with "Step x of y · name" and Back / Skip / Next: language → receiver (searches by itself, one Connect, login only when asked, address-only manual entry with Advanced) → sync server (only when the receiver runs one: Pair or Not now) → IPTV login from the server (only when one is waiting) → viewing mode → account (optional, "Skip, set up later") → Done, which starts the first full sync and offers optional extras (name and picture, channel lists, football). The step list is `domain/setup/SetupFlow`; the update dialog waits until setup is done. Existing users never see it; Settings › About › Run setup wizard again opens it any time.

- Signal meter: the satellites configured on the tuner (from `/etc/enigma2/settings`, or from the television bouquets when that file cannot be read). A spinner is shown while the list loads. OK on a satellite stops this TV's stream, so the dish can move, and zaps the first television channel on that orbital position. The meter then polls `/api/tunersignal` about once a second (strength, quality, SNR in dB, BER, tuner). Back restores the channel that was on before and allows playback again; it does not restart the stream by itself.
- Oscam: the Oscam web interface on the active receiver, default port 8888 (not OpenWebif). It shows the version and uptime, each reader or proxy with its connection, current channel and ECM time, and the dvbapi users. The live log comes from `logpoll.html` (Oscam 2.26 stores each line as base64 under `oscam.lines`). Restart and Stop ask first. Stop ends the process; Oscam can then only be started on the receiver, because the web interface stops with it. A reader can be restarted on its own, and reader or user ECM statistics can be reset. The log can be paused or cleared, and the debug level can be set (off, errors, DVBAPI, ECM, or all). Login and password, when the web interface requires them, are stored with the other secrets.

**Text fields** work the same everywhere (Settings › Receivers when adding or editing a box, Remote access, Stream type, and the connection screen). Moving onto a field only highlights it: amber border and a small "Press OK to edit" hint, with no keyboard and no cursor, so Up, Down, Left and Right move straight to the next item (Right from the IP field reaches AutoDetect). OK starts editing and opens the matching keyboard: URI for host/IP, numbers for ports, password for passwords. The keyboard's Done, OK on the remote, or one Back ends editing and leaves the highlight on the same field. Back while typing never closes the screen; on Android 9–10 the first Back may only close the keyboard. The value is kept as you type and stored with Save/Connect as before. In the multi-line WireGuard field, Enter types a new line.

Stable is the default. It uses a larger live buffer (about 2–15 s) and a 4 s live offset. Fast uses the short buffer (about 0.5–5 s) and a 1 s offset. Changing playback rebuilds the player, except for the audio sync, which applies at once.

Stereo downmixes to PCM when the TV has a MediaCodec for that format. AC3, E-AC3 (including JOC), DTS and DTS-HD that the TV cannot decode are passed through HDMI when the output reports them, even while Stereo is selected. A format that can neither be decoded nor passed through is decoded to PCM by the bundled FFmpeg audio decoder (nextlib, GPL-3). Hardware decode and passthrough stay ahead of that fallback. HDMI still prefers passthrough for those formats and leaves AAC and MPEG for later. DVB subtitles are drawn on the video when the transport stream has a text track. The Playback page says whether the current stream has subtitles.

Audio sync corrects sound that runs behind or ahead of the picture, from -500 to +500 ms in 20 ms steps. Stereo and HDMI passthrough each keep their own value, because an amplifier or soundbar that decodes AC3/E-AC3 adds its own delay. The row the current track uses is marked "active now", and a small preview of the stream sits next to the rows. A positive value makes the picture and subtitles wait for the sound. The player's clock (the audio position) is reported that much earlier, and the audio data itself is untouched, so the correction also works for passthrough.

### Settings import

Settings › Settings import loads a ready-made channel list or IPTV bouquets on the receiver.
  - **Channel lists** come from the GitHub repos of Morpheus883, Ciefp and Hans. You pick a tuner configuration (for example 19.2E, or 19.2E + 23.5E + 28.2E). The preview shows the number of TV and radio bouquets, whether a `satellites.xml` is included, and when the list was updated.
  - **IPTV bouquets** are built from the iptv-org and Free-TV M3U playlists. Load groups reads the playlists with a progress bar per playlist. Every group is selected by default, with Select all / Select none and an X / Y count. Each chosen group becomes a bouquet after your satellite bouquets, named `IPTV · <group>` (the prefix is never doubled). The satellite bouquets themselves are not changed. Service type 4097 uses the default player; 5001 and 5002 need GStreamer or exteplayer3 on the receiver.
  - **Bouquet order:** `bouquets.tv` and `bouquets.radio` are written exactly as the list has them, so the receiver shows the author's order. Only userbouquets those files (or a bouquet in them) refer to are copied; any other userbouquet in /etc/enigma2 is removed, because Enigma2 would load such unlinked files on top in directory order. Our own IPTV bouquets stay and are appended at the end of `bouquets.tv`.
  - **Copying to the receiver:** files go over FTP (port 21, user root, usually without a password; the login can be set per receiver under FTP login). The receiver then runs OpenWebif `servicelistreload`, and the app resyncs its channels.
  - **Several receivers:** a load can go to more than one saved receiver.
  - **Backup and rollback:** before every load, that receiver's channel list is backed up. Restore puts any backup back.
  - **Load dialog:** the load runs in a modal dialog with progress per receiver (backup, upload, reload). Cancel rolls the receiver back to its backup. The dialog ends with the result.
  - **Update automatically:** Off, daily, every 7 or 14 days, or monthly, run by WorkManager.
  - **After a load,** the app waits until OpenWebif lists the new bouquets, then replaces the channel cache and refreshes the TV Guide, tuner cards and the player's zap list.

### GUI skins

Settings › Appearance › GUI skins shows one tile per skin with a small preview in that skin's own colours and banner layout. Left and Right walk the tiles, and OK applies a skin at once: every screen and the OSD repaint without a restart. The choice is stored with the other settings and per profile. Fresh installs and new profiles start in Glass Earth. Once, on the first start after the update, settings, saved profiles and synced profiles that are on EnigmaCast (picked or never changed) move to Glass Earth; any other skin stays. After that the skin and a migration marker (`skinDefaults`) are always saved, so EnigmaCast picked again is kept, also through sync. A skin is only data in `ui/theme/Skins.kt`: colour tokens, a corner-radius scale, a text weight, a focus style (fill, outline or glow) and a banner layout. Screens only use token names, so they do not know which skin is active.

| Skin | Look | Channel banner |
|---|---|---|
| EnigmaCast | midnight blue, electric blue accent (the default before Glass Earth) | rounded floating card |
| OpenPLi | PLi-HD: #101214, glossy blue-grey selection bar with yellow text, square corners | PLi-HD InfoBar |
| Midnight | OLED black, teal accent, white outline focus | minimal card without border |
| Metrix | openATV MetrixHD: flat dark tiles, square corners, blue accent | flat bar with a coloured channel block |
| Aurora | glassy indigo/teal, larger corners, glowing focus | translucent gradient card |
| Glass Earth | frosted glass over a full-screen Earth-horizon photo, Inter font (the default) | orbit banner |
| Glass Wave | frosted glass over a full-screen blue particle-wave photo, Inter font | wave banner |

The OpenPLi skin follows `PLi-HD` from littlesat/skin-PLiHD: the colours from `<colors>` in `skin.xml`, and the InfoBar laid out on the skin's own 1280×720 grid, scaled to the screen.
- **Top of the bar:** the date and clock in yellow on the left, the recording mark, then the channel number and name. The encryption line is top right, and the service icons (HD / 16:9 / 4:3, TXT, DD, crypt) are on one row.
- **Middle:** the 840×8 bronze progress bar, then now and next with start time and title. The current programme shows "+N min" left; the next one shows its length.
- **Bottom left:** the 100×60 picon.
- **Right column:** tuner, video size, SNR and orbital position.

The skin's font (`nmsbd.ttf`) is not bundled; a semi-bold sans-serif is used instead.

## Receiver setup

1. Enable OpenWebif on the receiver (OpenATV: menu, plugins, OpenWebif).
2. Note the receiver IP, the HTTP port (usually 80), and whether a username and password are set.
3. Leave the streaming server on port 8001 unless the image uses another port.
4. Start EnigmaCast. The first launch opens the setup wizard; its second step is the connection screen.
5. Enter the receiver name, host, ports, and optional credentials (move to a field, press OK to type, then Done or Back).
6. Test connection, then Connect.

The test calls `/api/about`, probes `/grab` for PNG and JPG in `osd` and `all` modes, and checks that the streaming port accepts a TCP connection. A successful test shows the model, image, and Enigma version returned by OpenWebif.

HTTP is allowed because local OpenWebif is often plain HTTP. The connection screen warns that HTTP is not encrypted. HTTPS uses the system trust store. Trusting a self-signed certificate is off until you enable it in settings. There is no default trust-all configuration.

Passwords and WireGuard configs are encrypted with an AES-256-GCM key in the Android Keystore and kept out of the settings file; existing plain-text passwords move there on the first start. They are not written to logs, and app backup is off.

Further boxes are added under Settings → Receivers. See [Several receivers](#several-receivers).

**AutoDetect** is a button next to the IP field, on the connection screen and under Settings › Receivers (Add or Edit). It opens a dialog with a spinner ("Searching for Enigma2 receivers…") and Cancel. The search reads the TV's own IPv4 address and prefix (ConnectivityManager and the network interfaces; VPNs such as WireGuard are skipped) and scans that subnet (the /24 around its address on wider networks) on ports 80 and 8080: 40 connections at a time, 400 ms connect timeout, at most 8 seconds. mDNS `_http._tcp` runs alongside. Every open port (and the address already in the form) is asked `/api/about` with short timeouts and without login; only answers that look like OpenWebif are listed (HTTP 401 counts as a receiver that needs a login). The dialog then lists each receiver as model plus IP:port (and the image), except hosts that are already saved. When OpenWebif needs no login, a small video grab (`/grab?format=jpg&mode=video`) sits on the same row so you can tell boxes apart. OK fills in host, port and HTTPS, and Save stores it. If nothing answers, the dialog says how many addresses on which subnet were searched, with Try again and Close. If the only answers belong to boxes already in the list, it says those receivers are already added. Focus returns to the AutoDetect button when the dialog closes. WireGuard is not used, and typing the IP address stays the fallback.

## OpenWebif

All paths are built in `OpenWebifEndpoints`.

- JSON API: `/api/about` (also the `transcoding` flag), `/api/epgmulti`, `/api/epgnow`, `/api/epgservice`, `/api/epgsearch`, `/api/timeraddbyeventid`, `/api/timerlist`, `/api/statusinfo`, `/api/getcurrent`, `/api/powerstate` (`newstate`: 0 toggle standby, 1 deep standby, 2 reboot, 3 restart GUI, 4 wake, 5 standby), `/api/sleeptimer`, `/api/vol`, `/api/bouquets`, `/api/bouquets?stype=radio`, `/api/getservices`, `/api/zap`, `/api/remotecontrol`
- Playlist: `/web/stream.m3u?ref=…&device=phone`, only to find the transcoding port
- OSD: `/grab?format=png|jpg&mode=osd|all|video`
- Stream: `http://HOST:8001/<percent-encoded service reference>`, with `Authorization: Basic` from the receiver's username and password when a username is set
- Own-tuner mode also reads `/api/getservices` without `sRef` (bouquet start numbers), `/api/getservices?…&picon=1` (and the same URL without `picon=1` when the receiver answers HTTP 500), picons from `/picon/`, and the stream tuner's signal from `/file?action=download&file=/proc/stb/frontend/<n>/snr` (and `signal`, `snr_db`, `lock`; `/api/file` on images that mount the file controller there)

`/api/` is JSON. The older `/web/` routes return XML and are not used. `/grab` is on the web root, not under `/api`. The stream port stays HTTP even when OpenWebif itself is HTTPS.

Remote commands use Linux input codes (`KEY_OK` = 352, `KEY_UP` = 103, `KEY_EXIT` = 174, and the rest in `EnigmaKey`). OpenWebif `remoteControl()` sends key-down and key-up for a normal command. `type=long` adds the long-press flag. There is no key-up or native repeat parameter, so holding a direction or channel key resends the short command (default every 120 ms). Info, menu, EPG, audio, and the color keys send `type=long` after the long-press threshold.

In Follow receiver mode, Android Back sends Enigma2 EXIT (it does not close the app) and holding Back returns to the dashboard. In own-tuner mode Back is handled by the app (see [Viewing modes](#viewing-modes)). The Android Home key stays Home. Volume keys are left to Android, because the MPEG-TS audio is decoded on the TV and the receiver volume usually does not change that stream.

## Streaming and OSD

`LivePlaybackController` keeps a single ExoPlayer for the dashboard and the full-screen player. A zap to a different channel replaces the media item. Choosing the channel that is already playing leaves the item in place. The item is marked `video/mp2t` and read as a single program, so ExoPlayer does not sniff the first bytes. The stream connection has no read timeout. A player error is shown on the mini player. Video decoders are the TV's MediaCodec decoders, with fallback enabled: MPEG-2, H.264 and H.265 work when the hardware exposes them. Audio uses MediaCodec when the device has a decoder, HDMI passthrough when it does not and the output reports the format, and FFmpeg (AC3, E-AC3, DTS and the other codecs in nextlib) when neither applies.

The OSD is the Enigma2 GUI framebuffer (commonly 720p or 1080p), not the active video rectangle. A 4:3 broadcast is letterboxed inside that framebuffer while menus still cover the full GUI. The overlay is therefore aligned to the video surface. When the OSD aspect and the surface aspect match (both 16:9), it uses fill-bounds. When they differ, it is scaled uniformly and centered so the GUI is not stretched.

Grabs are hashed with CRC32. An unchanged image does not publish a new frame. Bitmaps are decoded into a two-slot pool so the bitmap on screen is not recycled. Polling is adaptive: a fast burst after a remote key (0, 50, 100, 175, 275, 400, 600, 900 ms), then slower, then idle. A nearly transparent PNG is treated as a hidden OSD. JPEG has no alpha, so a JPEG OSD is never treated as empty.

After a remote command the service monitor polls at 50, 100, 200, 350, 500, and 1000 ms, then returns to a 1.5 s idle poll. A new service reference switches the player URL.

OSD failure does not stop video. Video failure does not stop remote commands. If the receiver disappears, a connection overlay is shown and the app probes again. When the network returns, playback and the receiver probe are nudged.

With the TextureView output, which shows a frame as soon as the decoder hands it over, the renderer releases frames at most 20 ms ahead of their display time instead of Media3's 50 ms default, so the picture does not run ahead of the sound.

- **Channel lists per receiver:** each receiver keeps its own bouquets and channels. A change hash, checked at start and on resume, notices when the list on the box changed. Refresh channels & bouquets is now in Settings › Receivers. When a box is switched, its list is carried over only when it is the same list. The player's bouquet side panel follows the active receiver and reloads after a switch or a list change.
- **Video output:** SurfaceView by default with tunneling off. Settings › Playback has Video decoder (Automatic, Hardware, Software) and Compatibility mode (Automatic, On, Off). Compatibility mode gives a safe output with no refresh-rate switching or tunneling, and Off uses the old TextureView output. Automatic turns it on for Xiaomi and Amlogic sticks.
- **OpenWebif:** at most 4 calls at a time per receiver. A failed GET is retried once on a fresh connection, and IOException details are logged.

## Debugging

Settings can enable:

- Debug overlay: host, service, reference, video, audio, Media3 state, buffer or live offset, OSD state, grab latency, OSD frame rate, last key
- Verbose logging, tags `EnigmaNetwork`, `EnigmaRemote`, `EnigmaOsd`, `EnigmaPlayer`, `EnigmaService`, `EnigmaConnection`
- OSD diagnostics: Grab PNG OSD, Grab JPG OSD, Grab PNG ALL, Grab JPG ALL, with status, content type, resolution, size, duration, and whether a PNG alpha channel was detected
- `StrictMode` (debug builds only): disk reads/writes and network on the main thread are logged under the tag `StrictMode`, never a crash or dialog
- Torrent streaming logs reads that block longer than 200 ms and player BUFFERING states

## Known limitations

- OpenWebif cannot send a bare key-up or a kernel auto-repeat. Repeat is a series of short commands.
- PNG OSD alpha depends on the image and the `grab` binary. On some boxes `mode=osd` is opaque black outside the widgets. Use OSD diagnostics on the SF8008 to see which mode keeps transparency.
- AC3, E-AC3 and DTS passthrough need an HDMI output that reports those formats. When it does not, FFmpeg decodes them to PCM. With passthrough, Android cannot measure how long the amplifier or soundbar takes to decode, so lip sync may need the HDMI audio sync value. MPEG-2 still depends on the Android TV decoder. If the SoC has no video decoder, the picture stays black.
- Signal strength in own-tuner mode needs OpenWebif to allow downloading `/proc/stb/frontend/<n>/`. Images that block `/file` leave the strength and quality pills empty while the box is in standby. The service reference is not remapped when the next box takes a full tuner's channel, so that box must know the same reference. The signal meter uses `/api/tunersignal`, which follows the HDMI frontend, so it only measures after the app stream is stopped and the receiver is tuned to that satellite.
- Oscam is a separate web server. The app cannot start it after Stop, and it does not edit `oscam.conf`, `oscam.server` or `oscam.user`.
- DVB subtitle placement is a simple cue overlay. Bitmap cues that are not positioned as a fraction of the video fall back toward the bottom of the picture.
- AutoDetect scans only the TV's own subnet (the /24 around its address on wider networks) on ports 80 and 8080, plus mDNS. A receiver on another subnet, on another web port without mDNS, in deep standby, or behind a firewall that drops `/api/about` is not found; type the IP if needed.
- Navigation sounds off silences the click that EnigmaCast's own screens make. Android only plays it while its System sounds setting is on; the app cannot change that setting, and beeps that the TV or remote make by themselves are outside the app.
- AutoSwitch runs only while the app is open in the foreground (see AutoSwitch). A recording on this TV needs the app (and its capture service) to stay running; a recording on the receiver does not.
- Search uses OpenWebif `/api/epgsearch` for programme titles. Images without that route still match channels, bouquets and My Items. The microphone needs the TV's speech recognizer and the RECORD_AUDIO permission; Assistant SEARCH / PLAY work without it.
- Remote access, transcoding and the built-in WireGuard tunnel are built from the OpenWebif and WireGuard sources and tested only in unit tests, not on a TV and receiver. Some Android TV builds lack the VPN permission dialog; the app then says so and the WireGuard app is the way to go.
- Over the remote profile the receiver sees the router's address for this device's stream, so telling this device's own stream apart from others may miss it and count one tuner too many.
- Own-tuner mode estimates free tuners from what OpenWebif reports. OpenPLi-based images (E2OpenPlugins OpenWebif) do not say which tuner a stream uses and may list a stream as a recording; FBC tuners, unicable, and tuners shared through a loop-through count as separate tuners there even when the dish cabling allows fewer. When the estimate is wrong, the stream start check still catches a refused stream.
- A CI module or smartcard usually descrambles a limited number of channels at once, often one or two. Enigma2 still hands out the tuner, so a scrambled channel can start without a picture. The app says so after 12 seconds; it cannot see the CAM's limit in advance.
- A recording that starts while every tuner streams can end a stream; whether Enigma2 takes the tuner from a stream depends on the image. The device whose stream ends sees the "tuners busy" message.
- Channel numbers come from the bouquet start positions in `/api/getservices`; images without `startpos` number each bouquet from 1. When an internet bouquet repeats one position for several streams, the app keeps counting. Number entry looks only in the bouquet that is open.
- A future receiver-side plugin can replace the OpenWebif sources (`OsdSource`, `RemoteControlSource`, `ServiceStateSource`) with a push transport without rewriting the player screen.
- **Emulator video:** the Android TV emulator decodes video in software. It cannot play 4K HEVC 10-bit (Main10) files smoothly or at all; test those on a real TV box with a hardware decoder. Enigma2 channels and most 1080p H.264 files play.
- **Torrents:** start time and smoothness depend on the swarm (seeders, peers that accept connections) and the network; a torrent without peers or metadata fails with a message. Only one torrent streams at a time and its download is deleted when playback stops. Wi-Fi power saving on TV sticks makes swarm downloads slower, which is why the streaming service holds a Wi-Fi lock.
- **Stremio addons:** only the `stream` resource of an addon is used (no catalogs from the addon; browsing comes from TMDB). Direct links are played as they are; the app does not resolve debrid services itself.
- **IPTV row EPG:** a channel whose provider has no listings and whose name does not match a satellite channel shows no programme.

## Troubleshooting

- Test connection fails immediately: check the IP, that OpenWebif is enabled, and that the TV is on the same LAN. A VPN app on the TV can hide the receiver unless it routes the home subnet; the built-in tunnel only comes up when home does not answer.
- HTTP 401: fill in the OpenWebif username and password.
- AutoDetect finds nothing: `adb logcat -s EnigmaDiscovery` shows the interfaces the TV reported (`interface eth0 <address>/<prefix> via …`), the subnet that was scanned, every open port (`open <address>:80`), each `/api/about` answer (`about <address>:80 -> HTTP 200, Enigma2 …`) and the timing. "no private IPv4 LAN found" means the TV reported no usable address; a receiver missing from the `open` lines was not reachable on 80/8080 from the TV.
- An IPTV / internet channel tries the receiver and fails: the service reference should contain `http%3a//` or `https%3a//` after the tenth field. `adb logcat | grep "stream target"` should say `kind=internet`; if it says `kind=enigma2`, the bouquet entry has no embedded URL.
- An internet channel says the format is not supported: `adb logcat | grep -E "switching live stream|internet stream"` shows `type=` (HLS, DASH, TS or auto) and each try. The stream may need a header the app does not send, or use a format the TV's player cannot read; check the URL in VLC on a PC.
- The remote clicks on every move: Settings › Appearance › Navigation sounds off. If it still clicks, turn off System sounds in the Android TV settings (Device preferences › Sound) or check the TV's own key-beep option.
- Zapping feels slow: `adb logcat -s EcZap` prints one line per zap (also in release builds), e.g. `zap: reason=zap owif=180 prepare=2 connect=60 bytes=5 tracks=210 ready=420 frame=35 total=912 ms buffer=fast-start check=170ms wait=230ms`. Each value is the time that step took: `owif` the tuner check and release wait on the receiver, `connect`/`bytes` the receiver answering on port 8001, `tracks` reading PAT/PMT, `ready` buffering, `frame` the first picture. No address or credential is in the line. Settings → Playback → Picture → "Quick start" (off by default; older installs are switched off once) starts the picture after 0.75 s of stream and on the first I-frame; turn it off when a channel starts blocky or stutters right after a zap.
- The stream does not start: `adb logcat | grep -E "stream target|switching live stream"` shows the exact URL (without credentials), the route and whether it is transcoded. At home it should be `http://<receiver>:8001/<service reference>`; a transcoded URL on a closed port means the transcoding port in the receiver settings does not match the TranscodingSetup plugin (use "Ask the receiver" in Settings → Playback → Stream type), or choose Normal.
- HTTPS fails with a certificate error: enable the self-signed option only for that receiver, or install a certificate the TV already trusts.
- "All tuners are busy" while you expect a free tuner: put the receiver in standby (not deep standby) so its own picture frees a tuner, and check for running recordings or other devices streaming (OpenWebif's About page lists them). Choosing a channel on the same transponder as a running stream always works.
- "The receiver asks for a password for streams": Enigma2's "Require authentication for http streams" is on (OpenATV: Menu → Setup → Usage & GUI → Customize System Settings). The stream server checks the receiver's Linux account (usually `root` and its password), not a separate OpenWebif password. Enter that user and password under Settings → Receivers → Edit for that box, or turn the setting off. OpenWebif's "Enable authentication for streaming" only affects its playlists, not this check.
- Own-tuner mode shows no picture on a scrambled channel: the CI module or card is busy with another channel or stream. Pick a free-to-air channel or stop the other stream.
- Picture without OSD: open OSD diagnostics and try PNG OSD, then JPG OSD. Confirm `grab` works in a browser on the same URL.
- OSD without picture: confirm port 8001 is open and the receiver is not in standby. The player reconnects on its own when auto-reconnect is on.
- Zapping on the receiver updates the TV a moment later: that delay is the service poll plus tuner and decoder startup. Sending the zap from the Android remote triggers the fast poll.
- Sound behind the picture: open Settings → Playback → Audio sync and raise the value of the row marked "active now" until lips and voice match. Usually 60–200 ms with passthrough to an amplifier or soundbar. Check the TV or amplifier for its own lip-sync or "AV sync" setting first, so the two do not add up.
- Menus feel sluggish: turn on the debug overlay and read OSD latency and frame rate. The receiver's `grab` cost is usually the limit, not Compose.
- A torrent does not start: the loading page says whether metadata or the first pieces are missing. `adb logcat | grep -i torrent` shows peers, the gate and blocked reads. Try another source from the Streams dialog (more seeders). "The torrent engine is not available on this device (ABI …)" means no jlibtorrent binary could be loaded for that CPU type.
- The Stremio addon URL is refused: Settings › Torrent says why (not a valid address, manifest not loaded, not a Stremio manifest, no streams, no movies or series).

## Attribution and legal

- **TMDB:** film and series data and images come from TMDB. Settings › About shows the one attribution line, in all five languages: *"This product uses the TMDB API but is not endorsed or certified by TMDB."*
- **Football data:** football-data.org and PitchAPI (shown on the Soccer page as their data source).
- **Torrents:** EnigmaCast contains a BitTorrent client and can use third-party Stremio addons; it does not host, index or provide any content. You are solely responsible for what you search for, stream and share, and for complying with copyright law and the terms of the services you use in your country. The developers do not endorse piracy.
- **Third-party code:** FFmpeg audio decoders through nextlib (GPL-3), jlibtorrent / libtorrent, NanoHTTPD, WireGuard, DiceBear avatars (CC0) and others; see `NOTICE.md`.

## Changelog

Recent work (newest first; the current sideload is `1.0.0-beta.115`):

- **2026-10-27:** Back ladder on live TV (satellite and IPTV): the channel list overlay closes back to full screen, only Back on the full-screen picture opens the TV guide on the playing channel; new IPTV TV guide grid. Faster Enigma2 zaps with the `EcZap` timing log and an optional *Quick start* (off by default). IPTV zapping works right after autostart of the remembered channel.
- **2026-10-26:** Hotel mode (guest TV with hotel PIN, TV home screen, autostart, welcome screen with Wi-Fi QR code, bouquet/category whitelist, next-guest cleanup); guests never get an empty channel list. Sync server: find and pair with a running server, update it from the app, fast first sync in the background with bounded pairing, paged pulls; sync robustness and getservices de-duplication.
- **2026-10-25:** Right zaps to the previous channel, per-channel audio boost, live subtitles remembered and synced, a browsed category is only a preview; watched marks and film progress on info pages; D-pad Left returns to the open page's menu item.
- **2026-10-20 – 24:** Several IPTV providers with grouped categories, provider import/export and choice in the setup wizard, playlist access per profile, connection-limit handling; equivalent channel picker, recent-channels strip, profile PIN; stream recovery and network handoff, streams released on standby; animated navigation icons and the SAT-TV name; Free mode unlocks IPTV and Sport; anonymous usage counter.

- **2026-10-19:** Sport landing page: Sport now opens on a region picker (Netherlands, England, Germany, Spain, Italy, France, Portugal, Brazil & South America, Europe, World — the regions football-data.org's free plan and PitchAPI serve; `domain/soccer/SportRegions`). A hero shows the focused region (drawn backdrop in its colours, description, competition emblem from football-data.org) above a row of cards; OK opens the Soccer page for that region (it becomes the followed country), Back returns to the same card, Back again to the menu; Refresh reloads competitions and matches. Settings and Premium say "Sport" too.
- **2026-10-19:** Background status window: a small bar at the top shows what runs in the background (channel lists, TV guide with a count, Home films and series, IPTV channels, slow sync-server syncs), a filled bar when the count is known and a moving bar when not, "N more tasks waiting", a brief "Done ✓" or plain failure; never focusable, hidden over fullscreen video. Reported through `activity/BackgroundActivityTracker`. Shown only during the first sync after the setup wizard (a persisted setup session that ends when channel lists, guide, Home, IPTV and sync server are done, after 60 s without work, or after 10 minutes at most); never for scheduled refreshes, zap syncs or later starts.
- **2026-10-19:** Home tuner cards A–D: a raised surface that stands off the page (skin-derived tone with a soft vertical gradient, hairline edge, rounded corners, soft shadow); focus keeps the selected tone and highlight border with a faint glow.
- **2026-10-19:** Setup wizard rebuilt for non-technical users (5 standard steps, optional account, plain texts; beta Premium period now 1 year). IPTV channel list: now/next on every row (bulk Room guide first, then a per-stream cache, then Xtream short EPG / data table, 4 at a time after scrolling rests), per-row updates, logo loading limited and subsampled; no stalls while scrolling fast.
- **2026-10-18:**
  - Poster grids (Torrent, IPTV VOD): the whole focused card, including year and rating, stays on screen; cards have one height.
  - TMDB attribution only in Settings › About.
  - Live TV lists open at the playing channel without visible scrolling; narrower channel and category panels.
  - Torrents: Stremio-like start (small gate, reads on demand, piece deadlines), sequential streaming like torrent-stream, buffered bar in the VOD seek bar, no synchronous session calls per read.
  - Torrent Genre/Sort as compact dropdowns; left menu D-pad fixes (Right enters the open page, ends stay in the menu).
  - Main-thread safety pass (OpenWebif parsing, guide cache, settings/secrets, search and file IO off Main) and debug `StrictMode`.
  - Stremio-style VOD OSD for IPTV films/series, recordings and torrents.
  - Torrent page: TMDB browsing, info page, streams dialog, loading page, player, Settings › Torrent (own addon manifest URL), favourites and Continue watching synced, per-title resume with the remembered stream, SubSource subtitles; jlibtorrent native loader with x86.
  - Larger launcher art.
- **2026-10-17:** Picture-in-picture, catch-up, satellite/IPTV fallback; IPTV next-episode card and Watch Next; bouquet order from `bouquets.tv`; subtitle delay.
- **2026-10-15 – 16:** Sync server on the receiver (install over SSH from GitHub, pairing, admin takeover, profile and IPTV-login sync); per-profile viewing data; IPTV subtitles; programme on the IPTV channel rows.
- **2026-10-14:** IPTV (XStreamity and own M3U / Xtream playlists in Room), IPTV Films/Series grids, Library (favourites, Continue watching), Settings lock (PIN), five GUI skins, German / Italian / French, settings import dialog with rollback.
