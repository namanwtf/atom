# Atom 4.0.6 release scorecard

Written for the owner, in plain words. Everything below ran on GitHub's test machines (an Android phone emulator
plus a small AI model running on the same machine), never on a personal computer. Links go to the runs.

## What 4.0.6 brings

- **Make me a website.** "Make me a small website that tracks my water intake": Atom writes the page (HTML, CSS and
  JavaScript) and opens a live preview that reloads by itself when Atom changes a file. Each site has its own private
  address that only Atom's own screens can open; no other app on the phone can reach its files, and nothing listens
  on the phone's network. The preview can be reopened from a card in the chat or from Settings > Tools > Sites, opened in
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
  now taken from the saved conversation and counts against the context, so a long conversation still fits. While a
  model really thinks, an old step saved without its reasoning is sent as a plain note; models that do not think
  (deepseek-chat, Kimi K2 Instruct, Qwen Instruct) keep their tool steps exactly as in 4.0.5, and a new thinking model
  nothing knows yet still gets its reasoning back.
- **"Allow all commands for this task" works.** In 4.0.5 every terminal and shell command asked again and showed raw
  "terminal_run({...})" text: a wrapper that every tool passes through dropped the command's own prompt. Prompts now
  show the whole command ("Run in the Linux terminal: ...") and offer the button, and one tap covers the rest of the
  task (plainly destructive commands still ask).
- **The bubble's hold menu is closer.** Its three buttons sit 28dp from the bubble's edge instead of about 76dp.
- **The launcher icon has new resource names** (atom_launcher), so a phone that cached the old app's icon stops
  showing it. (The commit that renamed it also added density bitmaps; they were never used, because the adaptive icon
  always wins on Android 11 and newer, and a later commit removed them.)
- **Smaller fixes from review.** Website tools that write or delete files count as file tools, so a scheduled task
  limited to the web cannot change files; the problem report also removes web search, image, speech and MCP keys;
  "make a game plan" or "a page in Notion" no longer loads the website tools; only the user's own sites report page
  errors to the agent; a scheduled task never opens the preview on screen.
- **Tests.** The end-to-end tests warm up the model once, run every task on the same device clock, check results more
  strictly (the script really printed 5, the page really loaded), stop tasks that run out of time, and still print
  their results if the emulator dies. The floating bubble test now taps Send where it really is (it used to hit the
  keyboard's Enter key).

## Checks on the release branch

| Check | Result |
|---|---|
| Unit tests, debug and release builds, release-hygiene checks (CI) | pass on 07d5563, the final code ([run 45937912893](https://github.com/namanwtf/atom/actions/runs/45937912893)); the last step, Release readiness, holds this pull request on purpose until the owner approves it |
| Screenshots, dark and light | pass on 07d5563 ([run 45937912882](https://github.com/namanwtf/atom/actions/runs/45937912882)): the website preview at its new address before and after an edit, the preview card in the chat, Settings > Sites, "Report a problem", the routines, the key screen from "· add key", and the closer hold menu on both sides of the screen |
| End-to-end tasks | 12 of 15 core tasks on the final code, and the optional website task; every failure is an emulator limit or the small test model's wording (below) |

## End-to-end tasks (new app, small local model)

[Run 45937912909](https://github.com/namanwtf/atom/actions/runs/45937912909) on 07d5563, the final code (later commits
change only this scorecard): **12 of 15 core tasks passed, and the optional website task passed**. The run before it,
[45931347563](https://github.com/namanwtf/atom/actions/runs/45931347563) on 9a40e71 (before the last review round),
also passed 12 of 15 core tasks and the website task.

| | Task | Final code | Run before | Notes |
|---|---|---|---|---|
| g | Open the Lumen character | pass | pass | |
| c | Turn on Do Not Disturb | pass | pass | |
| d | What was my last notification about? | fail | pass | the small model asked the notification tool for an app named "unknown" and then said it found nothing; the tool itself worked. Model wording, not Atom |
| e | Remind me every day at 9am to drink water | pass | pass | |
| b | Summarize the top story on example.com in the agent browser | pass | pass | |
| o | Read a web page in the agent browser | pass | pass | |
| f | Stop a task halfway | pass | pass | |
| h | Floating bubble mini chat | pass | pass | |
| i | Automation start-up switch on while a task exists | pass | pass | |
| j | Normal notifications still saved | pass | pass | |
| m | Screen turned off during a command task | pass | pass | new check, passed both times: the command's approval showed "Run in Android's shell: ..." and offered "Allow all commands for this task" |
| n | Screen turned off while operating Settings | pass | fail (crash log only) | in the run before, the crash log held a crash of the Linux installer's PRoot process inside the emulator's ARM translator; Atom itself did not crash |
| l | After a reboot: start-up switch, boot start, alarm | pass | pass | |
| a | Write and run a small script in the terminal | fail | fail | emulator limit, as in 4.0.5: the Linux install does not finish on this emulator (and its PRoot process crashed in the ARM translator). The approval check passed: "Run in the Linux terminal: ..." with "Allow all commands for this task" |
| k | "Hey Atom" pauses on battery, listens when charging | fail | fail | emulator limit, as in 4.0.5: the wake-word model does not load on the emulator's CPU |
| p | Make a one-page site that says hello (optional) | **pass** | **pass** | the model made the site; site_preview gave its own address (https://hello-site-rf2gbh.invalid/), which matched the site's stored tag, and Atom's preview screen showed that address and the page saying hello. Nothing listens on a port any more, so the check goes through the app and the files |

## What was not tested, and what an emulator cannot tell

- Real phones: smoothness, battery, heat, mobile data.
- DeepSeek and Kimi themselves: the thinking fix is checked against the exact requests Atom sends (unit tests), not
  against the live services, which need paid keys.
- The website preview on a real phone's WebView version, and sharing the .zip into other apps. The new private
  address was checked on the emulator's WebView (screenshots and the website task).
- "Report a problem" on phones whose system restricts reading the app's own log (some ROMs): the report then says the
  log is empty and why.

## Verdict

The new features build, pass their unit tests and show correctly on screen in both themes. On the final code the
end-to-end tasks pass 12 of 15 and the website task passes; the failures are the two emulator limits known from 4.0.5
(the terminal's Linux install and "Hey Atom") and one answer worded badly by the small test model. The review fixes
are in: websites no longer listen on a port, "Allow all commands for this task" works again for terminal and shell
commands (now checked end to end), thinking models keep their reasoning where the vendors need it, and the bubble's
menu sits next to it.

This release has not been approved. It waits for the owner's review.

Result: awaiting owner review
