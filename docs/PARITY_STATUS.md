# Parity status log

Running log for the work of bringing this app to the old app's look and behaviour while keeping the new extras. The checklist itself is `docs/PARITY_SPEC.md`. This file records decisions taken while the owner was away, and the questions only the owner can answer.

## Process

- All building, testing and screenshots happen on GitHub's runners or in a disposable cloud VM. Nothing runs on a personal machine.
- The cloud VM cannot reach Google's Android SDK host, so Android builds, unit tests and emulator screenshots run in GitHub Actions. The `Screenshots` workflow prints each screenshot into the job log (base64) with a text dump of the screen; `tools/screenshots/extract.py` restores the images.
- Branches: `fix/ci-temp-delete-guard` (PR #1, CI fix) → `parity` (integration branch, starts at the CI fix) ← one PR per batch. One PR from `parity` into `main` is kept up to date for the owner to merge. Nothing is merged into `main` by an agent.
- Clean-room: the old app is studied through its docs, screenshots, string resources and behaviour. Agents that read old source write behaviour and measurements only, in their own words; the code here is new.

## Decisions taken on the owner's behalf

| # | Decision | Why |
|---|---|---|
| D1 | Android builds and screenshots run in GitHub Actions, not in the VM. | The VM's network blocks `dl.google.com`, so no Android SDK can be installed there. |
| D2 | Screenshots are printed into job logs as base64. | Workflow artifacts are served from a storage host the VM cannot reach; job logs are reachable. |
| D3 | CI fails on references to other apps (word-boundary, case-insensitive). The only allowed hit is the Greek letter in the LaTeX symbol table. | Owner asked that Atom is presented on its own. |
| D4 | Batches are PRs into `parity`, not straight into `main`. | Owner asked for an integration branch with review history. |

## Waiting for the owner

| # | Item | Status |
|---|---|---|
| Q1 | **Commit identity.** The owner asked for today's commits to come from their GitHub account and for today's non-main commits to be re-authored and force-pushed. The session's permission checks refused the identity change and the force-push, so commits are still made under the session's default identity, and `fix/ci-temp-delete-guard` keeps its original author. The owner can approve this, or re-author locally with `git rebase --exec 'git commit --amend --no-edit --reset-author'`. | Blocked: needs owner approval |
| Q2 | **Merging.** PR #1 (CI fix) is green and ready. `parity` → `main` is for the owner to merge. | Owner action |
