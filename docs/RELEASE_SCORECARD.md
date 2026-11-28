# ATOM 4.0.8 release scorecard

Written for the owner, in plain words. Everything below ran on GitHub's test machines (an Android phone emulator
plus a small AI model running on the same machine), never on a personal computer. Links go to the runs.

## What 4.0.8 brings

"Hey ATOM" you can see and type to, and models that get the room they need. (#40, #41)

- **"Hey ATOM" on screen.** After the wake word, a silver and cool-blue light glows around the screen edge and a
  small panel shows "Listening…" and the words as they are heard. The command then runs right there, as a voice run
  on the same path as the bubble; when the run needs to work the phone, the panel steps aside and the run capsule
  takes over with the same light. "Got it", "Working", "Done" and "Didn't catch that" are shown, so nobody has to
  guess what happens after the chime.
- **Screen context.** Once per wake, ATOM reads what is on screen (the accessibility outline, cut to 4,000
  characters, plus a screenshot for models that can see), so "summarise this" or "reply to this" works without
  Circle to Search. It is skipped for secure windows, visible password fields and ATOM's own screens. A chip in the
  panel and a switch in Speech settings turn it off.
- **Type instead of speaking.** The panel has a "Type to ATOM" bar like the app's composer. Tapping it stops
  listening at once (the microphone is released) and opens the keyboard, with any words already heard filled in so a
  misheard command can be fixed. Send runs it in place exactly like a spoken command. On the answer, the same bar
  takes a follow-up in the same conversation, and its mic listens again without the wake word.
- **A calmer panel.** The ATOM coin sits at the top centre and swells gently with the voice; live words are 17 sp
  (at most three lines, newest words kept), status 13 sp, answer 15 sp, all following the phone's font size.
- **One light everywhere.** "Hey ATOM", the run glow and the assistant entry share one edge light: a bright
  ice-white core, a saturated blue halo about 36 dp deep and a deep-blue keyline, so it reads on white as well as on
  dark screens. It animates only while listening; with animations off it is drawn still.
- **Notifications** carry the silver octopus and the name "ATOM" instead of the old cached picture.
- **Deep Think finishes its answers.** Every cloud model used to get 2,048 output tokens, so DeepSeek Flash with
  Deep Think reasoned until the limit and never answered. Thinking requests now get room for reasoning and answer:
  64K for Claude, GPT and Gemini, 32K for DeepSeek (64K at Max effort) and Kimi, and the provider's own figure when
  it reports a larger one (up to 128K), never more than a third of the model's window. An answer that still hits the
  limit gets one "answer now" retry, then a Continue button; a tool call cut off mid-way is never sent back.
- **Limits follow the model.** One place now sets every size limit from the model in use: how much of the
  conversation is kept, how many screenshots and images, how long tool, web, file and memory results may be, sub-task
  steps, and timeouts (thinking requests wait up to 5 minutes; a "slow down" answer from the provider is waited out
  as it asks). Large models get much more, small and on-device models stay light. Settings > LLM providers >
  Advanced lets a power user change them.

## Checks on the release branch

| Check | Result |
|---|---|
| Unit tests, debug and release builds, release-hygiene checks (CI) | pass on f589946, the release code ([run 46133486664](https://github.com/namanwtf/atom/actions/runs/46133486664)); only the last step, Release readiness, held the pull request until this scorecard |
| Screenshots, dark and light | pass ([run 46124620552](https://github.com/namanwtf/atom/actions/runs/46124620552)), including the new "Hey ATOM" shots over Files and Clock. After the merge of #41 the first launch twice lost a race with the Home key; the script now waits for the launcher and retries a stuck launch once |
| Side-by-side comparison | ran on earlier commits of this branch; not a release gate |
| End-to-end tasks | 16 of 18 on the release code f589946 ([run 46133484368](https://github.com/namanwtf/atom/actions/runs/46133484368)), including the new "Hey ATOM" overlay (r: the wake word, live words, the command run in place with the screen, the result) and the new chat bar (s: a typed command runs in place, a follow-up continues the same conversation), and the "Hey Atom" model check (q) and pause and listen (k) |

## End-to-end tasks that did not pass

| | Task | Why |
|---|---|---|
| a | Write and run a small script in the terminal | emulator limit, as in every release since 4.0.5: the Linux install does not finish on this emulator |
| d | Read the last notification | the 3B test model called the notification tool but left the dinner notification out of its answer; it passed in the two earlier full runs of this branch, and 4.0.8 does not change notifications |

Getting there took three runs of r. The first two failed in the test, not the app: the emulator's listening
screenshot took 16 s, longer than the overlay's 14 s guard for a capture that has gone quiet, so the overlay ended
before the test sent the final words. The guard now restarts with each new word (a real recognizer sends words while
someone speaks and always ends within 10 s), and the test keeps sending words while it takes the screenshot. On the
slowest runner (half the usual speed) the tasks that lean on the model (e, b, m) ran out of time once; they passed in
the other runs.

## What was not tested, and what an emulator cannot tell

- Real phones: smoothness, battery, heat. The panel and light exist only between the wake word and the end of the
  command; the light animates only while listening. No new polling, wake locks or services.
- A real microphone: the emulator feeds the words in. Partial words from a phone's own recognizer, and how quickly it
  ends, depend on the phone.
- Screen context on secure apps (banking) and with a password field on screen: covered by unit tests, not on a phone.
- Whether a phone that cached the old notification picture shows the new octopus straight away.
- Real providers: DeepSeek accepting 32,768 output tokens, the "answer now" retry, a 429 "slow down" being waited out,
  and six images accepted in one request. Unit tests check the requests ATOM sends.

## Verdict

Everything new in 4.0.8 builds, passes its unit tests and passes end to end: "Hey ATOM" listens, shows the words,
runs the command in place with the screen and shows the answer; the chat bar runs a typed command and a follow-up.
The two failures are an emulator limit and one answer from the small test model in a part of the app this release does
not change.

Shipped at the owner's request: "include everything in 4.0.8", "be quick".

Result: ready
