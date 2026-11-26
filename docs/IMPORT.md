# Importing from the previous Atom

Atom 4 installs over the earlier Atom app (same package name and signing key), so the earlier app's files are
still on the phone after the update. The first time Atom 4 starts and finds them, it copies what it can use into
its own storage, in the background. A notice on the chat screen says what was imported and what was not.

You can run it again at any time: **Settings > General > Data backup > Import from previous Atom**. A second run only
picks up what was not imported before; it never creates duplicates. If a run stops on an error or could not read
something, it tries again by itself on the next start (up to three starts), and the notice says what is left.

## What is imported

- **Providers**: every provider you had, with its name, address (base URL), on/off switch, endpoint mode (Chat
  Completions or Responses, AI Studio or Vertex AI with project and location), custom headers and body fields,
  hosted web search switch, Anthropic API version and system prompt. Built-in providers land on Atom 4's built-in of
  the same vendor; your own providers are added as custom providers.
- **Models** of each provider that were switched on, in the same order, with your context-window and reasoning
  overrides. Atom 4's own list of official models is kept as well.
- **API keys**, stored in Atom 4's encrypted vault.
- **Sign-ins** with ChatGPT and Grok, when the earlier app's tokens are still valid. Atom 4 refreshes them on first
  use. If a sign-in could not be carried over (signed out, expired without a way to refresh, or the phone's key store
  was reset), the notice lists that provider: open it in Models and sign in again.
- **Your default model**, unless you already picked one in Atom 4.
- **Conversations**, all of them, with titles, pinned state, the model and reasoning level each one used, your
  messages (including edits and attached pictures), answers, thinking, tool steps with their full arguments and
  results where the earlier app kept them, and notes such as "Stopped". Times are spread between when each
  conversation started and when it was last used, since the earlier app did not store a time per message.

## What is not imported

- MCP servers, skills, scheduled tasks, memory and other settings (appearance, voice, web tools, image generation).
- Character chats keep their messages and are named after the character, but they become normal chats: Atom 4's
  characters are separate, so the character's card and persona are not attached to them.
- Per-model custom headers and body fields (per-provider ones are imported), and your own models that were switched
  off (Atom 4's official models are always listed).
- Results the earlier app never kept: arguments and results of sensitive tools, and pictures inside tool results.
- A reply that was still running when the earlier app was closed.
- A built-in provider you removed in Atom 4 before the import stays removed.
- A single message longer than about a million characters is shortened, with a note saying so.

## Your old data is left alone

The import only reads. It never changes, moves or deletes any file of the earlier app, and it never overwrites a
provider, key or sign-in you set up in Atom 4 yourself (it only adds what is missing). To read the database safely,
Atom 4 makes a temporary copy (with its write-ahead log and journal, so an unfinished write is completed or undone on
the copy only) in its cache folder and deletes it right after. Without room for a copy, it reads the original only
when that cannot touch any file; otherwise it stops and asks for free space. Nothing leaves the phone, and keys are
never written to logs.

The earlier app's files keep using storage until you clear Atom's storage in Android's settings, which also clears
Atom 4's own data. [UPDATES.md](UPDATES.md) lists the file names each app uses.

## For developers

- Old format: `databases/atom.db` (SQLite, Room schema 30, read with plain SQLite and never through Room),
  `files/datastore/atom_settings.preferences_pb` (the default model) and
  `no_backup/provider_credentials/<sha256(provider id)>.bin` (AES-GCM sign-ins sealed with the Keystore alias
  `atom_provider_credentials_v1`, the provider id as AAD).
- Pure logic, tested on the JVM: `core/src/main/kotlin/atom/core/legacy/` (chunked values, the DataStore reader, the
  default-model rule, conversation mapping, the import plan) and `llm/cloud/src/main/kotlin/atom/llm/cloud/legacy/`
  (providers, models and sign-ins). The Android side is `app/src/main/kotlin/atom/app/data/legacy/`.
- What was imported is recorded in `files/atom4_legacy_import.json` (old ids to Atom 4 ids). Each old item is
  imported at most once; something you delete in Atom 4 afterwards does not come back.
