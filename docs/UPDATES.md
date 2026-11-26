# Updates and the path from the earlier Atom app

Atom 4 replaces the earlier Atom app (a separate, older codebase whose last release was `v3.4.5-atom.38`,
published 2026-11-24). A phone that has the earlier app updates straight into Atom 4, in place, with no uninstall.

## How an earlier install reaches Atom 4

Android accepts an APK as an update only when three things hold, and all three are kept on purpose:

| Rule | Earlier app | Atom 4 |
|---|---|---|
| Same `applicationId` | `io.github.namanwtf.atom` | `io.github.namanwtf.atom` (release builds) |
| Same signing certificate | SHA-256 `F3:BA:9F:48:…:CB:8F:A7` | the release workflow refuses to publish anything else |
| Higher `versionCode` | below `2026112500` | published releases start at `2026112601` |

The earlier app's updater calls `GET https://api.github.com/repos/namanwtf/atom/releases/latest`, reads
`tag_name` and `html_url`, strips a leading `v`, splits on `.` and compares segment by segment (numbers when both
segments are integers), ignoring its own `-atom.38` suffix. A normal (not draft, not pre-release) release tagged
`v4.0.0` or later is therefore newer than `3.4.5`. The app opens `html_url` in the browser, the user downloads
`atom-<version>.apk` and installs it over the earlier app. This repository now owns the name
`namanwtf/atom`, so every Atom 4 release is what the earlier app finds.

The full certificate fingerprint is
`F3:BA:9F:48:E1:53:A9:97:98:BB:0D:7C:74:C2:41:D8:D0:C3:F9:C1:DB:26:86:F7:B5:0C:28:F1:87:CB:8F:A7`.

Debug builds use `applicationIdSuffix = ".debug"` (`io.github.namanwtf.atom.debug`), so installing a CI debug
APK never touches a phone's release install. `tools/screenshots/capture.py` drives that id by default.

## Versions

- **Tag and `versionName`**: `4.<minor>.<patch>`. Each release takes the highest existing `v4.*.*` tag and adds
  one to the patch number; the first one is `v4.0.0`. The workflow never raises the minor number itself.
- **`versionCode`**: `yyyyMMdd * 100 + slot`, where `slot` is the 15-minute slot of the UTC day (0..95), the same
  rule the earlier app used. The release workflow takes the maximum of that value, the committed floor in
  `app/build.gradle.kts` plus one, and the previous release's code plus one, so it never decreases. The previous
  code is read from the last line of that release's notes (`versionCode N`, alone on its line), or else from its
  published APK; if a previous release exists and neither works, the run fails and publishes nothing.
- The committed values are `versionName = "4.0.0"` and `versionCode = 2026112600`. Only local and CI builds
  carry them; the workflow stamps the real values into the build file on the runner and never commits them, so
  the first published release has at least `2026112601`.

## Publishing (owner actions)

1. **Add the signing secrets to this repository** (Settings > Secrets and variables > Actions), with the same
   values the earlier app's repository used: `KEYSTORE_BASE64` (the release keystore, base64), `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD`. Never generate a new key: an APK signed with any other key cannot update existing
   installs, and Android will refuse it.
2. **Merge to `main`.** Every push to `main` runs `.github/workflows/release.yml`. If pushes landed before the
   secrets existed, run the workflow by hand (Actions > Release > Run workflow): a manual run releases `main`'s
   current tip. Do not re-run old failed runs; they skip anyway (below). The workflow:
   - fails at once, publishing nothing, if any of the four secrets is missing or empty;
   - releases only the current tip of `main`, and only when the previous release tag is an ancestor of it; a
     commit that is already released, a re-run of an older push, or a tip that moved during the build skips with
     a notice, so older code can never become the latest release;
   - computes the tag and `versionCode`, runs the unit tests (`test testDebugUnitTest`), builds
     `:app:assembleRelease`, zipaligns and signs with `apksigner` (the decoded keystore is removed on exit);
   - checks with `apksigner verify --print-certs` that there is exactly one signer and its SHA-256 is the earlier
     app's certificate, and with `aapt2` that the id, `versionCode` and `versionName` are the stamped ones;
     any mismatch fails the run before publishing;
   - attaches `atom-<version>.apk` and `atom-<version>.apk.sha256` (`sha256sum` format);
   - writes the release notes: a changelog of the commit subjects since the previous tag, the APK's SHA-256 and
     the `versionCode` line (there is no CHANGELOG file in the repository; the notes are the changelog);
   - publishes with `gh release create` as a normal release marked latest, then checks it is not a draft or
     pre-release and that `releases/latest` now returns it.

   The checkout keeps no credentials; only `gh` gets `GITHUB_TOKEN`, with `contents: write` on this job alone.
   Releases and tags created with that token do not start other workflow runs, so publishing never loops, and
   nothing is committed back to `main`. Pull requests build the unsigned release variant in `ci.yml`, so R8
   problems show before merge.

## The in-app updater (About > Check for updates)

Atom 4 checks the same `releases/latest` endpoint, only when About opens or the user taps the row. It compares
versions by semantic-version order, picks the release's `atom-<version>.apk` and its `.sha256`, and only accepts
download links under `https://github.com/namanwtf/atom/releases/download/`. On the release build, tapping
**Update**:

1. asks for Android's "Install unknown apps" permission for Atom the first time (`REQUEST_INSTALL_PACKAGES`);
2. downloads the checksum file, then the APK into the app cache (`cache/atom4_updates`), refusing anything larger
   than the size GitHub published;
3. checks the APK's SHA-256 against the published one;
4. reads the APK with `PackageManager.getPackageArchiveInfo(…, GET_SIGNING_CERTIFICATES)` and requires the same
   package name, the same signing certificate as the running app (or a rotated key whose lineage contains it) and a
   higher `versionCode`;
5. hands it to Android's own installer through a `PackageInstaller` session. Android then asks the user to
   confirm. The confirmation opens from About while it is in front (once on its own, then with
   **Continue update**) and from an "Atom update ready" notification, never from the background. While a session
   waits, Update does not download again.

Installer errors are shown with Android's own message. A release without a usable APK and checksum pair, and every
non-release build (such as `io.github.namanwtf.atom.debug`), get a **Download** button that opens the release page
instead: a release APK cannot install over a different package name. No third-party services, no proxies, no
telemetry and no background checks are involved.

## Data after updating from the earlier app

Installing over the earlier app is a normal Android update, so Android keeps:

- the app's data directory (`/data/data/io.github.namanwtf.atom`) and everything in it;
- runtime permissions already granted (microphone, notifications, and so on) and "Install unknown apps";
- special access that Android records per component, such as an enabled accessibility service or notification
  listener, only when Atom 4 has a component of the same class name. Atom 4's are
  `atom.device.AtomAccessibilityService`, `atom.context.notifications.AtomNotificationListener` and
  `atom.voice.assist.AtomVoiceInteractionService`; if the earlier app used other class names, the user turns these
  on again once in Android's settings. Launcher shortcuts that point at an activity Atom 4 does not have
  disappear.

Atom 4 does **not** read the earlier app's private data formats. Earlier conversations, provider API keys and
settings are not migrated, and the user adds provider keys again in Atom 4. Every file and folder Atom 4's own
code creates in `files/` (apart from the DataStore library's `datastore/` folder), `files/datastore/`,
`databases/` and `shared_prefs/`, and every Android Keystore alias it uses, has a name starting with `atom4`
(table below), a prefix new in Atom 4. Atom 4 never opens, changes or
deletes any other name there, so the earlier app's files in those places are left untouched on disk. They take up
space until the user clears the app's storage (Android Settings > Apps > Atom > Storage > Clear storage, which
also clears Atom 4's own data). Keys the earlier app sealed in the Android Keystore stay there too.

| Store | Atom 4 names |
|---|---|
| DataStore (`files/datastore/<name>.preferences_pb`) | `atom4_settings`, `atom4_providers`, `atom4_vault`, `atom4_runtime`, `atom4_appearance` |
| SQLite (`databases/`) | `atom4_notification_history.db` |
| SharedPreferences (`shared_prefs/<name>.xml`) | `atom4_overlay`, `atom4_web`, `atom4_speech`, `atom4_litert_backend`, `atom4_root`, `atom4_charge_limit`, `atom4_tool_switches`, `atom4_system`, `atom4_clock`, `atom4_automation_places`, `atom4_extend_secrets` |
| Android Keystore aliases | `atom4.vault`, `atom4_extend_secrets` |
| Folders and files in `files/` | `atom4_conversations/`, `atom4_run-journal/`, `atom4_models/`, `atom4_characters/`, `atom4_linux/`, `atom4_workspace/`, `atom4_extend/`, `atom4_attachments/`, `atom4_shared/`, `atom4_generated_images/`, `atom4_automation/state.json`, `atom4_undo_journal.json`, `atom4_firewall_rules.json` |

These distinct names are also why old files cannot crash Atom 4 at startup: a file of another format with the same
name would otherwise be read (a DataStore or SharedPreferences type mismatch throws, an SQLite database with a
higher version cannot be opened, a Keystore key with other parameters cannot encrypt, and some stores clean or
roll back their folders when they open). Atom 4 was never released under this id before, so the names needed no
migration. Keep new stores on the `atom4` prefix.

What the promise does not cover:

- **Libraries' own stores.** WorkManager keeps its database (`databases/androidx.work.workdb`) under the library's
  fixed name, shared with the earlier app if it used WorkManager. Work the earlier app scheduled stays queued; its
  worker classes do not exist in Atom 4, so a one-time job is marked failed when it comes due and a periodic one
  fails again each period, without crashing the app. WebView keeps its profile in its own folders
  (`app_webview/`), and androidx ProfileInstaller writes its `files/profileinstaller_*` markers.
- **`cache/`.** Android may clear it at any time, and Atom 4 and its libraries keep temporary files there (the
  built-in browser also clears the WebView cache). Atom 4's own cache files still use the prefix
  (`atom4_updates/`, `atom4_models_dev_catalog.json`), so a cached file the earlier app left is never read.
- **Commands the user runs.** The Android shell tool starts in `files/` with `HOME` set to it, so a command the user
  or the agent runs there can read or change any file, including the earlier app's.
