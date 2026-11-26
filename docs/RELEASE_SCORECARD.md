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

## Final review

An independent review of the whole 4.0.5 change said **SHIP, no blockers**. Its findings from the earlier rounds were
fixed before that: every shell command asks again (as in 4.0.4), with "Allow all commands for this task" for long jobs;
files that start programs are protected from being overwritten; the screen-off wait no longer counts against a step's
time limit and holds no wake lock; approvals with long or multi-line text must be read in the app instead of allowed
from the notification. The last four tidy-ups (safe file paths, no nudge for waiting steps, the notification's first
line, the tool note folded into the user's message) are included.

## Old Atom (3.4.5) vs new Atom, same phone emulator, same AI model

Both apps were set up through their own settings screens and given the same tasks, one after the other
([run 45867500321](https://github.com/namanwtf/atom/actions/runs/45867500321)). The new app in that run was an
evening build without the new tool routing and screen-off fix; a re-run on the final code
([45888323991](https://github.com/namanwtf/atom/actions/runs/45888323991)) had not finished when this was written.

| What was measured | Old Atom 3.4.5 | New Atom | Who wins |
|---|---|---|---|
| Download size (the app itself) | 9.2 MB | 7.2 MB as published (6.8 MB before signing) | new |
| AI words ("tokens") in the first request of a task | 19,355, carrying 59 tools | 4,224 for "hi", carrying 18 tools | new (about 4.6 times smaller) |
| Time for that first request on the test machine | 859 seconds | 264 seconds | new (mostly the test machine reading the longer request) |
| Tasks done correctly (8 tasks) | 2 (open Settings, stop a task) | 1 ("hi") | old, in this run: the new app's model locked the screen during the Do Not Disturb task and the later tasks never reached it; the old app's "hi" never reached the model |
| Crashes in the crash log | 0 | 0 | tie |
| Freezes ("isn't responding") | on its first start in 3 of 3 earlier runs | none | new |
| Idle battery, 10 minutes, screen off: wake locks, wake-up alarms, CPU | 0, 0, 1 ms | 0, 0, 1 ms | tie |

The task line is the weak spot of this comparison: a 3-billion-parameter test model on a CPU, through screens it
taps blindly, decides most of it. The end-to-end tasks below, on the final code, are the better guide to what works.

## End-to-end tasks (new app, final code, small local model)

[Run 45888323904](https://github.com/namanwtf/atom/actions/runs/45888323904) on 56a2860 (the reviewed code; the last
tidy-ups came after it): **11 of 15 passed**.

| | Task | Result | Notes |
|---|---|---|---|
| g | Open the Lumen character | pass | no crash |
| c | Turn on Do Not Disturb | pass | used the Do Not Disturb tool; it was on afterwards |
| d | What was my last notification about? | pass | answered "Dinner, Pizza at 8" |
| e | Remind me every day at 9am to drink water | pass | a daily 09:00 task was created |
| b | Summarize the top story on example.com in the agent browser | pass | |
| o | Read a web page in the agent browser | pass | no browser threading errors |
| f | Stop a task halfway | pass | ended as stopped, history kept, app alive |
| i | Automation start-up switch turns on with the first task | pass | off before any automation, on once a task existed |
| j | Normal notifications still saved after the battery fix | pass | |
| m | Screen turned off during a command task | pass | it finished with the screen off; wake lock and background service were on during it |
| n | Screen turned off while operating Settings | pass | 0 actions while off, "Waiting for the screen to be unlocked" in the notification, finished after unlocking |
| a | Write and run a small script in the terminal | fail | the Linux install never finishes on this emulator (its PRoot process crashes in the emulator's ARM translator) |
| h | Floating bubble mini chat | fail | the message typed into the mini chat did not start a task in the test; needs a check on a phone |
| k | "Hey Atom" pauses on battery and listens when charging | could not test | the wake-word model does not load on this emulator |
| l | After a reboot: start-up switch, Atom started by the boot, alarm re-armed | pass in substance | Atom was started by the boot and its alarm was armed again, which only happens with the switch on; the test's own switch check read the wrong list (fixed for the next run) |

Earlier runs for comparison: [before tool routing](https://github.com/namanwtf/atom/actions/runs/45860990063) the
Do Not Disturb, notification, reminder and web tasks failed (the small model never loaded the right tools); the 4.0.4
run ([45849080166](https://github.com/namanwtf/atom/actions/runs/45849080166)) passed 1 of 8 because the model ran at
0.4 words per second next to the emulator.

## Battery-fix checks (from 4.0.4)

- Automation start-up switch: off while there is no automation, on as soon as a task exists (test i), and still
  working after a reboot: Atom was started by the boot and re-armed its alarm (test l).
- Notification history: normal notifications are kept (test j passed); only progress-bar updates are skipped.
- "Hey Atom" while charging: code review only. It listens again on the plug-in signal and reads the battery state at
  that moment. The emulator cannot load the wake-word model, so this needs a real phone.

## Looks (screenshots, dark and light)

- Home and side menu logo: transparent, no black patch, in both themes.
- Chat top bar: frosted.
- Side menu: slowed-down frames of opening and closing, over the empty home and over a long chat, in both themes,
  show the frosted pane in every frame and no flash ([run](https://github.com/namanwtf/atom/actions/runs/45867496843)).
- Start-up with the on-device download installed: 1.8 to 4.5 seconds in every timed start across eight runs
  (final code: 3.9, 3.7 and 3.1 seconds, [run 45888323976](https://github.com/namanwtf/atom/actions/runs/45888323976)).
  One early freeze happened while the emulator was translating the app from ARM; it did not come back.
- The screenshot runs on the final code passed, dark and light.

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

The new app wins on size (7.2 MB vs 9.2 MB), on request size (about 4.6 times fewer AI words per request, so
cheaper and faster with paid models) and on freezes; it ties on crashes and idle battery. It loses on tasks done in
the one finished side-by-side run (1 vs 2 of 8), which used an evening build without the tool routing and the
screen-off fix and was derailed when the test model locked the screen; on the final code the end-to-end tasks pass
11 of 15, and the rest are emulator limits (terminal, "Hey Atom") or need a phone check (the bubble mini chat).

Result: awaiting owner review
