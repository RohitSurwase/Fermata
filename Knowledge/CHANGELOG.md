# Change History & Rationale

What differs from upstream Fermata and **why** — the durable reasoning a future contributor needs.
Not a commit log. For the live component list see `PROJECT_STATE.md`; for build commands see `BUILD.md`.

The app was slimmed from full upstream Fermata to a focused **core**: YouTube, Web browser,
audio/video playback, local/SAF/M3U file explorer, and the MediaPlayer/ExoPlayer/VLC engines.
Active Gradle modules: `:fermata`, `:utils`, `:exoplayer`, `:vlc`, `:web`.

---

## Removed features
TV/IPTV, Felex, Google Cast, ChatGPT, POI, the Subtitle generator (impl modules whisper/mlkit/opusmt),
and the SMB/SFTP/Google-Drive network VFS providers.

- **Mechanism:** excluded in `settings.gradle` (an `excludedModules` set). Excluded modules' `ext.addons`
  never reach the generated `BuildConfig.ADDONS`, so they vanish from the addon registry, Settings, and
  `dynamicFeatures` with no core `.java` change. Their directories still exist under `modules/` but do
  not build.
- **VFS core wiring** for SMB/SFTP/GDrive was also removed from `FermataVfsManager` and
  `FoldersFragment` (the "Add folder" menu now offers File system, Content/SAF, M3U only). Dropping
  GDrive left the dynamic-install machinery dead, so it was removed too.
- **Cast manifest meta-data** (`OPTIONS_PROVIDER_CLASS_NAME`) was removed from `fermata`'s manifest
  because the referenced class shipped only in the removed `:cast` module.

## Subtitle generator — disabled, not deleted (important)
`SubGenAddon` and `TranslateAddon` are compiled into `:fermata` and referenced by the media engine
(`MediaEngineManager`, `MediaEngineBase`, `SpeechRecognitionService`, `SettingsFragment`). They
**cannot be deleted** without breaking the engine compile. Instead, in `build.gradle` the `subgen`
addon is `enabled:false, hasSettings:false` and its `depends` trimmed to `exoplayer`. Result: it never
loads, shows no Settings row, and never forces ExoPlayer — but still compiles. Impl modules
(whisper/mlkit/opusmt) are excluded. Leave the façade classes in place.

## Sideloaded addons require the universal-APK build task
`web`, `exoplayer`, `vlc` are **dynamic-feature splits** (`fermata/build.gradle` → `dynamicFeatures`)
with `<dist:install-time/>` + `<dist:fusing include=true/>`. These manifest flags are bundletool
concepts: splits are fused into the base APK only on the bundle/universal path, not by
`assembleAutoRelease`.

Build (and sideload) with `fermata:packageAutoReleaseUniversalApk` (same task upstream `build.sh` / CI
use): it builds the `.aab`, then bundletool fuses every `fusing=true` split into one APK, so
`WebBrowserAddon`, `YoutubeAddon`, `VlcEngine(Provider)`, and `ExoPlayerEngine(Provider)` land in the
base dex. `AddonManager.loadAddon()`'s `Class.forName` then succeeds synchronously and the
`SplitInstallManager` fallback (which can't fetch off-Play → `SplitInstallException`) is never reached.
`assembleAutoRelease` does **not** fuse — its base APK omits the addon classes, so an addon enabled on
that sideloaded APK silently does nothing. Use the universal task for sideload.

## Fork-behavior changes (self-distribution)
- **Removed "Fermata Mirror" from the Android Auto launcher.** `MirrorService`'s `<service>`
  registration is commented out in `fermata/src/auto/AndroidManifest.xml`. Rationale: the user does
  not use phone-screen mirroring. Kept `MirrorService.java` and the shared `MirrorActivity`/
  `MirrorDisplay` (still referenced by `MirrorServiceFS` and `LauncherActivity`), and `CarService`'s
  own `CATEGORY_PROJECTION` filter (the primary AA media entry), so nothing else breaks.
- **Disabled the startup GitHub update check.** `MainActivity.checkUpdates()` is now a no-op. It had
  queried `api.github.com/repos/AndreyPavlenko/Fermata/releases/latest` and compared tags; for a fork
  that would prompt users to "update" to the upstream (different) APK. The `DELETE_ON_STARTUP` cleanup
  the method also did only ever serviced the updater, so it is skipped too. The "Check for updates on
  startup" row is removed from `SettingsFragment`. (`CHECK_UPDATES` prefs + `googlePlayAppUpdate` dep
  left in place as harmless dead code — the dep was never referenced by the update logic.)
- **Removed "Coffee for developer" (donate) menu option** (commented out in
  `NavBarMediator.showMenu()`).
- **Play-publishability is a separate, on-demand patch**
  (`patches/play-publishable-disable-carappservice.patch`): comments out the androidx.car.app
  CarAppService entry point (ACCESS_SURFACE/NAVIGATION_TEMPLATES, the `android.hardware.type.automotive`
  feature, and the `MirrorServiceFS` CarAppService/NAVIGATION filter) so Play accepts the upload
  (an app can't expose both `MediaBrowserService` and `CarAppService`). Required for a Play `.aab`;
  not needed for direct/universal-APK sideload. Trade-off: disables the `MirrorServiceFS`
  ("FS Fermata Mirror") car-app surface.

## UI changes
- **Favorites removed from the default bottom nav** (`NavBarMediator.getLayout()`). The feature is
  unchanged and still reachable via the item context menu, voice commands, the media-library Favorites
  root, and the notification favorite button — only the default tab is gone. (The `getItems()`
  renderer is kept so a user's saved custom nav layout still works.)

## Added feature — Internet access toggle
A Settings switch `ENABLE_INTERNET` (default on). When off, `App.isInternetAccessBlocked()`
short-circuits the in-house HTTP stack (`HttpConnection.connect` fails fast) and
`FermataWebClient.shouldInterceptRequest` serves a local "Internet disabled" page for WebView
main-frame navigation, with a one-time toast. Touches `App`, `HttpConnection`, `FermataApplication`,
`MainActivityPrefs`, `SettingsFragment`, `strings.xml`, `FermataWebClient`.

## Permissions
- Removed `READ_PHONE_STATE` (no code reference anywhere).
- Kept `WRITE_EPG_DATA` (used by the retained Android-TV home-screen publishing, `AtvInterface` — not
  the removed TV addon) and `RECORD_AUDIO` (voice control).

## Decisions recorded (so they aren't re-litigated)
- **YouTube Music as a separate tab — declined.** The YouTube addon is `WebBrowserAddon` with a fixed
  start URL; a YT-Music tab would be a near-duplicate addon (new class/manifest/icon/locale strings)
  for what a Web-browser bookmark to `music.youtube.com` achieves. Cheapest future option: a default
  bookmark, not a tab.
- **Ad-blocker — in-scope limits.** A domain blocklist can block web/YouTube *page* trackers/ads but
  **cannot** remove YouTube in-stream video ads (served from the same host as the video) and does no
  cosmetic element hiding. See `design/ADBLOCKER_DESIGN.md`.
- **Orphaned string/id resources** for removed features (e.g. `vfs_smb`, `cast_addon`) were left in
  place — harmless, stripped by R8 in release; removing across locales is churn for no benefit.
- **`ENABLE_GS` / google-services plugin** left as-is (already inert without `google-services.json`).

## Build verification baseline
`./gradlew projects` → the 5 active modules; `:fermata:compileAutoDebugSources` for a fast compile
check. Sideload artifact: the fused universal APK from `fermata:packageAutoReleaseUniversalApk`
(see `BUILD.md`). Confirm addons are fused with
`unzip -p <apk> 'classes*.dex' | strings | grep -E "WebBrowserAddon|YoutubeAddon"`.

## Open items
- Run unit tests: `./gradlew :fermata:testMobileDebugUnitTest :utils:testDebugUnitTest`.
- Playback stability backlog: `design/CORE_HARDENING.md §10`.
