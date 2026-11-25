# Updates and the path from the earlier Atom app

Atom 4 replaces the earlier Atom app (a separate, older codebase whose last release was `v3.4.5-atom.38`,
published 2026-11-24). A phone that has the earlier app updates straight into Atom 4, in place, with no uninstall.

## How an earlier install reaches Atom 4

Android accepts an APK as an update only when three things hold, and all three are kept on purpose:

| Rule | Earlier app | Atom 4 |
|---|---|---|
| Same `applicationId` | `io.github.namanwtf.atom` | `io.github.namanwtf.atom` (release builds) |
| Same signing certificate | SHA-256 `F3:BA:9F:48:…:CB:8F:A7` | the release workflow refuses to publish anything else |
| Higher `versionCode` | below `2026112500` | `2026112600` or higher |

The earlier app's updater calls `GET https://api.github.com/repos/namanwtf/atom/releases/latest`, reads
`tag_name` and `html_url`, strips a leading `v`, splits on `.` and compares segment by segment (numbers when both
segments are integers), ignoring its own `-atom.38` suffix. A normal (not draft, not pre-release) release tagged
`v4.0.0` or later is therefore newer than `3.4.5`. The app opens `html_url` in the browser, the user downloads
`atom-<version>.apk` and installs it over the earlier app. This repository now owns the name
`namanwtf/atom`, so every Atom 4 release is what the earlier app finds.

The full certificate fingerprint is
`F3:BA:9F:48:E1:53:A9:97:98:BB:0D:7C:74:C2:41:D8:D0:C3:F9:C1:DB:26:86:F7:B5:0C:28:F1:87:CB:8F:A7`.

Debug builds use `applicationIdSuffix = ".debug"` (`io.github.namanwtf.atom.debug`), so installing a CI debug
APK never touches a phone's release install.

## Versions

- **Tag and `versionName`**: `4.<minor>.<patch>`. Each release takes the highest existing `v4.*.*` tag and adds
  one to the patch number; the first one is `v4.0.0`. The workflow never raises the minor number itself.
- **`versionCode`**: `yyyyMMdd * 100 + slot`, where `slot` is the 15-minute slot of the UTC day (0..95), the same
  rule the earlier app used. The release workflow takes the maximum of that value, the committed floor in
  `app/build.gradle.kts` plus one, and the previous release's code plus one (read from that release's notes), so it
  never decreases. The floor is committed as `2026112600`; the stamped value is written into the build file on the
  runner only and is never committed.
- The committed `versionName` is `4.0.0`; local and CI debug builds report that.

## Publishing (owner actions)

1. **Add the signing secrets to this repository** (Settings > Secrets and variables > Actions), with the same
   values the earlier app's repository used: `KEYSTORE_BASE64` (the release keystore, base64), `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD`. Never generate a new key: an APK signed with any other key cannot update existing
   installs, and Android will refuse it.
2. **Merge to `main`.** Every push to `main` runs `.github/workflows/release.yml`, which:
   - fails at once, publishing nothing, if any of the four secrets is missing or empty;
   - skips the run if the commit is already released;
   - computes the tag and `versionCode`, builds `:app:assembleRelease`, zipaligns and signs with `apksigner`;
   - checks with `apksigner verify --print-certs` that there is exactly one signer and its SHA-256 is the earlier
     app's certificate, and with `aapt2` that the id, `versionCode` and `versionName` are the stamped ones;
     any mismatch fails the run before publishing;
   - attaches `atom-<version>.apk` and `atom-<version>.apk.sha256` (`sha256sum` format);
   - writes the release notes: a changelog of the commit subjects since the previous tag, the `versionCode` and
     the APK's SHA-256 (there is no CHANGELOG file in the repository; the notes are the changelog);
   - publishes with `gh release create` as a normal release marked latest (not draft, not pre-release).

   It uses `GITHUB_TOKEN` with `contents: write`. Releases and tags created with that token do not start other
   workflow runs, so publishing never loops, and nothing is committed back to `main`.

## The in-app updater (About > Check for updates)

Atom 4 checks the same `releases/latest` endpoint, only when About opens or the user taps the row. It compares
versions by semantic-version order, picks the release's `atom-<version>.apk` and its `.sha256`, and only accepts
download links under `https://github.com/namanwtf/atom/releases/download/`. Tapping **Update**:

1. asks for Android's "Install unknown apps" permission for Atom the first time (`REQUEST_INSTALL_PACKAGES`);
2. downloads the checksum file, then the APK into the app cache (`cache/updates`), refusing anything larger than
   the size GitHub published;
3. checks the APK's SHA-256 against the published one;
4. reads the APK with `PackageManager.getPackageArchiveInfo(…, GET_SIGNING_CERTIFICATES)` and requires the same
   package name, the same signing certificate as the running app (or a rotated key whose lineage contains it) and a
   higher `versionCode`;
5. hands it to Android's own installer through a `PackageInstaller` session, which asks the user to confirm.

A release without a usable APK and checksum pair falls back to a **Download** button that opens the release page.
No third-party services, no proxies, no telemetry and no background checks are involved. A debug build has a
different package name, so it never installs a release over itself; it offers the release page instead.

## Data after updating from the earlier app

Installing over the earlier app is a normal Android update, so Android keeps:

- the app's data directory (`/data/data/io.github.namanwtf.atom`) and everything in it;
- runtime permissions already granted (microphone, notifications, and so on) and "Install unknown apps";
- special access that Android records per component, such as an enabled accessibility service or notification
  listener, only when Atom 4 has a component of the same class name. Atom 4's are
  `atom.device.AtomAccessibilityService`, `atom.context.notifications.AtomNotificationListener` and
  `atom.voice.assist.AtomVoiceInteractionService`; if the earlier app used other class names, the user turns these
  on again once in Android's settings.

Atom 4 does **not** read the earlier app's private data formats. Earlier conversations, provider API keys and
settings are not migrated: their files are left untouched on disk in the data directory. Atom 4 never opens,
changes or deletes them, and they take up space until the user clears the app's storage (Android Settings > Apps >
Atom > Storage > Clear storage, which also clears Atom 4's own data). Keys the earlier app sealed in the Android
Keystore stay there too; Atom 4 uses its own aliases. The user adds provider keys again in Atom 4.

### Distinct file names (no crash on old files)

The earlier app's files sit in the same data directory, so every Atom 4 store whose format is typed or binary uses
a name with the `atom4_` prefix, which is new in Atom 4. A file of another format with the same name would otherwise
be read at startup (a DataStore or SharedPreferences type mismatch throws, an SQLite database with a higher
version cannot be opened, a Keystore key with other parameters cannot encrypt):

| Store | Name |
|---|---|
| DataStore (`files/datastore/<name>.preferences_pb`) | `atom4_settings`, `atom4_providers`, `atom4_vault`, `atom4_runtime`, `atom4_appearance` |
| SQLite (`databases/`) | `atom4_notification_history.db` |
| SharedPreferences (`shared_prefs/<name>.xml`) | `atom4_overlay`, `atom4_web`, `atom4_speech`, `atom4_litert_backend`, `atom4_root`, `atom4_charge_limit`, `atom4_tool_switches`, `atom4_system`, `atom4_clock`, `atom4_automation_places`, `atom4_extend_secrets` |
| Android Keystore aliases | `atom4.vault`, `atom4_extend_secrets` |
| Conversation and run history (`files/`) | `atom4_conversations/`, `atom4_run-journal/` |

Other folders under `files/` keep plain names (`models`, `characters`, `linux`, `workspace`, `extend`,
`attachments`, `shared`, `generated_images`, `automation/state.json`, `undo_journal.json`,
`firewall_rules.json`). The JSON readers among them (characters, automation state, undo journal, firewall
rules, conversations) treat a file they cannot parse as missing instead of failing, so a foreign file there does
not crash Atom 4 at startup; but if the earlier app used one of these exact names, Atom 4 may list or overwrite
files in it. The earlier app's own file list was not audited for this change. Atom 4 was never released under this id before, so these names needed no migration.
Keep new stores on the `atom4_` prefix.
