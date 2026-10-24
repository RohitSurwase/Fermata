# Module: web (WebView browser + YouTube)

- Gradle: `modules/web/build.gradle` · package `me.aap.fermata.addon.web` · dynamic-feature
  (has `main`, `auto`, `debug` source sets + manifests).
- Source files (16): `me/aap/fermata/addon/web/**`.

## Role
An in-app web browser plus a **YouTube player** that reuses Fermata's media engine via a custom
`MediaEngineProvider`.

## Key files
| File | Role |
|---|---|
| `WebBrowserAddon.java` | Addon entry (`FermataFragmentAddon`); owns the browser preference store, last URL, desktop/force-dark prefs. |
| `WebBrowserFragment.java` | Browser UI fragment; address bar `loadUrl`, navigation. |
| `FermataWebView.java` | `WebView` subclass. **`init()` enables JavaScript, DOM storage, DB, file access, third-party cookies, and `addJavascriptInterface(FermataJsInterface, "Fermata")`**. Media playback does not require a user gesture (`setMediaPlaybackRequiresUserGesture(false)`). |
| `FermataJsInterface.java` | `@JavascriptInterface` bridge named `Fermata`. Handles `JS_EDIT` (show keyboard) and `JS_ERR` (log). |
| `FermataWebClient.java` / `FermataChromeClient.java` | `WebViewClient`/`WebChromeClient` (navigation, fullscreen video, permissions). |
| `WebToolBarMediator.java` | Toolbar integration. |
| `yt/YoutubeAddon.java` | YouTube sub-addon entry. |
| `yt/YoutubeWebView.java` / `YoutubeFragment.java` / `YoutubeWebClient.java` / `YoutubeChromeClient.java` | YouTube WebView surface. |
| `yt/YoutubeMediaEngine.java` | A `MediaEngine` that drives the YouTube `WebView` as the playback surface; registered as a custom engine provider in `MediaEngineManager.setCustomEngineProvider`. |
| `yt/YoutubeJsInterface.java` / `YoutubeVideoView.java` | JS bridge + video view for YouTube. |
| `yt/YoutubeSponsorBlock.java` | **SponsorBlock** segment-skipping. 9 categories (`sponsor`, `selfpromo`, `interaction`, `intro`, `outro`, `preview`, `hook`, `music_offtopic`, `filler`), prefs `YT_SPONSOR_BLOCK` + `YT_SB_*`. Injects a JS script from `R.raw.youtube_sponsorblock`; builds config JSON `{"enabled":..,"categories":[..],"actionTypes":["skip"]}`. |

## Data flow (YouTube)
`YoutubeFragment` loads `DEFAULT_URL` into `YoutubeWebView` → `YoutubeMediaEngine` controls play
state through injected JavaScript (`loadUrl("javascript:...")`) → SponsorBlock JS fetches segment
data and auto-skips enabled categories.

## Security-relevant grounding (see 04_critical_findings §Security)
- `FermataWebView.init()` combines `setJavaScriptEnabled(true)` + `addJavascriptInterface(...)` +
  `setAllowFileAccess(true)` + third-party cookies. The JS bridge is annotated `@JavascriptInterface`
  (API-17+ safe surface) and exposes only `event(int,String)`, but the broad settings mean any loaded
  page runs with a native bridge present.
- Arbitrary URLs are loaded from the address bar and from intercepted requests
  (`FermataWebClient.java:68`, `YoutubeWebClient.java:27`, `WebToolBarMediator.java:86`). Combined
  with manifest `usesCleartextTraffic="true"`, cleartext navigation is permitted.
