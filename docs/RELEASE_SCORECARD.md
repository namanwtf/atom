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

## Checks on the release branch

| Check | Result |
|---|---|
| Unit tests, debug and release builds, release-hygiene checks (CI) | pass ([run 45903601682](https://github.com/namanwtf/atom/actions/runs/45903601682)); its last step, Release readiness, holds this pull request on purpose until the owner approves it |
| Screenshots, dark and light | pass ([run 45903601538](https://github.com/namanwtf/atom/actions/runs/45903601538)): the website preview before and after an edit, the preview card in the chat, Settings > Sites, "Report a problem" from a failed run and from About, the routines, and the DeepSeek key screen opened from "· add key" |
| End-to-end tasks | 13 of 15 core tasks passed (below) |

## End-to-end tasks (new app, small local model)

[Run 45903601569](https://github.com/namanwtf/atom/actions/runs/45903601569) on 226692c: **13 of 15 core tasks passed**.
The warm-up read Atom's first request (4,294 words) in 94 seconds; the tasks after it reused it.

| | Task | Result | Notes |
|---|---|---|---|
| g | Open the Lumen character | pass | |
| c | Turn on Do Not Disturb | pass | used the Do Not Disturb tool; it was on afterwards |
| d | What was my last notification about? | pass | "Dinner, Pizza at 8" |
| e | Remind me every day at 9am to drink water | pass | a daily task was created |
| b | Summarize the top story on example.com in the agent browser | pass | the page loaded without an error |
| o | Read a web page in the agent browser | pass | |
| f | Stop a task halfway | pass | ended as stopped, history kept |
| h | Floating bubble mini chat | **pass, for the first time** | the first tap still hit the keyboard (the panel's reported position lagged); with the keyboard closed, Send was found again and the bubble's conversation got an answer |
| i | Automation start-up switch on while a task exists | pass | |
| j | Normal notifications still saved | pass | |
| m | Screen turned off during a command task | pass | finished with the screen off |
| n | Screen turned off while operating Settings | pass | waited, then finished after unlocking |
| l | After a reboot: start-up switch, boot start, alarm | pass | |
| a | Write and run a small script in the terminal | fail | emulator limit, as in 4.0.5: the Linux install never finishes on this emulator, so the model tried Android's shell, which has no Python |
| k | "Hey Atom" pauses on battery, listens when charging | fail | emulator limit, as in 4.0.5: the wake-word model does not load on the emulator's CPU |
| p | Make a one-page site that says hello (optional) | site made, check failed | the model created the site, wrote an index.html that says hello and opened the preview; the test's own request to the preview server was refused because it named the wrong address, which is the server's protection working. The test is fixed on the branch |

After this run the bubble test closes the keyboard before its first tap, so it no longer types a stray line break,
and the website test names the right address. They are run again on the final code.

## What was not tested, and what an emulator cannot tell

- Real phones: smoothness, battery, heat, mobile data.
- DeepSeek and Kimi themselves: the thinking fix is checked against the exact requests Atom sends (unit tests), not
  against the live services, which need paid keys.
- The website preview on a real phone's WebView version, and sharing the .zip into other apps.
- "Report a problem" on phones whose system restricts reading the app's own log (some ROMs): the report then says the
  log is empty and why.

## Verdict

The new features build, pass their unit tests and show correctly on screen in both themes. The end-to-end tasks pass
13 of 15; the two failures are the same emulator limits as in 4.0.5 (terminal, "Hey Atom"), and the floating bubble,
the one task 4.0.5 left open, passes now. The website task did its job; only its check needed fixing.

This release has not been approved. It waits for the owner's review.

Result: awaiting owner review
