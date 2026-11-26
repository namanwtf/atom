# Atom parity spec (clean-room, user-facing)

What the old app does, written from the user's side, so the new app can match it feature for feature. It contains no code, no internal design and no source paths. Everything here was taken from the old app's public README/docs, screenshots, user-visible text resources, user-facing asset catalogs and its declared Android capabilities, then reworded.

Tags: **[core]** essential for parity · **[root]** needs root · **[lsposed]** needs the LSPosed module · **[battery]** has a clear battery or thermal cost, so design it with care · *(ColorOS)* / *(HyperOS)* means it only works on that vendor ROM.

Platform baseline: Android 14+, ARM64 only, one APK, English UI only (the language setting is just "Follow system"). Installing over an existing copy signed with the same key keeps keys, models and permissions.


**Status legend (updated 2026-11-26, re-checked against the current app after the Miuix UI, home and drawer, settings, bubble and overlay, feature-screen, chat/models/voice and update batches).** §1–§16 hold 440 items: **412 done, 21 partial, 7 missing.** `[x]` means the new app has the behaviour and a user can reach it. Open items carry **partial:** or **missing:** with the reason. The new app now uses the old Miuix look (pure black, blue accent, large titles, Miuix switches and cards); what is still open, including the COUI style, is summarised in "Gap list" at the end. Per-item evidence (file and line) is in the batch PRs and `docs/PARITY_STATUS.md`.

---

## 1. Onboarding & permissions

- [x] There is no setup wizard. A short startup splash opens straight onto the home chat. [core]
- [x] With no provider configured, the empty state tells the user to add one, and sending is blocked with a "configure a model first" message. [core]
- [x] The home empty state shows a "How can I help you?" headline and four suggestion cards: analyze the current screen (screenshot plus description), open a named messaging app, browse the web (the agent browser summarizes today's tech news), and check memory pressure (reads kernel memory and pressure stats and summarizes them). [core]
- [x] A Permissions & status page shows an overall health line ("All good" or "N items need attention"). Each row shows Available / Missing / Issue / Not enabled with a Grant or Go-to-settings action. [core]
- [x] The conversation sidebar shows a "Permissions need attention" hint when something is missing. [core]
- [x] Accessibility service, labelled "Atom Agent": reads the screen, taps, swipes, types and takes screenshots. Needed for screen control. [core]
- [x] Display over other apps (floating window), for the bubble, run capsule and overlays. [core] [battery]
- [x] Notification access (listener). Searchable notification history starts recording from the moment it is granted. [core]
- [x] Usage access, for recent apps and foreground time. [core]
- [x] Location, precise and approximate. Background tasks need "Always allow". The app explains when it only has foreground access. [core]
- [x] Calendar read and write. [core]
- [x] Do Not Disturb access, so silent, vibrate and ring can be switched without root. [core]
- [x] Post notifications, used for running-task controls, background command results and scheduled task results. [core]
- [x] Promoted (Live Update) notifications: during a run the task shows as a status-bar capsule ("Working" / "Answering") and on the lock screen. The user can turn it off. [core]
- [x] Microphone, for voice input. If it is denied, keyboard input stays available and the app links to its own permission settings. [core]
- [x] Local network access (Android 17 "Nearby devices"), for LAN models, MCP servers, the built-in browser and terminals. Internet access works without it. [core]
- [x] All files access is optional and only used for shared/public directories. The private workspace and import/export work without it. [core]
- [x] Exact alarms, so scheduled tasks fire on time. [core]
- [x] Unrestricted battery (background running), so scheduled and background tasks are not cut off by Doze. [core] [battery]
- [x] Full installed-app list visibility, so the agent can find apps. [core]
- [x] Also declared: set alarm, NFC, Wi-Fi and network state, modify audio settings, wake lock, start on boot, and foreground service types (microphone, media playback, special-use "interactive terminals, local Linux services and device automation"). [core]
- [x] Root authorization: the app probes once on first use (the su prompt waits up to 30 s), then shows Checking / Granted / Not detected / Timed out / Not granted, with Authorize and Check again buttons. It never re-prompts repeatedly. [root]
- [x] Root help text names Magisk, KernelSU (metamodule support must be on) and APatch, and explains when BusyBox applets are missing. [root]
- [x] An LSPosed "framework connection" status row, with help on module activation and scope. It warns that a connection does not prove the module is enabled. [lsposed]
- [x] The capability view toggles between "Current device" and "All capabilities". Users can browse every capability and its requirements without triggering any permission prompt. [core]
- [x] Losing a permission, root or the framework never resets the user's saved toggles. Features degrade and explain why. [core]

## 2. Chat & conversation UX

### Layout
- [x] Top bar: drawer button on the left. In the centre, a model capsule (provider logo, model name, chevron), or the conversation title with the model as subtitle. On the right, an overflow menu (new conversation, open terminal, open browser, more). [core]
- [x] The conversation list sits beside the chat and is revealed by a horizontal drag or the drawer button. The chat dims under a scrim, and tapping the scrim closes the list. [core]
- [x] The sidebar has a floating search capsule plus Permissions and Settings buttons at the bottom, and dock entries for "Models" and "Characters". [core]
- [x] Conversations are grouped as Pinned / Today / Yesterday / Recent. Each row previews the last message, "Atom is reasoning", or the tool being called. [core]
- [x] Per-conversation actions: Pin/Unpin, Rename, Delete (confirmed), Export. Deleting a running conversation offers "Stop task and delete": it stops the task and saves results first, and keeps the conversation if saving fails. [core]
- [x] Conversation search also matches message text that is not loaded yet. [core]
- [x] Wide screens get an adaptive layout. Landscape, large fonts, light and dark are all supported. [core]

### Composer
- [x] Placeholder "Ask Atom to do something". The bar holds a + attachments button, a reasoning chip, a mic button and a round send button (which becomes Stop while running). [core]
- [x] The reasoning chip switches between "Think" (off) and "Deep Think" (on). A per-conversation effort picker offers the subset of Off / Default / Low / Medium / High / XHigh / Max that the current model supports. [core]
- [x] The reasoning control is hidden for models that cannot reason. Forced-reasoning models show a fixed "Default". A saved effort that is no longer valid after a model switch snaps to the nearest valid level. [core]
- [x] Attachments: image (with count and size limits per message), file, folder, and "Enter path" (any absolute local path). Each attachment can be removed before sending. [core]
- [x] File and folder references are passed to the agent as paths, not uploads. The agent reads them with its file and terminal tools, which must be enabled. [core]
- [x] Share-to-Atom: the app accepts single or multiple shared items of any type from other apps. [core]
- [x] Haptic feedback on gestures. [core]

### While a task runs
- [x] Messages sent mid-task are queued ("Queued · N"). The user can remove one or tap "Send now" to steer the running task at its next safe point without cancelling it. Messages with images wait until the current task finishes. [core]
- [x] Stop cancels the run and saves the safe partial history. [core]
- [x] Several conversations can run tasks in parallel. Only one at a time can drive the phone UI; the others show "Waiting for phone control". [core]
- [x] Tasks keep running after leaving the app, shown in a "Running tasks" notification with Open and Stop actions. [core] [battery]
- [x] Optional "Keep screen on while running" (on by default), with a safety timeout so a crashed run cannot hold the screen on forever. [core] [battery]
- [x] After a crash or kill, finished work is restored and the run is marked interrupted. Nothing is replayed automatically. [core]
- [x] A transient model failure retries automatically (3 attempts, roughly 2/4/8 s apart) and shows a "Retrying model request" notice. [core]
- [x] Errors are explained in plain words: bad key (401), forbidden (403), wrong URL or model (404), rate limited (429), quota or billing, timeout, connection lost, provider content filter, output limit hit, context overflow. [core]

### Rendering
- [x] Streaming answer with a smooth character-by-character reveal and auto-scroll to the bottom that stops as soon as the user scrolls. A "back to bottom" button appears. [core]
- [x] Full Markdown: headings, lists, task lists, tables, code blocks with a copy button, quotes, links, and callouts (Note / Tip / Important / Warning / Caution). Anything unparseable falls back to raw text. [core]
- [x] Native LaTeX, inline and block, with all common delimiters. Long formulas scroll sideways. Copying keeps the LaTeX source. [core]
- [x] Visible reasoning shows as a collapsible block: "Reasoning…" with the last few lines while streaming, then "Reasoning completed · N seconds", auto-collapsed. [core]
- [x] The work timeline collapses to one line ("Thought N times · M tool calls · K failed") and expands into a step timeline with an icon per tool and a status (running / completed / failed / result unknown). Repeated calls to the same tool merge. A terminal step shows its last output line. Steps longer than 3 s show a live timer. [core]
- [x] Tool details expand to the exact command (Copy command) and the result. Browser steps show a live page preview. [core]
- [x] Citations from provider-hosted web search become clickable links. Gemini Google-search suggestions show under the answer and open in the external browser. [core]
- [x] Generated images appear as inline cards (see §13). [core]
- [x] Older history loads automatically on scroll-up, with a retry if loading fails. The reading position is restored when the conversation is reopened. [core]

### Message actions
- [x] Copy answer, copy code, read aloud. [core]
- [x] Regenerate, with a warning about how many later turns will be deleted. [core]
- [x] Edit a past user message: sending replaces that message and everything after it. [core]
- [x] Delete a message, plus any later turns, with confirmation. [core]
- [x] "Branch into a new conversation" from the final reply of a finished turn. It copies history up to that point and is named "<title> · Branch". Model and reasoning settings carry over; drafts and queued messages do not. [core]

### Titles, export, context
- [x] Auto-generated title after the first turn, in the user's language. Toggle, model and reasoning level are configurable (§12). Manual rename is always possible. [core]
- [x] Export a conversation as Markdown, including user and assistant text, thinking, tool calls with arguments and results, and an image count. [core]
- [x] A context-usage indicator shows how much of the model's window is used, measured after each response. [core]
- [x] Manual "Compact context" action. Automatic compaction runs at about 85% of the configured window (toggle in settings), and compaction status is reported. If the window is unknown, automatic compaction is off and the app says so. [core]
- [x] The agent can search its own full conversation history, even past a compaction. [core]
- [x] A session stats popover shows turns, steps, model time, tool time, average time-to-first-token, output tokens/s, token usage, cache hit/miss/read/write, compaction cost and payback, and subagent usage. [core]
- [x] If a conversation's saved model is gone, a notice says which model is being used instead. [core]

## 3. Models & providers

### Preset providers (12 built in, shown with "built-in" badge)
| Provider | Protocol | Public base URL | Preset models |
|---|---|---|---|
| OpenAI | OpenAI-compatible (Responses API by default) | https://api.openai.com/v1 | GPT-6.1 Sol (`gpt-6.1-sol`), GPT-6 Luna (`gpt-6-luna`), GPT-6 Astra (`gpt-6-astra`) |
| Anthropic | Anthropic Messages | https://api.anthropic.com | Claude Fable 5 (`claude-fable-5`), Claude Fable 5.1 (`claude-fable-5-1`), Claude Opus 4.8 (`claude-opus-4-8`), Claude Opus 5.5 (`claude-opus-5-5`), Claude Sonnet 5 (`claude-sonnet-5`) |
| Alibaba Bailian (DashScope / Qwen) | OpenAI-compatible | https://dashscope.aliyuncs.com/compatible-mode/v1 | Qwen3.7 Plus (`qwen3.7-plus`), Qwen3.8 Flash (`qwen3.8-flash`), Qwen3.8 Max (`qwen3.8-max`), DeepSeek V4.1 Flash (`deepseek-v4.1-flash`), Kimi K2.6 (`kimi-k2.6`), Kimi K2.7 Code (`kimi-k2.7-code`), Kimi K3 (`kimi-k3`) |
| DeepSeek | OpenAI-compatible | https://api.deepseek.com | DeepSeek Flash (`deepseek-flash`), DeepSeek V4 Pro (`deepseek-v4-pro`) |
| Kimi (Moonshot) | OpenAI-compatible | https://api.moonshot.cn/v1 | Kimi K2.6, Kimi K2.7 Code, Kimi K2.7 Code HighSpeed (`kimi-k2.7-code-highspeed`), Kimi K3 |
| MiMo (Xiaomi) | OpenAI-compatible | https://api.xiaomimimo.com/v1 | MiMo V2.5 (`mimo-v2.5`), MiMo V2.5 Pro (`mimo-v2.5-pro`), MiMo V2.6 Flash (`mimo-v2.6-flash`), MiMo V2.6 Pro (`mimo-v2.6-pro`) |
| MiniMax | OpenAI-compatible | https://api.minimaxi.com/v1 | MiniMax M3 (`MiniMax-M3`) |
| StepFun | OpenAI-compatible | https://api.stepfun.com/v1 | Step 3.7 Flash (`step-3.7-flash`), Step 5 Preview (`step-5-preview`) |
| SiliconFlow | OpenAI-compatible | https://api.siliconflow.cn/v1 | none preset; pull from remote |
| OpenRouter | OpenAI-compatible | https://openrouter.ai/api/v1 | none preset; pull from remote |
| xAI | OpenAI-compatible (API key) or Grok subscription login | https://api.x.ai/v1 | none preset; pull, or synced from account |
| Gemini | Google AI Studio (Interactions API) or Vertex AI | official Google endpoints | Gemini 3.5 Flash-Lite (`gemini-3.5-flash-lite`), Gemini 3.8 Flash (`gemini-3.8-flash`), Gemini 4 Argon (`gemini-4-argon`), Gemini 4 Carbon (`gemini-4-carbon`), Gemini 4 Hydrogen (`gemini-4-hydrogen`), Gemini 4 Neon (`gemini-4-neon`) |

- [x] Default selection: on a clean install the screenshots show **DeepSeek / DeepSeek Flash** as the current provider and model ("current" badge, check mark). The settings row reads "DeepSeek / DeepSeek Flash". Treat this as the shipped default and confirm with the owner. [core]
- [x] New built-in providers come pre-filled with a default system prompt that introduces the assistant as an on-device Android agent. If the user clears it, it stays empty. [core]
- [x] Gemini 4 reasoning presets: Neon and Hydrogen offer Default/Low/Medium/High/XHigh (server default High, reasoning cannot be turned off). Carbon offers Default/Low/Medium/High (server default Medium). [core]

### Provider management
- [x] The Model providers page has a search box, an "Add new provider" group and a "Configured providers (N)" list. Each row shows logo, name, base URL, protocol, model count, a "built-in" badge, and a radio button / check for the current provider. [core]
- [x] Add from the models.dev community catalog. It works from a bundled snapshot, refreshes online and caches, and never sends the user's keys. The user picks a provider and models, and they are imported as a custom provider that is disabled and has no key until the user adds one. [core]
- [x] Add an OpenAI-compatible provider (for ChatGPT, DeepSeek, Kimi, GLM, Qwen and others), with an endpoint mode of Chat Completions or Responses API. [core]
- [x] Add an Anthropic provider (official or compatible API). [core]
- [x] Add Gemini: choose AI Studio (API key, native Interactions API) or Vertex AI (API key plus Project ID or number and a location of global / us / eu / a single region; Gemini 4 only; custom HTTPS proxy allowed). [core]
- [x] Provider detail: enable switch, display name, base URL, API key (masked, with a visibility toggle), endpoint mode, per-provider system prompt, custom headers (can override User-Agent; auth headers are protected), extra request-body JSON, and Test connection. [core]
- [x] Models tab: pull the remote /models list into a picker ("N new models available" → "Add N"), add a custom model by hand (display name + model ID), edit model parameters, delete one or many models with multi-select, search models, "Set as default model". [core]
- [x] Per-model settings: context window (common sizes or a typed number, used for compaction), "Supports reasoning" (auto-detected or manual), and "Configure reasoning levels" (manually tick the levels the model supports). [core]
- [x] Remove provider (confirmed), Reset built-in configuration (restores the official model list and keeps the API key), duplicate a provider ("<name> Copy"). [core]
- [x] Capability detection: reasoning support and context window come from remote metadata or the built-in catalog, with family rules as a fallback. Results show as "Detected automatically" or "Auto: unsupported or unknown". [core]
- [x] Provider-hosted web search toggle (off by default; OpenAI Responses). Gemini Google Search grounding when supported. [core]

### Subscription / OAuth login
- [x] Authentication method per provider: API key or account sign-in. [core]
- [x] OpenAI Codex: "Sign in with ChatGPT". Authorization happens in the system browser with a local callback; the account's model list syncs automatically after login. [core]
- [x] xAI Grok subscription: sign in with a device code in the browser, using the account's subscription quota, with automatic model sync. [core]
- [x] Account controls: sign-in status, Open browser, Reauthorize, Manage usage, Sign out (Grok sign-out also revokes the token remotely). There are clear errors for expired, denied, mismatched-account and no-plan-access cases. [core]
- [x] Tokens are stored encrypted on the device and are never included in backups; the user signs in again after a restore. [core]

### Default vs per-conversation model
- [x] One global default provider and model ("Set as default"). [core]
- [x] A "Choose model" bottom sheet opens from the home capsule, with an All / per-provider filter and search. The choice is saved per conversation. [core]
- [x] Settings → "Reasoning enabled by default" toggle and "Default reasoning effort" (Model default / levels). [core]
- [x] Separate model choices, each able to "follow the primary model": floating assistant, subagents, title generation, each scheduled task, voice smart-correction (always the chat model), image generation (its own service). [core]

## 4. Voice

- [x] Chat dictation: tap the mic to record, then tap again or "Done". The text is inserted at the cursor as an editable draft and never overwrites existing text. [core]
- [x] Hold-to-talk in chat: press and hold the mic, release to transcribe and send, slide away to cancel ("Release to send, slide away to cancel"). [core]
- [x] Assistant overlay voice: one recognition starts automatically when the overlay opens. Streaming services can auto-send after a pause of 1, 1.5, 2 (default) or 3 s, or never. Hold-to-talk is also available. [core]
- [x] Live recognition preview for streaming services. Upload-then-transcribe services show only progress states (Connecting / Listening / Recognizing / Uploading / Proofreading). [core]
- [x] One recording lasts at most 60 s (DeepSeek allows 120 s / 8 MiB). [core]
- [x] "Enable speech recognition" master switch. When off, the mic disappears from chat and the overlay, and the overlay opens to text input with the keyboard so the IME's own voice input can be used. [core]
- [ ] Recognition services (one active): Qwen Realtime (default), Qwen Short audio, Qwen File transcription (uploads to the user's own Alibaba OSS bucket), Doubao Streaming 2.0, MiMo, DeepSeek. [core] — **partial:** all six services are there; the default is the phone's own recognizer instead of Qwen Realtime, so a fresh install works without a key (owner decision D15)
- [x] Qwen region (Beijing or Singapore, must match the key) and an optional base URL. Doubao: resource ID and optional legacy App ID + Access Token. MiMo and DeepSeek: separate keys. DeepSeek reuses the model-provider key if left blank. [core]
- [x] Recognition language: blank means auto (zh, en, yue and so on for Qwen). MiMo offers Auto / Chinese / English. [core]
- [x] Smart correction (optional): the current chat model makes one cautious proofreading pass to fix homophones and mixed-language terms, waits at most about 8 s, and keeps the raw text on failure. [core]
- [x] Recording storage page (for file transcription): OSS region, endpoint, bucket, prefix, AccessKey ID and secret. Objects are private and deleted after transcription; cleanup is retried. [core]
- [x] Spoken answers (TTS) services: Off (default), Qwen, Doubao, Gemini, MiniMax, MiMo, DeepSeek. [core]
- [x] Qwen voices: Cherry (default), Serena, Ethan, Chelsie, Momo, Vivian, Moon, Maia, Kai, Nofish, Bella, Katerina, Vincent, Neil, Dylan, Jada, plus Beijing-dialect male and Shanghai-dialect female voices. [core]
- [x] Doubao voices: Vivi (default), Xiaohe, Yunzhou, Xiaotian, Liufei, Fresh Voice, Cancan, Xiaoyuan, Taozi, Sisi, Girl Next Door, Zixin, Ahu, Yichen, Xiaoming, Late Night Podcast. [core]
- [x] Gemini TTS: model Gemini 3.8 Flash (quality) or Flash-Lite (lower latency); voices Kore, Puck, Charon, Aoede, Zephyr, Leda, Sulafat, Achernar (each described as firm, upbeat, informative and so on); optional reading-style text. [core]
- [x] MiniMax TTS: Speech 2.8 Turbo or HD; Mandarin voices (mature, sweet, girl, elegant, young male, elite male, executive, news anchor); speed 0.5–2x; number normalization; China or overseas site. [core]
- [x] MiMo TTS: Default, Rock Candy, Jasmine, Soda, Birch, Mia, Chloe, Milo, Dean; optional reading style (natural-language tone, emotion, speed or dialect, up to 500 chars). [core]
- [x] DeepSeek TTS: Starfish and Tide voices, streaming, automatic language detection. [core]
- [x] "Custom voice" / enter a voice ID by hand. A saved ID is matched back to its preset name. [core]
- [x] Settings page has "Test recognition" and "Preview voice" (editable preview text up to 500 chars). Both use the unsaved form and may cost money. Changes need an explicit Save. [core]
- [x] Read aloud on any answer. Tables, code, images, HTML and bare URLs are skipped; list numbering and task state are spoken. A "Filter Markdown formatting before reading aloud" toggle exists. [core]
- [x] Auto read-aloud toggle in the chat "More" menu reads each completed turn's final answer. It is hidden when no TTS is configured. [core]
- [x] Assistant overlay "Speak answers automatically" (off by default) speaks only the final answers to voice questions while the overlay is visible. [core]
- [x] Chat read-aloud keeps playing in the background or on the lock screen, with a "Atom is reading aloud" notification and a Stop button. [core] [battery]
- [x] Interruption: starting a recording stops playback. Losing audio focus or unplugging headphones stops playback. Closing or hiding the owner page ends the audio task. Nothing is spoken late after the fact. [core]
- [x] If the cloud TTS fails (bad key, timeout, refusal), the app falls back to the Android system voice instead of showing an error. [core]
- [x] Speech credentials are encrypted, kept separate from chat credentials, and excluded from backups. [core]
- [x] **Not present in the old app:** a continuous hands-free voice conversation loop (listening does not restart after an answer), a wake word (roadmap only), and background always-on listening. The owner wants "audio chat", so this is a new design item and needs careful battery work. [battery]

## 5. Screen control & automation

- [x] Observe the screen: UI tree plus an optional screenshot, at original resolution. [core]
- [x] Tap by element (preferred), by coordinate, or by area. [core]
- [x] Long-press by element or coordinate. [core]
- [x] Swipe up/down/left/right. Scroll the page or a specific element. [core]
- [x] Text input: replace, append, clear, optionally submit. The field is read back afterwards to confirm. [core]
- [x] Paste text, set the clipboard, read the clipboard. [core]
- [x] System keys: Back, Home, Recents, Notifications, Quick settings, Power, Volume up/down, Mute. [core]
- [x] Open system panels (notification shade, quick settings and similar). [core]
- [x] Wait for a duration, for text to appear, or for an app to reach the foreground. [core]
- [x] Search installed apps by name or package. Launch an app by name or package, which works without accessibility. [core]
- [x] Open a link or deep link in an external app. [core]
- [x] Verification loop: after every action the agent observes the screen again instead of assuming the action landed. Coordinates are only used when no element can be found. Ambiguous results trigger re-observation, not a repeated gesture. Outcomes are reported as done / failed / "submitted, unconfirmed". [core]
- [x] App-launch confirmation: the agent reports whether the target app's window was actually seen. [core]
- [x] Exclusive phone control: one task drives the UI at a time and others wait in line. The agent can explicitly release control. [core]
- [x] A run capsule overlay appears during phone actions and shows status. Tapping it reveals Pause, Resume, Add input (steer), and Stop. While paused, the user has control and other UI tasks wait. [core] [battery]
- [x] An edge glow / light on the main screen while the agent operates it. [battery]
- [x] A result card when done ("Result ready" / "Open Atom" / "Open screen" / "Done"). [core]
- [x] Background virtual display: cross-app tasks run on a hidden screen so the user's foreground is untouched. Routing is automatic: if the target app is already in front, or is the launcher/system UI, the agent works on the main screen. Settings toggle: "Background virtual display". [root] [lsposed] [battery]
- [x] A live mini viewfinder sits inside the run capsule. Tapping it expands a stage window for live preview, direct touch takeover ("Take over" / "Continue"), "Fullscreen" projection, or "Collapse stage". [root] [lsposed] [battery]
- [x] Hand-back to the user ("Present to main screen"): when the hidden task reaches a payment confirmation, QR code or final result page, that page moves smoothly onto the real screen. [root] [lsposed]
- [x] If the virtual display disappears, the agent reports "display unavailable" and never replays old coordinates on the main screen. [core]
- [x] Messaging (for example WhatsApp or WeChat) is done through the same generic screen tools. There is no special per-app integration. [core]
- [x] Root fallbacks: a system UI-dump when accessibility is not connected, and a root screenshot when a per-window screenshot fails. [root]
- [x] Accessibility "force keep" protection re-enables the service if the ROM drops it, with limited retries and backoff. [lsposed]
- [x] Password fields are never captured in screen context. [core]

## 6. Phone context & personal data access

All data is read on demand, in bounded slices, only when the agent calls the tool. Raw values (OTPs, notification text, Wi-Fi passwords and so on) are used for that turn only and are scrubbed from saved history.

- [x] Time and location: local time and offset, plus the most recent system location fix and its age. Clear messages when location is off, missing, or background permission is needed. [core]
- [x] Current notifications (title and text), via the notification listener. [core]
- [x] Notification history: kept on the device from the moment access is granted, for the last 7 days (up to 1000 entries), and searchable. [core] [battery]
- [x] SMS verification code: extracts only OTP codes from recent SMS. [core]
- [x] SMS search by sender or keyword. [core]
- [x] Contacts search by name. [core]
- [x] Call history search by number or contact. [core]
- [x] Calendar event search by title, location or description. [core]
- [x] Gallery photos and screenshots: search by file name or album path. [core]
- [x] Audio file search by title, file name or artist. [core]
- [ ] Recordings: normal and call recordings, plus recording summaries and transcripts. [core] *(summaries: ColorOS)* — **partial:** normal and call recordings are found; summaries and transcripts come from the ColorOS recorder and need a ColorOS device
- [x] Shared-storage documents and files search. [core]
- [x] Download history (system download tasks and files). [core]
- [x] Recent apps and foreground usage time (needs usage access). [core]
- [x] Current app and screen context: the digital-assistant overlay attaches a screenshot plus the foreground app's content at wake time. [core]
- [x] Clipboard history saved by the system keyboard. [root]
- [x] Health summary for a time window: steps, sleep, exercise, body metrics (from Health Connect data). [root]
- [ ] Alarms list and active timers. [core] — **partial:** shows the next system alarm plus the alarms and timers Atom set; the full list of other alarms needs the ColorOS clock and a ColorOS device
- [x] Wi-Fi saved passwords. [root]
- [x] Device status: battery and charging, memory, storage, OS version. Network status: connection type, Wi-Fi state, internet validated. Device environment: lock screen, DND, audio output, external display. [core]
- [x] Memory ranking (top processes) and storage ranking (per-app data and cache). [core]
- [ ] Notes and to-dos search; read a note by ID. [root] *(ColorOS)* — **missing:** ColorOS-only private data; needs a ColorOS device to build and test (decision D12)
- [ ] System memory search ("Breeno/Xiaobu memory"): bills, schedules, pickup codes, deliveries, saved places, attachments. [root] [lsposed] *(ColorOS)* — **missing:** ColorOS-only private data; needs a ColorOS device to build and test (decision D12)
- [ ] Personal orders: food delivery, shopping, parcels, tickets, travel. Saved places. [root] *(ColorOS)* — **missing:** ColorOS-only private data; needs a ColorOS device to build and test (decision D12)
- [ ] Bills search plus "Summarize bills" (exact totals over a time range, split into income, expense, transfer and unknown, top categories). [root] *(ColorOS)* — **missing:** ColorOS-only private data; needs a ColorOS device to build and test (decision D12)
- [ ] To-do leads, calendar to-dos, memory collections, life events, flights, hotel bookings, train trips. [root] *(ColorOS)* — **missing:** ColorOS-only private data; needs a ColorOS device to build and test (decision D12)
- [x] Read the full detail of any earlier search hit. [core]
- [ ] QQ and WeChat chat-image caches: recent image file listings, which can then be viewed. [root] — **partial:** no dedicated listing; with root the private-storage reader can browse those folders, but the images are not shown
- [x] Read-only inspection of other apps' private databases and files when no dedicated tool exists. [root]
- [x] The agent is told to sample several sources for broad "know me" questions rather than asking permission again for each source the user has already enabled. [core]

## 7. Root & system control

- [x] Flashlight on/off and status. [core]
- [x] Brightness (as a percentage), auto-brightness, auto-rotate, screen timeout (seconds). [root] (some work without root)
- [x] Dark mode, eye-comfort / night light. [root]
- [x] Volume per stream (media, alarm, ring, notification, call, system). [core]
- [x] Sound mode (normal / vibrate / silent). Without root this needs DND access. [core]
- [x] Do Not Disturb (priority only / alarms only / total silence). [core]
- [x] Wi-Fi and Bluetooth toggles. [root]
- [x] Mobile data, airplane mode, location, NFC, battery saver, personal hotspot (keeps the existing name and password). [root]
- [x] Media controls: play, pause, toggle, next, previous, stop, without touching the UI. [core]
- [x] Freeze, unfreeze and force-stop apps. [root]
- [x] Read and write protected Android settings keys (system, secure, global). [root]
- [x] Read system logs with filters for process, tag, level, time and text. [root]
- [x] Inspect app: version, UID, install source and path, enabled/stopped state, permission list. [core]
- [x] Every state change reports before → after and whether it was verified (for example "Auto-rotate: off → on"). An unconfirmed change is reported as unconfirmed, never as success. [core]
- [x] Rootless mode: dedicated root tools are hidden. The terminal and file tools run as the app user and Linux runs via PRoot. Nothing escalates silently. [core]
- [x] **No Shizuku support in the old app.** "Rootless" means standard permissions only. Adding Shizuku would go beyond parity. [core] *New app: standard permissions, plus Shizuku as an extra tier.*

## 8. Terminal & Linux

- [x] A user terminal opens from the home overflow menu. Console (full PTY) mode is the default, with "Simple mode" (a block view of command, output and exit code) available. [core]
- [x] Console: real xterm-256color terminal with colours, cursor movement, alternate screen, line drawing, wide CJK glyphs, scrollback, bracketed paste, resizing on rotation and keyboard changes, and a JetBrains Mono font. [core]
- [x] Key bar: Esc, Tab, Ctrl/Alt (tap for one-shot, long-press to lock), arrow keys with auto-repeat, Enter, Home/End, PgUp/PgDn, common symbols, Paste, show/hide keyboard. [core]
- [x] Simple mode: command, output and exit code per block. ANSI colours are rendered, progress bars collapse to their final state, and input works while a command runs (REPLs, prompts). Copy command / Copy output / Reuse command. [core]
- [x] Up to 6 concurrent sessions per mode. Session list: new, switch, restart, close. Sessions survive leaving the page. [core] [battery]
- [x] Environments: Android shell (app user, or root with Magisk/KernelSU/APatch BusyBox) and Linux. [core]
- [x] Linux distribution choice: Alpine (musl, lightweight) or Debian Trixie (glibc, broader compatibility). [core]
- [x] Execution mode: Standard (PRoot, no root, app-private) or Root mode (chroot with its own mount namespace). If root is lost, the chroot environment is kept and the user is offered PRoot. [core] [root]
- [x] Staged install: base environment, then base tools (rg, fd, git, ssh client, curl, jq, sqlite and others), then optional profiles. [core]
- [x] Optional profiles: Python via uv (latest stable Python), Node.js plus npm (latest stable), full OpenSSH server suite, and APK analysis (OpenJDK 25, JADX, Apktool, smali, baksmali; needs 768 MB; Apktool rebuilding is not supported). [core]
- [x] Install progress shows download percentage, extract and verify stages, Continue installation, Upgrade tools, cancel, "not enough space" errors, and integrity-verified downloads. [core]
- [x] Health check covers core commands, the /workspace mount, /sdcard and free space, with Repair (including Debian package recovery). [core]
- [x] /workspace is mapped to the app's private workspace. Shared storage is at /sdcard. [core]
- [x] Shared folders mount chosen Android directories at /workspace/mounts/<name> (name rules, duplicate and forbidden-path checks, a limit on count). They apply from the next session. [core]
- [x] Read-only Linux file browser with directory navigation and text previews (binary files are detected; previews are capped). [core]
- [x] Workspace & files page: import files (up to 32 MiB each), export files, private vs public directory access status. [core]
- [x] Background tasks panel: commands started in the background keep running after the reply ends, notify on completed / failed / timed out / stopped / interrupted with the exit code, and logs are viewable. There is a default 10-minute cap that can be disabled. [core] [battery]
- [x] Background services (daemons) run across agent tasks and can be listed, have their logs viewed, and be stopped. They do not survive a reboot. [core] [battery]
- [x] Agent terminal tools: one-shot commands, persistent sessions, background jobs, daemons, and a root check. Chat cards show the exact command, with copy. [core]
- [x] The agent also uses git, python, node and the others itself: it writes code, runs it, reads errors and fixes them. [core]

## 9. Memory, skills, MCP, subagents, scheduled tasks, characters

### Memory
- [x] A single long-term memory file (MEMORY.md, up to 1 MiB) that the user can view in Preview or Edit, Save or Discard, Clear all (confirmed), and switch on or off. Turning it off keeps the file. [core]
- [x] Only the "# Core memory" section is injected automatically, within a budget that scales with the model's context window; the agent reads the rest on demand. A seeded template prompts for user name and long-term preferences. [core]
- [x] The agent reads, appends, updates and clears memory itself. Concurrent edits are detected and never silently overwritten. [core]
- [x] Memory content is shown to the model as background, never as instructions. [core]

### Skills
- [x] Skills page: built-in skills (self-improving-agent, skill-creator, skill-installer) and user-installed skills, each with an enable switch, a description (expandable) and Delete (confirmed). [core]
- [x] Import a skill from a ZIP via the system file picker (one skill per ZIP; validated for safe paths and sizes). [core]
- [ ] The agent can install skills from a curated catalog or from any public GitHub repo, tree or blob URL (up to 20 per install). Installed skills work from the next turn and their scripts are never executed automatically. [core] — **partial:** install from a GitHub repo, folder or SKILL.md link (up to 20) and ZIP import work; there is no curated catalog to pick from
- [x] Replace-existing-skill confirmation. Built-in skills cannot be overwritten. Installs are transactional and recover after a failure. [core]

### MCP
- [x] MCP servers page: add a server (name, URL, optional bearer token). The tool catalog is discovered on add. Each server row shows "N of M tools enabled". [core]
- [x] Per-server settings: enable server, change or remove the token, refresh tools, delete server (confirmed). [core]
- [x] Tools are off by default and enabled one by one. Tools not marked read-only need an extra confirmation. Enable all / Disable all buttons. A truncation notice appears for oversized catalogs. [core]
- [x] Remote Streamable HTTP only: tools only (no resources, prompts, OAuth or stdio). HTTP, HTTPS and LAN URLs are allowed. [core]

### Subagents
- [x] Subagent settings: enable, model (follow main or a specific one), reasoning effort. [core]
- [x] Built-in subagents: Explore (read-only exploration and multi-source search), Coder (file editing and terminal work), Plan (read-only analysis and step-by-step plans), plus temporary ad-hoc subagents. [core]
- [x] Custom subagents: name (kebab-case), description, when to use, allowed tools (default is read, file, terminal and MCP), system instructions. Create, edit and delete them in the UI, or ask Atom in chat to create one (saved as agents/<name>.md). [core]
- [x] Subagents run in parallel in isolated contexts and return a summary. A details sheet shows the prompt, streaming steps, summary, tool-call count and token usage. [core]

### Scheduled tasks
- [x] Scheduled Tasks list shows "All tasks · N/M enabled", per-task next run, status and an enable switch, plus a health card (unrestricted battery, exact alarms, notifications). [core] [battery]
- [x] Quick-start presets: Daily evening briefing, Weekday morning digest, Web change radar, Weekly phone & habit review. [core]
- [x] Editor fields: name, prompt ("What should Atom do"), and schedule (Daily, Weekdays, Weekly with day picker, Interval from minutes up to 7 days, One-time delay, 5-field Cron), with a schedule preview. [core]
- [x] Notifications: notify every run, only on change, or archive silently. Option to show a progress notification while running. [core]
- [x] Advanced: model and reasoning effort (follow global or a specific one), per-task capabilities (chat-only, web & browser, device read, device controls, terminal/files/Linux, MCP, app UI automation (off by default)), result destination (isolated archive per run or a dedicated conversation channel), missed-run policy (catch up once within 3 h, or skip), quiet hours 23:00–07:30, max duration (2/5/10/15 min). [core] [battery]
- [x] Task detail: Run now, Stop, a task memo used to compare against the previous run (clearable), a run timeline (live progress or the full conversation, with follow-up), Delete. [core]
- [x] Reliability: alarm-based wake-ups, rescheduled after reboot, app update, or time and timezone changes. Overlapping runs are skipped. The task auto-pauses after repeated failures. UI tasks on a locked phone without a virtual display send a "ready to run" reminder instead. [core] [battery]
- [x] Two notification channels: results and alerts (failures, auto-pause, waiting for unlock). [core]
- [x] The agent can create and manage scheduled tasks from chat (for example "summarize important notifications every night"). [core]

### Characters / personas
- [x] Character library: search by name or tag, favorites, a "Mine" section. Ships with one editable default character that can be restored after deletion. [core]
- [x] Create or edit: name, profile, greetings (default plus alternates). Advanced: personality, scenario, example dialogue, system prompt, post-history instructions, creator notes, tags, creator, version, credits. [core]
- [x] Embedded worldbook: entries with primary and secondary keywords, a require-secondary option, always-active, before/after-definition placement, insertion order, scan depth, token budget, recursive scanning, enable per entry, compatibility notes for skipped entries. [core]
- [x] Import PNG or JSON character cards (V1/V2/V3). Export PNG or JSON. Duplicate, change image, delete (confirmed; existing chats keep a snapshot). [core]
- [x] Character detail: choose a greeting, then Start chat. "Use my persona" toggle. Story memory editor (shared across all chats with that character). [core]
- [x] My persona: a name and an identity/relationship description, used optionally per chat. [core]
- [x] Character chats keep real tool abilities. They can read but not write the real MEMORY.md, and they write story memory instead. [core]
- [x] In character chats: edit user or character messages in place (keeping later turns), regenerate a character reply as switchable candidates (previous/next) with no tools run, "<name> is replying…" indicator, and a "The story begins here" marker. [core]

## 10. Floating bubble & overlay

### Persistent bubble
- [x] An optional always-on floating bubble (Settings → Floating assistant → Floating bubble). It needs the overlay permission. [core] [battery]
- [x] Appearance: a round orb with a minimal atom glyph (nucleus with crossing orbits), three accent arcs, breathing and spinning only while working. When idle it is a static image to save CPU. [core] [battery]
- [x] States: idle, thinking/working, paused, dragging (it swells), and brace (bracing at the dismiss target). [core]
- [x] Tap opens the mini chat. [core]
- [x] Hold opens a radial hold menu anchored to the bubble, with icon-only buttons: Hide (X), Open chat, Talk (mic). A hint reads "hold the bubble for actions". [core]
- [x] Drag anywhere, with clamping and snapping at the screen edges. The position is remembered across restarts and the bubble never moves when the panel closes. [core]
- [x] Drag-to-dismiss: a bottom X target with a beam that strengthens as the bubble gets closer. Dropping on it hides the bubble. [core]
- [x] Mini chat panel: a full-height sheet between the status and navigation bars. Header has the title "Atom", + (new chat), expand (open the full app) and X (close). It shows a model chip, an empty state ("Ask Atom anything"), and a pill composer with mic plus a round send button. [core]
- [x] The panel shrinks so the composer sits on top of the keyboard, and returns to full height when the keyboard closes. [core]
- [x] The mini chat runs the full agent (tools plus streaming) and keeps one ongoing conversation across restarts, cleared only by "new chat". That conversation also appears in the main conversation list. [core]
- [x] Only one bubble ever shows. [core]

### Run capsule (during tasks)
- [x] A status capsule with run state; tapping it opens controls (Pause / Resume / Add input / Stop) and, on a virtual display, the mini viewfinder. See §5. [core] [battery]

### Full-screen assistant overlay (system assistant entry)
- [x] An edge-to-edge overlay drawn over the current app when Atom is invoked as the assistant. [core]
- [x] Entry animation: a halo and light band run down the screen edge, then a coloured wave ripple appears at the bottom that reacts to voice volume (skipped when graphics cannot render it). [core] [battery]
- [x] Three suggestion chips: Summarize screen, Explain this page, Extract key info. [core]
- [x] Listens first, with a keyboard toggle. Recognition errors show next to the input with a link to voice settings. [core]
- [x] The screenshot, foreground app and page content captured at wake time are sent automatically with the question. Follow-ups reuse that snapshot and note when it was taken. [core]
- [x] The reply card grows with the content. User messages show as small bubbles, thinking and tool steps fold into an expandable status row, and the answer streams as Markdown. There is a read-aloud button. [core]
- [x] Pull the top handle up to open the conversation in the full app ("Pull up to open Atom" / "Release to open Atom"). [core]
- [x] The overlay hides itself before the agent touches the screen, and shows the result afterwards. [core]
- [x] Separate model and reasoning settings for this overlay (§12). [core]

## 11. System integration

- [x] Registers as the system digital assistant (voice interaction service, assist from the lock screen, uses assist screenshots and structured screen content). Settings shows the current default (Atom / other app / not set) and opens the system picker. [core]
- [x] Handles the system ASSIST action, so the long-press or assistant gesture opens Atom. [core]
- [x] Declares a speech recognition service ("Atom speech recognition"), which Android requires for assistant eligibility. [core]
- [x] Accessibility service "Atom Agent". [core]
- [x] Notification listener "Atom notification history". [core]
- [x] Share target for any file type, single or multiple. [core]
- [ ] Notification channels: Running tasks (Open / Stop), Background command results, Scheduled task results, Scheduled task alerts, Read aloud, Assistant voice ("Atom is listening / reading aloud" with Stop). [core] — **partial:** all channels exist, but read-aloud uses the Assistant voice channel instead of its own "Read aloud" channel
- [x] Live Update / promoted status-bar capsule while running ("Working" / "Answering"). [core]
- [x] Boot, app-update and time-change receivers that reschedule tasks. [core]
- [x] File sharing out of the app (generated images, exported files) through a secure share provider. [core]
- [x] Power-key long-press target: System default / Gemini / Atom. [lsposed] *(ColorOS, HyperOS)*
- [x] "Automatically set default assistant" keeps the assistant role pointed at the chosen target. [lsposed]
- [x] Circle to Search by long-pressing the gesture bar. [lsposed] *(ColorOS, HyperOS)*
- [x] Circle to Search by two-finger touch-and-hold. [lsposed] *(ColorOS)*
- [x] HyperOS 4: gesture-handle long-press via Super XiaoAI screen search is redirected to Circle to Search. [lsposed] *(HyperOS)*
- [x] Google app eligibility and device spoofing so Circle to Search turns on (always on when the module is active; status lines for Google app installed or system priv-app). [lsposed]
- [x] Accessibility protection, which keeps the service enabled through the system. [lsposed]
- [x] **Not present in the old app:** Quick Settings tile, home-screen widgets, launcher app shortcuts, and a wake word. List them as optional extras, not as parity. [core]

## 12. Settings (every screen found)

### Settings root
- [x] LLM providers: Model providers (shows "provider / model"), Reasoning enabled by default (switch), Default reasoning effort (picker, "Model default" and levels). [core]
- [x] Context & extensions: Automatic context compaction (switch, on), Auto-generate titles, Memory, Subagent, Skills, MCP servers, Scheduled Tasks, Characters. [core]
- [x] Tools: Tool list, Web search and reading, AI image generation, Permissions, System enhancements, Linux environment, Workspace and files. [core]
- [x] General: Appearance & Theme, Language, Floating assistant, Speech, Keep screen on while running, Data backup, About. [core]

### Tool list / capabilities
- [x] Master switches: Enable web search, reading and browser; Enable device direct tools; Allow reading of sensitive device information; Allow sensitive device operation; Enable terminal/file tools; Enable memory; Provider-hosted web search; AI image generation. Most are on by default; hosted search is off. [core]
- [x] Tool cards grouped as Screens & controls, Text & clipboard, Applications & systems, Personal context, System & built-in apps, Terminal & files, Web tools, Document vision, Memory. Each card has an info/description view and jump links where relevant. "This device" vs "All capabilities" views. [core]
- [x] Switches are re-checked before every tool call, so turning one off takes effect mid-run. [core]

### Appearance & Theme
- [ ] UI style: Miuix (default) or COUI, switchable live without losing state. [core] — **missing:** COUI is deferred until Miuix is complete (decision D8); the UI style row says "COUI style coming soon"
- [x] Theme mode: Follow system, Light, Dark. [core]
- [x] Monet colours (system dynamic colour), colour style (Tonal Spot, Neutral, Vibrant, Expressive, Rainbow, Fruit Salad, Monochrome, Fidelity, Content, CMF). [core]
- [x] Accent colour: System, Blue, Purple, Pink, Red, Orange, Yellow, Green, Teal. [core]
- [x] Pure black background in dark mode (on by default, true #000000). [core]
- [x] Blur effects, top-bar blur style (gradient band or progressive), progressive blur (can be turned off for performance). [core] [battery]
- [x] Swipe-back-anywhere gesture. Predictive back animation. [core]

### Floating assistant
- [x] Floating bubble switch. [core]
- [x] Assistant model (follow primary or a specific model) and reasoning level (follow default or a specific level), for the power-key / system-assistant overlay. [core]

### Auto-generate titles
- [x] Enable auto-title, title model (follow conversation or a specific model), reasoning level (Auto minimal/off, Model default, levels). [core]

### Speech
- [x] Overview, Speech recognition, Spoken answers, Recording storage (OSS), Assistant group (auto-speak, auto-send pause), Smart correction, Filter Markdown, Test and Preview, Save. See §4. [core]

### Web search and reading
- [x] Search service and page-reading service, each Built-in default / DeepSeek / Custom endpoint. [core]
- [x] DeepSeek reuses the model-provider key, or the user can enter an override key and base URL. [core]
- [x] Custom endpoint: HTTP method, URL, optional API key (sent as a bearer token), request-body JSON template with placeholders (query/count for search; url for reading), path to the results array, title/link/snippet/body field paths, custom headers. [core]

### AI image generation
- [x] See §13. [core]

### System enhancements
- [x] Root status row (Authorize / Check again), framework connection row, module help. [root] [lsposed]
- [x] Digital assistant app row (opens the system picker). [core]
- [x] Power-key target picker, Automatically set default assistant, Gesture-bar long-press triggers Circle to Search, Two-finger long-press triggers Circle to Search, Enforce accessibility. [lsposed]
- [x] Background virtual display toggle. [root] [lsposed]

### Linux environment
- [x] Status card (version, mode, stage, result), distribution picker, execution mode picker, install/upgrade buttons, profiles, health check, shared folders, file browser, workspace. See §8. [core]

### Permissions
- [x] See §1. [core]

### Data backup
- [x] Export data / Import data. See §14. [core]

### Language
- [x] Follow system (English only). [core]

### About
- [x] Version, Check for updates ("Already on the latest version" / "Update available: latest X, current Y" → Download), Source code (GitHub, with a star prompt), Feedback. [core]

## 13. Files, web browsing, image generation, other tools

### Files
- [x] Agent file tools: read (paged), write/overwrite/append, exact-text edit with a uniqueness check, file info, list a directory (paged), find files by pattern, search file contents (literal text). They work on Android or Linux, as the app user or root. [core]
- [x] Image viewing: the agent can look at any local image path or gallery item it has been given, one image per turn. [core]
- [x] Share files out of the app. [core]

### Web
- [x] Web search (built-in public search by default, 5–10 results, title, link, snippet). [core]
- [x] Read web page (static fetch, paged; falls back to local fetching if a remote backend fails). [core]
- [x] Agent browser: off-screen browser sessions per conversation with a shared login (cookies). It can open pages, extract the article or text, find elements, click, type and submit forms, scroll, take screenshots, go back/forward, reload, and wait for an element. Each chat card shows a live page preview. [core] [battery]
- [x] The user can watch and take over a browser session ("You are taking over…"; the agent pauses on that page and can continue afterwards). [core]
- [x] The user-facing browser screen (overflow → Open browser) has a capsule address bar (search terms go to Google), back/forward, stop/refresh, and a menu with Open in another app, Close page and Clear all browser data (confirmed). [core]
- [x] Opening external links goes through the "open with app" screen action. [core]

### Image generation
- [x] "Enable AI image generation": any chat model can then call generate and edit image tools, and results show inline. [core]
- [x] Backends: Google Gemini ("Nano Banana"; AI Studio or Vertex), OpenAI & compatible, xAI Grok Imagine, Doubao Seedream (Volcengine Ark), Qwen-Image (Bailian, Beijing or Singapore), SiliconFlow. [core]
- [x] Credential source: automatically match an existing model provider (reusing its key and URL) or enter a separate key and URL. [core]
- [x] Model presets (as labelled): Gemini Nano Banana 2.1 (recommended), Gemini 3.1 Flash-Lite, Gemini 3.1 Flash, Gemini 3 Pro, Gemini 2.5 Flash; gpt-image-2.6-flare, gpt-image-2.6-sunburst, gpt-image-2.5-flare, gpt-image-2.5-sunburst, gpt-image-1.5 (gpt-image-1 flagged as retired); Grok Imagine 2.0 (up to 5 references); Seedream 5.5, 5.0 Pro, 5.0 Flash, 5.0 Lite, 4.5; Qwen-Image 2.0 Pro (recommended), 2.0; SiliconFlow Qwen-Image, Qwen-Image-Edit, Kolors, FLUX schnell; or a custom model ID. [core]
- [x] Defaults: aspect ratio (Auto, 1:1, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3), quality (Standard/High), resolution (1K/2K/4K, or fixed by the model), Doubao watermark (off), Qwen smart prompt enhancement, Qwen watermark (off). [core]
- [x] "Test generation" with a preview. Save settings. [core]
- [x] Image cards: Generating / Editing N images with an elapsed timer, then completed or failed. Actions: Save (to Pictures/Atom), Save all, Share, Edit (attaches the image to the composer for a follow-up), view the prompt. [core]
- [x] Generated images gallery: count and size, view, save, share, delete one, Clear all (confirmed). [core]

### Phone app operations
- [x] Calendar: list writable calendars, read, create one or a batch (up to 30) with reminders, update, delete (a whole recurring series only when explicit). Results are verified by reading back. [core]
- [ ] Alarms: create via the system clock (opens the clock to confirm on failure), list, change time, enable/disable, delete. [core] (edit/delete: [root] *(ColorOS)*) — **partial:** create, list, change, enable/disable and delete go through the clock app; editing or deleting in place needs the ColorOS clock and a ColorOS device
- [x] Timers up to 24 h, list active timers. [core]
- [ ] Notes: create, read, move to trash. [root] *(ColorOS)* — **missing:** ColorOS-only private data; needs a ColorOS device to build and test (decision D12)

## 14. Privacy, updates, backup/restore, export/import, logging

- [x] No telemetry or analytics SDK, no third-party download proxy. [core]
- [x] API keys, OAuth tokens, MCP tokens, and speech, web and image credentials are encrypted on the device. If a credential cannot be decrypted, the user is asked to re-enter it; it is never silently treated as empty. [core]
- [x] Sensitive tool data (OTPs, notifications, Wi-Fi passwords, logs, settings values, personal data, MCP results, memory contents) is used only within that turn and scrubbed from saved conversations. [core]
- [x] The system auto-backup is disabled. Only the manual backup exists. [core]
- [x] Data backup export: one JSON file with conversations, model providers (including API keys, with a warning), MEMORY.md and character data. [core]
- [x] Data backup import replaces current data after confirmation, is fully validated (rejects corrupt or foreign files and unsupported versions) and rolls back on failure. Running agent tasks must be stopped first. [core]
- [x] After import, subscription connections stay disabled until the user signs in again. If the default model cannot be restored, the user is asked to reselect it. [core]
- [x] Speech credentials and OAuth tokens are not included in backups. [core]
- [x] Per-conversation Markdown export. Character card export. Image save/share. File export from the workspace. [core]
- [x] Update channel: checks the project's own GitHub Releases and offers a download link. There are no automatic installs. [core]
- [x] Logging: release builds never log prompts, responses, keys, commands, file paths or tool results. [core]
- [x] "Clear all browser data" (cookies, cache, site data, sign-ins). [core]
- [x] Notification history is kept locally only (7 days, 1000 entries). [core]
- [x] Reading the models.dev catalog never sends the user's keys. [core]
- [x] Root needs are listed explicitly and nothing escalates silently. A partial result is always reported as partial. [core]

## 15. Anything else

- [x] Runs can be recovered: interrupted tasks resume or are marked interrupted, actions are tied to a run, and a second run cannot take over the phone mid-gesture. [core]
- [x] Deleting a running conversation stops and saves first. [core]
- [x] Reply language follows the user's language. [core]
- [x] Usage and token accounting, including cache hits, per session (§2). [core]
- [x] A foreground execution service holds terminal, PRoot and agent tasks, and releases itself when the last task ends. Notification stop actions reclaim the tasks. [core] [battery]
- [x] Wake lock only while an unpaused run is executing. It never wakes a phone the user locked. [core] [battery]
- [x] No polling, and no extra background timers when idle (this was an explicit design goal; the idle bubble animation was removed because it cost about 13% CPU). [battery]
- [x] Known open items in the old app (gaps, not parity targets): the bubble status pill (working / hands-on / paused / waiting approval next to the face) is not finished; keyboard-height fit is tuned to one device; old conversation titles are not rewritten.
- [x] Roadmap items that do not exist yet: wake word, cross-device agent hand-off, a third-party plugin surface.

---

## 16. Added after the full audit (items the first pass missed)

These come from the old app's screens, string resources and manifest. Each is tagged with the section it belongs to. All were checked against the new app on 2026-11-26.

### From §1 Onboarding & permissions
- [x] Startup: there is no custom splash. The system launch splash is held until the appearance settings have loaded, so the first frame already uses the right theme and UI style. [core]
- [x] The sidebar's "Permissions need attention" hint only fires for 4 core items: background running, floating window, accessibility, notifications. [core]
- [x] The Permissions page contains exactly the 12 rows listed in Appendix A, in that order. Microphone, exact alarm and all-files access are not on it. They are requested where they are used: the voice mic, the scheduled-task health banners, the workspace and shared folders. [core]
- [x] Settings root has a "Permissions" group: Floating window permissions (Authorized / Not authorized in red), Accessibility enhancement tools (Enabled / Disabled), and Enforce accessibility (LSPosed only). [core]
- [x] When local-network access is denied, explain it ("You can allow Nearby devices…") and fall back to app settings if the system page cannot be opened. [core]

### From §2 Chat & conversation UX
- [x] The sidebar dock has **5** entries in this order: Models (trailing value = active model, or "Not set"), Characters, Tools, Skills, Scheduled Tasks. [core]
- [x] The sidebar header reads "Atom" and has a new-conversation button. The empty list shows "No conversations yet" / "Tap the new chat button above to start". A search with no hits shows "No matching conversations". [core]
- [x] Conversation row anatomy:
  - The title, or the preview when there is no title.
  - A second line with the character name for character chats.
  - An accent-coloured run-status line while running.
  - Trailing: an accent dot for an active run, or a pin icon.
  - A small character avatar on the left for character chats.
  - The selected row shows a "…" button.
  - Long-press opens the action menu, with haptics. [core]
- [x] Rename dialog: title "Rename conversation", field hint "Conversation name", Save. [core]
- [x] The home overflow menu holds New conversation, Open terminal, Open browser, and an "Enable auto read-aloud" / "Disable auto read-aloud" toggle with speaker / mute icons. The toggle only shows when TTS is configured. [core]
- [x] The composer placeholder changes with context: "Ask Atom to do something" normally, "Atom is working…" while running, "Say something to <name>…" in a character chat. [core]
- [ ] Edit mode: a banner says "Sending will replace this message and everything after it" or "Sending will replace this message". The send button becomes "Save message" in character chats ("Saving updates this message in place…"). A "Cancel edit" action is available. [core] — **partial:** the banner and Cancel edit are there; character chats edit a message in an "Edit message" dialog instead of a "Save message" composer mode
- [x] A user message shows an "Edited" label once edited. Long-pressing a user bubble opens a menu with copy / edit / delete. [core]
- [x] The action row under an answer fades in only after the reveal finishes. Icon buttons in this order:
  1. Copy (turns into a check, "Copied")
  2. Read aloud
  3. Edit character reply (character chats only)
  4. Regenerate
  5. Delete
  6. Branch
  7. A per-turn stats pill, e.g. "TTFT · N tok/s" or the model time. Tapping it opens the Session stats popover. [core]
- [x] The queue strip shows text-less queued items as "[N images]" or "[N files]". "Interrupting" shows while a steer is being applied. [core]
- [x] Attachment menu entries: Image, File, Folder, Enter path. The Enter path dialog has the hint "input file path", the help text "Supports files and folders at any local absolute path", and an Add button. Possible outcomes: "The selected path has been added", "Conversation switched, selected path not added", "Added N items; M items could not be referenced". [core]
- [x] Full-screen image viewer for chat images: Save (to Pictures/Atom), Share, "Continue editing". [core]
- [x] Work header while running: "Analyzing task" or "Working · step N". When finished: "Analysis complete" or "Completed N steps". A step group shows "N calls". Each step's result reads Done / Completed / Failed. [core]
- [x] Browser tool steps show a progress line "Loading · N%" and an "Open current browser" button. [core]
- [ ] A long-history guard asks the user to scroll up to the start of the turn before continuing ("Scroll up to the user message that started this turn before continuing"). After compaction, a notice says the earlier context was compressed. [core] — **partial:** the compaction notice is there; the scroll-up guard is not
- [x] System notices that appear inline in the chat: "Stopped", "The run completed without a text response.", "Runtime failed", "Couldn't save the conversation. Please try again.", "Regeneration was interrupted. The original reply has been kept.", and the context-compaction statuses (completed, stopped, failed, interrupted). [core]
- [x] Labels for provider-hosted tools that appear in the timeline: Web search, File search, Code execution, Computer use, Image generation, MCP tool. They show as "Running <x>". [core]
- [x] The empty state in a character chat shows a 68 dp round avatar, the character name and "The story begins here". There are no suggestion cards there. [core]

### From §3 Models & providers
- [x] Provider detail is split into two tabs, "Configuration" and "Model". [core]
- [x] Anthropic providers have an editable "anthropic-version" field. [core]
- [x] Gemini providers label the hosted-search toggle "Google Search". [core]
- [x] In the model list, tapping a model sets it as current. Long-press enters multi-select with Select all / Select none. A pencil button opens "Edit model parameters". There is no separate "Set as default model" button; that string is an orphan. [core]
- [x] The model parameter sheet includes "Restore automatic" for the detected capabilities and "Covered model automatic capabilities". It also explains how Gemini and Vertex reasoning levels work. [core]
- [ ] Save outcomes for a provider: "Created and selected as the current model provider", "Saved and selected as the current model provider", "Saved, provider not enabled". A successful test reports "Connected (verified model: X)" for Vertex, or the fetched-model count. [core] — **partial:** the three save outcomes are there; Test connection shows the model's reply and the time taken, not "Connected (verified model: X)" or a fetched-model count
- [x] The models.dev catalog page:
  - A "Catalog status" card whose source line reads Built-in catalog snapshot / Local cached catalog / Online catalog, plus "Updated <time>".
  - "Importable providers (N)" and a Refresh button.
  - Typed errors for HTTP, timeout, DNS, SSL, size and format.
  - The model picker has "Import (N)". [core]
- [x] Only the Codex account card has "Manage usage", which opens the ChatGPT Codex usage page. [core]
- [x] After OAuth sign-in, the browser shows a callback page with the text "Authorization received. Return to Atom to finish signing in." or "Authorization incomplete. Return to Atom and retry." [core]

### From §4 Voice
- [x] The dictation bar has Cancel and Done. Labels: "Voice input", "Finish dictation". During hold-to-talk the hint switches to "Release to cancel" once the finger slides off. After release it reads "Transcribing and sending…". [core]
- [x] Progress copy: "Waiting for file transcription, tap to cancel anytime". The overlay input placeholder is "Message Atom…", and the mic hint is "Tap to speak". [core]
- [x] Default preview text for "Preview voice": a short self-introduction followed by a mock meeting reminder (the "Hi, I'm Atom…" string). [core]
- [ ] Voices are grouped as "Female" / "Male". MiMo also has language-specific defaults (Chinese and English, female and male). [core] — **partial:** voices are grouped Female / Male; MiMo has one "Default" voice instead of separate Chinese and English, female and male defaults

### From §5 Screen control & automation
- [x] Run capsule copy:
  - The capsule is labelled "<status>. Tap to show controls".
  - The input field hint reads "Add instructions; Atom continues the current task".
  - Status texts: Reasoning, Generating answer, Stopping, Paused, Continuing, "Finishing up. Add input after the result appears", "Additional input not supported here", "Additional input received. Continuing", Result ready, Request failed. [core]
- [ ] The virtual-display stage card shows a "Background canvas" label in automatic mode and "Manual control" during takeover. [root] [lsposed] — **partial:** the stage card reads "Atom is working here" / "You are in control" instead; not yet checked on a rooted or LSPosed phone
- [x] The background virtual display switch lives on the **Settings root** (Tools group), default on. It does not live on System enhancements. [root] [lsposed]

### From §8 Terminal & Linux
- [x] The terminal toolbar has an environment segmented switch on the left: Android and the selected Linux distribution. The toast reads "Switched to X". On the right are icons for Sessions, Background tasks, and the Console / Simple mode toggle. [core]
- [x] If Linux is not installed, the terminal shows "Linux environment is not installed yet" with "Install now". Simple mode's empty hint explains that this is a persistent shell. [core]
- [x] Sessions dialog: "New session", rows marked Current / Running / Idle / Exited, Restart, Close. "Session ended; reconnected" and "Reconnect" appear when a session drops. [core]
- [x] Linux page sections appear step by step: Files and directories (shared folders and file browser need the base environment); Optional tools need the base tools. [core]
- [x] Default distribution: Debian, unless only Alpine is already installed. [core]
- [x] Shared-folder dialog: a directory picker ("No subfolders here", "Cannot open this directory"), a Mount name field, and "Mount: <path>". Removing asks for confirmation ("Files in the source directory are not deleted"). [core]

### From §9 Memory, skills, MCP, subagents, scheduled tasks, characters
- [ ] Skills has a "Removed" group: a missing built-in skill shows "Click to reinstall". Built-in skills have no delete action. User skills have a "More actions" menu with View description and delete. [core] — **partial:** built-in skills have only a switch and user skills have the More actions menu; there is no "Removed" group with "Click to reinstall" (built-in skills cannot be removed, so it would never show)
- [x] Toasts after a skill change: "\"X\" was removed from Atom." and "\"X\" is enabled and will be available from the next conversation turn." [core]
- [x] MCP: enabling a tool that is not read-only asks "Enable this tool?" with an explanation. The truncation banner names the reason: the 2,048-tool limit, the 64-page limit, the size budget, or a duplicate cursor. [core]
- [x] Subagent settings: built-in profiles are listed by their lowercase names (explore / coder / plan) and only open a read-only view. "New Subagent" is in the empty state and as "+" in the top bar. [core]
- [ ] Subagent details sheet: "Prompt" (or "Prompt (Resumed)"), "Streaming" or "Summary", "N earlier steps folded", token chips "In / Cached / Out". Status values: Running… / Stopped / Incomplete / Completed · N tool calls. [core] — **partial:** everything except the "Prompt (Resumed)" label
- [x] Scheduled task detail has an "Enable schedule" switch, with the summaries "Automatically wakes…" and "Automatic triggers paused; you can still run manually anytime". [core]
- [x] Scheduled task toasts: "Started background test run", "This task is already running", "Stopping current run…", "Task memo cleared", "Failed to save scheduled task". [core]
- [x] Scheduled task defaults are listed in Appendix A: show-progress off, device controls off, quiet hours off, 5 consecutive failures before auto-pause. [core]
- [x] Run timeline rows show a trigger source (Scheduled / Manual run / Catch-up), a status (Running, Delivered, No change, Skipped, Failed, Timed out, Stopped) and a duration. Tapping one gives "Tap to watch live progress" or "Tap to view full conversation and follow up". [core]
- [ ] Default character: one built-in character named "Xiaoman". When the library is empty, it offers "Restore default character". [core] — **partial:** the built-in character is "Lumen", with "Restore Lumen" when it is gone; shipping "Xiaoman" instead is an owner decision (Q6)
- [x] Character detail layout:
  - Hero: avatar with "Change avatar", name, "By <creator>", Favorite.
  - A greeting chooser with "Read full text"; if there is no greeting, "No preset greeting — you start the conversation".
  - Start chat.
  - Use my persona switch and Edit my persona.
  - "Character profile".
  - A "Manage" group: Edit character, Story memory, Duplicate character, Export PNG character card, Export JSON character card, Compatibility notes, Delete character. [core]
- [x] Story memory page: Reload and Save memory. A conflict notice appears if a chat changed it meanwhile: "Story memory was updated by a conversation. Your draft is preserved…". [core]
- [x] Editor note: "Third-party scripts and extension UIs in character cards are not executed, and their data is preserved when exporting." [core]

### From §10 Floating bubble & overlay
- [x] Mini-chat model chip shows "No model" when nothing is configured. [core]
- [x] The mini-chat empty state uses the Atom/octopus emblem image. [core]
- [x] The bubble's status line reads "<status> · hold the bubble for actions". [core]

### From §11 System integration
- [x] Execution notification: title "Atom is running", text "N task(s) are running. Return to Atom to check progress.", actions Open and Stop. Low importance. [core]
- [x] Background-command notification: title by outcome (completed, timed out, stopped, interrupted, failed), "Exit code N", and the hint "View status and logs under background tasks in Terminal". [core]
- [x] Scheduled notification titles: "Task failed: X", "Task paused: X", "Ready to run: X". Fallback bodies: "Scheduled task completed. Tap to view full conversation." and "Execution failed. Tap to inspect details." [core]
- [ ] Assistant voice notification body: "Active only while the assistant overlay is visible". If the system blocks audio, the error is "The system does not allow the overlay to use audio…". [core] — **partial:** the body text is there; the "system does not allow the overlay to use audio" error is not
- [ ] LSPosed module scope declared by the app: system, SystemUI, Google app, ColorOS ColorDirectService, ColorOS AI memory (com.oplus.aimemory), Xiaomi voice assistant, and the Xiaomi home launchers (CN and global). [lsposed] — **partial:** the scope covers system, SystemUI, the Google app and the Xiaomi launcher; ColorDirectService, ColorOS AI memory, the Xiaomi voice assistant and the global Xiaomi launcher are not listed
- [ ] The ASSIST bridge activity runs translucent, is excluded from recents, has no history, can show over the lock screen and runs in its own `:voice` process. Recognition runs in its own `:recognition` process. [core] — **partial:** the lock-screen bridge is translucent, left out of recents and shows over the lock screen; it and recognition run in the main process, not in separate :voice / :recognition processes
- [ ] Cleartext HTTP is allowed app-wide (needed for LAN models and MCP). Only English is declared as a locale. [core] — **partial:** cleartext is allowed app-wide; the English-only locale list is not declared (the app only has English text, so users see no difference)
- [x] File-share provider paths: the generated-images folder and a cache folder for shared images. [core]
- [ ] An app-private signature permission guards the accessibility-protection control. A health provider is readable only by holders of the system manage-accessibility permission. [lsposed] — **partial:** accessibility protection runs inside the module; there is no signature-guarded control and no health provider

### From §12 Settings
- [x] A "System assistant takes over" group on the root, and a "Circle to Search" group that only shows when LSPosed has been seen (see Appendix A). [core] [lsposed]
- [x] An "about" group on the root (Version, Check for updates, Feedback, Source Code). The spec puts About under General, but it is a separate group. [core]
- [x] Image-gen row summary "On · <backend>" or "Off". [core]
- [x] Many labels really are lowercase in the shipped strings: page title "set up", group titles "tool" and "about", buttons "test connection", "create", "save", "delete", "edit", "copy", "return", "reset", "repair", and row labels such as "usage access" and "location permissions". Decide on purpose whether to match this or fix it; it looks like an unfinished translation pass. [core] *New app: proper capitals on purpose (decision D6, easy to revert).*

### From §13 Files, web, image generation
- [x] Browser address bar: placeholder "Search or enter address". Back and Forward buttons, Stop loading / Refresh. A "More options" menu with Open in another app, Close page, Clear all browser data (confirmed). Error text: "No app can open this webpage". [core]
- [x] Image-gen error texts users can see:
  - The gpt-image-1 retirement notice.
  - "The current model (X) does not support image editing…".
  - Reference-image count limit.
  - Redirect blocked for credential safety.
  - A per-HTTP-status explanation. [core]
- [x] Image card: when the local file was cleared it shows "Local image was cleared". After Edit it says "Attached to input. Describe the changes you want.". The prompt is under a "Prompt" label. [core]
- [x] Gallery: header "N images · size used (tap to view, save, or share)". Deleting one shows the warning "this local original image can no longer be loaded in chat history". Empty state: "No generated images yet" / "Enable AI image generation and ask Atom to draw…". [core]

### From §14 Privacy, backup
- [x] Backup **does include** character data (characters, avatars, story memory, persona, chat links, reply candidates), even though the on-screen copy says it only includes conversations, providers and MEMORY.md. It **excludes** all app settings: appearance, speech, web tools, image gen, MCP servers, skills, scheduled tasks, subagents, tool switches, and workspace files. [core] *New app: characters are included too; it also saves app, speech and appearance settings, and the screen lists what is saved.*
- [x] Backup import refuses to run while agent tasks are running ("Stop running agent tasks first") or while conversations are still saving ("Wait for conversations to finish saving"). [core]

### From §15 Other
- [ ] Settings use small coloured icons per row in the COUI skin (blue, orange) and plain rounded Material icons in Miuix. Brand logos for providers and model families come from a bundled set of brand logos (the old set is from Lobe Icons, MIT). [core] — **partial:** Miuix rounded icons and the bundled brand logos are in; the coloured COUI icons wait for the COUI style

---

## 17. Corrections to the sections above

Where these disagree with an item above, these win.

1. **§12 Settings root is wrong.** The real groups, in order: LLM providers → Context & extensions → tool → an untitled "Root and system enhancements" card → System assistant takes over → Circle to Search (LSPosed only) → General → Permissions → about. Details:
   - The Tools group holds Tool list, Web search and reading, AI image generation, the **Background virtual display** switch, and Linux tool environment.
   - The Tools group does **not** contain Permissions, System enhancements or Workspace and files.
   - General holds Keep screen on, Appearance, Speech, Language and Data backup. It does **not** hold Floating assistant or About.
   - Floating assistant sits under "System assistant takes over".
   - The page title is "set up", not "Settings".
2. **§12 Tool list "master switches" do not exist in the UI.** The strings "Enable web search, reading and browser", "Enable device direct tools", "Allow reading/operation of sensitive…" and "Enable terminal/file tools" are orphans. The Tools page uses **one switch per tool**, plus Enable all / Disable all per group and an "All n/m" chip filter. "Enable memory" lives on the Memory page. "Provider-hosted web search" lives in each provider's detail page. Image generation is enabled on its own settings page.
3. **§12 Tool group order is wrong.** The real order: Screens and controls, Text and clipboard, Web tools, Applications and Systems, System and built-in apps, Personal context, Document vision, memory, Terminal and files.
4. **§12 System enhancements page contents are wrong.** It only has the Root row (Authorize / Check again), Framework connection, Module activation and scope help, and informational rows for root and system capabilities. The digital assistant row, the power-key picker, auto-set default assistant, both Circle to Search switches, Enforce accessibility and the virtual display toggle are all on the **Settings root**.
5. **§1 Permissions page.** There is no overall "All good" / "N items need attention" health line; that card exists in the code but no screen uses it. There are no "Grant" or "Go to settings" buttons. Each row is an arrow row with a coloured status label on the right, and tapping the row runs the action.
6. **§12 Appearance.** "Blur effects" and "Top bar blur style (gradient band or progressive)" are **not in the UI** (orphan strings). Only "Progressive blur" exists, on Miuix only and only when runtime shaders are supported. Monet defaults to **off**, and palette, accent and the inner pure-black switch only show when Monet is on. The COUI skin hides Monet, accent, pure black and blur entirely.
7. **§10 / §12 Floating bubble is on by default.** The spec calls it "optional". The switch default is on, and it sits between Assistant model and Reasoning level on the Floating assistant page.
8. **§10 Bubble face.** The current build draws the bundled **Atom/octopus emblem PNG** (44 dp) on a dark 60 dp circle, inside the 74 dp ring. It does not draw a vector "minimal atom glyph"; the docs and CHANGELOG describe a glyph drawable that is no longer in the repo.
9. **§3 Default model.** Code picks the first *enabled* provider in sort order and its first model. All 12 built-in providers are enabled, and OpenAI is first, so a clean install lands on **OpenAI's first preset model**, not DeepSeek Flash. The DeepSeek selection in the screenshots was probably chosen by hand.
10. **§3 Provider detail.** There is **no "extra request-body JSON" field** in the UI. An error string for it exists internally, but nothing exposes it. There is an extra "anthropic-version" field for Anthropic providers.
11. **§3 "Set as default model".** It is not a button: tapping a model row sets it current. The "Set as current" / "Set as default" strings are orphans.
12. **§8 Linux health check.** "Health check covers core commands, /workspace, /sdcard and free space, with Repair", "Continue installation" and "Upgrade tools" are **not reachable in the UI**; those strings are orphans. The page offers Install base → Install base tools → per-profile Install, with progress and "Not enough space" messages. Debian package recovery only appears as an installer error text.
13. **§4 Speech.** "Filter Markdown formatting before reading aloud" is a **DeepSeek-TTS-only** toggle, default on. It is not a general setting; read-aloud always strips tables, code and URLs.
14. **§2 Overflow menu.** The 4th item is the auto read-aloud toggle, shown only when TTS is configured. There is no generic "more" item.
15. **§2 Sidebar dock.** It has 5 entries (Models, Characters, Tools, Skills, Scheduled Tasks), not 2.
16. **§1 "With no provider configured, the empty state tells the user to add one".** The home screen shows the normal "How can I help you?" state with its four cards. The "There is currently no model provider, please add one." string is an orphan. Only sending is blocked ("Please configure the model provider and model first"). The model capsule shows "Not selected" and the dock shows "Not set".
17. **§9 Skills.** Built-in skills cannot be deleted from the UI; they only have a switch. Removed built-in skills show in a "Removed" group with "Click to reinstall".
18. **§14 Backup scope.** Character data is included, contrary to the UI copy. Settings of every kind are excluded; see §16 (From §14).
19. **§11 Power-key target picker.** It only shows when the LSPosed framework has been seen, and it can only be changed while connected. Default "System default assistant". Changing the target does not move existing users who had the old boolean takeover setting.
20. **§9 Characters.** CHARACTERS.md says "Atom's UI does not display avatars", but the current UI does show them: a round avatar in the conversation row, the character chat empty state, the detail hero (with "Change avatar") and portrait cards. Follow the code.
21. **§1 "Root help text names Magisk, KernelSU…".** That text appears in the Linux requirements lines and in the root-status descriptions; the System enhancements page has no dedicated help block about it.

## Gap list (ordered by what users notice most)

Everything users meet day to day (the Miuix look, first run, home chat, drawer, bubble, settings tree, model providers, messages, permissions, tool list, notifications, voice and the assistant overlay) now matches. What is still open:

1. **COUI UI style** (§12 UI style, §15 coloured row icons): missing. *Deferred on purpose (D8): Miuix is the old default and is done; COUI is a second full skin.*
2. **ColorOS-only personal data** (§6 notes, system memory, orders and saved places, bills and bill summaries, to-dos and trips; §13 notes; recording summaries and transcripts): missing. *Needs a ColorOS device and its private providers to build and test (D12).*
3. **ColorOS clock details** (§6 full alarm list, §13 edit or delete an alarm in place): partial. *Android's alarm intents cover create, change, enable and delete through the clock app; the rest needs the ColorOS clock on a ColorOS device.*
4. **Defaults that differ on purpose** (§4 recognition service, §16 default character): partial. *Needs an owner decision: the phone's own recognizer instead of Qwen Realtime (D15), "Lumen" instead of "Xiaoman", and DeepSeek Flash as the starting model where the old code picked OpenAI's first model (Q6).*
5. **Chat edge cases** (§16 long-history scroll-up guard; "Save message" composer mode in character chats): partial. *Small follow-up; character chats already edit in place through a dialog.*
6. **Voice and notification copy** (§11 separate "Read aloud" channel, §16 overlay audio-blocked error, MiMo language-specific default voices): partial. *Small follow-up; read-aloud already works through the Assistant voice channel.*
7. **Skills** (§9 curated skill catalog, §16 "Removed" group): partial. *GitHub and ZIP install work; a catalog needs a list the owner is happy to ship.*
8. **Provider test wording** (§16 "Connected (verified model: X)" / fetched-model count): partial. *Small follow-up; the test already shows the model's reply and timing.*
9. **Subagent details** (§16 "Prompt (Resumed)" label): partial. *Small follow-up.*
10. **Background display stage labels** (§16 "Background canvas" / "Manual control"): partial. *Different wording today; needs a rooted or LSPosed phone to check on screen.*
11. **QQ and WeChat chat-image caches** (§6): partial. *No dedicated tool yet; needs a rooted phone with those apps to build and test.*
12. **Manifest internals users never see** (§16 LSPosed scope for ColorOS and Xiaomi assistant packages, separate :voice / :recognition processes, declared locale list, signature-guarded accessibility-protection control and health provider): partial. *Low priority; the extra scopes need ColorOS and HyperOS devices, and extra processes cost memory.*

New-app extras that stay, each now placed in the Miuix settings tree: Off/Low/Medium/High/XHigh/Max reasoning levels, on-device models (LiteRT and llama.cpp) with cloud fallback, the "Hey Atom" wake word, audio chat, Linux root mode (chroot) next to PRoot, god-mode device controls with the undo journal (Change history), power tools, Shizuku tier, event-triggered automations, notification history, messenger triage, text size.

## Top 25 most important for users

1. Chat with streaming Markdown and LaTeX, reasoning display, and a collapsible work timeline.
2. 12 preset providers with preset models, plus a clear global default (DeepSeek Flash on a fresh install) and a per-conversation model picker.
3. Custom OpenAI-compatible, Anthropic and Gemini/Vertex providers, models.dev import, remote model pull, test connection.
4. Subscription sign-in: ChatGPT/Codex and Grok, with model sync.
5. Per-conversation reasoning effort ("Deep Think") with capability detection.
6. Screen control through accessibility, with an observe-act-verify loop and honest "unconfirmed" reporting.
7. Run capsule with pause, take over, steer ("Add input") and stop.
8. Background virtual display plus "present to main screen" hand-back for payments and QR codes (root/LSPosed).
9. Floating bubble: tap for mini chat, hold menu (hide / chat / talk), drag-to-dismiss, remembered position.
10. System digital-assistant overlay with automatic screen context, voice first, and suggestion chips.
11. Voice: dictation, hold-to-talk, auto-send after a pause, many ASR and TTS services and voices, auto read-aloud, system-voice fallback.
12. Personal context: notifications plus 7-day history, OTP, SMS, contacts, calls, calendar, photos, files, downloads, location, usage.
13. Device controls with verified before → after: volume, DND, sound mode, flashlight, brightness, toggles, media.
14. Root extras: freeze/stop apps, protected settings, logs, Wi-Fi passwords, private app data.
15. Terminal (PTY plus simple mode, multiple sessions) and Alpine/Debian Linux with Python, Node, SSH and APK tools, in PRoot or chroot.
16. Background commands and daemons with completion notifications.
17. Long-term memory (MEMORY.md) that the user can edit and the agent maintains.
18. Scheduled tasks with presets, cron, per-task capabilities, quiet hours and notify-on-change.
19. Skills (built-in plus ZIP/GitHub install) and MCP servers with per-tool enabling.
20. Subagents (Explore / Coder / Plan / custom).
21. Agent browser with live preview and user takeover, plus web search and reading backends.
22. AI image generation and editing across 6 backends, with a gallery.
23. Message queue and steering, regenerate, edit, branch, export, search, pin, context compaction.
24. Backup/restore (JSON), encrypted credentials, no telemetry, scrubbing of sensitive data.
25. Appearance: Miuix/COUI styles, Monet palettes, accent colours, true black, blur, swipe-back.

---

## Appendix A. The old app's screens and settings tree

Written from the old app's screens and string resources. Titles are the shipped copy (including its lowercase labels).

### A.1 Navigation map

The app has one main activity with a saved route stack, plus two separate windows: the browser activity and the voice-assist bridge. In-app pages use the Miuix nav transitions. When "Swipe back gesture" is on, a horizontal swipe anywhere goes back. The top-bar titles below come from the shell's route-to-title table.

| Route / screen | Top-bar title copy | How it is reached |
|---|---|---|
| Home (chat) | none. The centre shows a model capsule, or the conversation title with the model as subtitle. In a character chat it shows a character header that opens the character | App launch. Share-to-Atom (SEND and SEND_MULTIPLE, any MIME type) lands here |
| Conversation side pane (not a route) | header "Atom" with a new-conversation button | Drawer button (label "Conversation history") or a horizontal drag on Home |
| Browser (separate activity, uses system back) | address capsule, no title | Home overflow → "Open browser". A tool card's "Open browser" or "Open current browser". Opens the browser session of the *current conversation*, or a manual session if no conversation is selected |
| Terminal | "Terminal" | Home overflow → "Open terminal". A background-command notification hint points here |
| Settings | **"set up"** (lowercase, as written) | Side pane bottom → Settings button |
| Permissions | "Permissions" | Side pane bottom → Permissions button. "Permissions need attention" hint in the pane. Tool card → "Manage permissions". Home action |
| System enhancements (title "Root and system enhancements") | "System enhancements" in the shell, "Root and system enhancements" on the page | Settings root (a card with no group header). Tools page card. Linux status card when root is lost. Permissions → "Root and system enhancements" row |
| Tools | "Tools" | Settings → Tool list. Side pane dock → Tools |
| Web search and reading | "Web search and reading" | Settings → Tools group. Tool card → "Configure service" |
| AI image generation | "AI image generation" | Settings → Tools group. Tool card → "Configure image generation" |
| Generated images (gallery) | "Generated images" | Image-gen settings → "Local images" → "Generated images gallery" |
| Skills | "Skills" | Settings → Context & extensions. Side pane dock → Skills |
| Scheduled Tasks | "Scheduled Tasks" | Settings. Side pane dock → Scheduled Tasks. Notifications |
| Task Details | "Task Details" | Tapping a task row. Result and alert notifications |
| New / Edit Scheduled Task | "New Scheduled Task" / "Edit Scheduled Task" | List "+" or a Quick start preset card (pre-filled). Edit from the task detail |
| Subagent | "Subagent" | Settings → Context & extensions |
| Auto-generate titles | "Auto-generate titles" | Settings → Context & extensions |
| Floating assistant | "Floating assistant" | Settings → "System assistant takes over" group |
| Characters (library) | "Characters" | Settings → Context & extensions. Side pane dock → Characters |
| Character details | "Character details" | Tapping a character card |
| Edit character | "Edit character" | Library "Create character". Detail → Manage → "Edit character" |
| My persona | "My persona" | Library entry row. Detail → "Edit my persona" |
| Story memory | "Story memory" | Character detail → Manage → Story memory |
| Speech | "Speech" | Settings → General |
| Speech Recognition / Spoken Answers | same | Speech overview rows |
| Recording Storage (OSS) | "Recording Storage" | Speech Recognition → "Configure your own OSS bucket" row. Shown only for the file-transcription service |
| Appearance & Theme | "Appearance & Theme" | Settings → General |
| Data backup | "Data backup" | Settings → General |
| Memory | "Memory" | Settings → Context & extensions |
| Linux environment | "Linux environment" (the settings row reads "Linux tool environment") | Settings → Tools group. Terminal's "Install now" when Linux is not installed |
| Workspace and files | "Workspace and files" | **Only** from Linux environment → "Files and directories". There is no settings-root row |
| Shared folders | "Shared folders" | Linux environment → Files and directories. Appears once the base environment is ready |
| Linux files | "Linux files" (the row is "File browser") | Linux environment → Files and directories. Appears once the base environment is ready |
| Model providers | "Model providers" | Settings → LLM providers. Side pane dock → Models (shows the active model as a trailing value, or "Not set") |
| Add from models.dev | "Add from models.dev" | Model providers → Add new provider group |
| Select models (catalog provider) | "Select models" | Picking a provider in the catalog |
| Provider details / New provider | "Provider details" / "New provider" | Provider row. "Add OpenAI-compatible provider" / "Add Anthropic provider" / "Add Gemini" |
| MCP servers / MCP server | "MCP servers" / "MCP server" | Settings → Context & extensions. A server row |

Overlays and system surfaces that are not routes:
- The floating bubble with its hold menu, the mini-chat panel and the drag-to-dismiss target.
- The run capsule, the virtual-display stage card and the result card.
- The full-screen assistant overlay.
- Notifications.
- Bottom sheets: "Choose model", subagent details and "Add models" (remote import).
- Dialogs for rename, delete, regenerate, update available, item description ("Got it"), MCP add/token/delete, skill replace/delete, and memory clear.

---

### A.2 Settings tree (real order, from the Miuix layout; COUI has the same rows)

Settings page, title "set up". Each group is a small title over a card. Rows have a leading coloured icon.

**LLM providers**
1. Model providers. Arrow row. Summary "<provider> / <model>", "<provider> / No model selected", or "Not configured".
2. Reasoning enabled by default. Switch, default **on**.
3. Default reasoning effort. Dropdown of the efforts the current default model supports, without Off. "Default" appears as "Model default". Disabled when reasoning is off. If the model offers one level or none, a disabled row reads "Not supported by selected model".

**Context & extensions**
1. Automatic context compaction. Switch, default **on**.
2. Auto-generate titles. Arrow.
3. Memory. Arrow.
4. Subagent. Arrow.
5. Skills. Arrow.
6. MCP servers. Arrow.
7. Scheduled Tasks. Arrow.
8. Characters. Arrow.

**tool** (the group title is lowercase in the strings)
1. Tool list. Arrow.
2. Web search and reading. Arrow.
3. AI image generation. Arrow. Summary "On · <backend short name>" or "Off" (COUI shows the backend or "Off" as a trailing value).
4. Background virtual display. Switch, default **on**.
5. Linux tool environment. Arrow.

(A card with no title) Root and system enhancements. Arrow.

**System assistant takes over**
1. Digital assistant app. Arrow. The trailing value is "Atom", the other app's name, or "Not set", shown in green when Atom is the default. Opens the system default-assistant picker. If that fails, a toast says "Couldn't open default assistant settings".
2. Floating assistant. Arrow.
3. Long press the power button. Dropdown: "System default assistant" / "Gemini" / "Atom", default System default. *Shown only when the LSPosed framework has connected at least once, and editable only while it is connected.*
4. Automatically set default assistant. Switch, default **off**. Same visibility rule.

**Circle to Search** (the whole group shows only when the framework has been seen)
1. Touch and hold the gesture bar to start Circle to Search. Switch, default **on**.
2. Touch and hold with two fingers to start Circle to Search. Switch, default **off**.

**General**
1. Keep screen on while running. Switch, default **on**.
2. Appearance & Theme. Arrow.
3. Speech. Arrow.
4. Language. Dropdown: "Follow system" / "English".
5. Data backup. Arrow.

**Permissions**
1. Floating window permissions. Arrow. Trailing "Authorized" (normal colour) or "Not authorized" (red). Tapping opens the system overlay settings only when the permission is missing.
2. Accessibility enhancement tools. Arrow. Trailing "Enabled" or "Disabled" (accent colour). Opens system accessibility settings.
3. Enforce accessibility. Switch, default off. Shows only when the framework has been seen. Disabled while a request is pending. Toasts on failure: "The accessibility protection backend is unavailable. Enable the system scope and restart" or "The system rejected the accessibility protection request".

**about** (lowercase)
1. Version. Info row, "3.4.5 (2026112201)".
2. Check for updates. Arrow. The summary reads "Checking…" while it runs. The result is a toast ("Already on the latest version" or "Update check failed. Try again later"), or a dialog "Update available" with "Latest version X, current version Y" and a "Download" button that opens the release URL.
3. Feedback. Arrow, opens the GitHub issues page.
4. Source Code. Arrow, summary "If Atom helps you, please star it on GitHub — it means a lot to us", opens the repo.

If a switch fails to save, a toast says "Couldn't save settings".

#### Sub-pages

**Auto-generate titles**: group "General & Model".
- Enable auto-title. Switch, default on.
- Title generation model. Arrow that opens a model sheet. Default "Follow conversation model (<name>)", hint "Uses the current conversation or default model".
- Reasoning level. Dropdown: "Auto (minimal / off)" (default), "Model default", then the levels.
- Footnote about using a light model to save tokens.

**Floating assistant**: group "Model & Reasoning".
1. Assistant model. Arrow that opens a sheet. Default "Follow primary model (<name>)".
2. **Floating bubble**. Switch, summary "Show the always-on atom bubble; tap it for quick chat access", default **on**.
3. Reasoning level. Dropdown: "Follow default" plus the levels. Disabled with "Not supported…" when there are none.
- Footnote about the power-key / system-assistant overlay.

**Subagent**
- "General & Model": Enable Subagent (switch, default on); Subagent model (sheet, "Follow main model (<name>)"); Reasoning effort (dropdown, "Follow model default" or "Auto" plus the levels). The last two are disabled while Subagent is off.
- "Built-in Subagents": explore, coder, plan. Lowercase names, each with a one-line summary. Tapping one shows a read-only inspection.
- "Custom Subagents": empty state "No custom Subagents" with an explanation and a "New Subagent" button. "+" in the top bar.
- Editor fields: Name (kebab-case), Description, When to use, Allowed tools, System instructions. Save. Delete is confirmed.

**Tools** (title "Tools")
- Segmented tabs "Current device" / "All capabilities". The default is Current device.
- A horizontal chip strip: "All n/m" plus one chip per group "<Group> n/m".
- A card "Root and system enhancements" (summary "Root authorization and framework status"), shown when the filter is All.
- Groups in this order, each with the header "<Group> · n/m" and an **Enable all / Disable all** pill:
  1. Screens and controls
  2. Text and clipboard
  3. Web tools
  4. Applications and Systems
  5. System and built-in apps
  6. Personal context
  7. Document vision
  8. memory
  9. Terminal and files
- Every tool is its **own switch row**: icon (tinted when on), title, info button that opens a description dialog with "Got it", and an optional action link ("Configure service", "Configure image generation", "Open browser", "Manage permissions").
- A requirement line where it applies: "Root access required", "Available after enabling accessibility", "Notification access required", "Usage access required", "Location access required", "Available on ColorOS", "Using standard capabilities; Root enables additional actions".

**Web search and reading**
- "Service": Web search and Web page reading, each a picker of Built-in default (default) / DeepSeek / Custom endpoint.
- Two notes.
- DeepSeek block: API Key (optional) with a linked-provider note, plus an "Advanced" expander holding the base URL.
- "Custom search" and "Custom reading" blocks: HTTP method, Endpoint URL, API Key, an expander "Request template and response fields" (body template, results path, title / link / snippet field paths, or the body field path for reading), custom headers, and placeholder notes.
- Save button ("Saving…").

**AI image generation**
1. Enable AI image generation. Switch, default **off**. Turning it on with no key gives the error "Enter an API key or link a configured model provider…".
2. "Image service": Provider (default **Gemini**), Credential source (auto-match a provider "<name> (No API key)" / "Enter standalone API key"), Model presets (with "Official default" and "Custom model ID"). Footer: "Reusing the API key and endpoint…".
3. "Connection & model": Platform (Gemini: AI Studio or Vertex), Vertex project and location, a per-backend base URL, Bailian region (China North 2 (Beijing) / Singapore (International)), Model ID (or "Model ID or Endpoint ID"), API Key with a visibility toggle.
4. "Default preferences": aspect ratio (default Auto), quality tier (Standard (Auto) / High quality (High)), resolution (default 1K, or "<x> (fixed by model)"). Toggles for Doubao watermark, Qwen smart prompt enhancement and Qwen watermark.
5. Buttons "Test generation" ("Generating…", preview "Test succeeded, tap to view full image"; the test prompt is an orange cat in a blue scarf) and "Save settings".
6. "Local images": Generated images gallery row, summary "N images · size" or "No local images yet".

**Permissions** (page title "Permissions", one group "Permissions and status")
- Arrow rows in this order:
  1. Background running permission
  2. Floating window permissions
  3. Application list reading
  4. Local network access
  5. Calendar access
  6. Do Not Disturb access
  7. location permissions
  8. Notice of use rights (notification history)
  9. usage access
  10. Accessibility permissions
  11. Background task notifications
  12. Root and system enhancements (summary "Optional enhancement permissions")
- Each row shows a coloured status on the right: Available / Missing / Issue / Not enabled. Tapping the row runs its action.
- Summaries change with state. Location, for example, reads "Used to understand the location of mobile phones on demand", "Location is available while the app is in the foreground; background tasks require additional location authorization", "System location service is turned off" or "Only read when the Agent calls the tool".

**Root and system enhancements**
- First card: "Root" row (status text plus an "Authorize" or "Check again" button) and "Framework connection" (connected or disconnected text).
- Card: "Module activation and scope" (help text).
- Group "Root capabilities", informational rows: App and system management; Protected data and files; Linux and terminal enhancements.
- Group "System assistant and enhancements", informational rows: Gemini and Circle to Search; Accessibility protection.
- **This page has no switches.** The power-key, Circle to Search, enforce-accessibility and virtual-display controls are all on the Settings root.

**Appearance & Theme**
- Miuix:
  - "Colors & Theme": UI style (Miuix (default) / COUI); Theme mode (Follow system default / Light / Dark); Monet colors (switch, default **off**). When Monet is on, Color style (default Tonal Spot), Accent color (default "System colors") and a Pure black background switch appear.
  - A separate card: Pure black background (default **on**). It appears twice while Monet is on.
  - "Interface & Effects": Swipe back gesture (on); Predictive back gesture (on); Progressive blur (on). Progressive blur only shows when the device supports runtime shaders.
- COUI: only UI style, Theme mode, Swipe back gesture and Predictive back gesture. No Monet, accent, pure-black or blur rows.

**Speech** (overview)
- "Service": Speech Recognition (summary: the service name, or "Disabled"); Spoken Answers (summary: the service name or "Off").
- "Assistant": Speak answers automatically. Switch, default off. Disabled when TTS is Off.
- Footnote "Tap the mic in chat to insert editable text…".

**Speech Recognition**
- Enable speech recognition (switch, default on).
- "Service": Service & model picker (default Qwen · Realtime), then a service note.
- Connection fields for the chosen service: region, key, base URL, resource ID, legacy auth, Advanced expander.
- Recognition language (text field, or a picker for MiMo).
- "Recording Storage" row, for file transcription only.
- "Recognition result": Auto-send after pause (1 s, 1.5 s, 2 s default, 3 s, Off) with its note; Smart correction (switch, default off).
- Test recognition. Save.
- When recognition is disabled, an explanatory note replaces the service sections.

**Spoken Answers**
- "Service": Speech service (default Off).
- Then connection, voice and model fields.
- "Filter Markdown formatting before reading aloud": **DeepSeek TTS only**, default on.
- "Preview voice" group with preview text and a Preview / Stop preview button.
- Save.

**Recording Storage**: note; "Storage" (Region, endpoint, bucket, "Directory prefix"); "Credentials" (AccessKey ID and secret).

**Data backup**
- Warning card "Keep the backup file safe".
- Group "Backup": Export data ("Save conversations, providers, and MEMORY.md to a JSON file"); Import data, with a confirmation dialog "Import Atom data?".
- Toasts: "Exported N conversations and M providers" and "Imported N conversations and M providers".

**Memory**
- Enable memory (switch, default on, with an explanation).
- Core memory injection budget (info, "Inject up to N characters per turn…").
- Editor card with Preview / Edit modes, empty state "No memories yet", and a seeded template with a "# core memory" heading, user name and long-term preferences.
- "Unsaved changes" / "Over the 1 MiB limit…" status, Discard, Save.
- Clear, with the dialog "Clear all memory?".

**Skills**
- "Import from ZIP" button ("Checking skill package", "Verifying and installing. Please wait").
- Group "Built-in": switch rows only.
- Group "Installed by you": switch rows plus a "More actions for <name>" menu with View description and delete.
- Group "**Removed**": built-in skills that are missing, each a row "Click to reinstall".
- Empty state "No Skills installed yet".

**MCP servers**
- "Add server" opens a dialog: Name, Server URL, Bearer token (optional), "Connecting…".
- "Servers · N" list. Rows read "x of y tools enabled", with " (truncated)" when the catalog was cut.
- Empty state "No MCP servers" / "Tap to add a server".
- Detail:
  - "Server" group: Enable server, Authentication ("Bearer token configured" / "No authentication"; a dialog to change it, where blank removes the token), Refresh tools.
  - "Tools · N" with a "<server> · N enabled" header and Enable all / Disable all. Tool rows show "Read-only tool" or "May modify data or call an external service" and an expandable description. Enabling a risky tool asks "Enable this tool?". Empty state "No compatible tools".
  - "Delete server" ("Remove this endpoint, its token, and tool settings").

**Scheduled Tasks list**
- Health banners, shown when needed:
  - "Allow unrestricted background battery" → "Open settings"
  - "Exact alarm permission not granted" → "Allow exact alarms"
  - "Notifications are turned off" → "Enable notifications"
- "Quick start" with the 4 preset cards.
- Intro text.
- "All tasks · n/m enabled". Rows show name, "Next <time>", status ("Running in background…", "Waiting for first run", "Paused") and a switch.

**Task editor defaults**
- Schedule: Daily at 09:00. Weekly starts with Monday to Friday selected. Interval defaults to 60 min (minimum 5 min, maximum 7 days).
- Notify after every run.
- "Show progress notification while running": **off**.
- Advanced is collapsed:
  - Model: follow global.
  - Reasoning: follow global.
  - Capabilities: Web & browser on, Device info reading on, **Device controls off**, Terminal on, MCP on, App UI automation off.
  - Result destination: isolated archive.
  - When missed: catch up once within 3 h.
  - Quiet hours: **off** (the window is 23:00–07:30).
  - Max duration: 5 min (Recommended).
- The task auto-pauses after **5** consecutive failures.

**Task detail**
- Next run, or "Currently paused".
- Run now / Stop.
- Enable schedule switch.
- "Prompt & configuration" (Model, "Notifications & session" badges, Capabilities).
- Task memo with "Clear memo".
- "Run timeline", empty text "No runs yet. Tap "Run now" above to test this task."
- Edit (top bar) and Delete scheduled task (confirmed).

**Linux environment**
- Status card: "<Distro> <version>", the mode, a status line, and one main button ("Install base" / "Install base tools" / "Installing", or "Root and system enhancements" when root is lost).
- "Environment configuration": Linux distribution (Alpine "Lightweight musl environment" / Debian "glibc environment…"); Execution mode ("Standard mode · PRoot" / "Root mode · chroot").
- "Files and directories": Workspace and files; Shared folders and File browser (both after the base is ready).
- "Optional tools" (after the base tools are ready): Python / uv environment, Node.js environment, SSH remote access, APK analysis. Each has Install or Installed and progress text.

**Workspace and files**
- Private workspace (info).
- Public directory access (authorize, or "Authorized; the terminal can access public directories").
- Import files.
- "Workspace files" browser: Parent directory, Folder entries, "<file> · Tap to export"; empty state "No files" / "Import files or generate them through terminal tasks".

**Model providers**
- Search box "Search provider".
- "Add new provider": Add from models.dev, Add OpenAI-compatible provider, Add Anthropic provider, Add Gemini.
- "Configured providers (N)". Each row has logo, name, base URL, protocol, model count, a "built-in" chip, "Disabled" when off, and "current".

**Provider detail**: two tabs, **"Configuration" | "Model"**.
- Configuration, "Connection" section:
  - name
  - Platform (Gemini)
  - Authentication: API Key / Account sign-in
  - Base URL
  - API Key (show/hide)
  - Project ID / Number, Location (Vertex)
  - **anthropic-version** (Anthropic)
  - Endpoint mode: Chat Completions API / Responses API
  - Provider-hosted web search, or "Google Search" for Gemini
  - the account sign-in card
  - test connection
- Then "Custom headers" (expandable; "Not set" / "N headers set").
- "Preferences and Strategies": Enable this provider.
- "System prompt" text area.
- Buttons: Save configuration (or "create"), Reset built-in configuration (built-in providers), Remove provider.
- Model tab:
  - "Model management": Automatically pull from remote ("Read the /models list from <url>") and Add custom model.
  - Search model.
  - "Models (N)" list. Tapping a model makes it current. **Long-press enters multi-select** (Select all / Select none, "Delete N models"). An edit button opens the parameter sheet.
  - Tags on each model: "current", "<N> context", "Supports Reasoning".

---
