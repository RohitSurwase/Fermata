# BUILD — Commands, Flags & Packaging

How to build the current **slimmed core** Fermata. Commands shown for macOS/Linux (`./gradlew`); on
Windows use `.\gradlew.bat` with the same arguments. Run from the project root.

> Derived from the original Windows build notes, **corrected to the current repo** (versions updated,
> whisper/NDK no longer required, module-exclusion is intentional). For what ships now see
> `PROJECT_STATE.md`.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ (21 tested) | |
| Android SDK | API 36 | compile/target SDK 36 |
| Gradle | 9.7.1 | provided by the wrapper (`gradlew`) — no manual install |
| AGP | 9.4.1 | from `gradle/libs.versions.toml` |
| Android NDK / CMake | **not required** | the native `whisper` module was removed |

Set the SDK via `local.properties` (`sdk.dir=/path/to/Android/sdk`) or `ANDROID_SDK_ROOT`.

## Build commands & their impact

| Command | Flavor | Output | Use when |
|---|---|---|---|
| `./gradlew projects` | — | lists active modules | sanity check — expect only `:fermata :utils :exoplayer :vlc :web` |
| `./gradlew :fermata:compileAutoDebugSources` | auto | compile only | **fast iteration** — verify code compiles (installed flavor) |
| `./gradlew fermata:packageAutoReleaseUniversalApk -PABI=arm64-v8a -PAPP_ID_SFX=.myname` | auto | **fused universal APK** (`fermata/build/outputs/apk_from_bundle/autoRelease/*-universal.apk`) | **sideloading / direct install** — the ONLY command that fuses feature splits into the base APK so addons load off-Play |
| `./gradlew assembleAutoRelease -PAPP_ID_SFX=.myname` | auto | base APK + **separate feature-split APKs** (`.../apk/auto/release/`) | ⚠️ NOT self-contained — addon classes are in the split APKs, not the base; sideloading this base APK reproduces the "addon does nothing" bug |
| `./gradlew bundleAutoRelease -PAPP_ID_SFX=.myname` | auto | `.aab` (`.../bundle/autoRelease/`) | **Play Store** upload |
| `./gradlew assembleMobileRelease` | mobile | base + split APKs (no Android Auto) | phone-only build |
| `./gradlew bundleMobileRelease` | mobile | `.aab` | phone-only Play build |
| `./gradlew clean` | — | deletes build dir | before a fresh/clean build |
| `./gradlew --stop` | — | stops Gradle daemons | **run first if a build hangs** (stale daemons hold locks) |

> `auto` flavor = Android Auto + phone; **this is the flavor users install**, so verify it.
> `mobile` flavor = phone only.
> **Sideload must use `packageAutoReleaseUniversalApk`** (or `./build.sh`), NOT `assembleAutoRelease`.
> `web`, `exoplayer`, `vlc` are dynamic-feature splits (`fermata/build.gradle` →
> `dynamicFeatures`). Their `<dist:install-time/>` + `<dist:fusing include=true/>` manifest config is
> honored only by **bundletool** on the bundle/universal path — `packageAutoReleaseUniversalApk`
> builds the `.aab` then fuses every `fusing=true` split into one APK, so the addon/engine classes
> (`WebBrowserAddon`, `YoutubeAddon`, `VlcEngine`, `ExoPlayerEngine`) land in the base dex and
> `AddonManager.loadAddon()`'s `Class.forName` succeeds with no SplitInstall. `assembleAutoRelease`
> does **not** run bundletool and does **not** fuse — its base APK lacks the addon classes. This is
> the same task upstream's `build.sh` / `.github/workflows/build-apk.yml` use.

## Package / application ID

Base id (`fermata/build.gradle`): `applicationId = -PAPP_ID or 'me.aap.fermata'`.
Auto flavor appends `.auto` + `-PAPP_ID_SFX`.

| `-PAPP_ID` | `-PAPP_ID_SFX` | Mobile package | Auto package |
|---|---|---|---|
| *(unset)* | *(unset)* | `me.aap.fermata` | `me.aap.fermata.auto` |
| *(unset)* | `.myname` | `me.aap.fermata` | `me.aap.fermata.auto.myname` |
| `me.aap.fermata.myname` | *(unset)* | `me.aap.fermata.myname` | `me.aap.fermata.myname.auto` |
| `me.aap.fermata.myname` | `.suffix` | `me.aap.fermata.myname` | `me.aap.fermata.myname.auto.suffix` |

Optional: `-PAPP_NAME="..."` sets the display name; `-PABI=arm64-v8a,...` limits native ABIs.

## Signing / keystore

- **Dev/testing (auto):** `settings.gradle` auto-generates `res/fermata.jks` (alias/password
  `fermata`) on first build if no keystore is configured.
- **Release:** add to `local.properties`:
  ```properties
  sdk.dir=/path/to/Android/sdk
  storeFile=/path/to/your-release-key.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```
  `settings.gradle` detects `storeFile` and skips the dev keystore.

## Google Services (Google Drive / Firebase)

Require `fermata/google-services.json`. **Not present ⇒ Google Services auto-disabled** (normal for
forks). Note: Google Drive VFS was removed in this build anyway, so GS is effectively unused. Set
`NO_GS=true` only to force-skip when a `google-services.json` exists.

## Example (typical sideload build)
```bash
export ANDROID_SDK_ROOT=/path/to/Android/sdk
export NO_GS=true
./gradlew --stop
./gradlew projects                                              # expect 5 modules
./gradlew fermata:packageAutoReleaseUniversalApk \
          -PABI=arm64-v8a -PAPP_ID_SFX=.core                    # fused, self-contained APK
# -> fermata/build/outputs/apk_from_bundle/autoRelease/fermata-2.0.2-auto-release-universal.apk

# Confirm addon/engine classes are fused into the base dex:
unzip -p <apk> 'classes*.dex' | strings \
  | grep -E "WebBrowserAddon|YoutubeAddon|VlcEngine|ExoPlayerEngine"
```
Or just use the repo's helper: `./build.sh` (arm64) / `./build.sh -a` (also arm) — it calls
`packageAutoReleaseUniversalApk` and renames artifacts into `dist/`.

## Troubleshooting
- **Build "hangs":** stale Gradle daemon — `./gradlew --stop`, then retry. Prefer
  `compileAutoDebugSources` over heavy `assemble`/`bundle` for quick checks.
- **Addon does nothing after enabling (sideload):** the installed APK's base dex is missing the
  addon classes. Root cause is the **build task**, not the delivery mode: `assembleAutoRelease`
  does not fuse feature splits, so its base APK lacks `WebBrowserAddon`/`YoutubeAddon` →
  `Class.forName` fails → SplitInstall → `SplitInstallException` off-Play. Fix: build (and install)
  the fused universal APK via `packageAutoReleaseUniversalApk` (or `./build.sh`). Verify with
  `unzip -p <apk> 'classes*.dex' | strings | grep -E "WebBrowserAddon|YoutubeAddon"`. See
  `CHANGELOG.md`.
- **Removed features' files still on disk:** `modules/tv`, `modules/cast`, etc. exist but are excluded
  in `settings.gradle` and do not build. See `PROJECT_STATE.md`.
