# Parity status log

Running log for the work of bringing this app to the old app's look and behaviour while keeping the new extras. The checklist itself is `docs/PARITY_SPEC.md`. This file records decisions taken while the owner was away, and the questions only the owner can answer.

## Process

- All building, testing and screenshots happen on GitHub's runners or in a disposable cloud VM. Nothing runs on a personal machine.
- The cloud VM cannot reach Google's Android SDK host, so Android builds, unit tests and emulator screenshots run in GitHub Actions. The `Screenshots` workflow prints each screenshot into the job log (base64) with a text dump of the screen; `tools/screenshots/extract.py` restores the images.
- Branches: `fix/ci-temp-delete-guard` (PR #1, CI fix) is the base. Each batch is a branch `parity-<name>` with its own PR into `parity`; those PRs are the review history. Agents in this setup are not allowed to merge PRs, so verified batches are combined on `claude/charming-cori-caxhqb`, whose PR #2 into `main` is the single integration PR for the owner. `parity` is only moved forward to that branch's tooling so batches start from the same base. Nothing is merged into `main` by an agent.
- Clean-room: the old app is studied through its docs, screenshots, string resources and behaviour. Agents that read old source write behaviour and measurements only, in their own words; the code here is new.

## Batches

Each batch was built by one agent, reviewed adversarially by a different agent, fixed, and then combined on the integration branch (PR #2). Every batch had green CI (unit tests, debug APK, unsigned release APK) and a green Screenshots run before being combined.

| PR | Batch | Review result | In PR #2 |
|---|---|---|---|
| #1 | CI fix: safe deletes inside the temp dir | green | base |
| #2 | Integration (screenshot tooling, other-app guard, this log) | — | — |
| #3 | Update path: package id, versioning, release workflow, in-app updater, `atom4_` storage | pass with fixes → fixed, re-reviewed | yes |
| #4 | Parity spec audit | docs | yes |
| #5 | README (standalone, into `main`) | docs | no (separate) |
| #6 | UI foundation: Miuix theme and shared components | fail (dark text) → fixed | yes |
| #7 | Behaviour: Live Update, voice channel, lock-screen assist, task capabilities, device tools | fail (lock screen) → fixed | yes |
| #8 | Bubble, hold menu, dismiss target, mini chat, run capsule, assistant overlay | fail (keyboard fit) → fixed | yes |
| #9 | Home chat and drawer | pass with fixes → fixed | yes |
| #10 | Settings tree, model providers, permissions, tool list | pass with fixes → fixed | yes |
| #11 | Feature screens: terminal, Linux, skills, MCP, subagents, tasks, characters, web, backup | fail (image cards) → fixed | yes |
| #12 | Chat, models and voice details | pass with fixes → fixes in the hardening batch | yes |
| #13 | Hardening: lock-screen privacy, approvals, licences, hosted search | pass | yes |
| #14 | Importer for the previous Atom: providers, keys, sign-ins, default model, conversations | pass with fixes → fixed (cross-checked against the old app's real schema and crypto) | yes |
| — | Spec re-check against the combined app (412 / 440 done) | docs | yes |

A final review of the combined branch found no blockers; its lock-screen, approval and licence findings were fixed in the hardening batch (#13), which passed review.

Known small follow-ups (reviewed, not blocking): the mini chat checks the assistant/global model and ignores a model chosen for the bubble conversation itself; automation rules don't yet narrow `step_capabilities` (direct steps can never reach the scheduled-task or automation tools); the automation approval preview cache has one slot; automatic in-run compaction keeps hosted search on; a very short final answer never shows "Generating answer" in the capsule. Not tested on a real device: the assistant overlay, the run capsule and result card, lock-screen behaviour, the release workflow's publish path.

## Decisions taken on the owner's behalf

| # | Decision | Why |
|---|---|---|
| D1 | Android builds and screenshots run in GitHub Actions, not in the VM. | The VM's network blocks `dl.google.com`, so no Android SDK can be installed there. |
| D2 | Screenshots are printed into job logs as base64. | Workflow artifacts are served from a storage host the VM cannot reach; job logs are reachable. |
| D3 | CI fails on references to other apps (word-boundary, case-insensitive). The only allowed hit is the Greek letter in the LaTeX symbol table. | Owner asked that Atom is presented on its own. |
| D4 | Batches are PRs into `parity`; integration happens on the session branch (PR #2 into `main`). | Owner asked for an integration branch with review history; merging PRs is not permitted for agents here. |
| D5 | The old look is rebuilt on the published **Miuix** Compose library (`top.yukonga.miuix.kmp`, Apache-2.0, Maven Central), the same library the old app used, with new code on top. Material 3 colours are mapped from Miuix so screens not yet ported still match. | Exact visual match without copying old code. |
| D6 | Proper capitalisation instead of the old app's lowercase labels: "Settings" (old: "set up"), "Tools" (old: "tool"), "About" (old: "about"), "Test connection", and so on. | The lowercase strings look like an unfinished translation pass, not a design choice. Easy to revert if the owner prefers them. |
| D7 | The bubble matches the old app's **current** code (the owner's octopus emblem on a dark disc with accent arcs), not the cartoon face in the old screenshots. | The face was the earlier upstream design; the old app had already moved to the emblem. |
| D8 | The COUI style is deferred; Miuix (the old default) comes first. The Appearance page shows the UI style row with COUI marked "coming". | COUI is a second full skin; Miuix is what users see by default. |
| D9 | Release identity: applicationId `io.github.namanwtf.atom` (debug builds add `.debug`), versionName `4.0.0`, versionCode floor `2026112600` (yyyyMMdd·100 + 15-minute slot, never decreasing). | The old updater reads this repo's latest release and treats `v4.0.0` as newer than 3.4.5; installing over the old app needs the same id and signing key. See `docs/UPDATES.md`. |
| D10 | The new app's stores use `atom4_` names. Old app data stays on disk untouched and is not migrated. | Prevents the new app from opening an old file of a different format. Migration of old keys and conversations needs the old formats and is listed as a question. |
| D11 | The README is a standalone PR into `main` (https://github.com/namanwtf/atom/pull/5). It lists only implemented features; planned ones are under "Coming". No old-app screenshots. | Owner asked for it early. |
| D13 | Floating bubble: hiding it from the hold menu or dropping it on the X turns the switch off (old behaviour). "Talk" opens the mini chat already listening (the assistant overlay only works when Atom is the default assistant). The snap to the edge keeps a 200 ms glide; the run edge glow is a static sweep to save power; pull-up-to-open needs 48 dp of travel to avoid accidental opens; the mini chat renders Markdown. | Closest match to the old design that is safe and cheap on battery. |
| D14 | Old defaults restored: Monet off, pure black on, theme follows the system, floating bubble on, reasoning enabled by default on, background virtual display on (only acts with root or LSPosed). Existing users keep their stored values. | Owner asked for the old behaviour. |
| D15 | The voice recognition default stays "Automatic" (the phone's own recognizer). The old default was a cloud service that needs an API key. | A fresh install would otherwise fail its first dictation. Easy to change. |
| D16 | The old app's master tool switches are replaced by its per-tool switches (as the old screens actually showed). Existing master switches that were off are converted into the matching per-tool switches once. | §17 correction 2. |
| D17 | Brand logos come from the Lobe Icons project itself (`@lobehub/icons-static-png` 2.12.0, MIT), credited in NOTICE and on the licences screen. | The old app used the same icon set; taking it from its source avoids copying old-app assets. Remove if the owner prefers monograms (Q5). |
| D18 | Assist from the lock screen is allowed, but anything beyond opening asks the user to unlock first, then continues the same request. Nothing (history, answers, tools) is shown or run over the keyguard. | Security; matches what a user expects from a phone assistant. |
| D12 | ColorOS-only personal data sources (notes, system memory, orders, bills, to-dos, chat-image caches) are left as gaps. | They need ColorOS devices and private providers to build and test. |

## Waiting for the owner

The owner asked (2026-11-26) not to be involved in technical decisions: the agents decide and only bring simple, non-technical actions to the owner. Technical questions raised earlier were closed as decisions D19–D23 below.

Only these need the owner:

| # | What to do | Why only the owner can |
|---|---|---|
| O1 | **Add four secrets to this GitHub repository** (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, with the same values the old repository used. | They are the app's signing key. Without them no release is published, and old installs can't update. The agents can't see or create them, and a new key would break updates. |
| O2 | **Merge the PRs on GitHub** in this order: #1, then #2, then #5. | Merging into `main` publishes a release to users, so it stays the owner's call. |
| O3 | *(Optional)* **Allow commits under your GitHub account**, if you want the history to show your name instead of the session's. | It changes the identity on your repository; the session's permission checks refused it without you. |

| # | Closed decision | Choice |
|---|---|---|
| D19 | Data from the old app | Imported automatically on first start (providers, keys, sign-ins, default model, conversations); old files deleted 7 days after a fully successful import, because the old app kept API keys unencrypted. Atom 4 keeps keys encrypted with the Android Keystore. |
| D20 | Brand logos | Keep the Lobe Icons logos (MIT, credited in NOTICE and on the licences screen). |
| D21 | Upstream-derived parts of the old app | Not reused. Atom 4 is new code; the bubble uses only the owner's emblem. Nothing to license. |
| D22 | Starting model, default character, speech recognizer | DeepSeek Flash stays the starting model; no default character is shipped beyond the built-in example; speech uses the phone's own recognizer until the user adds a cloud key. |
| D23 | Mentions of other apps | None in Atom 4 (files, commits, PR texts), enforced by CI. The old repository is not edited: it is being retired, and any push to it publishes a release to existing users. |
