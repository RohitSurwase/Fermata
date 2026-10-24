# 03 — Data Flow & Contracts

> ⚠️ **Describes the full UPSTREAM codebase.** Flows/contracts for removed features (SMB/SFTP/GDrive,
> TV, subtitle-gen, etc.) are historical. For the current build, read `Knowledge/PROJECT_STATE.md`.

> End-to-end traces of the primary workflows, plus the app's "contracts": content URIs, the media
> browser API, preference schemas, and the engine/addon SPIs. All steps cite source.

## 1. App startup → first screen

```
Process start
  └─ FermataApplication.attachBaseContext(ctx)       # apply persisted LOCALE from SharedPreferences("fermata")
  └─ FermataApplication.onCreate()                   # new FermataVfsManager(); new BitmapCache()
  └─ MainActivity launched (LAUNCHER / LEANBACK)      # AndroidManifest intent-filters
        └─ MainActivityDelegate                       # builds nav/body/control UI, binds media service
              └─ FermataMediaServiceConnection.bind   # ACTION_MEDIA_SERVICE, INTENT_ATTR_NOTIF_COLOR
                    └─ FermataMediaService.onBind → ServiceBinder
                          └─ FermataServiceUiBinder    # UI ↔ MediaSessionCallback bridge
```
- Service side: `FermataMediaService.onCreate()` creates `DefaultMediaLib`, `MediaSessionCompat`,
  `MediaSessionCallback` (+ `callback.onPrepare()`), schedules `lib::cleanUpPrefs` hourly.
- `AddonManager` (constructed lazily) loads every enabled addon from `BuildConfig.ADDONS`.

## 2. Browse the library (MediaBrowserService contract)

The service exposes the standard Android `MediaBrowserServiceCompat` API; Android Auto/Android TV and
the in-app UI are all clients.

```
Client subscribe(parentId)
  └─ FermataMediaService.onLoadChildren(parentId, Result<List<MediaItem>>)
        └─ MediaLib.getChildren(parentId, MediaLibResult.Wrapper)       # DefaultMediaLib
              └─ Item resolved from id prefix → BrowsableItem.getChildren()  (async FutureSupplier)
                    └─ result.sendResult(List<MediaItem>)

Client getItem(id) → onLoadItem → MediaLib.getItem(id, ...)
Client search(q)   → onSearch   → MediaLib.search(q, ...)   # SearchFolder
```

### Media-id scheme (the routing key)
Item ids are opaque strings whose **scheme prefix** selects the owning subsystem. `DefaultMediaLib`
dispatches by prefix and consults `AddonManager.getItem(lib, scheme, id)` so modules (TV, web, felex)
can own their own id spaces. `MediaLib.getItem(CharSequence)` returns
`FutureSupplier<? extends Item>`. `getRootId()` is the browse root returned by
`FermataMediaService.onGetRoot`.

Browse hints advertised in `onGetRoot` (`FermataMediaService.java`):
`SEARCH_SUPPORTED=true`, `CONTENT_STYLE_SUPPORTED=true`, browsable/playable hint = list item.

## 3. Play a track (state machine)

The playback state machine lives in
`fermata/src/main/java/me/aap/fermata/media/service/MediaSessionCallback.java`
(`MediaSessionCallback`, lines 141–1622), driving `PlaybackStateCompat` states
`NONE/STOPPED/PAUSED/PLAYING/ERROR/BUFFERING`.

```
UI/Auto transport → MediaControllerCompat → MediaSessionCompat → MediaSessionCallback
  onPlayFromMediaId / onPlay / onPause / onStop / onSeekTo / onSkipToNext|Previous
  onFastForward / onRewind / onCustomAction / onPlayFromSearch
        │
        ├─ resolve PlayableItem via MediaLib
        ├─ MediaEngineManager.createEngine(current, item, Listener)   # choose MP/Exo/VLC/Stream/custom
        │       └─ MediaEngine.prepare(item) → onEnginePrepared → start()
        ├─ engine.selectSubtitleStream() / audio stream selection (MediaEngine.selectMediaStream)
        ├─ publish PlaybackStateCompat + MediaMetadataCompat to the session
        └─ FermataMediaService.updateNotification(state, item)        # foreground notif
```

- Notification transport buttons post private broadcasts (`INTENT_PREV/RW/STOP/PLAY/PAUSE/FF/NEXT/
  FAVORITE_ADD/REMOVE`) received by `FermataMediaService.intentReceiver` → mapped back onto
  `callback.on*()` (`FermataMediaService.notificationInit`).
- Engine selection rules (`MediaEngineManager.createEngine`):
  1. If the item supplies its own engine (`item.getMediaEngine`), use it (YouTube/Felex do this).
  2. Else if a custom provider is set, use it.
  3. Else if neither Exo nor VLC is supported, use the built-in `MediaPlayer`.
  4. Else pick per-item pref (`getVideoEnginePref`/`getAudioEnginePref`); `SubGenAddon.ENABLED`
     forces ExoPlayer. Seekable streams are wrapped in `StreamEngine`.
- Audio focus, mute-on-transient-loss, and volume are handled through `MediaEngine` defaults
  (`requestAudioFocus`, `muteOnTransientFocusLoss`, `adjustVolume`).

## 4. Resume last position
- `MediaLib.getLastPlayedItem()` + `getLastPlayedPosition(PlayableItem)` restore the last track and
  position; per-item position is stored in `PlayableItemPrefs`. (README: "Remembers the last played
  track and position for each folder.")

## 5. Cover-art / image delivery (ContentProvider contract)

`FermataContentProvider` (read-only) serves two URI families:

| URI pattern | Resolves to |
|---|---|
| `content://<app>/image/<base64(origUri)>` | Cover art. `openFile` maps the decoded origin: `file://` → direct FD; `http(s)` → `BitmapCache.downloadImage().get().getLocalFile()`; `content://`/`android.resource://` → `BitmapCache.openResourceImage()`. `getStreamTypes` → `image/*`. |
| `content://<app>/addon/<name>/<base64(uri)>[/displayName]` | Delegated to `AddonManager.get().getAddon(name)` if it is a `FermataContentAddon` → `addon.openFile(uri)`; type via `addon.getFileType()`. |

- Helpers: `toImgUri(Uri)`, `toAddonUri(addon, uri, displayName)`, `getOrigUri(String)`.
- `query` returns a 1-row `MatrixCursor(_display_name, mime_type)`. `insert` returns the input uri;
  `delete`/`update` return 0 (no mutation). Supported file schemes gate:
  `isSupportedFileScheme` (`http, https, file, content, android.resource`, or any VFS scheme).
- The provider is `exported="true"` with `grantUriPermissions="true"` — see audit.

## 6. External VIEW intent (open a file from another app)
Manifest `MainActivity` has a large VIEW intent-filter covering dozens of audio/video extensions and
MIME types, plus the custom `fermata://open` / `fermata://play` and `.fxd` scheme. The incoming
`Uri` becomes an `IntentPlayable` (`media/lib/IntentPlayable.java`) and is routed through the normal
play flow (§3).

## 7. Subtitle generation (SubGen) + speech recognition

```
RecognizerIntent → SpeechRecognitionService.onStartListening(intent, Callback)
  ├─ set LANG pref from EXTRA_LANGUAGE
  ├─ permission check RECORD_AUDIO (skipped on car activity)
  ├─ AddonManager.getOrInstallAddon(SubGenAddon).then(getTranscriptor(prefs))
  ├─ AudioRecord(VOICE_RECOGNITION, 16 kHz, MONO, PCM_16BIT)   # fallback MIC/44.1 kHz
  └─ reader thread: loop read → Transcriptor.read/transcribe (CHUNK_LEN=5s)
         └─ Callback.segmentResults(...) (API 33+) / results(...)   # RESULTS_RECOGNITION bundle
```
- `SubGenAddon.Transcriptor` contract: `reset()`, `read(ByteBuffer, chunkLen, bytesPerSample, channels, rate)`,
  `transcribe()`, `release()`.
- Service bound with `android:permission="android.permission.BIND_VOICE_INTERACTION"`,
  `exported=false`.

## 8. Dynamic feature install flow (engines & addons)

```
Enable pref (EXO_ENABLED / VLC_ENABLED / <addon>_enabled)
  └─ PreferenceStore.Listener fires
        ├─ MediaEngineManager.onPreferenceChanged  → installPlayer(module) / deferredUninstall
        └─ AddonManager.onPreferenceChanged        → install(AddonInfo) / uninstall(AddonInfo)
              └─ DynamicModuleInstaller (wraps Play SplitInstallManager)
                    └─ on success: Class.forName(provider/addon).newInstance()  → register
                    └─ retry loop up to 180 s (AddonManager.scheduleLoadAddon)
```

## 9. Preference (persistence) contracts

Stores: `SharedPreferences("fermata")` (app) and `"medialib"` (library/engine), wrapped by
`me.aap.utils.pref.SharedPreferenceStore`. Typed `Pref<T>` keys. Key schemas (`fermata/.../media/pref`):

| Prefs class | Representative keys / meaning |
|---|---|
| `MediaPrefs` | `MEDIA_ENG_MP/EXO/VLC`, `VIDEO_ENGINE`, audio engine. |
| `MediaLibPrefs` | `EXO_ENABLED`, `VLC_ENABLED`. |
| `PlayableItemPrefs` | last position, bookmarks, selected audio/sub stream (`SubIdPref`, `SubLangPref`, `SubKeyPref`, `SubDelayPref`, `SubEnabledPref`), per-item speed. |
| `BrowsableItemPrefs` | sort order (`SORT_MASK_*`). |
| `FoldersPrefs` / `FavoritesPrefs` / `PlaylistsPrefs` / `PlaylistPrefs` | root lists, favorites, playlist membership. |
| `PlaybackControlPrefs` | FF/RW step, auto-play next, etc. |
| `MainActivityPrefs` | `LOCALE` (`Lang`), theme, layout. |
| Addon prefs | `<className>_enabled` (`AddonInfo`), `OPENAI_KEY`/`MODEL` (chat), `YT_SPONSOR_BLOCK`/`YT_SB_*` (web), `SFTP_MAX_SESSIONS`, SMB/SFTP `HOST/PORT/SHARE/USER/PASSWD/...`. |

### VFS credential record format (`NetFileSystemBase`)
Per root, keyed by `scheme://user@host:port#` + suffix: `...#P` (password), `...#F` (key file),
`...#K` (key passphrase). Password/passphrase values are `encrypt()`ed = hex(UTF-16) only.

## 10. SPIs (extension contracts)

- **Engine**: `MediaEngineProvider { init(ctx); createEngine(Listener); getMediaMetadata; isValidBitmap }`
  → `MediaEngine` (prepare/start/stop/pause/seek/speed; optional audio/subtitle streams; video view).
- **Addon**: `FermataAddon { getAddonId; getInfo; install/uninstall/start/stop; handleIntent }`
  + capability mix-ins `MediaLibAddon`, `FermataContentAddon`, `FermataFragmentAddon`,
  `FermataActivityAddon`, `FermataMediaServiceAddon`, `FermataToolAddon`.
- **VFS**: `me.aap.utils.vfs.VirtualFileSystem` + `VfsProviderBase` (Fermata-side settings form).
- **Async**: everything returns `me.aap.utils.async.FutureSupplier<T>`; `.main()` hops to the main
  thread, `.then/.map` chain, `.onSuccess/.onFailure` terminate.
