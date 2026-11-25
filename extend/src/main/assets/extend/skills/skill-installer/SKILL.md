---
name: skill-installer
description: Find and install skills from public GitHub repositories, folders or SKILL.md links. Use when the user asks to install, add or download a skill, or names a GitHub URL that contains skills.
---

# Skill installer

## Install from a link

1. Get the GitHub URL from the user. All of these work:
   - a repository: `https://github.com/owner/repo`
   - a folder: `https://github.com/owner/repo/tree/main/skills/pdf`
   - a single skill file: `https://github.com/owner/repo/blob/main/skills/pdf/SKILL.md`
2. Call `skill_install_github` with the `url`. Every folder holding a `SKILL.md` becomes one skill.
3. If the location holds more than 20 skills, the tool lists them. Show the user the list, ask which they want, then
   call the tool again with `names` (comma-separated folder names) or a narrower folder URL.
4. If a skill with the same name is already installed, the user is asked whether to replace it. Built-in skills are
   never replaced.
5. Report what was installed. New skills are usable from the user's next message and can be switched off in
   Settings → Skills.

## Finding skills

If the user has no link, suggest well-known public collections, for example `https://github.com/anthropics/skills`,
or search the web if a search tool is available. Install only from sources the user agrees to.

## Safety

- Before installing, mention that skills are instructions written by someone else; the user should trust the source.
- After installing, read the new skill with `skill_read` and tell the user if it includes scripts. Scripts never run
  automatically; they run only through a terminal tool, which asks the user each time.
- Private repositories are not supported, and GitHub allows about 60 unauthenticated requests per hour. If the rate
  limit is reached, suggest trying again later or importing a ZIP in Settings → Skills.

## Import a ZIP instead

In Settings → Skills → Import ZIP, the user can pick a ZIP holding exactly one skill (a `SKILL.md` at the top or
inside a single folder). Unsafe paths and oversized archives are rejected.
