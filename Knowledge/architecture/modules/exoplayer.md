# Module: exoplayer

- Gradle: `modules/exoplayer/build.gradle` · namespace `me.aap.fermata.engine.exoplayer` · dynamic-feature.
- Depends on `:utils`, `:fermata`, and media3: `media3-exoplayer`, `-hls`, `-dash`,
  `-datasource-cronet`, `-transformer` (`gradle/libs.versions.toml` media3 = 1.11.1).
- Source files (4): `me/aap/fermata/engine/exoplayer/*`.

## Role
Provides an alternative playback engine backed by **AndroidX media3 / ExoPlayer**. Registered by
class name `me.aap.fermata.engine.exoplayer.ExoPlayerEngineProvider`
(`fermata/.../media/engine/MediaEngineManager.java::EXO_PROV_CLASS`).

## Integration contract
- Implements `me.aap.fermata.media.engine.MediaEngineProvider` (`init`, `createEngine(Listener)`).
- The engine implements `me.aap.fermata.media.engine.MediaEngine` and is created/selected by
  `MediaEngineManager.createEngine(...)` when the item's engine pref is `MEDIA_ENG_EXO`, or forced
  when `SubGenAddon.ENABLED` (subtitle generation requires ExoPlayer).
- Install/uninstall is driven by `MediaEngineManager` via `DynamicModuleInstaller` and Play
  `SplitInstallManager` on the `EXO_ENABLED` preference (`MediaLibPrefs`).
- Supports HLS and DASH streaming (hence the `-hls`/`-dash` artifacts) and uses Cronet as the
  data source.

## Notes / grounding
- Transformer dependency (`media3-transformer`) is present — used by the SubGen path for media
  processing. `[UNVERIFIED]` exact transformer usage not traced to a specific call site in this pass.
