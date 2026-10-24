# Design Note — WebView Ad-Blocker (blocklist based)

Status: **Design / not implemented.** This records the proposed approach and — importantly — the
**realistic expectations** for a blocklist-based ad-blocker in Fermata's WebView, so the feature is
not misjudged in future.

Grounded in the current web module:
- `modules/web/.../FermataWebClient.java` — base `WebViewClientCompat`; already overrides
  `shouldInterceptRequest(view, request)` (used today by the Internet-access toggle).
- `modules/web/.../yt/YoutubeWebClient.java` — `extends FermataWebClient`, so any filtering in the
  base client covers **both** the Web browser and YouTube.
- `modules/web/.../WebBrowserAddon.java` — `SharedPreferenceStore` ("web" prefs) + `contributeSettings`
  pattern for toggles; `res/raw/youtube_sponsorblock.js` shows the bundled-asset pattern.
- HTTP stack for remote lists: `me.aap.utils.net.http.HttpFileDownloader` (in `:utils`).

---

## ⚠️ Expectation correction (read this first)

A **host/domain blocklist** (EasyList "hosts" / StevenBlack style) blocks **network requests to
ad/tracker domains**. It is NOT a full ad-blocking engine. Specifically:

| Expectation | Reality with a domain blocklist |
|---|---|
| Block ad/tracker network calls on web pages | ✅ Yes — this is what it does well |
| Remove banner/display ads loaded from 3rd-party ad domains | ✅ Mostly (the request is blocked) |
| Cosmetic element hiding (EasyList CSS `##selector` rules) | ❌ No — needs a CSS/JS cosmetic engine, not in scope |
| Same-domain / server-stitched ads | ❌ No — can't block without blocking the content itself |
| **YouTube in-stream video ads (pre-roll/mid-roll)** | ❌ **No — see below. This is the key misconception.** |

### Why YouTube video ads are NOT blockable by a domain blocklist
YouTube serves its **video ads from the same infrastructure as the actual video** —
`*.googlevideo.com` and the main `youtube.com`/`youtubei` APIs. The ad bytes and the content bytes
come from the **same hosts**. Blocking those hosts to kill the ad would also **break video
playback**. There is no separate "ad host" to add to a blocklist.

What *does* help YouTube:
- The existing **SponsorBlock** feature (`yt/YoutubeSponsorBlock.java`) skips community-marked
  sponsor/intro/outro **segments inside the creator's video** — a different mechanism (crowd-sourced
  timestamps + JS seek), not network blocking.
- Real YouTube-ad removal requires client-side player patching / API response rewriting (what
  dedicated apps like certain forks do), which is brittle, high-maintenance, and a cat-and-mouse
  game — explicitly **out of scope** here.

> Bottom line: the ad-blocker is valuable for the **Web browser** (and YouTube *page* trackers/banner
> ads), but it will **not** remove YouTube pre-roll/mid-roll video ads. Scope and message the feature
> accordingly.

---

## Proposed design (if/when implemented)

### Interception point (single, already present)
```
FermataWebClient.shouldInterceptRequest(view, request)
  ├─ [existing] Internet-blocked → block page
  ├─ [NEW] AdBlocker.isBlocked(request.getUrl(), pageHost) → return empty WebResourceResponse
  └─ null (allow)
```
Because `YoutubeWebClient extends FermataWebClient`, this covers browser + YouTube with no
duplication. Ordering: the Internet-block check stays first (if offline, nothing reaches the blocker).

### New component: `me.aap.fermata.addon.web.AdBlocker`
- Holds `Set<String>` of blocked hosts; supports exact + suffix match (`*.doubleclick.net`).
- `boolean isBlocked(Uri requestUri, String pageHost)`:
  - extract request host; **skip first-party** (same registrable domain as `pageHost`) to avoid
    breaking sites;
  - return true if the cross-party host is in the set.
- Blocked response: benign empty `new WebResourceResponse("text/plain", "utf-8", emptyStream)`
  (standard WebView ad-block technique; avoids broken-image/JS errors where possible).
- Loads lazily off the main thread via `App.get().execute(...)` (consistent with the codebase's
  `FutureSupplier` concurrency). Parsing a plain hosts file needs **no third-party dependency**.

### Blocklist sources (local + remote, as requested)
- **Local (bundled):** `modules/web/src/main/res/raw/adblock_hosts.txt`, one host per line
  (StevenBlack/EasyList "hosts" format). Offline, zero deps. Same pattern as `youtube_sponsorblock.js`.
- **Remote (optional):** pref `ADBLOCK_URL`; fetch with `HttpFileDownloader` (in-house HTTP stack),
  cache to app storage, refresh on interval; fall back to bundled on failure.

### Settings (in `WebBrowserAddon.contributeSettings`, existing pattern)
- `ADBLOCK_ENABLED` (boolean).
- `ADBLOCK_LIST_SOURCE` (list: Bundled / Remote / Both).
- `ADBLOCK_URL` (string; blank = bundled only).
- "Update blocklist now" button + last-updated/count subtitle (mirrors SubGen cleanup button).

### Safety / correctness
- First-party allowlisting prevents breaking site functionality.
- Pref-gated, default-reversible; disabled ⇒ `shouldInterceptRequest` behaves exactly as today.
- Respects the Internet toggle (ordering).
- No new dependency for the local path; remote path reuses `:utils` HTTP.

### Scope options
- **MVP:** bundled list + enable toggle + first-party allowlisting. ~1 new class, ~15 lines in
  `FermataWebClient`, 2 prefs + settings rows, 1 bundled list file. Offline, no deps.
- **Phase 2:** remote list URL + periodic refresh + "update now" button.

### Verification plan (when built)
- `./gradlew --stop` then `:fermata:compileAutoDebugSources` (fast) and `bundleAutoRelease
  -PAPP_ID_SFX=.core` (full), per `CHANGELOG.md → Build verification`.
- Manual: load an ad-heavy page with blocker on/off and confirm tracker hosts are blocked and the
  site still functions (first-party intact). Confirm YouTube **page** ads/trackers reduced but accept
  that **in-stream video ads remain** (expected, per the correction above).

---

## Lesson captured (to avoid repeating the mistake)
When evaluating "ad-blocker" requests for a WebView app, separate **three distinct problems** up front
and state which are in scope:
1. **Network tracker/ad-domain blocking** (blocklist) — feasible, what this design does.
2. **Cosmetic element hiding** (EasyList CSS) — needs a cosmetic engine; not in a simple blocklist.
3. **First-party / same-host video ads (YouTube)** — not solvable by blocklists; needs player
   patching and is out of scope. Point users to SponsorBlock for segment skipping instead.
