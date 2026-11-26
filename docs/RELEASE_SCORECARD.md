# Atom 4.0.5 release scorecard

Written for the owner, in plain words. Everything below ran on GitHub's test machines (an Android phone emulator
plus a small AI model running on the same machine), never on a personal computer. Links go to the runs.

## What 4.0.5 brings

- **Side menu fixed.** In 4.0.4 the side menu's frosted glass switched on only after the menu stopped moving, so it
  jumped and flashed when opening and closing. Now the menu is frosted from the first frame to the last, opening and
  closing, with one glass style shared by the menu, the chat's top bar and the typing area. Phones without the blur
  (older Android, battery saver, Blur turned off) get the same colours as a see-through tint.
- **One honest model label everywhere.** On a fresh install, Settings said "DeepSeek Flash" while home said "Not
  selected". Home, the side menu, Settings and the floating assistant now all say the same thing, for example
  "DeepSeek Flash · add key" or "Not set".
- **Screen turning off during a task.** A task that does not need the screen (a command, a web search) keeps going
  with the screen off. A task that operates apps now waits, shows "Waiting for the screen to be unlocked" in its
  notification and on its capsule, and carries on by itself once you unlock. Before, it kept tapping a locked screen.
- **On-device models (the optional download).** Fixes for small offline models such as Qwen3 0.6B writing garbage:
  the app now plans for the model's real memory size, keeps each step within budget, gives small models a shorter
  tool list, shows their "thinking" separately, stops made-up tool results and runaway output, and warns that tiny
  models are weak at phone tasks.
- **Tasks finish properly.** Long tasks are no longer cut off at 30 steps; a run that stops early always ends with a
  written reply. The right tools are loaded for each request, so small models find them. Shell commands that could
  change things ask first, with "Allow all commands for this task" for long jobs. Built-in web search falls back to
  other search pages before giving up, and the agent browser no longer crashes from background threads.
- **Release safety.** Every release pull request (not only the first one) now waits for this scorecard to be
  approved before it can merge.

## Old Atom (3.4.5) vs new Atom, same phone emulator, same AI model

| What was measured | Old Atom 3.4.5 | New Atom | Run |
|---|---|---|---|
| Download size (the app itself) | 9.2 MB | 7.2 MB (4.0.4 as published; 6.8 MB before signing) | [old vs new](https://github.com/namanwtf/atom/actions/runs/45853830245), [4.0.4 release](https://github.com/namanwtf/atom/actions/runs/45850482690) |
| Crashes and freezes seen | "Atom isn't responding" on its first start, in 3 of 3 runs | none during set-up in the same runs; none in the screens tour | [1](https://github.com/namanwtf/atom/actions/runs/45853830245), [2](https://github.com/namanwtf/atom/actions/runs/45859203081), [3](https://github.com/namanwtf/atom/actions/runs/45862967351) |
| Tasks done correctly | not measured yet | not measured yet | see below |
| AI words ("tokens") sent for a plain "hi" | not measured yet | not measured yet | see below |
| Time per task | not measured yet | not measured yet | see below |
| Idle battery (10 minutes, screen off) | not measured (the counting script read the wrong field; fixed) | not measured (same) | [run](https://github.com/namanwtf/atom/actions/runs/45853830245) |

Why several lines are still empty: both apps have to be set up through their own settings screens to talk to the
test model. Tonight the old app froze on its first start ("isn't responding"), and the emulator stopped answering
while the test was scrolling the old app's provider form. The emulator's graphics crash on blur effects, which the
old app uses too. The test now waits out the freeze, scrolls differently and draws with a sturdier graphics mode;
the next comparison run fills these lines. Nothing in this table is estimated.

## End-to-end tasks (new app, debug build, small local model)

| | Task | Result | Notes |
|---|---|---|---|
| g | Open the Lumen character | pass | no crash (this crashed before 4.0.4) |
| f | Stop a task halfway | pass | ended as stopped, history kept, app alive |
| j | Normal notifications still saved after the battery fix | pass | "Parcel" and "Dinner" both in the history |
| n | Screen off while operating Settings | passes every check that matters | 0 actions while the screen was off, the notification said "Waiting for the screen to be unlocked", it finished after unlocking (the test counted "Done" as unfinished; fixed) |
| m | Screen off during a command task | pass in substance | the command's output came back with the screen off, the run finished, and the wake lock and foreground service were on during it (the test counted "Done" as unfinished; fixed) |
| c | Turn on Do Not Disturb | pass (with the new tool routing) | used the Do Not Disturb tool; Do Not Disturb was on afterwards |
| d | What was my last notification about? | pass (with the new tool routing) | "Your last notification was about dinner plans... Pizza at 8" |
| e | Remind me every day at 9am to drink water | pass (with the new tool routing) | a daily 09:00 scheduled task was created |
| o | Read a web page in the agent browser | pass | the page text reached the model and the answer summarized it; no browser threading errors |
| b | Summarize the "top story" on example.com in the agent browser | fail | opened the agent browser correctly but then tapped around a page that has no top story, and ran out of time |
| a | Write and run a small script in the terminal | fail | the Linux install never finishes on this emulator (see below), so the terminal was not ready |
| h | Floating bubble mini chat | fail | the mini chat did not open in the test |
| k | "Hey Atom" pauses on battery and listens when charging | could not test | the wake-word model does not load on this emulator (it runs the app through ARM translation) |
| i, l | Automation start-up switch, and after a reboot | not yet conclusive | no task existed because the model did not create one; the test now creates it directly |

Runs: [before tool routing](https://github.com/namanwtf/atom/actions/runs/45860990063) (Do Not Disturb, notification,
reminder and web failed: the small test model never loaded the right tools), [with tool routing](https://github.com/namanwtf/atom/actions/runs/45876766521)
(c, d, e, o now pass; that run was stopped after the terminal task by a newer push). The 4.0.4 run
([45849080166](https://github.com/namanwtf/atom/actions/runs/45849080166)) passed 1 of 8 because the model ran at
0.4 words per second next to the emulator and leftover tasks blocked later ones; both are fixed in the test.

## Battery-fix checks (from 4.0.4)

- Automation start-up switch: off while there is no automation (seen on the emulator). Code review: it turns on
  whenever a task or rule is saved or enabled, and Android remembers it across reboots. The emulator check with a
  real task is in the next run.
- Notification history: normal notifications are kept (test j passed); only progress-bar updates are skipped.
- "Hey Atom" while charging: code review only. It listens again on the plug-in signal and reads the battery state at
  that moment. The emulator cannot load the wake-word model, so this needs a real phone.

## Looks (screenshots, dark and light)

- Home and side menu logo: transparent, no black patch, in both themes.
- Chat top bar: frosted.
- Side menu: slowed-down frames of opening and closing, over the empty home and over a long chat, in both themes,
  show the frosted pane in every frame and no flash ([run](https://github.com/namanwtf/atom/actions/runs/45867496843)).
- Start-up with the on-device download installed: 2.7 to 4.5 seconds in every timed start across five runs. One
  earlier freeze happened while the emulator was translating the app from ARM; it did not come back.

## What was not tested, and what an emulator cannot tell

- Real battery drain, heat, mobile data and GPS: an emulator has no real battery or radio.
- Smoothness at 60/120 Hz on a real phone: the emulator draws in software. The frosted side menu blurs the chat on
  every frame of the slide; on older mid-range phones this may drop a few frames.
- On-device models on a phone's GPU/NPU, and the exact "#1 1 #111" garbage the owner saw: the fixes were checked on
  the test machine's CPU only, where that exact output was not reproduced.
- "Hey Atom" wake word and the Linux terminal: neither runs on this emulator (ARM translation; the terminal's PRoot
  process crashed inside the emulator's translator).
- Floating menus (the ⋮ menu, model picker, approval sheet) still use solid backgrounds, not glass. They are separate
  windows and need Android's window blur; that comes after 4.0.5.

## Found tonight

Bugs:
- Small models rarely load hidden tool groups. Since 4.0.4 most tools are loaded on demand, and the test model never
  asked for the "settings" or "messages" tools, so it tapped around instead (fixed in this release: the likely tool
  groups are now loaded for each request; Do Not Disturb, notification and reminder tasks pass since).
- A runaway loop: in one task the model saved the same memory note dozens of times, and nothing stopped it before the
  step limit (addressed in this release: a loop guard ends runs that repeat themselves without new information, and
  every run that stops early ends with a written reply).
- The model locked the screen by itself ("lock screen") in four different tasks. Idea: ask before locking.
- On a fresh install, home said "Not selected" while Settings showed a model (fixed in this release).
- The side menu flashed when opening and closing (fixed in this release).
- Release pull requests after the first could have merged without a scorecard (fixed in this release).

Feature ideas:
- A one-tap "add key" from the home model label when it says "· add key".
- Window blur for the floating menus, so every surface is the same glass.
- An x86 build of the Linux tool for emulators and Chromebooks, which would also make these tests faster.

## Verdict

Not decided yet: the old-vs-new task, token, time and battery lines still need the next comparison run. On what is
measured so far, the new app wins on size (7.2 MB vs 9.2 MB) and on crashes and freezes (none vs a freeze on every
first start).

Result: awaiting owner review
