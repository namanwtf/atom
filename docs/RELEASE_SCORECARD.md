# ATOM 4.0.7 release scorecard

Written for the owner, in plain words. Everything below ran on GitHub's test machines (an Android phone emulator
plus a small AI model running on the same machine), never on a personal computer. Links go to the runs.

## What 4.0.7 brings

Two fixes for bugs the owner found on a real phone with 4.0.6. (#39)

- **"Hey Atom" loads again.** Speech settings showed "Wake-word model failed to load: ... Node number 2 (CONV_2D)
  failed to prepare." The melspectrogram model's input is stored in the file as one sample, and LiteRT's interpreter
  prepares the model at that stored size as soon as it is opened, before ATOM could give it the real size (1,760
  samples). Its first convolution needs a 512-sample window, so it could not be prepared. ATOM now writes the real
  input size into its in-memory copy of the model before opening it (two bytes; the file on disk and the computation
  are unchanged, and the output matches the resized original exactly). A load error now names the model file. The
  end-to-end "Hey Atom" task had been failing with this same message since 4.0.5; it was this bug, not an emulator
  limit.
- **Tiny offline models answer cleanly.** Qwen3 0.6B answered "set an alarm for 8:00" with stray `</think>` tags and
  fragments of ATOM's own prompt. Two causes:
  - The model writes `</think>` again after its chat template has already closed the thinking block, and the
    LiteRT-LM engine passed it on as text. The answer text of both on-device engines now goes through the think-tag
    filter, which drops a closing tag with nothing open and a second opening tag.
  - The full agent prompt (about 2,100 of its 4,096 tokens: instructions, memory and skills notes, 17 core tools and
    the tool catalog) overwhelmed a 0.6B model. A model under 1.5B parameters now gets a short prompt of its own
    (about 370 tokens), only the tools of the one-step group the request needs (alarms and timers, or quick
    settings), and at most 4 steps; plain chat gets no tools. A task it has no tools for says that a larger model is
    needed and suggests Gemma 4 E2B, and the on-device models screen says what a tiny model can do.

## Checks on the release branch

| Check | Result |
|---|---|
| Unit tests, debug and release builds, release-hygiene checks (CI) | pass on e5e0194 ([run 46045568569](https://github.com/namanwtf/atom/actions/runs/46045568569)); only the last step, Release readiness, held the pull request until this scorecard |
| On-device model workflow | pass ([run 46045568443](https://github.com/namanwtf/atom/actions/runs/46045568443)): the new wake-word job loads all three "Hey Atom" models with LiteRT 2.4.0, with and without XNNPack, and runs each once on silence; it also reproduces the 4.0.6 error with the old loading. Qwen3 0.6B on the runner: 4.0.6's prompt never called `set_alarm` and wrote a made-up result; the new prompt called `set_alarm` (hour 8, minute 0) and answered "Set your alarm for 8:00". Plain chat was clean, and a task without tools got "A larger model can." |
| Side-by-side comparison | pass ([run 46045568505](https://github.com/namanwtf/atom/actions/runs/46045568505)) |
| Screenshots, dark and light | pass ([run 46045568481](https://github.com/namanwtf/atom/actions/runs/46045568481)); the first try on each commit timed out launching the app ("no app thread"), the same flake seen on release/next-2, and the reruns passed |
| End-to-end tasks | 14 of 17 on the final code ([run 46045568458](https://github.com/namanwtf/atom/actions/runs/46045568458)), including the new "Hey Atom" model check (q) and "Hey Atom" pause and listen (k), which both passed in both runs |

## End-to-end tasks that did not pass

| | Task | Why |
|---|---|---|
| a | Write and run a small script in the terminal | emulator limit, as in 4.0.5 and 4.0.6: the Linux install does not finish on this emulator |
| h | Floating bubble mini chat | the Send tap missed on the emulator; it also failed once on 4.0.6. Nothing in 4.0.7 touches the bubble |
| n | Screen turned off while operating Settings | the 3B test model finished the task before the screen was turned off, so there was no wait to check |

These runs use the cloud-style test model (Qwen2.5 3B), so the tiny-model plan is not involved in them. Tasks e, b, o
and p failed only in the first run and passed in the second.

## What was not tested, and what an emulator cannot tell

- Real phones: smoothness, battery, heat. 4.0.7 adds no polling, timers, wake locks or services. "Hey Atom" now really
  runs (in 4.0.6 it failed and stopped, so it drew nothing); it runs at its designed cost, with the speech gate
  keeping the models idle in silence.
- "Hey Atom" triggering on a real microphone. The emulator checks that the models load and run.
- Qwen3 0.6B on a phone's GPU. The prompt was checked on the runner's CPU. Its answers vary from run to run, so a
  model this small can still sometimes set an alarm wrong.
- The stray `</think>` never appeared on the runner; its fix rests on the rendered chat template and unit tests with
  the phone's exact output.

## Verdict

Both fixes build, pass their unit tests and pass their new on-device and end-to-end checks; the "Hey Atom" task now
passes end to end for the first time since 4.0.5. The three end-to-end failures are an emulator limit, a tap that
missed in a part of the app this release does not change, and a task the test model finished too quickly to check.

Shipped at the owner's request: "Go".

Result: ready
