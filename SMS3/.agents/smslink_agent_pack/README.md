# SMS-link Agent Pack

This starter kit upgrades your current emulator QA skill into a multi-agent workflow for Codex / Claude Code style environments.

## What this pack contains

- `skills/smslink-orchestrator/`  
  The supervisor. Reads the requirements, decides the round target, assigns work, and merges evidence.
- `skills/smslink-executor/`  
  The device runner. Controls Android emulators, runs preflight, drives UI flows, collects screenshots and logs.
- `skills/smslink-triage/`  
  The log-and-failure classifier. Separates platform blockers from app defects.
- `skills/smslink-fixer/`  
  The repair agent. Patches product code only when evidence points at the app.
- `templates/`  
  Reusable report formats.
- `scripts/`  
  Helper install notes and a Windows setup helper.

## Recommended stack

This pack assumes the following execution stack:

1. Your existing emulator preparation and log collection scripts.
2. `agent-device` as the primary system/UI executor.
3. `Maestro` for stable, replayable regression flows.
4. `OpenHands` as the code-fix layer after evidence is collected.

## Folder placement

Put this folder in a workspace that your coding agent can read.

A simple layout is:

```text
repo-root/
  .agents/
    smslink_agent_pack/
  app/
  scripts/
  docs/
```

If your tool supports local skill loading, point it at `smslink_agent_pack/skills`.

## Install order

1. Install Android Studio and create two stable AVDs first.
2. Install ADB / platform-tools and verify `adb devices` works.
3. Install Maestro CLI.
4. Install agent-device.
5. Install OpenHands if you want autonomous fix loops.
6. Copy your current project-specific files into this pack:
   - `acceptance-rules.md`
   - `log-playbook.md`
   - `test-matrix.md`
   - `prepare_android_emulator.ps1`
   - `collect_android_logs.ps1`
7. Load the skills and start with `smslink-orchestrator`.

## Minimal run flow

1. The orchestrator reads the requirements.
2. It tells the executor to bring up exactly two emulators and run preflight.
3. The executor runs a 1-level round and saves evidence.
4. The triage skill labels each failure.
5. The fixer only edits code for app defects.
6. Rebuild with `assembleDebug`.
7. Repeat the failed case once. If the same non-app blocker remains, stop retrying and reclassify the evidence source.
8. After 1-level passes, move to a 2-level round on four emulators.

## Identity-first rule

This pack is optimized to avoid wasting time on stale or mismatched device state.

- Treat discovery-log `deviceId` as the source of truth for pairing.
- Do not use old relay peers, old screenshots, or memory of previous rounds as a pairing target.
- After restart or reconnect, verify whether the device is:
  - paired
  - persisted
  - connected
- If a mismatch repeats after one refresh cycle, classify it as stale-data or platform-state contamination before any code fix.

## How to invoke in text

Prefer the single-entry skill below. Paste this prompt into the chat textbox:

- `Use $smslink-auto-vm-qa to run the full SMS-link Android emulator workflow from requirements to evidence to triage to fix, with one refresh cycle max for stale identity mismatches, exact device identity checks, 1-level on two healthy emulators first, and 2-level only after 1-level passes.`

If you need to split execution manually, these are the component skills:

- `Use $smslink-orchestrator ...`
- `Use $smslink-executor ...`
- `Use $smslink-triage ...`
- `Use $smslink-fixer ...`

## VSCode usage

### Claude Code

If Claude Code can load local skills in your setup:

1. Make sure the workspace points at `smslink_agent_pack/skills`.
2. Use the single-entry prompt above in the Claude Code textbox.
3. Keep the workflow in one round unless the agent explicitly tells you a blocker is non-program-related.

This repository also includes a root-level [CLAUDE.md](../CLAUDE.md) that Claude Code can load automatically.

If Claude Code does not expose local skill loading:

1. Paste the same prompt directly into Claude Code.
2. The prompt itself is the contract.

### Gemini

If Gemini in VSCode supports local agent instructions in your setup:

1. Point it at the same workspace folder.
2. Paste the single-entry prompt above into the Gemini chat panel.
3. Optionally attach [GEMINI_PROMPT.md](../GEMINI_PROMPT.md) with `@GEMINI_PROMPT.md` so the model gets the full contract in context.

If Gemini does not support local skill loading:

1. Paste the same prompt directly into Gemini.
2. Ask it to follow the prompt exactly and keep stale-data / platform blockers separate from app defects.

## Important boundary

Do not let the fixer patch product code when the evidence points to:

- System UI ANR
- DocumentsUI instability
- launcher instability
- package manager not ready
- emulator boot failure
- Google Play noise unrelated to the app

That is a platform blocker, not a product bug.
