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

## The import only reads

The import never changes, moves or deletes any file of the earlier app, and it never overwrites a provider, key or
sign-in you set up in Atom 4 yourself (it only adds what is missing). To read the database safely, Atom 4 makes a
temporary copy (with its write-ahead log and journal, so an unfinished write is completed or undone on the copy only)
in its cache folder and deletes it right after. Without room for a copy, it reads the original only when that cannot
touch any file; otherwise it stops and asks for free space. Nothing leaves the phone, and keys are never written to
logs.

## Your old files are removed a week later

The earlier app kept your API keys **unencrypted** in its database. Atom 4 stores the imported keys in its own vault,
encrypted with a key held in the Android Keystore. So the old copy is not kept forever:

- **7 days after an import that brought everything over** (no error, no provider or conversation that failed), Atom 4
  deletes the earlier app's files the next time it starts. The week is there so you can still run the import again if
  something looks off. A sign-in that had to be redone does not hold this up: the old sign-in could not be used anyway.
- **Earlier, by hand**: **Settings > General > Data backup > Remove previous Atom's data** deletes them right away,
  after you confirm. The row is shown only while old files are on the phone. Until an import has run it says
  "Import first".
- **If no import ever finished cleanly** (for example it keeps failing), nothing is deleted automatically. The row
  stays available, with a warning that whatever was not imported yet will be lost.

Your imported data stays in Atom 4. After the removal, the "Import from previous Atom" row goes away, since there is
nothing left to import. What is removed, under the app's data folder, and nothing else:

| What | Path |
|---|---|
| Database, with the plaintext API keys | `databases/atom.db`, `atom.db-wal`, `atom.db-shm`, `atom.db-journal` |
| Sign-in files | `no_backup/provider_credentials/` (the whole folder) |
| Settings | `files/datastore/atom_settings.preferences_pb` |
| Saved secrets (MCP tokens, voice and image-generation keys) | `shared_prefs/atom_mcp_secrets.xml`, `atom_speech_secrets.xml`, `atom_image_gen_secrets.xml` |
| Android Keystore keys, each only once its files are gone | `atom_provider_credentials_v1`, `atom_mcp_credentials_v1`, `atom_speech_credentials_v1`, `atom_image_gen_credentials_v1` |

None of these names starts with `atom4`, the prefix of every Atom 4 store ([UPDATES.md](UPDATES.md)), so Atom 4's
own data is never touched. Other leftovers of the earlier app (pictures it generated, character folders, terminal
and skills files, the bubble chat) are not removed; clearing Atom's storage in Android's settings removes them, along
with Atom 4's own data.

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
- Removal of the old files: the list and the rules (`completedAt` plus 7 days, never without a clean import, once,
  recorded as `legacyCleanedAt`) are in `core/src/main/kotlin/atom/core/legacy/LegacyCleanup.kt`, with tests that the
  list holds no Atom 4 name. The check runs at app start and only reads the marker until it is due. Files are deleted
  with `File.delete()`, the sign-in folder with `deleteTreeNoFollow()` (links are removed, never followed); the
  removal waits for a running import, and the time is recorded only once every file and key is gone (otherwise it is
  tried again at the next start).
