# Atom 4.0.4 release scorecard

Shipped at the owner's request on 2026-11-26, so they and a few friends can test it tonight. It went out after the
unit tests and the trace guards passed on the combined code, before the full side-by-side comparison finished.

## What's in

- The agent uses its own browser and a hidden background screen, not your browser (#23)
- Crash fixes: stopping a task mid-run, opening the Lumen character, root status that didn't refresh (#19)
- Frosted chat top bar, so messages never run into the title (#21)
- A light app (6.6 MB, was 19 MB). On-device AI is an optional download inside the app (#22)
- Battery: "Hey Atom" no longer wakes every few seconds while charging; the automation start-up hook is off
  while there are no automations; download progress notifications are no longer saved one by one (#22)
- 43 permissions instead of 54, each asked only when a feature first needs it (#24)
- Leaner requests: the context meter uses the model's real window, and tools are loaded only when needed (#25)
- End-to-end task tests with a local model, so no API keys are stored on GitHub (#26)
- Project clean-up (#27)
- The Atom emblem in notifications, on home, in the drawer and on setup, transparent in light and dark themes

## Checked before shipping

| Check | Result |
|---|---|
| Unit tests on the combined code | passed (CI run 45848405901) |
| Guards (unsafe deletes, other-app names, build-tool traces) | passed |
| Each part on its own: CI and emulator screenshots | passed on every PR above |

## Known issue

- On the emulator, after the optional on-device AI download was installed, one cold start of the app took more than
  60 seconds. Until it is fixed, don't install the on-device AI download. The fix is the next update.

## Still running

- The side-by-side with the previous Atom (tasks finished, tokens per message, time, crashes, size, idle battery)
  and the full end-to-end task set. The results go into the next update's scorecard.

Result: ready
