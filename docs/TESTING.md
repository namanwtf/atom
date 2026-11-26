# Testing

Atom is built and tested only on GitHub's disposable runners, never on a personal machine (see the note at the top of
`.github/workflows/ci.yml`).

| Workflow | What it does | When |
|---|---|---|
| **CI** (`ci.yml`) | Unit tests, debug and release builds, the release-hygiene checks | Every push to main and every pull request |
| **Screenshots** (`screenshots.yml`) | Walks the debug build's screens on an emulator and prints each screenshot into the log | Pull requests that touch UI modules |
| **E2E** (`e2e.yml`) | Real agent tasks on an emulator, end to end | On demand, weekly, and on labelled or release pull requests |

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

A run takes about an hour or a little more: a few minutes to build and boot, then the scenarios. Atom's first request
carries its whole system prompt and every tool schema (about 17,000 tokens today), which the runner's CPU, shared with
the emulator, reads at roughly 20-30 tokens a second. So the runner reads it once, in a warm-up task that is not
scored, and every scenario after it reuses the server's cached prompt. For that cache to match, the emulator's clock is
set to the same minute (09:00 today, through `adb root`) before each task starts: the system prompt states the time to
the minute, ahead of the tool list. A leaner prompt makes all of this faster.

### Scenarios

Each scenario starts a fresh conversation the way a voice command does (MainActivity's `EXTRA_PROMPT`), waits for the
run to finish with a bounded timeout, and then checks tool calls and device state, never the model's exact words.
Confirmations the agent asks for are approved, as the person at the phone would.

| | Scenario | Passes when |
|---|---|---|
| a | Vibe coding: "In the terminal, create hello.py that prints the sum of 2 and 3, run it, and tell me the output." | Terminal or shell tools are called and the answer contains 5 |
| b | Agent browser: "Open the agent browser and summarize the top story on https://example.com." | `browser_open` is called (not `open_url`) and there is an answer |
| c | Phone control: "Turn on Do Not Disturb." | A Do Not Disturb tool is called and `settings get global zen_mode` is no longer 0 |
| d | Context: a notification "Dinner / Pizza at 8" is posted, then "What was my last notification about?" | A notification tool is called and the answer mentions Pizza or Dinner |
| e | Scheduling: "Every day at 9am remind me to drink water." | `scheduled_task_create` is called and a new scheduled task exists |
| f | Stop mid-task: a longer task, stopped from the running-task notification after its first tool call | The run ends as stopped, its partial history is saved and the app is alive |
| g | Characters: open the library and tap Lumen | No crash |
| h | Bubble: open the floating bubble, type into the mini chat and send | The bubble's conversation gets a reply |

All of them are core: the job fails if any of them fails. Every scenario also fails on any entry in the crash buffer
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
`approve` / `deny` (a pending confirmation) and `linux_install`. Runner: `tools/e2e/run.py` (scenario keys can be passed
as arguments, e.g. `run.py c d`).
