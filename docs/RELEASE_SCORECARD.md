# Atom 4.0.6 release scorecard

Written for the owner, in plain words. Everything below ran on GitHub's test machines (an Android phone emulator
plus a small AI model running on the same machine), never on a personal computer. Links go to the runs.

## What 4.0.6 brings

- **Make me a website.** "Make me a small website that tracks my water intake": Atom writes the page (HTML, CSS and
  JavaScript), serves it on the phone only (127.0.0.1) and opens a live preview that reloads by itself when Atom
  changes a file. The preview can be reopened from a card in the chat or from Settings > Tools > Sites, opened in
  Atom's browser and shared as a .zip. It needs no Linux and no internet. (#32)
- **Report a problem.** Settings > About > Report a problem, and a small "Report a problem" under a failed run in the
  chat. It builds one text file on the phone (versions, device, the last run's steps without their contents, recent
  crashes and freezes, Atom's own log lines) with keys, tokens and personal data removed. You read it first and share
  it yourself; nothing is sent automatically. (#31)
- **Ready-made routines.** On the Scheduled tasks screen: Morning brief, Price watch, Weekly phone cleanup (report
  only) and Evening wrap-up. Each opens the usual editor filled in; you only tap Save. (#31)
- **"· add key" is one tap.** When home says "DeepSeek Flash · add key", tapping it opens DeepSeek's key screen
  directly instead of the model list. Back returns to the chat.
- **Thinking models after a restart.** DeepSeek and Kimi in thinking mode refused to continue a conversation with
  earlier tool steps once the app had been restarted, because the model's reasoning was kept in memory only. It is
  now taken from the saved conversation; an old step saved without it is sent as a plain note instead.
- **Tests.** The end-to-end tests warm up the model once, run every task on the same device clock, check results more
  strictly (the script really printed 5, the page really loaded), stop tasks that run out of time, and still print
  their results if the emulator dies. The floating bubble test now taps Send where it really is (it used to hit the
  keyboard's Enter key).

## Checks on the final code

To be filled in from the runs on the release pull request.

| Check | Result |
|---|---|
| Unit tests, debug and release builds (CI) | pending |
| Screenshots, dark and light | pending |
| End-to-end tasks | pending |

## End-to-end tasks (new app, final code, small local model)

Pending.

## What was not tested, and what an emulator cannot tell

- Real phones: smoothness, battery, heat, mobile data.
- DeepSeek and Kimi themselves: the thinking fix is checked against the exact requests Atom sends (unit tests), not
  against the live services, which need paid keys.
- The website preview on a real phone's WebView version, and sharing the .zip into other apps.
- "Report a problem" on phones whose system restricts reading the app's own log (some ROMs): the report then says the
  log is empty and why.

## Verdict

Pending the runs above and the owner's review. This release has not been approved.

Result: awaiting owner review
