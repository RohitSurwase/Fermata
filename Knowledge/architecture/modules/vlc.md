# Module: vlc

- Gradle: `modules/vlc/build.gradle` · namespace `me.aap.fermata.engine.vlc` · dynamic-feature.
- Depends on `:utils`, `:fermata`, `org.videolan.android:libvlc-all` 3.7.6 (`libs.libvlc`).
- Source files (2): `me/aap/fermata/engine/vlc/*`.

## Role
Provides the **VLC (libVLC)** playback engine — the default video engine when available.
`MediaEngineManager` sets `VIDEO_ENGINE = MEDIA_ENG_VLC` on first run if the VLC provider class is
found (`fermata/.../media/engine/MediaEngineManager.java` ctor).

## Integration contract
- Registered by class name `me.aap.fermata.engine.vlc.VlcEngineProvider`
  (`MediaEngineManager.java::VLC_PROV_CLASS`), implementing `MediaEngineProvider`.
- The VLC engine implements `MediaEngine` and uniquely supports the richer features declared as
  defaults-off in the `MediaEngine` interface: multiple audio streams
  (`getAudioStreamInfo`/`setCurrentAudioStream`), audio delay, embedded subtitle streams
  (`isSubtitlesSupported`, `getSubtitleStreamInfo`, subtitle delay), and video sizing/menu
  (`hasVideoMenu`, `setSurfaceSize`). Per `README.md`, the video player's subtitle + audio-stream
  support is "VLC Engine only."
- Install/uninstall driven by `MediaEngineManager` on the `VLC_ENABLED` pref via Play
  `SplitInstallManager` (`deferredUninstall`) and `DynamicModuleInstaller`.
- NDK ABI filters (`arm64-v8a`, `armeabi-v7a`, `x86_64`) from root `settings.gradle` apply to the
  native libVLC `.so`s.
