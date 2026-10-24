# Module: cast (Google Cast)

- Gradle: `modules/cast/build.gradle` · package `me.aap.fermata.addon.cast` · dynamic-feature.
- Depends on `play-services-cast-framework` 22.3.1. Source files (5).

## Role
Adds **Google Cast** support so media can be sent to Chromecast/cast-enabled receivers.

## Key files
| File | Role |
|---|---|
| `CastOptionsProvider.java` | `com.google.android.gms.cast.framework.OptionsProvider`. Declared in the main manifest via meta-data `OPTIONS_PROVIDER_CLASS_NAME = me.aap.fermata.addon.cast.CastOptionsProvider`. Returns `CastOptions` with the **default media receiver app id** (`DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`), `setAndroidReceiverCompatible(true)`, and an expanded-controller activity of `FermataActivity`. `getAdditionalSessionProviders` returns `null`. |
| `CastAddon.java` + others | Addon entry + cast session wiring into playback. |

## Grounding notes
- Because the `OptionsProvider` is referenced by the **base** app manifest meta-data, the Cast
  framework expects the class to be present; the module is a dynamic feature, so Cast is only active
  once the `cast` module is installed/enabled.
- Uses Google's default receiver application id (no custom receiver app).
