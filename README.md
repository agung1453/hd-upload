# Nimbus Browser

A fast, minimalist Android browser with an iOS-Safari-inspired interface — rounded
address pill, bottom navigation bar, native-feeling tab grid and a start page
that is built from real Android views rather than a web page in a wrapper.

Everything is original work. No Apple logos, trademarks or assets are used or
imitated; this is an independent project and is not affiliated with, endorsed by
or derived from any other browser vendor.

---

## Features

**Browsing**
- Android WebView engine with JavaScript, cookies and DOM storage enabled
- Intelligent address bar: type a query or a URL, it decides which one it is
- Back / forward / reload / stop with live enabled-state on every control
- File download through the system `DownloadManager` (session cookies included)
- File upload via the system picker (`<input type="file">`)
- `target="_blank"`, `tel:`, `mailto:`, `market:` and `intent://` link handling
- Fullscreen video with a proper `CustomView` / system-bar dance
- Share sheet, copy link, add to shortcuts

**Tabs**
- Unlimited tabs, each with independent history
- Safari-style tab overview: 2-column card grid, live tab count
- New tab, close tab, close all, new private tab
- Only the 3 most-recently-used tabs keep a live `WebView`; everything else parks
  its navigation history in a `Bundle` and is restored without a network
  round-trip. This is what keeps memory flat no matter how many tabs are open.

**Start page**
- Prominent search column honouring the chosen search engine
- Pinned shortcut tiles (long-press to remove)
- Frequently visited + recent history sections
- Time-of-day greeting

**Privacy**
- Private / incognito tabs: excluded from history, DOM storage and cache
- No analytics, no ads, no background sync, no third-party SDKs
- Invalid TLS certificates are rejected — the browser cannot be trivially
  intercepted. User-installed CAs are deliberately not trusted.

**Settings**
- Light / dark / follow-system theme, applied instantly
- Search engine choice (Google, DuckDuckGo, Bing, Brave, Startpage, Ecosia, Firefox)
- Desktop-mode toggle, text zoom, cache strategy, third-party cookie blocking
- One-tap clearing of history, cookies, cache, form data and download history
- Clear data, downloads, shortcuts reset and an about screen

**Performance / size**
- Single `WebView` per hot tab, aggressive recycling
- R8 `minifyEnabled` + `shrinkResources` on release builds
- No image-loading library, no reflection-heavy frameworks; ~68 hand-written
  vector drawables instead of raster assets
- No native libraries → no per-ABI splits needed
- Database, cache and bookmark reads are cached in memory and only touch
  SQLite off the main thread

---

## Requirements

| | |
|---|---|
| minSdk | 29 (Android 10) |
| targetSdk / compileSdk | 35 |
| Language | Kotlin 2.0.21 |
| Gradle | 8.9 (via the checked-in wrapper) |
| Android Gradle Plugin | 8.7.3 |
| JDK | 17 |

All dependencies are stable, well-established AndroidX / Material releases.

---

## Opening the project in Android Studio

1. **Install Android Studio Ladybug (2024.2) or newer.**
2. **Settings → Languages & Frameworks → Android SDK**: make sure
   *Android 15 (API 35)* is installed and the SDK path is set.
3. **File → Open**, select this repository's root folder (the one containing
   `settings.gradle.kts`), and click **OK**.
4. Wait for **Gradle Sync** to finish. The bundled Gradle wrapper (8.9) and the
   JDK 17 that ships with Android Studio are used automatically — no extra
   setup, and nothing is installed globally.
5. Select the **app** run configuration and press **Run**.

To build from the command line instead (Android Studio or a terminal with the
SDK path in `local.properties`):

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

---

## Building via GitHub Actions

The repository ships a complete workflow at
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml). It needs
no manual steps on the runner.

**Triggers:** every `push`, every `pull_request`, and manual `workflow_dispatch`
(which exposes a `release_build` boolean input).

**What it does:**
1. Checks out the source.
2. Sets up **Temurin JDK 17** — the version AGP 8.7 and Gradle 8.9 require.
3. Sets up the **Gradle wrapper with caching** (`gradle/actions/setup-gradle@v4`),
   so `~/.gradle` and the wrapper distribution are reused between runs.
4. Restores the release keystore from repository **Secrets** (see below).
5. Runs `./gradlew assembleDebug assembleRelease`.
6. Collects the APKs plus a `SHA256SUMS.txt`.
7. Uploads `nimbus-debug-apk` and `nimbus-release-apk` as **artifacts**.

### Downloading the APK

1. Open the repository on GitHub → the **Actions** tab.
2. Pick the run you want (filter by branch or by the person who pushed).
3. At the bottom of the run summary, expand **Artifacts**.
4. Download `nimbus-release-apk` (or `nimbus-debug-apk`).
   GitHub downloads it as a `.zip`; the `.apk` is inside.
5. Copy the `.apk` to an Android 10+ device and allow installation from that
   source when prompted.

Pushing a tag named `v1.0.0` (or similar) additionally publishes the APKs as a
GitHub **Release** with auto-generated notes, so they can be downloaded without
signing in to read run logs.

### Release signing (Secrets only — no keystore in the repo)

Create a keystore once, locally:

```bash
keytool -genkey -v -keystore nimbus-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias nimbus
```

Base64-encode it **without** the wrapping newlines becoming an issue:

```bash
base64 -w0 nimbus-release.jks > nimbus-release.jks.b64
```

Then add four repository secrets under **Settings → Secrets and variables →
Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `nimbus-release.jks.b64` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias, e.g. `nimbus` |
| `KEY_PASSWORD` | key password |

The workflow decodes the keystore into `$RUNNER_TEMP` (never into the
workspace) and exports `NIMBUS_STORE_FILE`, `NIMBUS_STORE_PASSWORD`,
`NIMBUS_KEY_ALIAS` and `NIMBUS_KEY_PASSWORD`, which `app/build.gradle.kts`
reads. `.gitignore` also blocks `*.jks`, `*.keystore` and `keystore.properties`
as a second line of defence.

> **Pull requests from forks cannot read secrets.** That is expected and
> supported: with no secrets present the release build still succeeds and
> simply produces an **unsigned** APK, so `assembleRelease` never breaks CI for
> external contributors.

---

## Project layout

```
app/src/main/java/com/nimbus/browser/
├── NimbusApp.kt                 Application: prefs, theme, repository warm-up
├── core/                        Constants, Prefs, SearchEngine, UrlUtils, ViewExt
├── data/                        SQLite store + History/Bookmark repositories
├── model/                       BrowserTab, TabManager (the WebView lifecycle owner)
├── web/                         WebViewFactory + WebViewClient / WebChromeClient
├── util/                        DownloadHelper
└── ui/
    ├── browser/                 BrowserActivity, StartPageController
    ├── tabs/                    Tab overview grid
    ├── history/  bookmarks/     List screens
    ├── settings/                Settings + clear-browsing-data dialog
    ├── about/
    └── common/                  BottomMenu sheet
```

### The central idea

`TabManager` is a process-wide singleton and the single owner of every
`WebView`. `BrowserActivity` is just a host: it tells the manager which tab is
visible and puts the returned `WebView` into the container.

```
BrowserActivity ──▶ TabManager.activate(tab)
                        │
                        ├─ tab has a live WebView?  ── reuse it (instant switch)
                        ├─ tab is the start page?    ── release WebView, show native view
                        └─ otherwise                  ── build + restoreState + loadUrl
                        │
                        └─▶ trim(): keep only the 3 hottest WebViews alive
```

That single rule is what makes the app cheap: tab switching is instant for
recent tabs, older tabs come back from a parked `Bundle`, and the number of
live `WebView`s never depends on how many tabs the user opened.

---

## Static validation

`tools/validate_project.py` checks the project **without** requiring the Android
SDK or Gradle:

- every XML file is well-formed
- every `@drawable/`, `@color/`, `@string/`, `@dimen/`, `@style/`, `@array/`
  reference resolves to a real resource
- every `R.<type>.<name>` in Kotlin resolves
- every `*Binding` class used in Kotlin has a matching layout
- every `binding.<id>` access exists in that layout
- every Activity declared in the manifest exists as a Kotlin class
- every `com.nimbus.*` import resolves to a declared type

```bash
python3 tools/validate_project.py
```

It exits non-zero on any problem, so it can be dropped into a pre-commit hook or
a CI step too.

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | load pages |
| `ACCESS_NETWORK_STATE` | detect offline state before error pages |

Nothing else is requested — no location, camera, microphone, contacts or
notification permissions, which is also why web prompts for geolocation are
always denied.

---

## Licence

Provided as-is for personal and educational use.