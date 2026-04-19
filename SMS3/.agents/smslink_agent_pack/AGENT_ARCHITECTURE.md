# Agent Architecture

## 1. smslink-orchestrator

Role: supervisor / reviewer / round manager.

Responsibilities:
- Read the requirements and classify features into:
  - emulator-testable
  - degrade-or-guide-only
  - true-device-only
- Decide whether the round is 1-level or 2-level.
- Ask the executor for evidence, not opinions.
- Ask the triage agent to classify every failure.
- Only send product defects to the fixer.
- Produce the final round report.
- Treat discovery-log identity as authoritative for pairing.
- Refuse to use stale relay peers, stale screenshots, or cached device IDs as targets.

Inputs:
- requirements document
- acceptance rules
- test matrix
- prior round report

Outputs:
- feature matrix
- round checklist
- pass/fail/blocker summary
- identity mismatch summary
- stale-data contamination summary

## 2. smslink-executor

Role: device and workflow runner.

Responsibilities:
- Start the target emulators.
- Run emulator health gates.
- Install the APK.
- Launch the app.
- Execute flows on emulator(s).
- Capture screenshots, window focus, activity state, services state, and logs.
- Use `dumpsys` evidence when system UI is unstable.

Inputs:
- round plan
- APK path
- package name
- device serials

Outputs:
- structured run evidence
- screenshots
- log bundles
- per-step success/failure state

## 3. smslink-triage

Role: failure classifier.

Responsibilities:
- Read logs and classify each failure into:
  - platform_boot
  - platform_ui
  - platform_noise
  - stale_data
  - identity_mismatch
  - reconnect_state
  - compile
  - crash
  - app_anr
  - permission
  - link
  - state
  - missing_feature
  - limit
- Stop app-side repair when the issue is not inside the app.
- Tell the orchestrator what to retry and what to isolate.
- Distinguish paired, persisted, connected, and reconnected state instead of collapsing them into one success state.

Outputs:
- root-cause labels
- retry advice
- product-fix advice or no-fix decision

## 4. smslink-fixer

Role: guarded repair agent.

Responsibilities:
- Read the failing evidence.
- Patch product code only when the app is implicated.
- Keep changes minimal and reversible.
- Rebuild after each patch.
- Return a short patch note and suspected impact.
- Require focused compile verification before rerunning a fixed case.

Hard limits:
- no repair attempt on platform blockers
- no broad refactors during a failing round
- no speculative fixes without evidence
- no blind retries beyond one refresh cycle for the same stale identity mismatch
