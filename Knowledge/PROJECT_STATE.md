# PROJECT STATE — Current Snapshot (authoritative)

> **Single source of truth for "what the project is RIGHT NOW."** Read this before trusting the
> architecture docs `01`–`03` or any `Knowledge/modules/*.md` — those describe the **full upstream**
> codebase and still mention features that have since been removed from the build. When this file and
> those docs disagree, **this file + the actual source/`settings.gradle` win.**
>
> Last updated: 2026-10-24. Fermata 2.0.2 (versionCode 268).

---

## ⚠️ Critical nuance: removed ≠ deleted from disk

The removed features were taken **out of the Gradle build** (excluded in `settings.gradle`), but their
**module directories still physically exist** under `modules/`. So a filesystem/code search WILL find
`modules/tv/`, `modules/cast/`, etc. **Do not assume a feature is active just because its files exist.**
The authoritative "what is built" list is `settings.gradle` + `./gradlew projects`.

---

## Active Gradle modules (what actually builds)
`:fermata`, `:utils`, `:exoplayer`, `:vlc`, `:web`

Verify anytime with: `./gradlew projects` (expect exactly those five).

## Retained features (present AND built — work on these)
- **Playback engines:** MediaPlayer (built-in, in `:fermata`), ExoPlayer (`:exoplayer`), VLC (`:vlc`).
- **Addons (in `:web`):** YouTube (`YoutubeAddon`), Web browser (`WebBrowserAddon`) — both build from
  `modules/web/`. Includes SponsorBlock (`modules/web/.../yt/YoutubeSponsorBlock.java`).
- **File explorer / VFS:** Local storage, SAF `content://` trees, M3U playlists only.
  (`fermata/src/main/java/me/aap/fermata/vfs/FermataVfsManager.java` registers only these.)
- **Core:** media library/browse, favorites feature, playlists, audio effects, subtitles (external +
  VLC embedded), the media session/service, Android-Auto (`auto` flavor), Android-TV home publishing
  (`AtvInterface`).
- **Added feature:** Settings toggle to enable/disable Internet access (`ENABLE_INTERNET`).

### ⚠️ Feature-module delivery + how to build for sideload (do not regress)
`web`, `exoplayer`, `vlc` are **dynamic-feature splits** (`fermata/build.gradle` → `dynamicFeatures`)
with `<dist:install-time/>` + `<dist:fusing include=true/>`. The fusing happens only via bundletool on
the bundle/universal path. So for a **sideloaded** build use `fermata:packageAutoReleaseUniversalApk`
(or `./build.sh`): it builds the `.aab` then fuses the splits into one APK, putting the addon/engine
classes in the base dex so `AddonManager.loadAddon()` loads them with no SplitInstall.
`assembleAutoRelease` does **NOT** fuse — its base APK omits the addon classes and sideloading it
produces the `SplitInstallException` ("Failed to install module") failure. See `BUILD.md` / `CHANGELOG.md`.

### Fork-behavior changes (self-distributed sideload build)
- **"Fermata Mirror" removed from the Android Auto launcher** — `MirrorService` `<service>`
  registration commented out in `fermata/src/auto/AndroidManifest.xml`. The class and the shared
  `MirrorActivity`/`MirrorDisplay` stay (used by `MirrorServiceFS` + `LauncherActivity`); `CarService`
  keeps its own `CATEGORY_PROJECTION` filter so the primary AA media entry is unaffected.
- **Startup GitHub update check disabled** — `MainActivity.checkUpdates()` is now a no-op (it queried
  `api.github.com/repos/AndreyPavlenko/Fermata/releases/latest`, which for a fork would prompt users
  toward the upstream APK). The "Check for updates on startup" Settings row is removed from
  `SettingsFragment`. The `googlePlayAppUpdate` dependency was already dead code (no references).
- **"Coffee for developer" (donate) menu item removed** — commented out in `NavBarMediator.showMenu()`.
- **Play-publishability patch (optional, `patches/play-publishable-disable-carappservice.patch`)** —
  only needed to upload a `.aab` to Play; disables the `MirrorServiceFS` CarAppService surface. NOT
  needed for direct/universal-APK sideload.

## Removed features (files may exist on disk, but NOT built — do NOT treat as active)
| Feature | Dir on disk (excluded) | Notes |
|---|---|---|
| TV / IPTV | `modules/tv/` | excluded in `settings.gradle` |
| Felex (language tutor) | `modules/felex/` | excluded |
| Google Cast | `modules/cast/` | excluded; Cast manifest meta-data removed from `:fermata` |
| ChatGPT | `modules/chat/` | excluded |
| POI (Android Auto) | `modules/poi/` | excluded |
| SMB VFS | `modules/smb/` | excluded; core wiring removed from `FermataVfsManager`/`FoldersFragment` |
| SFTP VFS | `modules/sftp/` | excluded; core wiring removed |
| Google Drive VFS | `modules/gdrive/` | excluded; core wiring removed |
| Subtitle-gen impls | `modules/whisper/`, `modules/mlkit/`, `modules/opusmt/` | excluded |

### Partially-present (compiled but inert)
- **Subtitle generator façades** `SubGenAddon` + `TranslateAddon` remain compiled in `:fermata`
  (referenced by the media engine), but are **disabled & hidden**: in `build.gradle` the `subgen`
  addon is `enabled:false, hasSettings:false`, so `AddonManager` never loads it and no Settings row
  shows. Their impl modules (whisper/mlkit/opusmt) are removed, so the feature is dark. Leave the
  façade classes in place (removing them breaks the engine compile).
- **Favorites** is fully functional but **no longer a default bottom-nav tab** (removed from
  `NavBarMediator.getLayout()`). Reachable via context menu, voice, and the media-library root.

## Also removed
- Permission `READ_PHONE_STATE` (unused) — removed from `fermata/src/main/AndroidManifest.xml`.
- Kept intentionally: `WRITE_EPG_DATA` (used by retained Android-TV publishing), `RECORD_AUDIO`
  (voice control).

## Known stale references in the docs (expected, not bugs)
- `Knowledge/01_architecture_overview.md`, `02_module_breakdown.md`, `03_data_flow_and_contracts.md`
  describe the **full upstream** codebase → they still mention SMB/SFTP/GDrive, TV, Cast, etc.
  Treat those as historical/architectural reference, cross-check against this file.
- `Knowledge/modules/{tv,cast,chat,poi,felex,vfs-providers,ai-ml}.md` document **removed** modules.
- `Knowledge/04_critical_findings_and_audit.md` has a post-removal banner marking which findings are
  now moot (e.g., B1/B2 SMB/SFTP credential + host-key issues).

## If a removed feature is ever reinstated
Re-add its module name to the `excludedModules` set removal in `settings.gradle` (i.e. stop excluding
it), restore any core wiring if it was a VFS provider (see `CHANGELOG.md`), and rebuild. The module
source is still on disk, so this is mostly a `settings.gradle` + wiring change.
