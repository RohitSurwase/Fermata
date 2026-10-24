# 01 — Architecture Overview

> ⚠️ **Describes the full UPSTREAM codebase.** Several components documented here (TV, Cast, ChatGPT,
> POI, Felex, SMB/SFTP/GDrive VFS, whisper/mlkit/opusmt, subtitle-gen) have been **removed from the
> current build**. For what actually ships now, read `Knowledge/PROJECT_STATE.md` first.

> Scope: This document describes the system-level architecture of **Fermata Media Player**,
> grounded in the actual source tree under `fermata/`, `depends/utils/`, and `modules/`.
> Every claim cites a concrete file. Where a behavior could not be verified in code it is
> explicitly marked `[UNVERIFIED]`.

## 1. What the project is

Fermata is a native **Android** audio/video/TV media player written almost entirely in **Java**
(450 `.java` files; 0 Kotlin; 1 C file). It is distributed as an Android **App Bundle** whose
optional features ship as **Play dynamic-feature modules** that are downloaded and installed on
demand at runtime.

- App id / entry application: `me.aap.fermata` → `fermata/src/main/java/me/aap/fermata/FermataApplication.java`
- Version: `appVersionName = "2.0.2"`, `appVersionCode = "268"` (`gradle/libs.versions.toml`)
- Min SDK 28, target/compile SDK 36, NDK `29.0.14206865`, Java 17 with core-library desugaring
  (`build.gradle` `subprojects.android.compileOptions`).

## 2. Gradle module topology

Defined in `settings.gradle` and `build.gradle`:

```
rootProject  "Fermata Music Player"
├── :fermata                 com.android.application   (fermata/build.gradle)
├── :utils  → depends/utils  com.android.library       (depends/utils/build.gradle)
└── modules/*  (each dir)     com.android.dynamic-feature
     cast  chat  exoplayer  felex  gdrive  mlkit  opusmt
     poi   sftp  smb  tv  vlc  web  whisper
```

- `settings.gradle` auto-includes every subdirectory of `modules/` as a project. `:gdrive`
  is skipped unless Google Services are enabled (`gradle.ext.enableGoogleServices`, decided by
  the presence of `fermata/google-services.json` and absence of env `NO_GS`).
- `build.gradle` `subprojects {}` applies the plugin per module: `fermata` → application,
  any `modules/*` → dynamic-feature, everything else (`:utils`) → library.
- `fermata/build.gradle` registers all dynamic modules via `dynamicFeatures.addAll(gradle.ext.modules)`.

### Product flavors / build types
Two flavors in dimension `version` (`fermata/build.gradle` `productFlavors`):
- `mobile` — `BuildConfig.AUTO = false`
- `auto` — `BuildConfig.AUTO = true`, `applicationIdSuffix '.auto'`, pulls `lib/auto/aauto.aar`
  and `compileOnly lib/auto/xposed-api-82.jar`, and adds `androidx.car.app` (`autoImplementation libs.androidxCarApp`).

Build types: `release` (minified, ProGuard, `BuildConfig.D=false`) and `debug`
(`applicationIdSuffix '.debug'`, `BuildConfig.D=true`). Both use the same signing config.

### Addon registry generation (build-time code generation)
`build.gradle` `gradle.projectsEvaluated {}` collects an `addons` list (from the root `ext.addons`
plus each module's `ext.addons`) and emits a `BuildConfig.ADDONS` field of type
`me.aap.fermata.addon.AddonInfo[]` into both product flavors. This is the single source of truth
that `me.aap.fermata.addon.AddonManager` reads at runtime (`AddonManager(PreferenceStore)` iterates
`BuildConfig.ADDONS`). Root-defined addons: `translate` (`TranslateAddon`) and `subgen`
(`SubGenAddon`, `depends: 'exoplayer,translate'`).

## 3. Runtime process architecture

The app is a single Android process hosting these Android components (declared in
`fermata/src/main/AndroidManifest.xml`):

| Component | Class | Role |
|---|---|---|
| Application | `me.aap.fermata.FermataApplication` (extends `me.aap.utils.app.NetSplitCompatApp`) | Process-wide singletons: VFS manager, bitmap cache, preference store, addon manager |
| Launcher Activity | `me.aap.fermata.ui.activity.MainActivity` | Single-activity UI host; `singleTop`; handles LAUNCHER + LEANBACK_LAUNCHER + a large VIEW intent-filter for audio/video file types and the `fermata://` scheme |
| Media Service | `me.aap.fermata.media.service.FermataMediaService` (extends `androidx.media.MediaBrowserServiceCompat`) | Foreground `mediaPlayback` service; owns `MediaSessionCompat`, playback notification, and the browse tree |
| Recognition Service | `me.aap.fermata.service.SpeechRecognitionService` (extends `android.speech.RecognitionService`) | On-device speech-to-text used by the SubGen/voice features |
| Content Provider | `me.aap.fermata.provider.FermataContentProvider` | Serves cover-art images and addon-provided files via `content://${applicationId}/...` URIs |
| Receiver | `androidx.media.session.MediaButtonReceiver` + `me.aap.fermata.media.lib.AtvUpdateReceiver` | Hardware media keys; Android-TV channel refresh |

### Application bootstrap — `FermataApplication`
Grounded in `fermata/src/main/java/me/aap/fermata/FermataApplication.java`:
- `attachBaseContext(Context)` reads a persisted `LOCALE` pref from `SharedPreferences("fermata")`
  and wraps the context with a configuration locale before `super.attachBaseContext`.
- `onCreate()` constructs `FermataVfsManager` and `BitmapCache`.
- Lazy, double-checked singletons: `getPreferenceStore()` (→ `SharedPreferenceStore` over
  `getSharedPreferences("fermata", MODE_PRIVATE)`) and `getAddonManager()` (→ `AddonManager`).
- `getMaxNumberOfThreads()` returns `5` (thread-pool sizing used by the `me.aap.utils.app.App` base).
- Logging: `getLogFile()` → `Fermata.log` in external files dir; `getCrashReportEmail()` returns a
  hardcoded developer email (`andrey.a.pavlenko@gmail.com`) — see audit §Security.
- Android-Auto mirroring: `setMirroringMode(int)` binds/unbinds
  `me.aap.fermata.auto.XposedEventDispatcherService` by `ComponentName`; only active when
  `BuildConfig.AUTO`.

### Media service lifecycle — `FermataMediaService`
Grounded in `fermata/src/main/java/me/aap/fermata/media/service/FermataMediaService.java`:
- `onCreate()` builds `DefaultMediaLib`, a `MediaSessionCompat("FermataMediaService")`, and a
  `MediaSessionCallback` (constructed with `PlaybackControlPrefs` and the app `Handler`), calls
  `callback.onPrepare()`, and schedules `lib::cleanUpPrefs` once per hour via `App.getScheduler()`.
- Notifies all `FermataMediaServiceAddon`s on create/destroy.
- `onGetRoot(...)` returns `BrowserRoot(getLib().getRootId(), extras)` and advertises
  `SEARCH_SUPPORTED` + Android-Auto `CONTENT_STYLE_*` hints.
- `onLoadChildren` / `onLoadItem` / `onSearch` delegate directly to `MediaLib`.
- Playback notification (`createNotification`) builds a `MediaStyle` notification with 6 transport
  actions wired to private broadcast intents handled by an internally registered
  `BroadcastReceiver` (registered `RECEIVER_NOT_EXPORTED`).
- `ServiceBinder` (a `Binder`) exposes `getMediaSessionCallback()` to in-process UI clients.

## 4. Core subsystems (layered view)

```
                         ┌─────────────────────────────────────────┐
  Android UI / Auto / TV │  MainActivity → MainActivityDelegate      │
                         │  ui/fragment/*  ui/view/*  action/*       │
                         └───────────────┬───────────────────────────┘
                                         │ FermataServiceUiBinder (in-proc bind)
                         ┌───────────────▼───────────────────────────┐
  Media session / state  │  FermataMediaService → MediaSessionCallback│
                         │  (PlaybackStateCompat state machine)       │
                         └───────┬───────────────────┬────────────────┘
                                 │                   │
             ┌───────────────────▼───┐     ┌─────────▼─────────────────┐
  Playback   │ MediaEngineManager     │     │ MediaLib (DefaultMediaLib)│  Content model
  engines    │  MediaPlayer/Exo/VLC   │     │ Folders/Favorites/Playlist│
             │  StreamEngine          │     │ Items, Prefs, Metadata    │
             └───────────┬────────────┘     └─────────┬─────────────────┘
                         │                            │
             ┌───────────▼────────────────────────────▼─────────────────┐
  Platform   │ depends/utils : App, async (FutureSupplier/Promise),      │
  library    │ vfs (local/content/smb/sftp/gdrive/generic), net (NIO+TLS)│
             │ pref, ui, io, security, voice, collection, log            │
             └───────────────────────────────────────────────────────────┘
```

### 4.1 `depends/utils` (`me.aap.utils`) — the foundation library
A self-contained Android utility framework the whole app is built on. Key packages
(`depends/utils/src/main/java/me/aap/utils/`):
- `app/` — `App`, `NetApp`, `SplitCompatApp`, `NetSplitCompatApp`. Base `Application` providing a
  thread pool (`getMaxNumberOfThreads`), scheduler, main `Handler`, crash logging.
- `async/` — custom futures: `FutureSupplier`, `Promise`, `Completed`, `Async`, `CompletableSupplier`,
  `ObjectPool`, `PromiseQueue`. This is the concurrency backbone used everywhere
  (e.g., `MediaLib.getItem` returns `FutureSupplier<? extends Item>`).
- `vfs/` — a virtual file-system abstraction: `VirtualFileSystem`, `VirtualResource`, `VirtualFile`,
  `VirtualFolder`, `VfsManager`, plus providers `local/`, `content/`, `smb/`, `sftp/`, `gdrive/`,
  `generic/`. `NetFileSystemBase` is the base for networked file systems and also handles
  persisting root credentials (see audit).
- `net/` + `net/http/` + `net/ssdp/` — a from-scratch non-blocking (`SelectorHandler`) networking
  stack with its own TLS layer (`SslChannelImpl`), HTTP handling, and SSDP discovery.
- `pref/` — `PreferenceStore` / `SharedPreferenceStore` typed preference abstraction with change
  broadcasting; `PreferenceSet` / `PreferenceView` for building settings UIs.
- `ui/` — activity delegate framework (`ActivityDelegate`, `ActivityBase`), fragments, custom views,
  overlay menus, notifications.
- `security/` — `SecurityUtils` (BouncyCastle-based self-signed cert/key generation for the TLS stack).
- `voice/` — `SpeechToText`, `TextToSpeech` wrappers.
- `module/` — `DynamicModuleInstaller` wrapping Play `SplitInstallManager`.

### 4.2 Media library (`fermata/.../media/lib`)
Interface `MediaLib` (`media/lib/MediaLib.java`) with impl `DefaultMediaLib`. Content is a tree of
`Item`s: `BrowsableItem` (folders/groups) and `PlayableItem` (tracks). Concrete item types include
`FolderItem`, `FileItem`, `CueItem`/`CueTrackItem`, `M3uItem`/`M3uTrackItem`/`M3uGroupItem`,
`DefaultFolders`, `DefaultFavorites`, `DefaultPlaylists`/`DefaultPlaylist`, `SearchFolder`,
`ExtRoot`/`ExtBrowsable`/`ExtPlayable` (addon-contributed). `AtvInterface` + `AtvUpdateReceiver`
push favorites/playlists to the Android-TV home screen. Details in `02_module_breakdown.md`.

### 4.3 Playback engines (`fermata/.../media/engine`)
Pluggable engine SPI: `MediaEngineProvider` → `MediaEngine`. Built-in provider is
`MediaPlayerEngineProvider` (Android `MediaPlayer`). Optional providers live in dynamic modules and
are loaded reflectively by `MediaEngineManager`:
- ExoPlayer: `me.aap.fermata.engine.exoplayer.ExoPlayerEngineProvider` (module `exoplayer`)
- VLC: `me.aap.fermata.engine.vlc.VlcEngineProvider` (module `vlc`)

`MediaEngineManager` (`media/engine/MediaEngineManager.java`) selects the engine per item
(`createEngine`), supports a custom engine provider (used by the YouTube addon), wraps seekable
streams in `StreamEngine`, and installs/uninstalls the `exoplayer`/`vlc` modules on demand through
`DynamicModuleInstaller` / Play `SplitInstallManager`.

### 4.4 Addon system (`fermata/.../addon`)
`FermataAddon` is the base SPI with capability sub-interfaces: `MediaLibAddon`, `FermataContentAddon`,
`FermataFragmentAddon`, `FermataActivityAddon`, `FermataMediaServiceAddon`, `FermataToolAddon`.
`AddonManager` loads enabled addons reflectively from `BuildConfig.ADDONS`, installs missing dynamic
modules (with dependency resolution via `AddonInfo.depends`), and bridges preference changes to
install/uninstall. See `02_module_breakdown.md` per-module.

## 5. External integrations & dependencies

From `gradle/libs.versions.toml` and the per-module `build.gradle` files:

| Capability | Library / API | Where |
|---|---|---|
| Media session / notifications | `androidx.media` 1.8.0 | `:fermata` |
| ExoPlayer engine | `androidx.media3` 1.11.1 (exoplayer, hls, dash, cronet, transformer) | `modules/exoplayer` |
| VLC engine | `org.videolan.android:libvlc-all` 3.7.6 | `modules/vlc` |
| Dynamic feature delivery | Google Play `feature-delivery`/`asset-delivery`/`app-update` | `:fermata` |
| Android Auto / Car | `androidx.car.app` 1.7.0 (auto flavor only) | `:fermata` |
| Google Cast | `play-services-cast-framework` 22.3.1 | `modules/cast` |
| Google Drive VFS | `google-api-services-drive`, `google-api-client-android`, auth | `modules/gdrive` (gated on google-services) |
| SMB file access | `com.hierynomus:smbj` 0.15.0 | `modules/smb` + `utils/vfs/smb` |
| SFTP file access | `com.github.mwiede:jsch` 2.28.7 + BouncyCastle 1.86 | `modules/sftp` + `utils/vfs/sftp` |
| On-device translation | `com.google.mlkit:translate` 17.0.3 | `modules/mlkit` |
| OPUS-MT / ONNX translation | `com.microsoft.onnxruntime:onnxruntime-android` 1.30.0 | `modules/opusmt`, `modules/whisper` |
| ChatGPT addon | OpenAI HTTP API (key stored in prefs) | `modules/chat` |
| Analytics transport | Firebase encoders + `datatransport` | `:fermata` |
| Android-TV EPG | `androidx.tvprovider` 1.1.0 | `:fermata`, `modules/tv` |

Networking of the SMB/SFTP/HTTP paths largely uses the in-house `me.aap.utils.net` stack rather than
OkHttp/Retrofit. The app declares `android:usesCleartextTraffic="true"` (`AndroidManifest.xml`).

## 6. Permissions (manifest-declared)

`fermata/src/main/AndroidManifest.xml` requests, among others: `INTERNET`, `ACCESS_NETWORK_STATE`,
`READ_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `ACCESS_MEDIA_LOCATION`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`, `RECORD_AUDIO`,
`POST_NOTIFICATIONS`, `ACCESS_WIFI_STATE`, `READ_PHONE_STATE`, the TV EPG write permission
`com.android.providers.tv.permission.WRITE_EPG_DATA`, and the protected `WRITE_SETTINGS`
(flagged with `tools:ignore="ProtectedPermissions"`).

## 7. Build pipeline

- Wrapper: Gradle (`gradle/wrapper/gradle-wrapper.properties`), AGP `9.4.1`.
- CI: `.github/workflows/build-apk.yml`.
- Docker build: `docker/Dockerfile` + `docker/entrypoint.sh` (image `andreypavlenko/fermata`).
- Signing: `res/build_jks.gradle` auto-generates a dev keystore `res/fermata.jks` with default
  alias/password `fermata` when none is configured in `local.properties` (`settings.gradle`).
- Primary build commands (from `README.md`): `./gradlew bundleAutoRelease -PAPP_ID_SFX=...`.

## 8. Design patterns observed
- **Service Provider Interface + reflection loading** for engines (`MediaEngineProvider`) and addons
  (`FermataAddon`), enabling the dynamic-feature split.
- **Future/Promise async** everywhere via `me.aap.utils.async.FutureSupplier` (no RxJava/coroutines).
- **Delegate pattern** for the activity (`MainActivity` → `MainActivityDelegate`, a 1345-line class).
- **Observer / event broadcasting** via `BasicEventBroadcaster` and `PreferenceStore.Listener`.
- **Object pooling** for network sessions (`ObjectPool`, `SftpRoot.SessionPool`).
- **Typed preference store** as the persistence layer (no Room/SQLite for app content; `utils/db/SQLite`
  exists but see module breakdown for usage).

See `02_module_breakdown.md` for file-by-file detail, `03_data_flow_and_contracts.md` for end-to-end
flows and contracts, and `04_critical_findings_and_audit.md` for the audit.
