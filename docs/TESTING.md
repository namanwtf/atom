# Testing

Atom is built and tested only on GitHub's disposable runners, never on a personal machine (see the note at the top of
`.github/workflows/ci.yml`).

| Workflow | What it does | When |
|---|---|---|
| **CI** (`ci.yml`) | Unit tests, debug and release builds, the release-hygiene checks | Every push to main and every pull request |
| **Screenshots** (`screenshots.yml`) | Walks the debug build's screens on an emulator and prints each screenshot into the log | Pull requests that touch UI modules |
| **E2E** (`e2e.yml`) | Real agent tasks on an emulator, end to end | On demand, weekly, and on labelled or release pull requests |
| **Compare** (`compare.yml`) | The previous Atom (latest 3.4.x) and the new one, side by side on one emulator and model | On demand, and on the release pull request |
| **On-device model** (`ondevice-model.yml`) | The catalog's Qwen3 0.6B (`.litertlm`) on the runner's CPU with the app's LiteRT-LM version, and the "Hey Atom" models with the pack's LiteRT version | On demand, and on pull requests that touch the LiteRT-LM engine, the on-device AI pack or the probes |

## End-to-end tasks

The end-to-end tasks run the real app on an Android emulator and give it real tasks, the way a person would: "turn on
Do Not Disturb", "what was my last notification about?". The agent loop, tool selection, approvals, the tools
themselves and the phone's resulting state are all exercised. Only the model is a stand-in.

### No keys, no secrets

The model runs on the GitHub runner itself, so nothing leaves the runner and no API key or repository secret is
involved:

- **Server:** a pinned [llama.cpp](https://github.com/ggml-org/llama.cpp) release (`llama-server`, Linux x64, CPU), checked
  against a pinned SHA-256. It serves an OpenAI-compatible API on port 8080 with `--jinja`, so tool calls are parsed
  from the model's own chat template.
- **Model:** Qwen2.5 3B Instruct, Q4_K_M GGUF (about 2 GB), from Hugging Face at a pinned revision, checked against a
  pinned SHA-256 and cached between runs. It was chosen because it fits a 4-vCPU / 16 GB runner, is quick enough on the
  CPU, and has native tool calling. Pins live at the top of `e2e.yml`; a mismatch fails the job early and prints the
  values a new pin needs.
- **The app:** the emulator reaches the runner as `10.0.2.2`. A debug-only hook (below) adds a provider named "Local
  test model" (`http://10.0.2.2:8080/v1`, no key, the server's model id and its real context window), selects it and
  finishes onboarding, so nothing has to be typed into the setup screens.

### Running them

- **Actions → E2E → Run workflow**, on any branch.
- Or add the label **`e2e`** to a pull request: it runs then and on every push after. Pull requests into a `release/*`
  branch run it without the label. Ordinary pull requests skip it, so their checks stay fast.
- It also runs every Monday on main.

A run takes about an hour: roughly 15 minutes to build, then the scenarios. A first request carries Atom's whole system
prompt and core tool schemas (about 4,000 tokens), which the runner's CPU, shared with the emulator, needs minutes to
read. So the runner reads it once, in a warm-up task (scenario 0, not scored) with the app in the background, and the
scenarios after it reuse the server's cached prompt. The system prompt carries no clock time (the time rides on the
latest user message), so that prefix is the same in every task.

Each task also starts on the same device clock: with `adb root` (google_apis emulator images allow it), the clock is
set to 09:00 today before every run, so the time a task sees is the same from run to run. Without root the clock runs
as usual and the log says so. The emulator has no lock screen (`locksettings set-disabled true`): an agent may lock the
phone, and the next task needs the screen. Before each task the runner wakes the screen, closes the shade, goes home and
makes sure Atom's accessibility service is connected (it turns it off and on again when it is not). A task that runs
past its time limit is stopped, so the single model slot is free for the next one, and the time limit counts as a
failed check. If the emulator itself stops answering, the remaining scenarios are marked "not run" and the scorecard is
still printed.

### Scenarios

Each scenario starts a fresh conversation the way a voice command does (MainActivity's `EXTRA_PROMPT`), waits for the
run to finish with a bounded timeout, and then checks tool calls and device state, never the model's exact words.
Confirmations the agent asks for are approved, as the person at the phone would.

| | Scenario | Passes when |
|---|---|---|
| a | Vibe coding: "In the terminal, create hello.py that prints the sum of 2 and 3, run it, and tell me the output." | Terminal or shell tools are called, one of their results shows the script printing 5, and the answer contains 5 |
| b | Agent browser: "Open the agent browser and summarize the top story on https://example.com." | `browser_open` is called (not `open_url`) and loads the page without an error, and there is an answer |
| o | Agent browser, end to end: "Use the agent browser to open https://example.com, read the page, and tell me in one sentence what it says." | Browser tools are called without WebView threading errors, `browser_open` succeeds, the page text reaches the model and the answer summarizes it |
| c | Phone control: "Turn on Do Not Disturb." | A Do Not Disturb tool is called and `settings get global zen_mode` is no longer 0 |
| d | Context: a notification "Dinner / Pizza at 8" is posted, then "What was my last notification about?" | A notification tool is called and the answer mentions Pizza or Dinner |
| e | Scheduling: "Every day at 9am remind me to drink water." | `scheduled_task_create` is called and a new scheduled task exists |
| f | Stop mid-task: a longer task, stopped from the running-task notification after its first tool call | The run ends as stopped, its partial history is saved and the app is alive |
| g | Characters: open the library and tap Lumen | No crash |
| h | Bubble: open the floating bubble, type into the mini chat and send | The message leaves the composer, the run comes from the bubble, and the bubble's conversation gets a reply |
| i | Battery fix: the boot / clock receiver, after e created a task | The receiver is enabled while an enabled task exists |
| j | Battery fix: an ordinary notification is posted | It is in the searchable notification history (only progress updates are skipped) |
| q | "Hey Atom" models: the melspectrogram, embedding and classifier models are loaded from the installed on-device AI pack the way a listening session loads them, and each runs once on silence | All three load (no tensor allocation error) and give finite outputs with a score between 0 and 1 |
| k | Battery fix: "Hey Atom" on (needs the on-device pack, which the job installs), screen off, then plugged in | Listening with the screen on, paused with the screen off on battery, listening again when plugged in |
| r | The "Hey ATOM" overlay over the Clock app (the Settings app hides other apps' overlays), driven through the debug hook's `hey_overlay` the way the wake-word service drives it (wake, voice level, partial words, final words, a miss, a cancel; no microphone) | The light and the panel windows appear, the app in front is read once and goes with the command, the live words show, the command runs in place as a Voice run (ATOM does not come to the front), the result stays open until the panel is closed (Back), then the overlay is gone; the conversation keeps only a note about the screen; a miss stays until closed; a cancel closes it |
| s | The "Hey ATOM" chat bar over the Clock app, driven through `hey_overlay` (wake, partial words, "type", keyboard input with `input text`, "send") | Typing stops listening and keeps the words heard so far in the field, the panel takes the keyboard only now, the keyboard types into the field, the typed command runs in place as a Voice run, and a follow-up typed under the result continues the same conversation |
| m | Screen off mid-task: a shell command task, screen turned off after its first step | It completes with the screen off; the task wake lock and foreground service are held during it and the wake lock is released after; the command's approval prompt shows the command ("Run in ...") and offers "Allow all commands for this task" (also checked in a) |
| n | Screen off mid-task: a task operating Settings, screen turned off after its first step | It neither fails nor spins (at most 2 tool calls while off), waits with "Waiting for the screen to be unlocked" in its notification without holding the task wake lock, and completes after unlocking |
| p | Make a website: "Make a one-page site that says hello." (optional) | Site tools are called, `files/sites/<site>/index.html` says hello, `site_preview` returns the site's own address (`https://<id>-<tag>.invalid/`, its tag matching the site's stored `.origin`), and the app's preview screen for that site is open, shows that address and shows the page saying hello. Nothing listens on a port, so the page is checked through the app and the files, not over the network |
| l | Battery fix: the emulator reboots | The receiver is still enabled and Atom's alarm is armed again |

Bubble send (h): the mini chat moves up for the on-screen keyboard, and the accessibility bounds of its nodes can still
be those from before the move, so a tap on Send landed on the keyboard's Enter key. The runner therefore closes the
keyboard first (the first Back closes only the keyboard; the panel stays), finds Send again from a fresh dump and taps
it on the composer field's row; if the message is still in the composer, it finds Send and taps once more. The log
shows the bounds each tap used.

All of them are core except the warm-up (0) and p: the job fails if any core scenario fails. p is marked optional because the 3B test
model may not manage a whole site; its result is still printed and shown as "FAIL (optional)" in the scorecard. Every scenario also fails on any entry in the crash buffer
(`adb logcat -b crash`).

Before the scenarios start, the runner grants what a person would grant on the Permissions page: Atom's accessibility
service, notification access, Do Not Disturb access, notifications, and later "display over other apps" for the
bubble. It also queues the Linux (Alpine) install that the terminal task needs.

### Reading the results

The **Run the tasks** step prints, for each scenario, every tool call with its arguments and result, each phase change,
the final answer and a screenshot at the end (plus one mid-run for long tasks). Screenshots are printed into the log as
base64 between `=====SHOT name=====` and `=====END=====`, like the Screenshots workflow; turn a downloaded log into
images with `python3 tools/screenshots/extract.py <log> <out-dir>`. They are also uploaded as the `e2e` artifact, with the model
server's log.

At the end comes the scorecard (also shown on the run's summary page):

```
  | Scenario                     | Result | Tool calls                 | Time | Model speed
--+------------------------------+--------+----------------------------+------+--------------------------------
c | Phone control: DND           | PASS   | set_do_not_disturb         | 212s | prefill 9120 tok @ 61 tok/s, ...
```

- **Result** is PASS only when every check passed; the notes under the table list each failed check (and the run's
  error or notice when there was one).
- **Tool calls** are the tools the agent called, in order.
- **Time** is the whole scenario, including waiting for the model.
- **Model speed** is what the server did during that scenario: prompt tokens read and generated tokens, with their
  rates. A slow first prefill is normal; a scenario with no prefill at all never reached the model.

A failure tells you where to look: no tool call means the tool was not offered or the model did not pick it (the log
lists how many tools were offered, and for the browser task which browser tools); a tool call with an `ERROR` result
points at the tool or a missing permission; a crash prints the crash buffer.

### The debug-only hook

The tasks drive the app through `atom.app.e2e.E2eProvider` in `app/src/debug`. That source set is compiled only into
debug builds; the release variant never contains it, and CI checks the release APK's manifest and dex for it on every
pull request. The provider answers only the adb shell and root users:

```
adb shell content call --uri content://io.github.namanwtf.atom.debug.e2e --method configure \
    --extra base_url:s:http://10.0.2.2:8080/v1 --extra model:s:<id> --extra context_window:i:<n>
adb shell content read --uri content://io.github.namanwtf.atom.debug.e2e/state   # JSON: runs, tool calls, answers
```

Other methods: `clear` (forget finished runs), `stop` (the same broadcast the notification's Stop button sends),
`approve` / `deny` (a pending confirmation), `linux_install` and `run_tool` (one of the app's tools, run directly the
way the agent would; the Screenshots workflow makes its demo website with it). Runner: `tools/e2e/run.py` (scenario keys can be passed
as arguments, e.g. `run.py c d`).

## On-device model (LiteRT-LM)

`ondevice-model.yml` installs the LiteRT-LM Python runtime at the version the app ships (`litertlm` in
`gradle/libs.versions.toml`, the `litert-lm` wheel on PyPI) and downloads the catalog's Qwen3 0.6B from the same URL,
checked against the same SHA-256, as the app's model downloader (`models/ModelCatalog.kt`). `tools/ondevice/litert_probe.py`
then prints:

- the model file's own metadata: its context size (`max_num_tokens`, 4096 for Qwen3 0.6B), default sampler, stop
  tokens, thinking channel and the Jinja chat template the runtime applies;
- how the chat tokens tokenize (`<|im_start|>`, `<|im_end|>`, `<think>` must each be one special token);
- the prompt the runtime renders from Atom's messages (the compact system prompt plus `PromptedToolEngine`'s tool
  block) and the streamed answer, with the model's default sampler and thinking, and with thinking off and Qwen's
  non-thinking sampler; for a chat question, the first step of a browser task, and prompts sized just under and over
  the model's window;
- the bubble's "set an alarm for 8:00" with 4.0.6's prompt and with the tiny-model plan (`core/.../agent/TinyModel.kt`:
  a short prompt and only the clock tools), the plan's second step after the tool result, plain chat, and a task the
  plan has no tools for. Each answer's count of stray `<think>` / `</think>` tags is printed too.

The `wake-word` job runs `tools/ondevice/wake_probe.py` with the LiteRT Python runtime at the version the pack ships
(`com.google.ai.edge.litert:litert` in `ondevice-pack/build.gradle.kts`, the `ai-edge-litert` wheel). It opens the
pack's three "Hey Atom" models the way the pack does (the input shape the pipeline feeds written into the model's
stored shape, then allocated, as LiteRT's Java constructor allocates) with and without XNNPack, runs each once on
silence, and fails when one does not load or gives non-finite output. It also lists where the pack's Java
interpreter allocates tensors (`javap` of `NativeInterpreterWrapper`).

It also lists the Kotlin API of the app's LiteRT-LM AAR (`javap`), so a runtime upgrade shows what changed. The
runner has no GPU, so the phone's GPU and NPU paths and their speed are not covered.

## Old vs new (Compare)

`compare.yml` installs the previous Atom (the latest 3.4.x release of `namanwtf/atom-old`, its published APK) and the
new release build (signed on the runner with a throwaway key), one after the other on the same emulator. They share
the application id, so each is uninstalled before the other goes on. Both are set up only through their own screens:
Settings > Model providers > Add OpenAI-compatible provider, pointed at `http://10.0.2.2:8081/v1`.

Port 8081 is `tools/compare/proxy.py`, a pass-through in front of the same llama-server. It writes one line per chat
request: which app and task, the messages and tools sent, the model's tool calls and answer, and the server's own
token counts. So both apps are measured the same way, without any hook inside either app. While the CPU is still
reading a long prompt, the proxy sends SSE comment lines, which clients ignore, so neither app's read timeout measures
the runner's CPU instead of the app.

`tools/compare/run.py` then runs the same tasks in each app (a new conversation each, typed into the composer):
"hi", Do Not Disturb, the last notification, a daily reminder, reading example.com, opening Settings, a shell command,
and Stop during a longer task. Checks are on device state and the model's answers. After the tasks, the app sits 10
minutes with the screen off and the charger "unplugged"; `dumpsys batterystats` then gives its wake locks, wakeup
alarms, CPU time and jobs. `tools/compare/report.py` prints the table (also on the run's summary page).

What an emulator cannot tell: real battery drain in mAh, radio and GPS cost, thermal behaviour, and on-device model
speed on a phone's GPU or NPU.
