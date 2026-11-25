---
name: self-improving-agent
description: Learn from mistakes, corrections and repeated requests so later tasks go better. Use after the user corrects you, when a task needed several attempts, or when the user says "remember how to do this".
---

# Self-improving agent

Turn what happened in this conversation into something that helps next time: a memory entry for facts about the
user, or a skill for a repeatable way of working.

## When to use

- The user corrected you ("no, I meant…", "don't do that again", "always use…").
- A task needed several attempts before it worked, and you now know the reliable path.
- The user asks for the same kind of task again and again.
- The user explicitly asks you to remember a procedure or preference.

## Steps

1. **Name the lesson in one sentence.** Example: "On this phone, Wi-Fi settings are reached through Connections, not Network."
   If you cannot state it in one sentence, it is not ready to save.
2. **Pick where it belongs.**
   - A fact or preference about the user → memory. Use `memory_append` with section "Core memory" only if it matters
     in nearly every conversation (name, language, tone, units). Everything else goes under a topical section such
     as "Preferences" or "Learnings".
   - A procedure with steps → a skill. Read the existing skill with `skill_read` first if one fits; otherwise create a
     new one with `skill_write` (see the skill-creator skill for the format).
3. **Check for duplicates.** Call `memory_read` (or `skill_read`) and update the existing line with `memory_update`
   instead of adding a near-copy.
4. **Tell the user in one line** what you saved and where, so they can undo it. Ask first if the lesson touches
   anything personal or sensitive.

## Rules

- Never store passwords, one-time codes, card numbers, tokens or other secrets.
- Save conclusions, not transcripts. Short bullets beat paragraphs.
- Prefer fixing an existing entry over adding a new one.
- Do not save guesses about the user. Only what they said or clearly confirmed.
- Memory is background information, not a list of orders; write entries as facts ("Prefers Celsius"), not as
  commands to yourself.
