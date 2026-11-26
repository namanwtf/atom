# Product decisions

Choices made while bringing Atom 4 to the old app's look and behaviour, with the reason for each. The feature
checklist is [`PARITY_SPEC.md`](PARITY_SPEC.md); its gap list refers to these entries by number. Numbers are
stable, so a few are unused.

| # | Decision | Why |
|---|---|---|
| D3 | CI fails on references to other apps (word-boundary, case-insensitive). The only allowed hit is the Greek letter in the LaTeX symbol table. | Atom is presented on its own. |
| D5 | The old look is rebuilt on the published **Miuix** Compose library (`top.yukonga.miuix.kmp`, Apache-2.0, Maven Central), the same library the old app used, with new code on top. Material 3 colours are mapped from Miuix so screens not yet ported still match. | Exact visual match without copying old code. |
| D6 | Proper capitalisation instead of the old app's lowercase labels: "Settings" (old: "set up"), "Tools" (old: "tool"), "About" (old: "about"), "Test connection", and so on. | The lowercase strings look like an unfinished translation pass, not a design choice. Easy to revert. |
| D7 | The bubble matches the old app's **current** design (the Atom octopus emblem on a dark disc with accent arcs), not the cartoon face in the old screenshots. | The face was the earlier upstream design; the old app had already moved to the emblem. |
| D8 | The COUI style is deferred; Miuix (the old default) comes first. The Appearance page shows the UI style row with COUI marked "coming". | COUI is a second full skin; Miuix is what users see by default. |
| D9 | Release identity: applicationId `io.github.namanwtf.atom` (debug builds add `.debug`), versionName `4.0.0`, versionCode floor `2026112600` (yyyyMMdd·100 + 15-minute slot, never decreasing). | The old updater reads this repository's latest release and treats `v4.0.0` as newer than 3.4.5. See [`UPDATES.md`](UPDATES.md). |
| D10 | The new app's stores use `atom4_` names. The old app's files are never opened as new-format stores; only the importer (D19) reads them. | Prevents the new app from opening an old file of a different format. |
| D12 | ColorOS-only personal data sources (notes, system memory, orders, bills, to-dos, chat-image caches) are left as gaps. | They need ColorOS devices and private providers to build and test. |
| D13 | Floating bubble: hiding it from the hold menu or dropping it on the X turns the switch off (old behaviour). "Talk" opens the mini chat already listening (the assistant overlay only works when Atom is the default assistant). The snap to the edge keeps a 200 ms glide; the run edge glow is a static sweep to save power; pull-up-to-open needs 48 dp of travel to avoid accidental opens; the mini chat renders Markdown. | Closest match to the old design that is safe and cheap on battery. |
| D14 | Old defaults restored: Monet off, pure black on, theme follows the system, floating bubble on, reasoning enabled by default on, background virtual display on (only acts with root or LSPosed). Existing users keep their stored values. | Matches the old app's behaviour. |
| D15 | The voice recognition default stays "Automatic" (the phone's own recognizer). The old default was a cloud service that needs an API key. | A fresh install would otherwise fail its first dictation. Easy to change. |
| D16 | The old app's master tool switches are replaced by its per-tool switches (as the old screens actually showed). Existing master switches that were off are converted into the matching per-tool switches once. | §17 correction 2. |
| D17 | Brand logos come from the Lobe Icons project itself (`@lobehub/icons-static-png` 2.12.0, MIT), credited in NOTICE and on the licences screen. | The old app used the same icon set; taking it from its source avoids copying old-app assets. |
| D18 | Assist from the lock screen is allowed, but anything beyond opening asks the user to unlock first, then continues the same request. Nothing (history, answers, tools) is shown or run over the keyguard. | Security; matches what a user expects from a phone assistant. |
| D19 | Data from the old app is imported automatically on first start (providers, keys, sign-ins, default model, conversations, long-term memory). The old files are deleted 7 days after a fully successful import. | The old app kept API keys unencrypted; Atom 4 keeps keys encrypted with the Android Keystore. See [`IMPORT.md`](IMPORT.md). |
| D20 | Keep the Lobe Icons brand logos (D17). | Recognisable providers at a glance; MIT-licensed and credited. |
| D21 | Upstream-derived parts of the old app are not reused. Atom 4 is new code; the bubble uses only the Atom emblem. | Nothing to license. |
| D22 | DeepSeek Flash stays the starting model; no default character is shipped beyond the built-in example; speech uses the phone's own recognizer until the user adds a cloud key. | Works on a fresh install without extra setup. |
| D23 | No mentions of other apps in Atom 4 (files, commits, pull request texts), enforced by CI (D3). | Atom is presented on its own. |
| D24 | A fresh release signing key. Phones with the earlier app reinstall once; afterwards every release updates in place. The fingerprint is pinned in `release.yml`; only two secrets are needed. | The earlier app was never launched publicly. See [`UPDATES.md`](UPDATES.md). |
