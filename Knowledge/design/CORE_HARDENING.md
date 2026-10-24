# Core Hardening — Post-Removal Stabilization Plan

> Companion to `../CHANGELOG.md`. After TV, Felex, Cast, ChatGPT,
> POI, and the Subtitle-generator are disabled/removed, this is the focused plan to make the
> **remaining core** rock-solid: YouTube, Web browser, Audio/Video playback, File explorer, and the
> Media engine. Every item cites the code it targets.

> **Build status (verified):** both variants build clean after the removals + the Internet-access
> toggle. `mobile`: `assembleMobileDebug` ✓. `auto` (the installed flavor): `compileAutoDebugSources`
> ✓ and the full `bundleAutoRelease -PAPP_ID_SFX=.core` ✓ → `fermata-2.0.2-auto-release.aab` (~75 MB).
> See `CHANGELOG.md → Build verification`.
>
> **Build gotchas** (to avoid "stuck" builds): run `./gradlew --stop` first to clear stale daemons;
> prefer `compileAutoDebugSources` for a fast check vs the heavy `assemble/bundle` tasks; the former
> native `whisper` CMake/NDK compile is gone with the module removal (one less hang source). On stock
> macOS, `timeout` is unavailable — run long builds in the background with a watchdog or in Android
> Studio.

---

## 0. What "core" is now (retained surface)

| Area | Primary code | Retained modules |
|---|---|---|
| Media engine | `fermata/.../media/engine/*` | `exoplayer`, `vlc` |
| Playback / session | `fermata/.../media/service/{FermataMediaService,MediaSessionCallback}.java` | — |
| Content / file explorer | `fermata/.../media/lib/*`, `fermata/.../vfs/*` | `smb`, `sftp`, `gdrive` + local/content VFS |
| YouTube + Web | `modules/web/**` | `web` |
| UI shell | `fermata/.../ui/**` | — |

Verified: `modules/web` has **no** imports of any removed addon (tv/cast/chat/poi/felex/subgen/
translate), so YouTube/Web are self-contained on the retained set.

---

## 1. Media engine — correctness & stability

Target: `MediaEngineManager`, `MediaEngine`, `MediaEngineBase`, `MediaPlayerEngine`, `StreamEngine`,
plus Exo/VLC providers.

### 1.1 Engine selection decision table (add tests)
`MediaEngineManager.createEngine(current, item, listener)` (lines ~101–150) chooses MP/Exo/VLC/Stream.
After removing subgen, the `SubGenAddon.ENABLED` branch (lines 119, 139) is always false, so the path
simplifies to pref-based selection. 
- **Action:** add unit tests covering: item supplies own engine; custom provider set; neither Exo nor
  VLC available (→ MediaPlayer); audio vs video engine pref; seekable-stream → `StreamEngine`
  wrapping/unwrapping on engine switch.
- **Risk now reduced:** the subgen→ExoPlayer forcing is gone, so audio/video default engine behaves
  per the user's `VIDEO_ENGINE`/audio-engine prefs only.

### 1.2 Engine install/uninstall on pref change
`MediaEngineManager.onPreferenceChanged` installs/uninstalls `exoplayer`/`vlc` via Play
`SplitInstallManager` + `DynamicModuleInstaller`.
- **Action:** verify toggling ExoPlayer/VLC in Settings on a device still installs/uninstalls cleanly
  now that the addon set is smaller (no shared dependency surprises). Confirm `deferredUninstall`
  does not remove a module an engine still needs.
- **Harden:** `isProviderAvailable`/`setExoPlayer`/`setVlcPlayer` swallow `Throwable` silently — add
  an info log of the cause so a failed engine load is diagnosable (ties to audit A1).

### 1.3 Engine lifecycle edge cases
- Engine swap mid-buffering, audio-focus loss during `prepare`, surface detach during VLC playback.
- **Action:** exercise rapid next/prev, play→background→foreground, headset unplug (transient focus),
  and rotating during video. Watch for `MediaEngine.close()` races in `MediaEngineManager.create`
  (it closes `current` before creating new — confirm no use-after-close in `MediaSessionCallback`).

---

## 2. Playback & session — the state machine

Target: `MediaSessionCallback` (52 KB, lines 141–1622) + `FermataMediaService`.

- **Action (tests):** state transitions `NONE→PLAYING→PAUSED→STOPPED/ERROR`, seek while paused,
  skip at list boundaries, `onPlayFromSearch`, resume-last-position
  (`MediaLib.getLastPlayedItem`/`getLastPlayedPosition`).
- **Notification:** `FermataMediaService.updateNotification` + the private broadcast transport
  (`INTENT_PREV/RW/STOP/PLAY/PAUSE/FF/NEXT/FAVORITE_*`). Verify each notification button maps to the
  right `callback.on*()` and that `POST_NOTIFICATIONS`-denied devices degrade gracefully (the paused
  path already early-returns without the permission).
- **Harden:** guard `createNotification` against null `session.getController().getMetadata()` early in
  lifecycle (audit A4).
- **Foreground-service compliance:** confirm `startForeground` is always called promptly on
  `STATE_PLAYING` to satisfy Android 12+ FGS timing (type `mediaPlayback`).

---

## 3. Audio & Video playback — acceptance matrix

Run a device matrix (phone + Android Auto head unit if used) against each retained engine:

| Case | MediaPlayer | ExoPlayer | VLC |
|---|---|---|---|
| Local MP3/FLAC/OPUS audio | ✓ | ✓ | ✓ |
| Local MP4/MKV/AVI video | ✓ | ✓ | ✓ |
| HLS / DASH stream | n/a | ✓ (media3-hls/dash) | ✓ |
| Gapless / speed change | ✓ | ✓ | ✓ |
| Audio-stream switch (video) | — | — | ✓ (VLC only per README) |
| Embedded subtitles | — | — | ✓ (VLC only) |
| External subtitle file | per `media/sub/FileSubtitles` | ↑ | ↑ |

- **Action:** since generated subtitles are removed, confirm the **remaining** subtitle paths still
  work: external `.srt`/sidecar (`media/sub/FileSubtitles`, `Subtitles`, `SubScheduler`, `SubGrid`)
  and VLC embedded streams (`MediaEngine.getSubtitleStreamInfo`). These do **not** depend on SubGen.
- **Verify** `MediaEngineBase:187` (auto-select generated subs when `SubGenAddon.ENABLED`) stays
  inert — it will, because the pref is false.

---

## 4. File explorer & VFS

Target: `media/lib/{FolderItem,FileItem,DefaultFolders,CueItem,M3uItem}`, `vfs/*`,
`utils/vfs/{local,content,smb,sftp,gdrive}`.

- **Action:** browse local storage, SAF `content://` trees, SMB share, SFTP host, Google Drive (if GS
  enabled). Validate: add/remove root, deep folder navigation, sort orders
  (`BrowsableItemPrefs.SORT_MASK_*`), CUE album expansion, M3U playlist parsing.
- **Stability:** SFTP `SessionPool` (max 3, 5 s idle close) — watch for reconnect churn on fast
  browsing; the idle timeout is tunable via `SFTP_MAX_SESSIONS`-style prefs.
- **Security carry-over (from audit, now more important as a "core" feature):**
  - 🔴 Credential storage is hex-only (`utils/vfs/NetFileSystemBase.java:323-330`) — schedule the
    `EncryptedSharedPreferences` fix.
  - 🔴 SFTP auto-accepts unknown host keys (`utils/vfs/sftp/SftpRoot.java`) — add host-key TOFU.
  - These are the top two core-stability/security debts remaining after feature removal.

---

## 5. YouTube & Web browser

Target: `modules/web/**` (`FermataWebView`, `WebBrowserFragment`, `YoutubeMediaEngine`,
`YoutubeWebView`, `YoutubeSponsorBlock`).

- **Action:** navigate URLs, address-bar input, back/forward, fullscreen video, YouTube playback via
  `YoutubeMediaEngine` (registered as the custom engine provider), SponsorBlock skip categories
  (`YT_SB_*`), dark-mode toggles.
- **Harden (from audit B4):** `FermataWebView.init` enables JS + `allowFileAccess(true)` + JS bridge +
  third-party cookies. For a "rock-solid core," review:
  - Set `setAllowFileAccess(false)` unless a feature needs it.
  - Confirm the `@JavascriptInterface` surface (`FermataJsInterface.event`) is the only bridge method.
  - Consider a network-security-config to scope `usesCleartextTraffic` (manifest currently allows all
    cleartext).
- **Regression guard:** YouTube engine integrates with `MediaEngineManager.setCustomEngineProvider`;
  verify switching between a YouTube item and a normal local track cleanly swaps engines.

---

## 6. UI shell

Target: `MainActivity`/`MainActivityDelegate` (1345 lines), `ControlPanelView`, `VideoView`,
`MediaItemListView`, nav/tool/floating mediators.

- **Action:** verify the navigation bar no longer shows removed features (it is driven by the addon
  registry + fragments — removed addons simply don't appear). Confirm no empty nav entries or dead
  menu items reference TV/Cast/Chat/Felex/POI.
- **Settings screen:** confirm the addon section (`SettingsFragment:769` loop over `BuildConfig.ADDONS`)
  now lists only YouTube + Web (+ the inert subgen row unless hidden per optional hardening).

---

## 7. Permissions review (after removal)

Declared in `fermata/src/main/AndroidManifest.xml`. With these features gone, re-evaluate:

| Permission | Keep? | Reason |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | ✅ | Web/YouTube/streams/VFS |
| media read (`READ_MEDIA_AUDIO/VIDEO`, `READ_EXTERNAL_STORAGE`, `ACCESS_MEDIA_LOCATION`) | ✅ | File explorer / playback |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` | ✅ | Playback service/notification |
| `RECORD_AUDIO` | ⚠️ review | Used by SubGen (removed) **and** voice control. Keep only if voice control is retained; otherwise consider removing + the `SpeechRecognitionService` wiring. |
| `com.android.providers.tv.permission.WRITE_EPG_DATA` | ❌ candidate | TV/EPG only — safe to drop with TV removed (also `AtvInterface` TV-home publishing — verify it's not used for non-TV favorites first). |
| `USE_FULL_SCREEN_INTENT`, `READ_PHONE_STATE`, `WRITE_SETTINGS` | ⚠️ review | Audit actual usage; `WRITE_SETTINGS` is protected and should be justified. |

> Treat permission removal as a **separate, individually tested** change — removing one that a core
> path actually uses will break playback or browsing.

---

## 8. Regression test suite to establish (minimum)

Given current coverage is only `utils/*` + `media/sub`, add:
1. `MediaEngineManagerTest` — engine selection decision table (§1.1).
2. `MediaIdRoutingTest` — `DefaultMediaLib.getItem` prefix routing (now without TV/felex id spaces).
3. `FermataContentProviderTest` — `UriInfo.parse` for `image/` and (retained) addon URIs.
4. Instrumented smoke: launch → browse → play audio → play video → open web → play YouTube →
   background/foreground → notification controls.

---

## 9. Done criteria

- App builds (`./gradlew clean bundleMobileRelease`) with only `web`, `exoplayer`, `vlc` features
  (network VFS providers `smb`/`sftp`/`gdrive` were since removed — see `../CHANGELOG.md`
 ).
- No crash/log-error referencing a removed addon at startup or in Settings.
- Full §3 playback matrix passes on the target device(s).
- YouTube + Web + file explorer (local + SAF + M3U) pass smoke tests.
- Audit items B1/B2 (credential encryption, SFTP host keys) are now moot for the stripped build
  since SMB/SFTP are removed; keep them in mind only if network VFS is ever reinstated.

---

## 10. Verified playback crash risks (from Temp/Media_Playback_Analysis.md, fact-checked)

These were line-verified against the current tree (see `../CHANGELOG.md` (Temp review was folded in)). They are the most
concrete crash/stuck-state risks in the retained playback stack and should be the P0 hardening work:

| # | Risk | Location | Fix |
|---|---|---|---|
| H1 | `MediaPlayer.stop()/reset()` not state-safe; can throw `IllegalStateException` after completion/switch | `media/engine/MediaPlayerEngine.java:93-99` | Make `stop()`/`close()` idempotent + try/catch; track state |
| H2 | Paused-play relies on `assert` (stripped in release) → NPE on null engine/source | `media/service/MediaSessionCallback.java:476-477` | Replace asserts with explicit null checks → STOPPED/ERROR |
| H3 | Audio-focus denial leaves state stuck in `CONNECTING` (`playOnPrepared` not cleared) | `MediaSessionCallback.java:~1244` | Clear flag, release engine, set recoverable state |
| H4 | Unbounded IPTV stream retry (`playItem(i,0)` on every end) | `MediaSessionCallback.java:1032` | Bounded retry + exponential backoff + user-visible error |
| M1 | Stale engine callbacks act on replaced engine during fast switch | `MediaSessionCallback.java:884-918` | Playback generation/request token + identity checks |
| M2 | ExoPlayer `onPlaybackStateChanged` derefs cleared `source` after stop | `modules/exoplayer/.../ExoPlayerEngine.java` | Null-check source; remove listener before release |
| M3 | VLC native lifecycle/surface races on rapid switch/close | `modules/vlc/.../VlcEngine.java` | Detach listener/surface before native release; serialize |
| M4 | Service `onDestroy` doesn't await async engine teardown | `media/service/FermataMediaService.java` | Await teardown or ignore late callbacks after close |
| M5 | Fallback + first-run default favor VLC (higher native-crash risk) | `media/engine/MediaEngineManager.java` | Prefer ExoPlayer for standard media; VLC per-item |
| M6 | Availability probe via `Class.forName().newInstance()` | `MediaEngineManager.java` | Use class-presence check, not instantiation |

Recommended engine policy (from the analysis, still valid): ExoPlayer as the stability default for
general audio/video, VLC as a per-item compatibility choice (embedded subs/audio tracks, exotic
codecs), MediaPlayer as last-resort fallback.

