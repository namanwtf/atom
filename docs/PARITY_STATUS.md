# Parity status log

Running log for the work of bringing this app to the old app's look and behaviour while keeping the new extras. The checklist itself is `docs/PARITY_SPEC.md`. This file records decisions taken while the owner was away, and the questions only the owner can answer.

## Process

- All building, testing and screenshots happen on GitHub's runners or in a disposable cloud VM. Nothing runs on a personal machine.
- The cloud VM cannot reach Google's Android SDK host, so Android builds, unit tests and emulator screenshots run in GitHub Actions. The `Screenshots` workflow prints each screenshot into the job log (base64) with a text dump of the screen; `tools/screenshots/extract.py` restores the images.
- Branches: `fix/ci-temp-delete-guard` (PR #1, CI fix) is the base. Each batch is a branch `parity-<name>` with its own PR into `parity`; those PRs are the review history. Agents in this setup are not allowed to merge PRs, so verified batches are combined on `claude/charming-cori-caxhqb`, whose PR #2 into `main` is the single integration PR for the owner. `parity` is only moved forward to that branch's tooling so batches start from the same base. Nothing is merged into `main` by an agent.
- Clean-room: the old app is studied through its docs, screenshots, string resources and behaviour. Agents that read old source write behaviour and measurements only, in their own words; the code here is new.

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
| D12 | ColorOS-only personal data sources (notes, system memory, orders, bills, to-dos, chat-image caches) are left as gaps. | They need ColorOS devices and private providers to build and test. |

## Waiting for the owner

| # | Item | Status |
|---|---|---|
| Q1 | **Commit identity.** The owner asked for today's commits to come from their GitHub account and for today's non-main commits to be re-authored and force-pushed. The session's permission checks refused the identity change and the force-push, so commits are still made under the session's default identity, and `fix/ci-temp-delete-guard` keeps its original author. The owner can approve this, or re-author locally with `git rebase --exec 'git commit --amend --no-edit --reset-author'`. | Blocked: needs owner approval |
| Q2 | **Merging.** PR #1 (CI fix) is green and ready. PR #2 is the integration PR into `main`. PR #5 (README) is standalone. | Owner action |
| Q3 | **Signing secrets.** Releases must be signed with the old app's key (SHA-256 `F3:BA:9F:48:…:8F:A7`) or old installs can't update. This session can't see repository secrets. Add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to this repository (the values the old repository used). The release workflow refuses to publish without them and checks the certificate. | Owner action |
| Q4 | **Data migration from the old app.** Provider keys, conversations and settings of old installs are not carried into the new app (left untouched on disk). Accessibility and notification-access grants are tied to class names and must be re-enabled after updating. Should a one-time importer be built for old provider keys and conversations? | Owner decision |
| Q5 | **Licensing of upstream-derived parts of the old app.** The old app documents its agent foundation as a fork of an upstream app (GPL-3.0) and its bubble design as adapted from another (Apache-2.0). The new app reuses neither: it is new code, and its bubble uses only the owner's emblem. Old-app provider logos came from Lobe Icons (MIT); if the new app should show brand logos, they would be taken from the Lobe Icons project itself with attribution. OK? | Owner decision |
| Q6 | **Defaults.** The old code's clean install lands on OpenAI's first model; the old screenshots show DeepSeek Flash; the new app pre-selects DeepSeek Flash. The old app also ships one default character with a Chinese name. Keep DeepSeek Flash, and should the new app ship a default character? | Owner decision |
| Q7 | **Cross-session messages.** The session that relayed the owner's updates runs on the owner's computer; this cloud session may not message it without the owner's in-person approval, so no reply was sent. | Info |
