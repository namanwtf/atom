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
| Unit tests, debug and release builds, release-hygiene checks (CI) | pass on e0ea9b6 ([run 45908328599](https://github.com/namanwtf/atom/actions/runs/45908328599)) and on 226692c ([run 45903601682](https://github.com/namanwtf/atom/actions/runs/45903601682)); the last step, Release readiness, holds this pull request on purpose until the owner approves it |
| Screenshots, dark and light | pass on e0ea9b6 ([run 45908328522](https://github.com/namanwtf/atom/actions/runs/45908328522)) and on 226692c ([run 45903601538](https://github.com/namanwtf/atom/actions/runs/45903601538)): the website preview before and after an edit, the preview card in the chat, Settings > Sites, "Report a problem" from a failed run and from About, the routines, and the DeepSeek key screen opened from "· add key" |
| End-to-end tasks | 12 of 15 core tasks on the final code, 13 of 15 in the run before; every failure is an emulator limit (below) |

## End-to-end tasks (new app, small local model)

Two runs. [Run 45908328610](https://github.com/namanwtf/atom/actions/runs/45908328610) on e0ea9b6 (the final code;
later commits change only this scorecard): **12 of 15 core tasks passed, and the optional website task passed**. The
test machine was about half as fast as in the run before (13 instead of 25 words a second read), so tasks took longer.
[Run 45903601569](https://github.com/namanwtf/atom/actions/runs/45903601569) on 226692c: **13 of 15 core tasks
passed**; the website task made the site, but the test's own check of the preview was wrong (fixed since).

| | Task | Final code | Run before | Notes |
|---|---|---|---|---|
| g | Open the Lumen character | pass | pass | |
| c | Turn on Do Not Disturb | pass | pass | used the Do Not Disturb tool; it was on afterwards |
| d | What was my last notification about? | pass | pass | "Dinner, Pizza at 8" |
| e | Remind me every day at 9am to drink water | pass | pass | a daily task was created |
| b | Summarize the top story on example.com in the agent browser | pass | pass | the page loaded without an error |
| o | Read a web page in the agent browser | pass | pass | |
| f | Stop a task halfway | fail (crash log only) | pass | stopped, history kept, app alive both times. In the final run the crash log held a crash of the Linux installer's PRoot process inside the emulator's ARM translator, the same emulator limit that breaks the terminal task (a); Atom itself did not crash |
| h | Floating bubble mini chat | **pass** | **pass** | it never passed before 4.0.6. Final run: keyboard closed first, one tap on Send, and the bubble's conversation got an answer |
| i | Automation start-up switch on while a task exists | pass | pass | |
| j | Normal notifications still saved | pass | pass | |
| m | Screen turned off during a command task | pass | pass | finished with the screen off |
| n | Screen turned off while operating Settings | pass | pass | waited, then finished after unlocking |
| l | After a reboot: start-up switch, boot start, alarm | pass | pass | |
| a | Write and run a small script in the terminal | fail | fail | emulator limit, as in 4.0.5: the Linux install never finishes on this emulator, so the model tried Android's shell, which has no Python |
| k | "Hey Atom" pauses on battery, listens when charging | fail | fail | emulator limit, as in 4.0.5: the wake-word model does not load on the emulator's CPU |
| p | Make a one-page site that says hello (optional) | **pass** | site made, check wrong | the model created the site, wrote an index.html that says hello and opened the live preview; the preview server served it with the reload script |

## What was not tested, and what an emulator cannot tell

- Real phones: smoothness, battery, heat, mobile data.
- DeepSeek and Kimi themselves: the thinking fix is checked against the exact requests Atom sends (unit tests), not
  against the live services, which need paid keys.
- The website preview on a real phone's WebView version, and sharing the .zip into other apps.
- "Report a problem" on phones whose system restricts reading the app's own log (some ROMs): the report then says the
  log is empty and why.

## Verdict

The new features build, pass their unit tests and show correctly on screen in both themes. The end-to-end tasks pass
12 to 13 of 15; every failure is an emulator limit already known from 4.0.5 (the terminal's Linux install and "Hey
Atom"; in the final run the same Linux crash also landed in the stop task's crash log). The floating bubble, the one
task 4.0.5 left open, passes in both runs, and a small local model built a working website end to end.

This release has not been approved. It waits for the owner's review.

Result: awaiting owner review
