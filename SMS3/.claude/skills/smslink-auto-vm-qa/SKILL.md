---
name: smslink-auto-vm-qa
description: Single-entry SMS-link emulator QA skill that builds the matrix, runs preflight, classifies blockers, and only patches confirmed product defects.
---

# SMS-link Auto VM QA

Use this as the only entry skill for the full Android emulator QA loop.

## Mission

Take one requirements document and drive the entire workflow:

1. Build the feature matrix.
2. Run emulator preflight.
3. Capture identity evidence.
4. Separate platform blockers, stale-data contamination, reconnect-state ambiguity, and product defects.
5. Only patch confirmed app defects.
6. Rebuild and rerun the exact failed case once.
7. Stop retrying after one refresh cycle if the same non-app blocker remains.

## Operating rules

- Treat discovery-log `deviceId` as authoritative.
- Do not reuse cached relay peers, stale screenshots, or previous-round IDs.
- Do not let system UI, launcher, DocumentsUI, package-manager readiness, or boot failures consume product-fix time.
- If the same mismatch repeats after one refresh, classify it as stale-data or platform-state contamination and switch evidence source.
- Use 1-level on exactly two healthy emulators first.
- Promote to 2-level on four healthy emulators only after the 1-level peer flows pass.

## Fast blocker map

Classify these as non-program issues first unless logs prove the app is at fault:

- `adb devices` empty or emulator processes missing
- `prepare_android_emulator.ps1` times out, but manual health gates pass
- emulator NAT / host bridge / proxy path failures during 2-emulator file transfer
- `CryptoUpcalls`, `NONEwithECDSA`, `RSA routines`, or `SSLHandshakeException` tied to AndroidKeyStore / Conscrypt private-key provider incompatibility
- `mergeDebugJavaResource` failures caused by duplicate BouncyCastle `META-INF/versions/9` resources
- EOF / `Connection reset` after transfer completion
- Windows UTF-8 / GBK decode or encode failures in UI-dump scripts
- UI coordinate drift between phone and tablet layouts
- `dumpsys package` not showing non-exported services
- `adb shell am start-foreground-service` denial for `exported=false` services

## Cross-model compatibility

This skill is designed to be invoked by Codex, Claude Code, or Gemini with the same plain-text prompt.

- Do not assume the caller can load chained sub-skills.
- Treat this skill as a single-entry workflow even when the host tool does not support nested skill execution.
- If the caller only supports prompt text, use the prompt in the final section exactly as written.
- If the caller supports local skill loading, point it at this skill directory and use the same prompt.

## Required evidence

For each feature or failure, require:

- target serial(s)
- discovery-log `deviceId`
- visible UI label or QR target
- relay peer snapshot timestamp
- preflight result
- app launch result
- top activity / focus owner
- screenshot or structured step result
- relevant log lines
- expected result vs actual result

## Non-program issue rules

1. If `adb devices -l` is empty, do not blame the app. Recover the environment first.
2. If the preflight script times out but manual gates pass, classify `preflight_script_timeout` and continue QA.
3. If file transfer between two emulators fails with `ECONNREFUSED` or `Unable to establish connection`, verify the QA host proxy + `adb forward` bridge before touching product code.
4. If TLS logs contain `CryptoUpcalls`, `NONEwithECDSA`, `RSA routines`, or `SSLHandshakeException`, treat it as TLS private-key provider incompatibility, not generic network failure.
5. If compilation fails with BouncyCastle resource conflicts, fix packaging excludes first.
6. If logs show EOF or `Connection reset` after successful send/receive, treat it as expected close unless the completion signals are missing.
7. If UI click targets drift on phone/tablet, use XML `text` / `content-desc` / `bounds` instead of hard-coded coordinates.
8. If `dumpsys package` misses a service, check the manifest and merged manifest before declaring the service absent.
9. If shell service start is denied for `exported=false`, use the app's internal UI or broadcast trigger instead of retrying shell launches.

## Role handoff rules

- Use the orchestrator behavior to plan and merge evidence.
- Use the executor behavior to run emulators and collect artifacts.
- Use the triage behavior to label every failure before any fix.
- Use the fixer behavior only when the failure is confirmed as product-related.

## Prompt to start

Paste this exact text when you want to run it:

`Use $smslink-auto-vm-qa to run the full SMS-link Android emulator workflow from requirements to evidence to triage to fix, with one refresh cycle max for stale identity mismatches, exact device identity checks, 1-level on two healthy emulators first, and 2-level only after 1-level passes.`

## VSCode usage

- Claude Code in VSCode: load the skill if your workspace/extension supports local skills, then paste the prompt above in the agent textbox.
- Gemini in VSCode: paste the prompt above directly into the Gemini chat panel. If the extension does not support local skills, the prompt alone is the contract.
