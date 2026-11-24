# Atom parity spec (clean-room, user-facing)

What the old app does, written from the user's side, so the new app can match it feature for feature. It contains no code, no internal design and no source paths. Everything here was taken from the old app's public README/docs, screenshots, user-visible text resources, user-facing asset catalogs and its declared Android capabilities, then reworded.

Tags: **[core]** essential for parity · **[root]** needs root · **[lsposed]** needs the LSPosed module · **[battery]** has a clear battery or thermal cost, so design it with care · *(ColorOS)* / *(HyperOS)* means it only works on that vendor ROM.

Platform baseline: Android 14+, ARM64 only, one APK, English UI only (the language setting is just "Follow system"). Installing over an existing copy signed with the same key keeps keys, models and permissions.

---

## 1. Onboarding & permissions

- [ ] There is no setup wizard. A short startup splash opens straight onto the home chat. [core]
- [ ] With no provider configured, the empty state tells the user to add one, and sending is blocked with a "configure a model first" message. [core]
- [ ] The home empty state shows a "How can I help you?" headline and four suggestion cards: analyze the current screen (screenshot plus description), open a named messaging app, browse the web (the agent browser summarizes today's tech news), and check memory pressure (reads kernel memory and pressure stats and summarizes them). [core]
- [ ] A Permissions & status page shows an overall health line ("All good" or "N items need attention"). Each row shows Available / Missing / Issue / Not enabled with a Grant or Go-to-settings action. [core]
- [ ] The conversation sidebar shows a "Permissions need attention" hint when something is missing. [core]
- [ ] Accessibility service, labelled "Atom Agent": reads the screen, taps, swipes, types and takes screenshots. Needed for screen control. [core]
- [ ] Display over other apps (floating window), for the bubble, run capsule and overlays. [core] [battery]
- [ ] Notification access (listener). Searchable notification history starts recording from the moment it is granted. [core]
- [ ] Usage access, for recent apps and foreground time. [core]
- [ ] Location, precise and approximate. Background tasks need "Always allow". The app explains when it only has foreground access. [core]
- [ ] Calendar read and write. [core]
- [ ] Do Not Disturb access, so silent, vibrate and ring can be switched without root. [core]
- [ ] Post notifications, used for running-task controls, background command results and scheduled task results. [core]
- [ ] Promoted (Live Update) notifications: during a run the task shows as a status-bar capsule ("Working" / "Answering") and on the lock screen. The user can turn it off. [core]
- [ ] Microphone, for voice input. If it is denied, keyboard input stays available and the app links to its own permission settings. [core]
- [ ] Local network access (Android 17 "Nearby devices"), for LAN models, MCP servers, the built-in browser and terminals. Internet access works without it. [core]
- [ ] All files access is optional and only used for shared/public directories. The private workspace and import/export work without it. [core]
- [ ] Exact alarms, so scheduled tasks fire on time. [core]
- [ ] Unrestricted battery (background running), so scheduled and background tasks are not cut off by Doze. [core] [battery]
- [ ] Full installed-app list visibility, so the agent can find apps. [core]
- [ ] Also declared: set alarm, NFC, Wi-Fi and network state, modify audio settings, wake lock, start on boot, and foreground service types (microphone, media playback, special-use "interactive terminals, local Linux services and device automation"). [core]
- [ ] Root authorization: the app probes once on first use (the su prompt waits up to 30 s), then shows Checking / Granted / Not detected / Timed out / Not granted, with Authorize and Check again buttons. It never re-prompts repeatedly. [root]
- [ ] Root help text names Magisk, KernelSU (metamodule support must be on) and APatch, and explains when BusyBox applets are missing. [root]
- [ ] An LSPosed "framework connection" status row, with help on module activation and scope. It warns that a connection does not prove the module is enabled. [lsposed]
- [ ] The capability view toggles between "Current device" and "All capabilities". Users can browse every capability and its requirements without triggering any permission prompt. [core]
- [ ] Losing a permission, root or the framework never resets the user's saved toggles. Features degrade and explain why. [core]

## 2. Chat & conversation UX

### Layout
- [ ] Top bar: drawer button on the left. In the centre, a model capsule (provider logo, model name, chevron), or the conversation title with the model as subtitle. On the right, an overflow menu (new conversation, open terminal, open browser, more). [core]
- [ ] The conversation list sits beside the chat and is revealed by a horizontal drag or the drawer button. The chat dims under a scrim, and tapping the scrim closes the list. [core]
- [ ] The sidebar has a floating search capsule plus Permissions and Settings buttons at the bottom, and dock entries for "Models" and "Characters". [core]
- [ ] Conversations are grouped as Pinned / Today / Yesterday / Recent. Each row previews the last message, "Atom is reasoning", or the tool being called. [core]
- [ ] Per-conversation actions: Pin/Unpin, Rename, Delete (confirmed), Export. Deleting a running conversation offers "Stop task and delete": it stops the task and saves results first, and keeps the conversation if saving fails. [core]
- [ ] Conversation search also matches message text that is not loaded yet. [core]
- [ ] Wide screens get an adaptive layout. Landscape, large fonts, light and dark are all supported. [core]

### Composer
- [ ] Placeholder "Ask Atom to do something". The bar holds a + attachments button, a reasoning chip, a mic button and a round send button (which becomes Stop while running). [core]
- [ ] The reasoning chip switches between "Think" (off) and "Deep Think" (on). A per-conversation effort picker offers the subset of Off / Default / Low / Medium / High / XHigh / Max that the current model supports. [core]
- [ ] The reasoning control is hidden for models that cannot reason. Forced-reasoning models show a fixed "Default". A saved effort that is no longer valid after a model switch snaps to the nearest valid level. [core]
- [ ] Attachments: image (with count and size limits per message), file, folder, and "Enter path" (any absolute local path). Each attachment can be removed before sending. [core]
- [ ] File and folder references are passed to the agent as paths, not uploads. The agent reads them with its file and terminal tools, which must be enabled. [core]
- [ ] Share-to-Atom: the app accepts single or multiple shared items of any type from other apps. [core]
- [ ] Haptic feedback on gestures. [core]

### While a task runs
- [ ] Messages sent mid-task are queued ("Queued · N"). The user can remove one or tap "Send now" to steer the running task at its next safe point without cancelling it. Messages with images wait until the current task finishes. [core]
- [ ] Stop cancels the run and saves the safe partial history. [core]
- [ ] Several conversations can run tasks in parallel. Only one at a time can drive the phone UI; the others show "Waiting for phone control". [core]
- [ ] Tasks keep running after leaving the app, shown in a "Running tasks" notification with Open and Stop actions. [core] [battery]
- [ ] Optional "Keep screen on while running" (on by default), with a safety timeout so a crashed run cannot hold the screen on forever. [core] [battery]
- [ ] After a crash or kill, finished work is restored and the run is marked interrupted. Nothing is replayed automatically. [core]
- [ ] A transient model failure retries automatically (3 attempts, roughly 2/4/8 s apart) and shows a "Retrying model request" notice. [core]
- [ ] Errors are explained in plain words: bad key (401), forbidden (403), wrong URL or model (404), rate limited (429), quota or billing, timeout, connection lost, provider content filter, output limit hit, context overflow. [core]

### Rendering
- [ ] Streaming answer with a smooth character-by-character reveal and auto-scroll to the bottom that stops as soon as the user scrolls. A "back to bottom" button appears. [core]
- [ ] Full Markdown: headings, lists, task lists, tables, code blocks with a copy button, quotes, links, and callouts (Note / Tip / Important / Warning / Caution). Anything unparseable falls back to raw text. [core]
- [ ] Native LaTeX, inline and block, with all common delimiters. Long formulas scroll sideways. Copying keeps the LaTeX source. [core]
- [ ] Visible reasoning shows as a collapsible block: "Reasoning…" with the last few lines while streaming, then "Reasoning completed · N seconds", auto-collapsed. [core]
- [ ] The work timeline collapses to one line ("Thought N times · M tool calls · K failed") and expands into a step timeline with an icon per tool and a status (running / completed / failed / result unknown). Repeated calls to the same tool merge. A terminal step shows its last output line. Steps longer than 3 s show a live timer. [core]
- [ ] Tool details expand to the exact command (Copy command) and the result. Browser steps show a live page preview. [core]
- [ ] Citations from provider-hosted web search become clickable links. Gemini Google-search suggestions show under the answer and open in the external browser. [core]
- [ ] Generated images appear as inline cards (see §13). [core]
- [ ] Older history loads automatically on scroll-up, with a retry if loading fails. The reading position is restored when the conversation is reopened. [core]

### Message actions
- [ ] Copy answer, copy code, read aloud. [core]
- [ ] Regenerate, with a warning about how many later turns will be deleted. [core]
- [ ] Edit a past user message: sending replaces that message and everything after it. [core]
- [ ] Delete a message, plus any later turns, with confirmation. [core]
- [ ] "Branch into a new conversation" from the final reply of a finished turn. It copies history up to that point and is named "<title> · Branch". Model and reasoning settings carry over; drafts and queued messages do not. [core]

### Titles, export, context
- [ ] Auto-generated title after the first turn, in the user's language. Toggle, model and reasoning level are configurable (§12). Manual rename is always possible. [core]
- [ ] Export a conversation as Markdown, including user and assistant text, thinking, tool calls with arguments and results, and an image count. [core]
- [ ] A context-usage indicator shows how much of the model's window is used, measured after each response. [core]
- [ ] Manual "Compact context" action. Automatic compaction runs at about 85% of the configured window (toggle in settings), and compaction status is reported. If the window is unknown, automatic compaction is off and the app says so. [core]
- [ ] The agent can search its own full conversation history, even past a compaction. [core]
- [ ] A session stats popover shows turns, steps, model time, tool time, average time-to-first-token, output tokens/s, token usage, cache hit/miss/read/write, compaction cost and payback, and subagent usage. [core]
- [ ] If a conversation's saved model is gone, a notice says which model is being used instead. [core]

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

- [ ] Default selection: on a clean install the screenshots show **DeepSeek / DeepSeek Flash** as the current provider and model ("current" badge, check mark). The settings row reads "DeepSeek / DeepSeek Flash". Treat this as the shipped default and confirm with the owner. [core]
- [ ] New built-in providers come pre-filled with a default system prompt that introduces the assistant as an on-device Android agent. If the user clears it, it stays empty. [core]
- [ ] Gemini 4 reasoning presets: Neon and Hydrogen offer Default/Low/Medium/High/XHigh (server default High, reasoning cannot be turned off). Carbon offers Default/Low/Medium/High (server default Medium). [core]

### Provider management
- [ ] The Model providers page has a search box, an "Add new provider" group and a "Configured providers (N)" list. Each row shows logo, name, base URL, protocol, model count, a "built-in" badge, and a radio button / check for the current provider. [core]
- [ ] Add from the models.dev community catalog. It works from a bundled snapshot, refreshes online and caches, and never sends the user's keys. The user picks a provider and models, and they are imported as a custom provider that is disabled and has no key until the user adds one. [core]
- [ ] Add an OpenAI-compatible provider (for ChatGPT, DeepSeek, Kimi, GLM, Qwen and others), with an endpoint mode of Chat Completions or Responses API. [core]
- [ ] Add an Anthropic provider (official or compatible API). [core]
- [ ] Add Gemini: choose AI Studio (API key, native Interactions API) or Vertex AI (API key plus Project ID or number and a location of global / us / eu / a single region; Gemini 4 only; custom HTTPS proxy allowed). [core]
- [ ] Provider detail: enable switch, display name, base URL, API key (masked, with a visibility toggle), endpoint mode, per-provider system prompt, custom headers (can override User-Agent; auth headers are protected), extra request-body JSON, and Test connection. [core]
- [ ] Models tab: pull the remote /models list into a picker ("N new models available" → "Add N"), add a custom model by hand (display name + model ID), edit model parameters, delete one or many models with multi-select, search models, "Set as default model". [core]
- [ ] Per-model settings: context window (common sizes or a typed number, used for compaction), "Supports reasoning" (auto-detected or manual), and "Configure reasoning levels" (manually tick the levels the model supports). [core]
- [ ] Remove provider (confirmed), Reset built-in configuration (restores the official model list and keeps the API key), duplicate a provider ("<name> Copy"). [core]
- [ ] Capability detection: reasoning support and context window come from remote metadata or the built-in catalog, with family rules as a fallback. Results show as "Detected automatically" or "Auto: unsupported or unknown". [core]
- [ ] Provider-hosted web search toggle (off by default; OpenAI Responses). Gemini Google Search grounding when supported. [core]

### Subscription / OAuth login
- [ ] Authentication method per provider: API key or account sign-in. [core]
- [ ] OpenAI Codex: "Sign in with ChatGPT". Authorization happens in the system browser with a local callback; the account's model list syncs automatically after login. [core]
- [ ] xAI Grok subscription: sign in with a device code in the browser, using the account's subscription quota, with automatic model sync. [core]
- [ ] Account controls: sign-in status, Open browser, Reauthorize, Manage usage, Sign out (Grok sign-out also revokes the token remotely). There are clear errors for expired, denied, mismatched-account and no-plan-access cases. [core]
- [ ] Tokens are stored encrypted on the device and are never included in backups; the user signs in again after a restore. [core]

### Default vs per-conversation model
- [ ] One global default provider and model ("Set as default"). [core]
- [ ] A "Choose model" bottom sheet opens from the home capsule, with an All / per-provider filter and search. The choice is saved per conversation. [core]
- [ ] Settings → "Reasoning enabled by default" toggle and "Default reasoning effort" (Model default / levels). [core]
- [ ] Separate model choices, each able to "follow the primary model": floating assistant, subagents, title generation, each scheduled task, voice smart-correction (always the chat model), image generation (its own service). [core]

## 4. Voice

- [ ] Chat dictation: tap the mic to record, then tap again or "Done". The text is inserted at the cursor as an editable draft and never overwrites existing text. [core]
- [ ] Hold-to-talk in chat: press and hold the mic, release to transcribe and send, slide away to cancel ("Release to send, slide away to cancel"). [core]
- [ ] Assistant overlay voice: one recognition starts automatically when the overlay opens. Streaming services can auto-send after a pause of 1, 1.5, 2 (default) or 3 s, or never. Hold-to-talk is also available. [core]
- [ ] Live recognition preview for streaming services. Upload-then-transcribe services show only progress states (Connecting / Listening / Recognizing / Uploading / Proofreading). [core]
- [ ] One recording lasts at most 60 s (DeepSeek allows 120 s / 8 MiB). [core]
- [ ] "Enable speech recognition" master switch. When off, the mic disappears from chat and the overlay, and the overlay opens to text input with the keyboard so the IME's own voice input can be used. [core]
- [ ] Recognition services (one active): Qwen Realtime (default), Qwen Short audio, Qwen File transcription (uploads to the user's own Alibaba OSS bucket), Doubao Streaming 2.0, MiMo, DeepSeek. [core]
- [ ] Qwen region (Beijing or Singapore, must match the key) and an optional base URL. Doubao: resource ID and optional legacy App ID + Access Token. MiMo and DeepSeek: separate keys. DeepSeek reuses the model-provider key if left blank. [core]
- [ ] Recognition language: blank means auto (zh, en, yue and so on for Qwen). MiMo offers Auto / Chinese / English. [core]
- [ ] Smart correction (optional): the current chat model makes one cautious proofreading pass to fix homophones and mixed-language terms, waits at most about 8 s, and keeps the raw text on failure. [core]
- [ ] Recording storage page (for file transcription): OSS region, endpoint, bucket, prefix, AccessKey ID and secret. Objects are private and deleted after transcription; cleanup is retried. [core]
- [ ] Spoken answers (TTS) services: Off (default), Qwen, Doubao, Gemini, MiniMax, MiMo, DeepSeek. [core]
- [ ] Qwen voices: Cherry (default), Serena, Ethan, Chelsie, Momo, Vivian, Moon, Maia, Kai, Nofish, Bella, Katerina, Vincent, Neil, Dylan, Jada, plus Beijing-dialect male and Shanghai-dialect female voices. [core]
- [ ] Doubao voices: Vivi (default), Xiaohe, Yunzhou, Xiaotian, Liufei, Fresh Voice, Cancan, Xiaoyuan, Taozi, Sisi, Girl Next Door, Zixin, Ahu, Yichen, Xiaoming, Late Night Podcast. [core]
- [ ] Gemini TTS: model Gemini 3.8 Flash (quality) or Flash-Lite (lower latency); voices Kore, Puck, Charon, Aoede, Zephyr, Leda, Sulafat, Achernar (each described as firm, upbeat, informative and so on); optional reading-style text. [core]
- [ ] MiniMax TTS: Speech 2.8 Turbo or HD; Mandarin voices (mature, sweet, girl, elegant, young male, elite male, executive, news anchor); speed 0.5–2x; number normalization; China or overseas site. [core]
- [ ] MiMo TTS: Default, Rock Candy, Jasmine, Soda, Birch, Mia, Chloe, Milo, Dean; optional reading style (natural-language tone, emotion, speed or dialect, up to 500 chars). [core]
- [ ] DeepSeek TTS: Starfish and Tide voices, streaming, automatic language detection. [core]
- [ ] "Custom voice" / enter a voice ID by hand. A saved ID is matched back to its preset name. [core]
- [ ] Settings page has "Test recognition" and "Preview voice" (editable preview text up to 500 chars). Both use the unsaved form and may cost money. Changes need an explicit Save. [core]
- [ ] Read aloud on any answer. Tables, code, images, HTML and bare URLs are skipped; list numbering and task state are spoken. A "Filter Markdown formatting before reading aloud" toggle exists. [core]
- [ ] Auto read-aloud toggle in the chat "More" menu reads each completed turn's final answer. It is hidden when no TTS is configured. [core]
- [ ] Assistant overlay "Speak answers automatically" (off by default) speaks only the final answers to voice questions while the overlay is visible. [core]
- [ ] Chat read-aloud keeps playing in the background or on the lock screen, with a "Atom is reading aloud" notification and a Stop button. [core] [battery]
- [ ] Interruption: starting a recording stops playback. Losing audio focus or unplugging headphones stops playback. Closing or hiding the owner page ends the audio task. Nothing is spoken late after the fact. [core]
- [ ] If the cloud TTS fails (bad key, timeout, refusal), the app falls back to the Android system voice instead of showing an error. [core]
- [ ] Speech credentials are encrypted, kept separate from chat credentials, and excluded from backups. [core]
- [ ] **Not present in the old app:** a continuous hands-free voice conversation loop (listening does not restart after an answer), a wake word (roadmap only), and background always-on listening. The owner wants "audio chat", so this is a new design item and needs careful battery work. [battery]

## 5. Screen control & automation

- [ ] Observe the screen: UI tree plus an optional screenshot, at original resolution. [core]
- [ ] Tap by element (preferred), by coordinate, or by area. [core]
- [ ] Long-press by element or coordinate. [core]
- [ ] Swipe up/down/left/right. Scroll the page or a specific element. [core]
- [ ] Text input: replace, append, clear, optionally submit. The field is read back afterwards to confirm. [core]
- [ ] Paste text, set the clipboard, read the clipboard. [core]
- [ ] System keys: Back, Home, Recents, Notifications, Quick settings, Power, Volume up/down, Mute. [core]
- [ ] Open system panels (notification shade, quick settings and similar). [core]
- [ ] Wait for a duration, for text to appear, or for an app to reach the foreground. [core]
- [ ] Search installed apps by name or package. Launch an app by name or package, which works without accessibility. [core]
- [ ] Open a link or deep link in an external app. [core]
- [ ] Verification loop: after every action the agent observes the screen again instead of assuming the action landed. Coordinates are only used when no element can be found. Ambiguous results trigger re-observation, not a repeated gesture. Outcomes are reported as done / failed / "submitted, unconfirmed". [core]
- [ ] App-launch confirmation: the agent reports whether the target app's window was actually seen. [core]
- [ ] Exclusive phone control: one task drives the UI at a time and others wait in line. The agent can explicitly release control. [core]
- [ ] A run capsule overlay appears during phone actions and shows status. Tapping it reveals Pause, Resume, Add input (steer), and Stop. While paused, the user has control and other UI tasks wait. [core] [battery]
- [ ] An edge glow / light on the main screen while the agent operates it. [battery]
- [ ] A result card when done ("Result ready" / "Open Atom" / "Open screen" / "Done"). [core]
- [ ] Background virtual display: cross-app tasks run on a hidden screen so the user's foreground is untouched. Routing is automatic: if the target app is already in front, or is the launcher/system UI, the agent works on the main screen. Settings toggle: "Background virtual display". [root] [lsposed] [battery]
- [ ] A live mini viewfinder sits inside the run capsule. Tapping it expands a stage window for live preview, direct touch takeover ("Take over" / "Continue"), "Fullscreen" projection, or "Collapse stage". [root] [lsposed] [battery]
- [ ] Hand-back to the user ("Present to main screen"): when the hidden task reaches a payment confirmation, QR code or final result page, that page moves smoothly onto the real screen. [root] [lsposed]
- [ ] If the virtual display disappears, the agent reports "display unavailable" and never replays old coordinates on the main screen. [core]
- [ ] Messaging (for example WhatsApp or WeChat) is done through the same generic screen tools. There is no special per-app integration. [core]
- [ ] Root fallbacks: a system UI-dump when accessibility is not connected, and a root screenshot when a per-window screenshot fails. [root]
- [ ] Accessibility "force keep" protection re-enables the service if the ROM drops it, with limited retries and backoff. [lsposed]
- [ ] Password fields are never captured in screen context. [core]

## 6. Phone context & personal data access

All data is read on demand, in bounded slices, only when the agent calls the tool. Raw values (OTPs, notification text, Wi-Fi passwords and so on) are used for that turn only and are scrubbed from saved history.

- [ ] Time and location: local time and offset, plus the most recent system location fix and its age. Clear messages when location is off, missing, or background permission is needed. [core]
- [ ] Current notifications (title and text), via the notification listener. [core]
- [ ] Notification history: kept on the device from the moment access is granted, for the last 7 days (up to 1000 entries), and searchable. [core] [battery]
- [ ] SMS verification code: extracts only OTP codes from recent SMS. [core]
- [ ] SMS search by sender or keyword. [core]
- [ ] Contacts search by name. [core]
- [ ] Call history search by number or contact. [core]
- [ ] Calendar event search by title, location or description. [core]
- [ ] Gallery photos and screenshots: search by file name or album path. [core]
- [ ] Audio file search by title, file name or artist. [core]
- [ ] Recordings: normal and call recordings, plus recording summaries and transcripts. [core] *(summaries: ColorOS)*
- [ ] Shared-storage documents and files search. [core]
- [ ] Download history (system download tasks and files). [core]
- [ ] Recent apps and foreground usage time (needs usage access). [core]
- [ ] Current app and screen context: the digital-assistant overlay attaches a screenshot plus the foreground app's content at wake time. [core]
- [ ] Clipboard history saved by the system keyboard. [root]
- [ ] Health summary for a time window: steps, sleep, exercise, body metrics (from Health Connect data). [root]
- [ ] Alarms list and active timers. [core]
- [ ] Wi-Fi saved passwords. [root]
- [ ] Device status: battery and charging, memory, storage, OS version. Network status: connection type, Wi-Fi state, internet validated. Device environment: lock screen, DND, audio output, external display. [core]
- [ ] Memory ranking (top processes) and storage ranking (per-app data and cache). [core]
- [ ] Notes and to-dos search; read a note by ID. [root] *(ColorOS)*
- [ ] System memory search ("Breeno/Xiaobu memory"): bills, schedules, pickup codes, deliveries, saved places, attachments. [root] [lsposed] *(ColorOS)*
- [ ] Personal orders: food delivery, shopping, parcels, tickets, travel. Saved places. [root] *(ColorOS)*
- [ ] Bills search plus "Summarize bills" (exact totals over a time range, split into income, expense, transfer and unknown, top categories). [root] *(ColorOS)*
- [ ] To-do leads, calendar to-dos, memory collections, life events, flights, hotel bookings, train trips. [root] *(ColorOS)*
- [ ] Read the full detail of any earlier search hit. [core]
- [ ] QQ and WeChat chat-image caches: recent image file listings, which can then be viewed. [root]
- [ ] Read-only inspection of other apps' private databases and files when no dedicated tool exists. [root]
- [ ] The agent is told to sample several sources for broad "know me" questions rather than asking permission again for each source the user has already enabled. [core]

## 7. Root & system control

- [ ] Flashlight on/off and status. [core]
- [ ] Brightness (as a percentage), auto-brightness, auto-rotate, screen timeout (seconds). [root] (some work without root)
- [ ] Dark mode, eye-comfort / night light. [root]
- [ ] Volume per stream (media, alarm, ring, notification, call, system). [core]
- [ ] Sound mode (normal / vibrate / silent). Without root this needs DND access. [core]
- [ ] Do Not Disturb (priority only / alarms only / total silence). [core]
- [ ] Wi-Fi and Bluetooth toggles. [root]
- [ ] Mobile data, airplane mode, location, NFC, battery saver, personal hotspot (keeps the existing name and password). [root]
- [ ] Media controls: play, pause, toggle, next, previous, stop, without touching the UI. [core]
- [ ] Freeze, unfreeze and force-stop apps. [root]
- [ ] Read and write protected Android settings keys (system, secure, global). [root]
- [ ] Read system logs with filters for process, tag, level, time and text. [root]
- [ ] Inspect app: version, UID, install source and path, enabled/stopped state, permission list. [core]
- [ ] Every state change reports before → after and whether it was verified (for example "Auto-rotate: off → on"). An unconfirmed change is reported as unconfirmed, never as success. [core]
- [ ] Rootless mode: dedicated root tools are hidden. The terminal and file tools run as the app user and Linux runs via PRoot. Nothing escalates silently. [core]
- [ ] **No Shizuku support in the old app.** "Rootless" means standard permissions only. Adding Shizuku would go beyond parity. [core]

## 8. Terminal & Linux

- [ ] A user terminal opens from the home overflow menu. Console (full PTY) mode is the default, with "Simple mode" (a block view of command, output and exit code) available. [core]
- [ ] Console: real xterm-256color terminal with colours, cursor movement, alternate screen, line drawing, wide CJK glyphs, scrollback, bracketed paste, resizing on rotation and keyboard changes, and a JetBrains Mono font. [core]
- [ ] Key bar: Esc, Tab, Ctrl/Alt (tap for one-shot, long-press to lock), arrow keys with auto-repeat, Enter, Home/End, PgUp/PgDn, common symbols, Paste, show/hide keyboard. [core]
- [ ] Simple mode: command, output and exit code per block. ANSI colours are rendered, progress bars collapse to their final state, and input works while a command runs (REPLs, prompts). Copy command / Copy output / Reuse command. [core]
- [ ] Up to 6 concurrent sessions per mode. Session list: new, switch, restart, close. Sessions survive leaving the page. [core] [battery]
- [ ] Environments: Android shell (app user, or root with Magisk/KernelSU/APatch BusyBox) and Linux. [core]
- [ ] Linux distribution choice: Alpine (musl, lightweight) or Debian Trixie (glibc, broader compatibility). [core]
- [ ] Execution mode: Standard (PRoot, no root, app-private) or Root mode (chroot with its own mount namespace). If root is lost, the chroot environment is kept and the user is offered PRoot. [core] [root]
- [ ] Staged install: base environment, then base tools (rg, fd, git, ssh client, curl, jq, sqlite and others), then optional profiles. [core]
- [ ] Optional profiles: Python via uv (latest stable Python), Node.js plus npm (latest stable), full OpenSSH server suite, and APK analysis (OpenJDK 25, JADX, Apktool, smali, baksmali; needs 768 MB; Apktool rebuilding is not supported). [core]
- [ ] Install progress shows download percentage, extract and verify stages, Continue installation, Upgrade tools, cancel, "not enough space" errors, and integrity-verified downloads. [core]
- [ ] Health check covers core commands, the /workspace mount, /sdcard and free space, with Repair (including Debian package recovery). [core]
- [ ] /workspace is mapped to the app's private workspace. Shared storage is at /sdcard. [core]
- [ ] Shared folders mount chosen Android directories at /workspace/mounts/<name> (name rules, duplicate and forbidden-path checks, a limit on count). They apply from the next session. [core]
- [ ] Read-only Linux file browser with directory navigation and text previews (binary files are detected; previews are capped). [core]
- [ ] Workspace & files page: import files (up to 32 MiB each), export files, private vs public directory access status. [core]
- [ ] Background tasks panel: commands started in the background keep running after the reply ends, notify on completed / failed / timed out / stopped / interrupted with the exit code, and logs are viewable. There is a default 10-minute cap that can be disabled. [core] [battery]
- [ ] Background services (daemons) run across agent tasks and can be listed, have their logs viewed, and be stopped. They do not survive a reboot. [core] [battery]
- [ ] Agent terminal tools: one-shot commands, persistent sessions, background jobs, daemons, and a root check. Chat cards show the exact command, with copy. [core]
- [ ] The agent also uses git, python, node and the others itself: it writes code, runs it, reads errors and fixes them. [core]

## 9. Memory, skills, MCP, subagents, scheduled tasks, characters

### Memory
- [ ] A single long-term memory file (MEMORY.md, up to 1 MiB) that the user can view in Preview or Edit, Save or Discard, Clear all (confirmed), and switch on or off. Turning it off keeps the file. [core]
- [ ] Only the "# Core memory" section is injected automatically, within a budget that scales with the model's context window; the agent reads the rest on demand. A seeded template prompts for user name and long-term preferences. [core]
- [ ] The agent reads, appends, updates and clears memory itself. Concurrent edits are detected and never silently overwritten. [core]
- [ ] Memory content is shown to the model as background, never as instructions. [core]

### Skills
- [ ] Skills page: built-in skills (self-improving-agent, skill-creator, skill-installer) and user-installed skills, each with an enable switch, a description (expandable) and Delete (confirmed). [core]
- [ ] Import a skill from a ZIP via the system file picker (one skill per ZIP; validated for safe paths and sizes). [core]
- [ ] The agent can install skills from a curated catalog or from any public GitHub repo, tree or blob URL (up to 20 per install). Installed skills work from the next turn and their scripts are never executed automatically. [core]
- [ ] Replace-existing-skill confirmation. Built-in skills cannot be overwritten. Installs are transactional and recover after a failure. [core]

### MCP
- [ ] MCP servers page: add a server (name, URL, optional bearer token). The tool catalog is discovered on add. Each server row shows "N of M tools enabled". [core]
- [ ] Per-server settings: enable server, change or remove the token, refresh tools, delete server (confirmed). [core]
- [ ] Tools are off by default and enabled one by one. Tools not marked read-only need an extra confirmation. Enable all / Disable all buttons. A truncation notice appears for oversized catalogs. [core]
- [ ] Remote Streamable HTTP only: tools only (no resources, prompts, OAuth or stdio). HTTP, HTTPS and LAN URLs are allowed. [core]

### Subagents
- [ ] Subagent settings: enable, model (follow main or a specific one), reasoning effort. [core]
- [ ] Built-in subagents: Explore (read-only exploration and multi-source search), Coder (file editing and terminal work), Plan (read-only analysis and step-by-step plans), plus temporary ad-hoc subagents. [core]
- [ ] Custom subagents: name (kebab-case), description, when to use, allowed tools (default is read, file, terminal and MCP), system instructions. Create, edit and delete them in the UI, or ask Atom in chat to create one (saved as agents/<name>.md). [core]
- [ ] Subagents run in parallel in isolated contexts and return a summary. A details sheet shows the prompt, streaming steps, summary, tool-call count and token usage. [core]

### Scheduled tasks
- [ ] Scheduled Tasks list shows "All tasks · N/M enabled", per-task next run, status and an enable switch, plus a health card (unrestricted battery, exact alarms, notifications). [core] [battery]
- [ ] Quick-start presets: Daily evening briefing, Weekday morning digest, Web change radar, Weekly phone & habit review. [core]
- [ ] Editor fields: name, prompt ("What should Atom do"), and schedule (Daily, Weekdays, Weekly with day picker, Interval from minutes up to 7 days, One-time delay, 5-field Cron), with a schedule preview. [core]
- [ ] Notifications: notify every run, only on change, or archive silently. Option to show a progress notification while running. [core]
- [ ] Advanced: model and reasoning effort (follow global or a specific one), per-task capabilities (chat-only, web & browser, device read, device controls, terminal/files/Linux, MCP, app UI automation (off by default)), result destination (isolated archive per run or a dedicated conversation channel), missed-run policy (catch up once within 3 h, or skip), quiet hours 23:00–07:30, max duration (2/5/10/15 min). [core] [battery]
- [ ] Task detail: Run now, Stop, a task memo used to compare against the previous run (clearable), a run timeline (live progress or the full conversation, with follow-up), Delete. [core]
- [ ] Reliability: alarm-based wake-ups, rescheduled after reboot, app update, or time and timezone changes. Overlapping runs are skipped. The task auto-pauses after repeated failures. UI tasks on a locked phone without a virtual display send a "ready to run" reminder instead. [core] [battery]
- [ ] Two notification channels: results and alerts (failures, auto-pause, waiting for unlock). [core]
- [ ] The agent can create and manage scheduled tasks from chat (for example "summarize important notifications every night"). [core]

### Characters / personas
- [ ] Character library: search by name or tag, favorites, a "Mine" section. Ships with one editable default character that can be restored after deletion. [core]
- [ ] Create or edit: name, profile, greetings (default plus alternates). Advanced: personality, scenario, example dialogue, system prompt, post-history instructions, creator notes, tags, creator, version, credits. [core]
- [ ] Embedded worldbook: entries with primary and secondary keywords, a require-secondary option, always-active, before/after-definition placement, insertion order, scan depth, token budget, recursive scanning, enable per entry, compatibility notes for skipped entries. [core]
- [ ] Import PNG or JSON character cards (V1/V2/V3). Export PNG or JSON. Duplicate, change image, delete (confirmed; existing chats keep a snapshot). [core]
- [ ] Character detail: choose a greeting, then Start chat. "Use my persona" toggle. Story memory editor (shared across all chats with that character). [core]
- [ ] My persona: a name and an identity/relationship description, used optionally per chat. [core]
- [ ] Character chats keep real tool abilities. They can read but not write the real MEMORY.md, and they write story memory instead. [core]
- [ ] In character chats: edit user or character messages in place (keeping later turns), regenerate a character reply as switchable candidates (previous/next) with no tools run, "<name> is replying…" indicator, and a "The story begins here" marker. [core]

## 10. Floating bubble & overlay

### Persistent bubble
- [ ] An optional always-on floating bubble (Settings → Floating assistant → Floating bubble). It needs the overlay permission. [core] [battery]
- [ ] Appearance: a round orb with a minimal atom glyph (nucleus with crossing orbits), three accent arcs, breathing and spinning only while working. When idle it is a static image to save CPU. [core] [battery]
- [ ] States: idle, thinking/working, paused, dragging (it swells), and brace (bracing at the dismiss target). [core]
- [ ] Tap opens the mini chat. [core]
- [ ] Hold opens a radial hold menu anchored to the bubble, with icon-only buttons: Hide (X), Open chat, Talk (mic). A hint reads "hold the bubble for actions". [core]
- [ ] Drag anywhere, with clamping and snapping at the screen edges. The position is remembered across restarts and the bubble never moves when the panel closes. [core]
- [ ] Drag-to-dismiss: a bottom X target with a beam that strengthens as the bubble gets closer. Dropping on it hides the bubble. [core]
- [ ] Mini chat panel: a full-height sheet between the status and navigation bars. Header has the title "Atom", + (new chat), expand (open the full app) and X (close). It shows a model chip, an empty state ("Ask Atom anything"), and a pill composer with mic plus a round send button. [core]
- [ ] The panel shrinks so the composer sits on top of the keyboard, and returns to full height when the keyboard closes. [core]
- [ ] The mini chat runs the full agent (tools plus streaming) and keeps one ongoing conversation across restarts, cleared only by "new chat". That conversation also appears in the main conversation list. [core]
- [ ] Only one bubble ever shows. [core]

### Run capsule (during tasks)
- [ ] A status capsule with run state; tapping it opens controls (Pause / Resume / Add input / Stop) and, on a virtual display, the mini viewfinder. See §5. [core] [battery]

### Full-screen assistant overlay (system assistant entry)
- [ ] An edge-to-edge overlay drawn over the current app when Atom is invoked as the assistant. [core]
- [ ] Entry animation: a halo and light band run down the screen edge, then a coloured wave ripple appears at the bottom that reacts to voice volume (skipped when graphics cannot render it). [core] [battery]
- [ ] Three suggestion chips: Summarize screen, Explain this page, Extract key info. [core]
- [ ] Listens first, with a keyboard toggle. Recognition errors show next to the input with a link to voice settings. [core]
- [ ] The screenshot, foreground app and page content captured at wake time are sent automatically with the question. Follow-ups reuse that snapshot and note when it was taken. [core]
- [ ] The reply card grows with the content. User messages show as small bubbles, thinking and tool steps fold into an expandable status row, and the answer streams as Markdown. There is a read-aloud button. [core]
- [ ] Pull the top handle up to open the conversation in the full app ("Pull up to open Atom" / "Release to open Atom"). [core]
- [ ] The overlay hides itself before the agent touches the screen, and shows the result afterwards. [core]
- [ ] Separate model and reasoning settings for this overlay (§12). [core]

## 11. System integration

- [ ] Registers as the system digital assistant (voice interaction service, assist from the lock screen, uses assist screenshots and structured screen content). Settings shows the current default (Atom / other app / not set) and opens the system picker. [core]
- [ ] Handles the system ASSIST action, so the long-press or assistant gesture opens Atom. [core]
- [ ] Declares a speech recognition service ("Atom speech recognition"), which Android requires for assistant eligibility. [core]
- [ ] Accessibility service "Atom Agent". [core]
- [ ] Notification listener "Atom notification history". [core]
- [ ] Share target for any file type, single or multiple. [core]
- [ ] Notification channels: Running tasks (Open / Stop), Background command results, Scheduled task results, Scheduled task alerts, Read aloud, Assistant voice ("Atom is listening / reading aloud" with Stop). [core]
- [ ] Live Update / promoted status-bar capsule while running ("Working" / "Answering"). [core]
- [ ] Boot, app-update and time-change receivers that reschedule tasks. [core]
- [ ] File sharing out of the app (generated images, exported files) through a secure share provider. [core]
- [ ] Power-key long-press target: System default / Gemini / Atom. [lsposed] *(ColorOS, HyperOS)*
- [ ] "Automatically set default assistant" keeps the assistant role pointed at the chosen target. [lsposed]
- [ ] Circle to Search by long-pressing the gesture bar. [lsposed] *(ColorOS, HyperOS)*
- [ ] Circle to Search by two-finger touch-and-hold. [lsposed] *(ColorOS)*
- [ ] HyperOS 4: gesture-handle long-press via Super XiaoAI screen search is redirected to Circle to Search. [lsposed] *(HyperOS)*
- [ ] Google app eligibility and device spoofing so Circle to Search turns on (always on when the module is active; status lines for Google app installed or system priv-app). [lsposed]
- [ ] Accessibility protection, which keeps the service enabled through the system. [lsposed]
- [ ] **Not present in the old app:** Quick Settings tile, home-screen widgets, launcher app shortcuts, and a wake word. List them as optional extras, not as parity. [core]

## 12. Settings (every screen found)

### Settings root
- [ ] LLM providers: Model providers (shows "provider / model"), Reasoning enabled by default (switch), Default reasoning effort (picker, "Model default" and levels). [core]
- [ ] Context & extensions: Automatic context compaction (switch, on), Auto-generate titles, Memory, Subagent, Skills, MCP servers, Scheduled Tasks, Characters. [core]
- [ ] Tools: Tool list, Web search and reading, AI image generation, Permissions, System enhancements, Linux environment, Workspace and files. [core]
- [ ] General: Appearance & Theme, Language, Floating assistant, Speech, Keep screen on while running, Data backup, About. [core]

### Tool list / capabilities
- [ ] Master switches: Enable web search, reading and browser; Enable device direct tools; Allow reading of sensitive device information; Allow sensitive device operation; Enable terminal/file tools; Enable memory; Provider-hosted web search; AI image generation. Most are on by default; hosted search is off. [core]
- [ ] Tool cards grouped as Screens & controls, Text & clipboard, Applications & systems, Personal context, System & built-in apps, Terminal & files, Web tools, Document vision, Memory. Each card has an info/description view and jump links where relevant. "This device" vs "All capabilities" views. [core]
- [ ] Switches are re-checked before every tool call, so turning one off takes effect mid-run. [core]

### Appearance & Theme
- [ ] UI style: Miuix (default) or COUI, switchable live without losing state. [core]
- [ ] Theme mode: Follow system, Light, Dark. [core]
- [ ] Monet colours (system dynamic colour), colour style (Tonal Spot, Neutral, Vibrant, Expressive, Rainbow, Fruit Salad, Monochrome, Fidelity, Content, CMF). [core]
- [ ] Accent colour: System, Blue, Purple, Pink, Red, Orange, Yellow, Green, Teal. [core]
- [ ] Pure black background in dark mode (on by default, true #000000). [core]
- [ ] Blur effects, top-bar blur style (gradient band or progressive), progressive blur (can be turned off for performance). [core] [battery]
- [ ] Swipe-back-anywhere gesture. Predictive back animation. [core]

### Floating assistant
- [ ] Floating bubble switch. [core]
- [ ] Assistant model (follow primary or a specific model) and reasoning level (follow default or a specific level), for the power-key / system-assistant overlay. [core]

### Auto-generate titles
- [ ] Enable auto-title, title model (follow conversation or a specific model), reasoning level (Auto minimal/off, Model default, levels). [core]

### Speech
- [ ] Overview, Speech recognition, Spoken answers, Recording storage (OSS), Assistant group (auto-speak, auto-send pause), Smart correction, Filter Markdown, Test and Preview, Save. See §4. [core]

### Web search and reading
- [ ] Search service and page-reading service, each Built-in default / DeepSeek / Custom endpoint. [core]
- [ ] DeepSeek reuses the model-provider key, or the user can enter an override key and base URL. [core]
- [ ] Custom endpoint: HTTP method, URL, optional API key (sent as a bearer token), request-body JSON template with placeholders (query/count for search; url for reading), path to the results array, title/link/snippet/body field paths, custom headers. [core]

### AI image generation
- [ ] See §13. [core]

### System enhancements
- [ ] Root status row (Authorize / Check again), framework connection row, module help. [root] [lsposed]
- [ ] Digital assistant app row (opens the system picker). [core]
- [ ] Power-key target picker, Automatically set default assistant, Gesture-bar long-press triggers Circle to Search, Two-finger long-press triggers Circle to Search, Enforce accessibility. [lsposed]
- [ ] Background virtual display toggle. [root] [lsposed]

### Linux environment
- [ ] Status card (version, mode, stage, result), distribution picker, execution mode picker, install/upgrade buttons, profiles, health check, shared folders, file browser, workspace. See §8. [core]

### Permissions
- [ ] See §1. [core]

### Data backup
- [ ] Export data / Import data. See §14. [core]

### Language
- [ ] Follow system (English only). [core]

### About
- [ ] Version, Check for updates ("Already on the latest version" / "Update available: latest X, current Y" → Download), Source code (GitHub, with a star prompt), Feedback. [core]

## 13. Files, web browsing, image generation, other tools

### Files
- [ ] Agent file tools: read (paged), write/overwrite/append, exact-text edit with a uniqueness check, file info, list a directory (paged), find files by pattern, search file contents (literal text). They work on Android or Linux, as the app user or root. [core]
- [ ] Image viewing: the agent can look at any local image path or gallery item it has been given, one image per turn. [core]
- [ ] Share files out of the app. [core]

### Web
- [ ] Web search (built-in public search by default, 5–10 results, title, link, snippet). [core]
- [ ] Read web page (static fetch, paged; falls back to local fetching if a remote backend fails). [core]
- [ ] Agent browser: off-screen browser sessions per conversation with a shared login (cookies). It can open pages, extract the article or text, find elements, click, type and submit forms, scroll, take screenshots, go back/forward, reload, and wait for an element. Each chat card shows a live page preview. [core] [battery]
- [ ] The user can watch and take over a browser session ("You are taking over…"; the agent pauses on that page and can continue afterwards). [core]
- [ ] The user-facing browser screen (overflow → Open browser) has a capsule address bar (search terms go to Google), back/forward, stop/refresh, and a menu with Open in another app, Close page and Clear all browser data (confirmed). [core]
- [ ] Opening external links goes through the "open with app" screen action. [core]

### Image generation
- [ ] "Enable AI image generation": any chat model can then call generate and edit image tools, and results show inline. [core]
- [ ] Backends: Google Gemini ("Nano Banana"; AI Studio or Vertex), OpenAI & compatible, xAI Grok Imagine, Doubao Seedream (Volcengine Ark), Qwen-Image (Bailian, Beijing or Singapore), SiliconFlow. [core]
- [ ] Credential source: automatically match an existing model provider (reusing its key and URL) or enter a separate key and URL. [core]
- [ ] Model presets (as labelled): Gemini Nano Banana 2.1 (recommended), Gemini 3.1 Flash-Lite, Gemini 3.1 Flash, Gemini 3 Pro, Gemini 2.5 Flash; gpt-image-2.6-flare, gpt-image-2.6-sunburst, gpt-image-2.5-flare, gpt-image-2.5-sunburst, gpt-image-1.5 (gpt-image-1 flagged as retired); Grok Imagine 2.0 (up to 5 references); Seedream 5.5, 5.0 Pro, 5.0 Flash, 5.0 Lite, 4.5; Qwen-Image 2.0 Pro (recommended), 2.0; SiliconFlow Qwen-Image, Qwen-Image-Edit, Kolors, FLUX schnell; or a custom model ID. [core]
- [ ] Defaults: aspect ratio (Auto, 1:1, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3), quality (Standard/High), resolution (1K/2K/4K, or fixed by the model), Doubao watermark (off), Qwen smart prompt enhancement, Qwen watermark (off). [core]
- [ ] "Test generation" with a preview. Save settings. [core]
- [ ] Image cards: Generating / Editing N images with an elapsed timer, then completed or failed. Actions: Save (to Pictures/Atom), Save all, Share, Edit (attaches the image to the composer for a follow-up), view the prompt. [core]
- [ ] Generated images gallery: count and size, view, save, share, delete one, Clear all (confirmed). [core]

### Phone app operations
- [ ] Calendar: list writable calendars, read, create one or a batch (up to 30) with reminders, update, delete (a whole recurring series only when explicit). Results are verified by reading back. [core]
- [ ] Alarms: create via the system clock (opens the clock to confirm on failure), list, change time, enable/disable, delete. [core] (edit/delete: [root] *(ColorOS)*)
- [ ] Timers up to 24 h, list active timers. [core]
- [ ] Notes: create, read, move to trash. [root] *(ColorOS)*

## 14. Privacy, updates, backup/restore, export/import, logging

- [ ] No telemetry or analytics SDK, no third-party download proxy. [core]
- [ ] API keys, OAuth tokens, MCP tokens, and speech, web and image credentials are encrypted on the device. If a credential cannot be decrypted, the user is asked to re-enter it; it is never silently treated as empty. [core]
- [ ] Sensitive tool data (OTPs, notifications, Wi-Fi passwords, logs, settings values, personal data, MCP results, memory contents) is used only within that turn and scrubbed from saved conversations. [core]
- [ ] The system auto-backup is disabled. Only the manual backup exists. [core]
- [ ] Data backup export: one JSON file with conversations, model providers (including API keys, with a warning), MEMORY.md and character data. [core]
- [ ] Data backup import replaces current data after confirmation, is fully validated (rejects corrupt or foreign files and unsupported versions) and rolls back on failure. Running agent tasks must be stopped first. [core]
- [ ] After import, subscription connections stay disabled until the user signs in again. If the default model cannot be restored, the user is asked to reselect it. [core]
- [ ] Speech credentials and OAuth tokens are not included in backups. [core]
- [ ] Per-conversation Markdown export. Character card export. Image save/share. File export from the workspace. [core]
- [ ] Update channel: checks the project's own GitHub Releases and offers a download link. There are no automatic installs. [core]
- [ ] Logging: release builds never log prompts, responses, keys, commands, file paths or tool results. [core]
- [ ] "Clear all browser data" (cookies, cache, site data, sign-ins). [core]
- [ ] Notification history is kept locally only (7 days, 1000 entries). [core]
- [ ] Reading the models.dev catalog never sends the user's keys. [core]
- [ ] Root needs are listed explicitly and nothing escalates silently. A partial result is always reported as partial. [core]

## 15. Anything else

- [ ] Runs can be recovered: interrupted tasks resume or are marked interrupted, actions are tied to a run, and a second run cannot take over the phone mid-gesture. [core]
- [ ] Deleting a running conversation stops and saves first. [core]
- [ ] Reply language follows the user's language. [core]
- [ ] Usage and token accounting, including cache hits, per session (§2). [core]
- [ ] A foreground execution service holds terminal, PRoot and agent tasks, and releases itself when the last task ends. Notification stop actions reclaim the tasks. [core] [battery]
- [ ] Wake lock only while an unpaused run is executing. It never wakes a phone the user locked. [core] [battery]
- [ ] No polling, and no extra background timers when idle (this was an explicit design goal; the idle bubble animation was removed because it cost about 13% CPU). [battery]
- [ ] Known open items in the old app (gaps, not parity targets): the bubble status pill (working / hands-on / paused / waiting approval next to the face) is not finished; keyboard-height fit is tuned to one device; old conversation titles are not rewritten.
- [ ] Roadmap items that do not exist yet: wake word, cross-device agent hand-off, a third-party plugin surface.

---

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
