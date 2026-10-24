# Module: poi (Android-Auto POI overlay)

- Gradle: `modules/poi/build.gradle` · package `me.aap.fermata.addon.poi` · dynamic-feature.
- **Source is in the `auto` source set only** (`modules/poi/src/auto/...`), i.e. the add-on targets the
  Android-Auto flavor. Source files (8).

## Role
A points-of-interest overlay for driving (Android Auto). Loads a POI database and surfaces nearby
points; integrates with the media service (started/stopped in
`FermataMediaService.onBind/onUnbind` via `getAddon("poi").start()/stop()`).

## Key files
| File | Role |
|---|---|
| `PoiAddon.java` | Addon entry; `start()/stop()` lifecycle called by the media service. |
| `Poi.java` | POI value type. |
| `PoiDb.java` (8 KB) | SQLite-backed POI store (loading/querying points). |
| `Voyageur.java` | Integration with the "Voyageur" navigation/trip data source. |
| `LufopProvider.java` / `LufopFileProvider.java` | "LUFOP" POI data provider (speed-camera / POI dataset) + file provider. |
| `NativeLoader.java` (6 KB) | Loads native library/data for the POI engine. |
| `Props.java` | Configuration properties. |

## Grounding notes
- `FermataMediaService.onBind` conditionally starts `poi` when `BuildConfig.D` or when the bind
  intent lacks the notif-color extra (`fermata/.../media/service/FermataMediaService.java`).
- `[UNVERIFIED]` Exact remote endpoints/update cadence for the LUFOP/Voyageur datasets were not
  traced in this pass.
