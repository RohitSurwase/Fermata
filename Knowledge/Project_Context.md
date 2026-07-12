# Fermata Media Player — Project Context

## Purpose
Fermata is a free, open-source Android media player focused on playing audio/video/TV content organized in folders and playlists. It supports Android Auto, Android TV, IPTV (with XMLTV EPG), CUE/M3U playlists, bookmarks, audio effects, customizable playback speed, and pluggable media engines (MediaPlayer, ExoPlayer, VLC).

**Author:** Andrey Pavlenko  
**License:** Open source (no specific license file but free)  
**Play Store:** `me.aap.fermata`  
**Scam warning:** The only official source is this GitHub repo. Sites like `fermata-auto.com` are scams.

---

## Quick Start (Build)
See `BUILD.md` for full Windows build instructions. Key points:

- **Java 17+** (JDK 21 tested)
- **Android SDK API 36** (compile/target), **min SDK 28** (Android 9)
- **NDK 29.0.14206865** (for whisper native module)
- **CMake 4.3.1** (on system PATH)
- Gradle wrapper handles the rest

```powershell
# Build Auto AAB (Play Store ready)
.\gradlew.bat bundleAutoRelease "-PAPP_ID=me.aap.fermata.YOUR_PKG"

# Build Auto APK
.\gradlew.bat assembleAutoRelease "-PAPP_ID=me.aap.fermata.YOUR_PKG"

# Mobile only (no Android Auto)
.\gradlew.bat bundleMobileRelease "-PAPP_ID=me.aap.fermata.YOUR_PKG"
```

Package ID flags:
- `-PAPP_ID` — base app ID (default: `me.aap.fermata`)
- `-PAPP_ID_SFX` — suffix for auto flavor (e.g. `.myname` → `me.aap.fermata.auto.myname`)
- Auto flavor always gets `.auto` suffix

---

## SDK & Version Info

| Property | Value |
|---|---|
| minSdk | **28** (Android 9) |
| targetSdk | **36** (Android 15) |
| compileSdk | **36** |
| Version Code | 268 |
| Version Name | 2.0.2 |
| NDK | 29.0.14206865 |
| AGP | 9.2.1 |
| Java | 17 (source/target compat) |

---

## Architecture Overview

### Two Flavors
1. **`mobile`** — Standard Android app (phones, tablets, Android TV)
2. **`auto`** — Android Auto variant (adds `.auto` to app ID, includes AA-specific features)

### 3 Android Auto Launchers (CarService, MirrorService, MirrorServiceFS)

The `auto` flavor registers **3 distinct launcher services** on Android Auto's projection screen:

#### 1. CarService (`me.aap.fermata.auto.CarService`)
- **Type:** `CarActivityService` (uses Google's proprietary `aauto.aar` SDK)
- **Purpose:** Native Android Auto media player UI. Renders `MainCarActivity` which provides a full-featured media browsing and playback interface optimized for car use.
- **Intents:** `CATEGORY_PROJECTION` + `CATEGORY_PROJECTION_OEM`
- **Icon:** `@drawable/media_service`
- **AA template:** Declares `media`, `template`, `service` in `automotive_app_desc.xml`

#### 2. MirrorService (`me.aap.fermata.auto.MirrorService`)
- **Type:** `CarActivityService` (uses `aauto.aar`)
- **Purpose:** Screen mirroring — projects the phone's display onto the car screen. Uses `MirrorDisplay` to capture and forward touch events. Renders `MirrorActivity`.
- **Intents:** `CATEGORY_PROJECTION` + `CATEGORY_PROJECTION_OEM`
- **Icon:** `@drawable/mirror`
- **AA template:** Uses `media`, `template`, `service`, `projection` features

#### 3. MirrorServiceFS (`me.aap.fermata.auto.MirrorServiceFS`)
- **Type:** `CarAppService` (uses AndroidX Car App library, API level 5+)
- **Purpose:** Full-screen mirroring using the modern `androidx.car.app` API (requires min car API level 5). Provides a `NavigationTemplate` with `SurfaceCallback` for touch/scroll/scale handling. This is the **Play Store compliant** mirroring launcher.
- **Intents:** Commented out `CarAppService` intent filter in auto manifest (to avoid conflicts with `MediaBrowserService`)
- **Icon:** `@drawable/mirror_fs`
- **Label:** `@string/mirror_fs_service_name`

**Key distinction:** CarService and MirrorService use the older proprietary `aauto.aar` SDK (Google's `CarActivityService`), while MirrorServiceFS uses the modern open AndroidX `CarAppService` API. The first two are registered under `CATEGORY_PROJECTION` for Android Auto projection; the third uses the `CarAppService` system (API 5+).

### Other Auto-specific Components
- **LauncherActivity** — Custom app launcher grid for AA, allowing users to pick apps to launch from the car screen
- **ProjectionActivity** — Handles projection display
- **AccessibilityEventDispatcherService** — Captures accessibility events for mirroring
- **XposedEventDispatcherService** — Xposed-based event hooking for Android Auto
- **MediaServiceStarter** — Broadcast receiver that auto-starts media service on Bluetooth ACL connect
- **MirrorDisplay** — Core mirroring logic (surface management, touch forwarding)
- Support files: `ProjectionService`, `CarEditText`, `MainCarActivity`, `MirrorActivity`, `EventDispatcher`, `Su`, `Xposed`

### Xposed Integration (Auto Only)
- Declares `xposedmodule: true` with min version 54
- Scope targets: `com.google.android.projection.gearhead`, `com.android.chrome`, `com.google.android.apps.maps`
- Xposed hooks: `Xposed.java`, `XposedEventDispatcherService.java` — hooks into Android Auto's GearHead app for enhanced functionality

---

## Module System (Dynamic Feature Modules)

The app uses **Android Dynamic Feature modules** for addons and engines. All modules under `modules/` are included as dynamic features at build time.

### Media Engine Modules
| Module | Library | Description |
|---|---|---|
| `exoplayer` | AndroidX Media3 (1.10.1) | ExoPlayer engine (HLS, DASH, Cronet) |
| `vlc` | libVLC (3.7.2) | VLC engine (broader format support, subtitles) |

### Addon Modules (loadable at runtime via AddonManager)
| Module | Addon Class | Description |
|---|---|---|
| `tv` | `TvAddon` | IPTV / M3U TV with XMLTV EPG and Catchup |
| `web` | `YoutubeAddon`, `WebBrowserAddon` | YouTube browser + WebView with SponsorBlock |
| `cast` | `CastAddon` | Google Cast / Chromecast streaming |
| `chat` | `ChatAddon` | ChatGPT assistant integrated as fragment |
| `felex` | `FelexAddon` | Foreign language learning vocabulary tool |
| `poi` | `PoiAddon` | Location-based audio content (GPS proximity) |
| `translate` | `TranslateAddon` (base addon) | Text translation framework |
| `mlkit` | `MlkitTranslateAddon` | Google ML Kit translation |
| `opusmt` | `OpusMtTranslateAddon` | OPUS-MT on-device translation via ONNX Runtime |
| `subgen` | `SubGenAddon` (base addon) | Subtitle generation |
| `whisper` | `WhisperAddon` | OpenAI Whisper speech-to-text (native CMake) |

### VFS (Virtual File System) Modules
| Module | Protocol | Description |
|---|---|---|
| `gdrive` | Google Drive | Cloud media storage (requires `google-services.json`) |
| `sftp` | SFTP | Remote SSH file server |
| `smb` | SMB/CIFS | Windows network shares |

---

## Key Source Packages (`fermata/src/main/java/me/aap/fermata/`)

| Package | Description |
|---|---|
| `FermataApplication.java` | App entry point, global state, preference store, addon manager |
| `action/` | Custom actions (e.g. long-press actions) |
| `addon/` | Addon abstraction: `AddonManager`, base classes (`FermataAddon`, `MediaLibAddon`, `TranslateAddon`, `SubGenAddon`, etc.), `AddonInfo` |
| `media/` | Media playback core: `engine/` (engine interfaces), `lib/` (media library), `pref/` (playback prefs), `service/` (`FermataMediaService`, `FermataMediaServiceConnection`, `MediaSessionCallback`), `sub/` (subtitle parsing) |
| `provider/` | `FermataContentProvider` — content provider for file access |
| `service/` | Speech recognition service |
| `ui/` | UI layer: `activity/` (`MainActivity`, `FermataActivity`, `MainActivityDelegate`, `MainActivityPrefs`), `fragment/`, `view/` (custom views: `MediaItemListView`, `VideoView`, etc.) |
| `util/` | Utility classes |
| `vfs/` | VFS abstraction: `VfsProvider`, `VfsProviderBase`, `FermataVfsManager`, M3U support |

---

## Key Dependencies (from `gradle/libs.versions.toml`)

| Library | Version | Notes |
|---|---|---|
| AndroidX AppCompat | 1.7.1 | |
| AndroidX Media | 1.8.0 | `androidx.media` |
| AndroidX Media3 | 1.10.1 | ExoPlayer family |
| AndroidX Car App | 1.7.0 | `MirrorServiceFS` uses this |
| AndroidX TV Provider | 1.1.0 | Android TV home screen integration |
| Android Material | 1.14.0 | Material Components |
| Google Play Feature Delivery | 2.1.0 | Dynamic feature module delivery |
| Google Play Services Cast | 22.3.1 | Chromecast |
| libVLC | 3.7.2 | VLC engine |
| Guava | 33.6.0-android | |
| ONNX Runtime Android | 1.26.0 | OPUS-MT translation |
| Google ML Kit Translate | 17.0.3 | ML Kit translation |
| Firebase/Transport | 4.1.1 | Analytics/crash reporting |
| VLC | |

---

## Notable Build Config

- **`fermata/build.gradle`**: `namespace = 'me.aap.fermata'`, app ID overridable via `-PAPP_ID`
- **Bundle splits disabled**: `language { enableSplit = false }`, `density { enableSplit = false }`, `abi { enableSplit = false }` (Windows fix to avoid missing native libs)
- **Minification disabled**: `minifyEnabled false`, `shrinkResources false` (prevents ProGuard from stripping reflection-accessed addon classes)
- **Desugaring**: `coreLibraryDesugaringEnabled = true` (Java 8+ API desugaring)
- **Dynamic features**: All modules added as dynamic features dynamically via `settings.gradle`
- **Google Services**: Conditional — if `fermata/google-services.json` exists, GS features are enabled; otherwise `gdrive` module is excluded
- **NDK**: `abiFilters` defaults to `arm64-v8a, armeabi-v7a, x86_64` (customizable via `-PABI`)
- **Res configs**: `en, ru, it, tr, de, pt, vi, pl, hr, ja, zh_TW, ko, fr, ro, ar, es, km`

---

## Permissions

### Mobile (main manifest)
INTERNET, ACCESS_NETWORK_STATE, READ_EXTERNAL_STORAGE, READ_MEDIA_AUDIO, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION, FOREGROUND_SERVICE, USE_FULL_SCREEN_INTENT, RECORD_AUDIO, WRITE_EPG_DATA, POST_NOTIFICATIONS, ACCESS_WIFI_STATE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, READ_PHONE_STATE, WRITE_SETTINGS

### Auto (additional)
WAKE_LOCK, DISABLE_KEYGUARD, SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES, REQUEST_DELETE_PACKAGES, FOREGROUND_SERVICE_MEDIA_PROJECTION, BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_ADMIN, MANAGE_EXTERNAL_STORAGE

---

---

## CI/CD Pipeline (GitHub Actions)

Located at `.github/workflows/build-apk.yml`.

**Triggers:** Every push and pull request to any branch.

**What it does:**
- Runs on `ubuntu-latest`
- Checks out repo with submodules
- Reads version from `gradle/libs.versions.toml` and appends short commit hash (e.g. `2.0.2-d9ea897`)
- Sets up JDK 17, Android SDK, Android NDK
- Builds **2 universal APKs** (arm64-v8a + armeabi-v7a) using `packageAutoReleaseUniversalApk`
- Uses `NO_GS=true` (Google Services disabled)
- Uploads both APKs as workflow artifacts (retained 30 days)

**Package ID:** Pass your package suffix via a GitHub repo Variable:
- Go to repo → **Settings** → **Secrets and variables** → **Actions** → **Variables** → **"New repository variable"**
- Add `APP_ID_SFX` with value e.g. `.myname`
- Optionally set `APP_NAME` (display name) and `ARTIFACT_SFX` (artifact filename suffix)
- If not set, values auto-generate from the repo owner name

**Does NOT create GitHub Releases.** APKs are only stored as workflow artifacts. To publish a release:
1. Push a tag (e.g. `git tag v2.0.2 && git push origin v2.0.2`)
2. Go to Actions tab → find the workflow run for that tag
3. Download the APK artifacts
4. Go to GitHub Releases → Draft a new Release → attach the APKs

**Known issue:** The whisper native module disables Vulkan (`GGML_VULKAN OFF`) for Windows compatibility. On Linux CI, the NDK paths are resolved via CMake's `WIN32` conditional, so both platforms are supported.

---

## Key Constraints & Gotchas

1. **Google Play compliance**: The app cannot mix `CarAppService` and `MediaBrowserService` in the same process — the `CarAppService` intent-filter for `MirrorServiceFS` is commented out in the auto manifest to avoid rejection.
2. **No automotive hardware feature**: `android.hardware.type.automotive` is commented out — this is **not** an Automotive OS app, it's Android Auto (phone projection).
3. **3 AA Launchers**: All three coexist. The old `aauto.aar`-based services (`CarService`, `MirrorService`) work alongside the modern `CarAppService`-based `MirrorServiceFS`.
4. **Addon resolution**: Addon classes are loaded via reflection (`Class.forName`), so minification must be off.
5. **CMake + Windows**: The whisper module requires CMake 4.3.1+ with Vulkan disabled and NDK paths adjusted for Windows.
6. **Google services**: Without `google-services.json`, the `gdrive` module is excluded and Firebase features are silently disabled.
