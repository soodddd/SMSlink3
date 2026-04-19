@.claude/skills/android-real-device-qa/SKILL.md

## Claude Code project instructions

- For Android physical-device QA tasks, use `$android-real-device-qa` as the single entry workflow.
- Read the user-provided requirements or technical document first, then derive the scenario matrix from that document.
- Keep device availability, authorization, boot, unlock, install, launch, permission, and system UI blockers separate from product defects.
- Do not use emulator-specific assumptions or emulator-specific evidence paths.
- Only patch confirmed product defects, then rebuild, reinstall, and rerun the exact failed case.
- Use `GEMINI_PROMPT.md` when you need the Gemini-facing prompt text.

