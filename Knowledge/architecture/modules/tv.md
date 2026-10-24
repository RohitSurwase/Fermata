# Module: tv (IPTV)

- Gradle: `modules/tv/build.gradle` · package `me.aap.fermata.addon.tv` · dynamic-feature.
- Source files (15): `me/aap/fermata/addon/tv/**`.

## Role
IPTV add-on: plays TV channels from **M3U** playlists, overlays **XMLTV EPG** program data, and
supports **catchup/archive** playback of past programs.

## Key files
| File | Role |
|---|---|
| `TvAddon.java` | Addon entry (`MediaLibAddon` + fragment); registers the TV root with the media lib. |
| `TvRootItem.java` | Root `BrowsableItem` for the TV tree. |
| `TvFragment.java` / `TvFloatingButtonMediator.java` | TV UI. |
| `TvItem.java` | Marker interface for TV items. |
| `m3u/TvM3uFile.java` / `TvM3uFileSystem.java` / `TvM3uFileSystemProvider.java` | Virtual FS exposing an M3U source (URL/file) with refresh. |
| `m3u/TvM3uItem.java` / `TvM3uGroupItem.java` / `TvM3uTrackItem.java` | Playlist → groups → channels (`TvM3uTrackItem` is 15 KB: per-channel logo/EPG/catchup config). |
| `m3u/XmlTv.java` | **XMLTV EPG parser (21 KB)** — parses `<programme>`/`<channel>` into EPG items. |
| `m3u/TvM3uEpgItem.java` | A single EPG program entry (time range, title, description). |
| `m3u/TvM3uArchiveItem.java` | Catchup/archive playable (time-shifted stream URL). |
| `m3u/KnownProviders.java` | Built-in known IPTV provider presets. |

## Data flow
`TvM3uFile` downloads/parses the M3U → builds `TvM3uGroupItem` → `TvM3uTrackItem` channels →
`XmlTv` parses the referenced EPG XML → attaches `TvM3uEpgItem` program guide to each channel →
selecting a past program builds a `TvM3uArchiveItem` catchup URL. Channels are published to the
Android-TV home screen through `me.aap.fermata.media.lib.AtvInterface`.

## Grounding notes
- EPG times and catchup URL templates are parsed from the M3U/XMLTV attributes in
  `TvM3uTrackItem`/`XmlTv`. Streams are passed to the selected `MediaEngine` (VLC/Exo) like any
  other playable.
