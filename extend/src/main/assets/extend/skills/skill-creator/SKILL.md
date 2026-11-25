---
name: skill-creator
description: Create a new skill or improve an existing one, with a clear name, a description that says when to use it, and step-by-step instructions. Use when the user asks for a new skill, a reusable workflow, or to turn something you just did into a repeatable procedure.
---

# Skill creator

A skill is a folder with a `SKILL.md` file and, optionally, supporting files. Atom lists every enabled skill by
name and description in its instructions, and loads the full `SKILL.md` only when a task matches. So the
description decides when a skill is used, and the body decides how well.

## 1. Understand the job

Ask (or infer from the conversation): what task should the skill handle, what does a good result look like, and
what went wrong before? Collect one or two concrete example requests.

## 2. Choose the name

Lowercase words joined by hyphens, at most 64 characters, e.g. `weekly-report`, `trip-packing-list`. Built-in
names (self-improving-agent, skill-creator, skill-installer) cannot be reused.

## 3. Write the description

One or two sentences, at most 1024 characters, covering **what** it does and **when** to use it. Include the words
a user would actually say. Good: "Draft a weekly status report from calendar events and notes. Use when the user asks
for a weekly report, recap or summary of their week." Weak: "Helps with reports."

## 4. Write the instructions

Use Markdown. Keep the body focused, usually under 300 lines:

- A short purpose paragraph.
- Numbered steps the agent follows, naming the tools to use.
- Rules and pitfalls (what to never do, what to check before finishing).
- One worked example of input and expected output.

Move long reference material (templates, tables, examples) into separate files, and say in the steps when to read
each one, e.g. "Read `templates/report.md` before writing the report."

## 5. Save it

Call `skill_write` with `name`, `description` and `instructions`. Add a supporting file with `resource_path` and
`resource_content`; call `skill_write` again for each additional file. Updating an existing skill asks the user.

## 6. Check it

Call `skill_read` with the new name and read it as if you had never seen it: would you know when to use it and what
to do? Fix anything vague. Then tell the user it is ready from their next message and can be switched off in
Settings → Skills.

## Scripts

A skill may include scripts (for example `scripts/convert.py`). They never run automatically. The instructions
should say to run them with a terminal tool, which asks the user first, and what the script expects and returns.
