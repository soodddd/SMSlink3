# Install Notes

## Windows host + Android Studio emulator

### 1. Android base tools

Install or verify:
- Android Studio
- Android SDK Platform-Tools
- Android SDK Command-line Tools
- at least 2 AVDs for 1-level testing
- preferably 4 AVDs if you want 2-level fan-out tests later

Check:

```powershell
adb devices
emulator -list-avds
```

### 2. Maestro

Install Maestro CLI according to its official documentation, then verify:

```powershell
maestro --version
```

### 3. agent-device

Install from the official repository or package instructions, then verify the CLI is callable.

### 4. OpenHands

Install only if you want autonomous code-fix loops.

### 5. Place the pack

Recommended:

```text
<your-repo>\.agents\smslink_agent_pack
```

### 6. Add your project files

Copy in these existing files from your current QA setup:
- `acceptance-rules.md`
- `log-playbook.md`
- `test-matrix.md`
- `prepare_android_emulator.ps1`
- `collect_android_logs.ps1`

### 7. First run prompt

Give the supervisor this task:

> Read the SMS-link requirements, build the feature matrix, start a 1-level round on exactly two emulators, run emulator preflight first, separate platform blockers from app defects, and do not let the fixer patch product code unless triage confirms an app defect on a healthy emulator.
