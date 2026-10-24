# 04 — Critical Findings & Audit

> Principal-architect / security-audit pass. Every finding cites a concrete location. Severity is the
> auditor's assessment for a general-purpose, open-source, locally-installed media player (no server
> backend, no multi-tenant data). Where impact depends on unseen runtime behavior it is marked
> `[NEEDS RUNTIME VERIFICATION]`. This is an analysis document — no code was modified.

Legend: 🔴 High · 🟠 Medium · 🟡 Low / hardening · 🔵 Informational

> **Post-removal status (see `CHANGELOG.md`):** This audit covers the full/upstream codebase.
> In the current slimmed build, several findings are now **moot or reduced**:
> - **B1** (hex-only credential storage) and **B2** (SFTP host-key auto-accept) — **moot**: the
>   SMB/SFTP/GDrive providers were removed. Re-evaluate only if network VFS is reinstated.
> - **B4/B8** (WebView + cleartext) — **unchanged** (web/YouTube retained); the optional
>   Internet-access toggle in `CHANGELOG.md` can gate all HTTP/WebView traffic as a mitigation.
> - **A6 / CORE_HARDENING §10** (playback crash risks) — **still fully applicable** to the retained
>   MediaPlayer/ExoPlayer/VLC stack and are the top P0 items.

---

## A. Crashes & Unhandled Exceptions

### A1. 🟠 Reflection-based addon/engine loading swallows all errors silently
- `fermata/.../addon/AddonManager.java` ctor: `catch (Exception ignore) {}` around
  `Class.forName(i.className).newInstance()`; `loadAddon(AddonInfo)` likewise returns `false` on any
  `Exception` without logging the cause.
- `fermata/.../media/engine/MediaEngineManager.java::isProviderAvailable` catches `Throwable` and
  returns false silently.
- Impact: a broken/incompatible dynamic module fails invisibly; the user sees a feature "do nothing"
  with no diagnostic. The `scheduleLoadAddon` retry runs for up to 180 s (`AddonManager`) then gives
  up with a single error log.
- Recommendation: log the throwable at debug/info even on the "expected missing module" path;
  distinguish "module not installed" from "module present but failed to construct".

### A2. 🟠 `MediaSessionCallback` is a 1482-line god-class state machine
- `fermata/.../media/service/MediaSessionCallback.java` (class lines 141–1622). A single class owns
  the entire playback state machine, engine lifecycle, focus, favorites, voice, and notification
  coordination.
- Risk: high surface for edge-case state bugs (e.g., engine swap during buffering, focus loss during
  prepare). Difficult to unit test — there are no tests for this class (tests exist only for
  `media/sub`).
- Recommendation: extract engine-transition and focus handling into separately testable units;
  add state-transition tests.

### A3. 🟡 Speech reader thread uses `assert` for buffer invariants
- `fermata/.../service/SpeechRecognitionService.java::reader`:
  `assert !buffer.hasRemaining() : "Buffer is not fully consumed...";`
- Android does not enable assertions by default, so this invariant is effectively a no-op in release.
  If the transcriptor ever leaves bytes unconsumed, the data is silently dropped
  (`buffer.clear()` follows). The thread also catches `Throwable` and only logs.
- Recommendation: replace `assert` with an explicit check + handling for the leftover-bytes case.

### A4. 🟡 `FermataMediaService` destroy path order
- `onDestroy()` calls `super.onDestroy()` before `callback.close()`/`session.release()` and
  `unregisterReceiver(intentReceiver)` is guarded, but `createNotification` dereferences
  `callback.isDefaultImage(...)` and `session.getController().getMetadata()` which can be null early
  in lifecycle. The `STATE_PAUSED` notification path returns early only on missing POST_NOTIFICATIONS
  permission. `[NEEDS RUNTIME VERIFICATION]` for a prepare→destroy race.

### A5. 🔵 Broad `catch (Throwable)` in the networking reactor
- `depends/utils/src/main/java/me/aap/utils/net/SelectorHandler.java` (5 empty/ignore catches). In a
  hand-rolled NIO selector loop this is partly defensive (one bad channel must not kill the loop) but
  masks root causes. Informational given it is a mature in-house stack.

### A6. 🔴 Verified playback-engine crash risks (see CORE_HARDENING §10)
A dedicated, line-verified review of the playback stack (originally `Temp/Media_Playback_Analysis.md`,
fact-checked; see `../CHANGELOG.md`) found concrete crash/stuck-state risks in the **retained** core:
- `MediaPlayerEngine.stop()` is not state-safe (`stop()`+`reset()` with no try/catch) —
  `media/engine/MediaPlayerEngine.java:93-99`.
- Paused-play relies on `assert` (a no-op in release) and can NPE —
  `media/service/MediaSessionCallback.java:476-477`.
- Audio-focus denial leaves state stuck in `CONNECTING`; unbounded IPTV retry at
  `MediaSessionCallback.java:1032`; stale engine callbacks; ExoPlayer null-source deref; VLC native
  races; service shutdown not awaiting engine teardown.
- Full table + fixes: `../design/CORE_HARDENING.md` §10. These are the top P0 stability items for the slimmed
  build.

---

## B. Security Vulnerabilities

### B1. 🔴 Network credentials stored with fake "encryption" (hex only)
- `depends/utils/.../vfs/NetFileSystemBase.java:323-330`:
  ```java
  // Weak encryption
  private static void encrypt(String v, SharedTextBuilder tb) { appendHexString(tb, v.getBytes(UTF_16BE)); }
  private static String decrypt(String v) { return new String(hexToBytes(v), UTF_16BE); }
  ```
  SMB/SFTP passwords and SSH key passphrases are persisted to `SharedPreferences` as hex-encoded
  UTF-16 — **trivially reversible**, not encryption. Collected in
  `modules/smb/.../Provider.java` (`PASSWD`) and the SFTP provider.
- Impact: any process with access to the app's prefs file (rooted device, backup extraction, or an
  `allowBackup="true"` cloud backup — see B6) recovers plaintext credentials.
- Recommendation: use `EncryptedSharedPreferences` / Android Keystore (AES-GCM with a Keystore-held
  key). At minimum, document the limitation to users.

### B2. 🔴 SFTP accepts unknown host keys automatically (no host-key verification)
- `depends/utils/.../vfs/sftp/SftpRoot.java` — the jsch `UserInfo.promptYesNo(String)` returns
  `true` unconditionally, and no `StrictHostKeyChecking`/known-hosts is configured.
- Impact: man-in-the-middle on first (and every) connect is undetectable — defeats SSH's core
  protection.
- Recommendation: implement TOFU (persist and verify host keys), or prompt the user on key change.

### B3. 🟠 Exported ContentProvider with URL-driven file/network fetch
- `fermata/src/main/AndroidManifest.xml`: `FermataContentProvider` is
  `android:exported="true"` with `android:grantUriPermissions="true"`.
- `fermata/.../provider/FermataContentProvider.java::openFile`: for `image/<base64>` URIs it will
  open a local `file://` path directly (`ParcelFileDescriptor.open(new File(...))`) or perform an
  outbound `http(s)` download (`BitmapCache.downloadImage`) based on a caller-supplied, base64-encoded
  URI.
- Impact: another app can (a) ask Fermata to fetch arbitrary `http(s)` URLs (SSRF-style / tracking
  beacon via Fermata's network identity) and (b) potentially read local files Fermata can read by
  supplying a `file://` origin — classic content-provider file-exposure pattern. Only `image/*` is
  advertised, but `openFile` does not restrict the decoded path to a safe directory.
  `[NEEDS RUNTIME VERIFICATION]` whether a malicious `file://` origin can target arbitrary app-readable
  files.
- Recommendation: set `exported="false"` (the in-process UI uses it directly) or validate the decoded
  origin against an allowlist of directories/schemes; never open arbitrary `file://` from an external
  caller.

### B4. 🟠 WebView hardening gaps (web addon)
- `modules/web/.../FermataWebView.java::init` enables `setJavaScriptEnabled(true)`,
  `setAllowFileAccess(true)`, `setDomStorageEnabled/DatabaseEnabled(true)`, third-party cookies,
  `setJavaScriptCanOpenWindowsAutomatically(true)`, `setMediaPlaybackRequiresUserGesture(false)`, and
  `addJavascriptInterface(FermataJsInterface, "Fermata")`.
- The JS bridge method is `@JavascriptInterface`-annotated and minimal (`event(int,String)`), which is
  the correct modern guard, but combined with `allowFileAccess(true)` and arbitrary user navigation
  (`loadUrl` from the address bar / intercepted requests) the browser surface is broad.
- Combined with manifest `android:usesCleartextTraffic="true"`, HTTP pages are allowed.
- Recommendation: disable file access unless required; consider `safe-browsing`; scope the JS bridge;
  restrict cleartext via a network-security-config where feasible.

### B5. 🟠 Root (`su`) command execution in the Android-Auto mirroring feature
- `fermata/src/auto/java/me/aap/fermata/auto/EventDispatcher.java` (`su.exec("cmd input ...")`) and
  `ProjectionActivity.java:135` (`su.exec("appops set " + getPackageName() + " PROJECT_MEDIA allow")`).
- These are in the **auto** source set (screen-mirroring to the car head unit) and require an
  already-rooted device / user opt-in. Commands embed `getPackageName()` and integer coordinates
  (low injection risk), but shelling out as root is inherently high-privilege.
- `MirrorServiceFS.java:70` returns `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` for the Car App host
  validator — acceptable only because it is the car-mirroring host surface, but it disables host
  validation.
- Recommendation: gate behind explicit user consent (appears to be the case), document the trust
  model, and avoid building any `su` command from untrusted strings.

### B6. 🟡 `allowBackup="true"` with cleartext credentials
- `fermata/src/main/AndroidManifest.xml`: `android:allowBackup="true"` plus
  `dataExtractionRules`/`fullBackupContent`. Because credentials are only hex-encoded (B1), they can
  leave the device via backup unless the descriptors exclude the prefs files.
  `[NEEDS RUNTIME VERIFICATION]` of `@xml/backup_descriptor` / `@xml/data_extraction_rules` contents.
- Recommendation: exclude credential-bearing prefs from backup, or fix B1 first.

### B7. 🟡 Hardcoded developer email + on-device crash log
- `FermataApplication.getCrashReportEmail()` returns `andrey.a.pavlenko@gmail.com`;
  `getLogFile()` writes `Fermata.log` to external files dir. Logs may contain URLs/paths. Low risk,
  but logs are world-readable historically on some setups. Informational/by-design for an OSS app.

### B8. 🔵 `usesCleartextTraffic="true"` app-wide
- Enables plain HTTP everywhere (needed for many IPTV/M3U sources). Expected for this app class but
  worth a network-security-config to scope it.

---

## C. Performance Bottlenecks

### C1. 🟠 `MediaEngineProvider.isValidBitmap` is O(width×height) per bitmap
- `fermata/.../media/engine/MediaEngineProvider.java::isValidBitmap` iterates **every pixel**
  (`bm.getPixel(x,y)`) to decide if artwork is non-blank. For large embedded cover art this is a
  costly per-track scan.
- Recommendation: sample a sparse grid of pixels, or downscale before scanning.

### C2. 🟠 Reflection on hot/startup paths
- `AddonInfo.isInstalled()` and `MediaEngineManager.isProviderAvailable()` call `Class.forName(...)`
  (the latter also `.newInstance()`) to probe availability. Done per addon at startup and on pref
  changes. Minor, but `newInstance()` just to test presence constructs throwaway objects.
- Recommendation: cache probe results; use `Class.forName(name, false, loader)` to avoid
  initialization/instantiation.

### C3. 🟡 Metadata extraction cache correctness vs. cost
- `fermata/.../media/engine/MetadataRetriever.java` (19 KB) caches tags/art in SQLite
  (`me.aap.utils.db.SQLite`). Good for avoiding N× re-scan on browse, but cache invalidation on file
  change is `[NEEDS RUNTIME VERIFICATION]` (stale metadata if a file is replaced in place).

### C4. 🟡 Per-session idle timers and keep-alive on SFTP
- `SftpRoot.SftpSession.startTimer()` schedules a 5 s idle close; `useChannel` sends a keep-alive on
  release. Pool max is 3 (`SFTP_MAX_SESSIONS`). Reasonable, but aggressive 5 s close can cause
  reconnect churn for bursty browsing. Tunable via pref.

### C5. 🔵 `MemOutputStream.MAX_SIZE = min(Int.MAX/2, maxMemory/2)`
- `depends/utils/.../io/MemOutputStream.java` caps in-memory buffers at half the heap — sane guard,
  but a single large in-memory download could still approach that bound on low-RAM devices.

---

## D. Architectural Debt & Improvement Opportunities

### D1. 🟠 Very large classes / low cohesion hotspots
- `MediaSessionCallback` (52 KB), `MainActivityDelegate` (44 KB / 1345 lines), `SettingsFragment`
  (32 KB), `ControlPanelView` (29 KB), `MediaItemMenuHandler` (25 KB), `Dict` (26 KB),
  `FelexListView` (26 KB). These concentrate behavior and are hard to test/evolve.
- Recommendation: progressive extraction of cohesive responsibilities; add targeted tests before
  refactoring.

### D2. 🟠 Sparse automated test coverage
- Tests exist only in `depends/utils` (async, concurrent, collection, io, net, net/http, vfs/local)
  and `fermata` `media/sub`. The core playback/engine/library/service logic is untested.
  `build.gradle` wires `junit4` + Espresso but there are no instrumentation tests of note.
- Recommendation: add unit tests for engine selection (`MediaEngineManager.createEngine` decision
  table), media-id routing, and `FermataContentProvider.UriInfo.parse`.

### D3. 🟡 Stringly-typed media ids and intent actions
- Item routing relies on string scheme prefixes parsed ad hoc; transport actions are string
  constants (`INTENT_PREV`, etc.) matched in a `switch`. Works, but fragile and undocumented.
- Recommendation: centralize the id grammar; document it (now partially captured in
  `03_data_flow_and_contracts.md §2`).

### D4. 🟡 Custom concurrency & networking stack (build-vs-buy)
- The app reimplements futures (`FutureSupplier`/`Promise`), an NIO reactor (`SelectorHandler`), TLS
  (`SslChannelImpl`), and HTTP in `me.aap.utils`. This is powerful and dependency-light but is a large
  maintenance/security surface compared to coroutines/OkHttp. Intentional design choice; flagged as
  long-term debt and a security-review focus area (TLS/host validation).

### D5. 🟡 Reflection-coupled module boundaries
- Engines and addons are coupled by **string class names** in `MediaEngineManager`
  (`EXO_PROV_CLASS`, `VLC_PROV_CLASS`) and `BuildConfig.ADDONS`. Renames are silent-break risks
  (caught only at runtime, and errors are swallowed per A1).

### D6. 🔵 Dead/guarded code paths by flavor
- A significant `src/auto` surface (mirroring, Xposed, root) only compiles into the `auto` flavor.
  Not dead, but invisible to `mobile` builds; documented here so it is "accounted for" (task rule 3).

---

## E. Dependency & Supply-chain Notes

- Dependencies are centralized and version-pinned in `gradle/libs.versions.toml` (good).
- Notable third parties: `com.github.mwiede:jsch` (a maintained jsch fork — intentional, the original
  jsch is abandoned), `com.hierynomus:smbj`, `org.bouncycastle`, `org.videolan.android:libvlc-all`,
  `com.microsoft.onnxruntime`. All are well-known; no obvious typosquats.
- The dev signing keystore is auto-generated with default password `fermata`
  (`res/build_jks.gradle`, `settings.gradle`) — fine for local/dev, **must not** be used for Play
  releases (release signing must come from a real `local.properties`/secret).

---

## F. Prioritized remediation summary

| # | Finding | Severity | Effort |
|---|---|---|---|
| B1 | VFS credentials stored as hex, not encrypted | 🔴 | Medium (EncryptedSharedPreferences) |
| B2 | SFTP auto-accepts unknown host keys | 🔴 | Medium (TOFU) |
| B3 | Exported provider opens caller-supplied file/URL | 🟠 | Low (`exported=false` or allowlist) |
| B4 | WebView file access + broad settings | 🟠 | Low–Medium |
| A1 | Silent swallow of addon/engine load errors | 🟠 | Low (logging) |
| B6 | Backup may exfiltrate cleartext creds | 🟡 | Low (backup rules) |
| C1 | Per-pixel `isValidBitmap` | 🟠 | Low (sampling) |
| D2 | No tests on core playback | 🟠 | High |

> All items above are findings for the maintainers to evaluate; none were changed by this audit.
