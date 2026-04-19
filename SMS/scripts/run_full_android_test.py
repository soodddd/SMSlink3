#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional


WORKSPACE = Path(__file__).resolve().parents[1]
APP_PACKAGE = "com.smslink"
LISTENER_COMPONENT = "com.smslink/com.smslink.feature.notification.SmsLinkNotificationListenerService"
MODULES = [
    ":app",
    ":core:common",
    ":core:model",
    ":core:database",
    ":core:preferences",
    ":network:protocol",
    ":network:transport",
    ":network:discovery",
    ":network:hotspot",
    ":feature:device",
    ":feature:notification",
    ":feature:call",
    ":feature:transfer",
    ":feature:settings",
    ":audio",
    ":ui",
]

STEP_TIMEOUT = 300
DEFAULT_BUILD_TASK = ":app:assembleDebug"
INSTALL_TIMEOUT = 420


@dataclass
class ProcResult:
    returncode: int
    output: str


@dataclass
class LogCapture:
    process: subprocess.Popen[str]
    thread: threading.Thread
    stop_event: threading.Event
    log_path: Path


class RunContext:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.log_lines: list[str] = []
        self.summary_lines: list[str] = []
        self.build_root = root / "build"
        self.logs_root = root / "logs"
        self.screens_root = root / "screenshots"
        self.videos_root = root / "videos"
        self.uia_root = root / "uia"
        for directory in [
            root,
            self.build_root,
            self.logs_root,
            self.screens_root,
            self.videos_root,
            self.uia_root,
        ]:
            directory.mkdir(parents=True, exist_ok=True)

    def write(self, message: str) -> None:
        print(message, flush=True)
        self.log_lines.append(message)
        self.summary_lines.append(f"- {message}")

    def section(self, title: str) -> None:
        border = "=" * 72
        print()
        print(border)
        print(title)
        print(border)
        self.summary_lines.append("")
        self.summary_lines.append(f"## {title}")

    def warn(self, message: str) -> None:
        self.write(f"WARN: {message}")

    def fail(self, message: str) -> None:
        self.write(f"FAIL: {message}")

    def flush_summary(self) -> None:
        summary_path = self.root / "summary.md"
        artifact_lines = [
            "",
            "Artifacts:",
            f"- Logs: {self.logs_root}",
            f"- Screenshots: {self.screens_root}",
            f"- Videos: {self.videos_root}",
            f"- UI dumps: {self.uia_root}",
            f"- Build logs: {self.build_root}",
        ]
        summary_path.write_text("\n".join(self.summary_lines + artifact_lines) + "\n", encoding="utf-8")


def quote_cmd(cmd: Iterable[str]) -> str:
    return " ".join(subprocess.list2cmdline([part]) for part in cmd)


def run_cmd(
    ctx: RunContext,
    cmd: list[str],
    log_path: Path,
    *,
    cwd: Path = WORKSPACE,
    timeout: Optional[int] = None,
    input_text: Optional[str] = None,
    check: bool = True,
) -> ProcResult:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    header = f">> {quote_cmd(cmd)}"
    ctx.write(header)
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"\n{header}\n")
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.PIPE if input_text is not None else None,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        try:
            output, _ = proc.communicate(input=input_text, timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            output, _ = proc.communicate()
            raise RuntimeError(f"Command timed out after {timeout} seconds: {quote_cmd(cmd)}")
        if output:
            log.write(output)
        result = ProcResult(returncode=proc.returncode, output=output or "")
        if check and result.returncode != 0:
            raise RuntimeError(f"Command failed with exit code {result.returncode}: {quote_cmd(cmd)}")
        return result


def get_sdk_root() -> Path:
    candidates = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        r"C:\Users\forek\AppData\Local\Android\Sdk",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            return Path(candidate)
    raise RuntimeError("Android SDK root not found.")


def sdkmanager_path(sdk_root: Path) -> Path:
    return sdk_root / "cmdline-tools" / "latest" / "bin" / "sdkmanager.bat"


def adb_path(sdk_root: Path) -> Path:
    return sdk_root / "platform-tools" / "adb.exe"


def ensure_sdk_components(ctx: RunContext, sdk_root: Path) -> None:
    required = {
        "platforms;android-35": sdk_root / "platforms" / "android-35",
        "build-tools;35.0.0": sdk_root / "build-tools" / "35.0.0",
    }
    sdkmanager = sdkmanager_path(sdk_root)
    if not sdkmanager.exists():
        raise RuntimeError(f"sdkmanager not found: {sdkmanager}")

    for component, path in required.items():
        if path.exists():
            ctx.write(f"SDK component present: {component}")
            continue
        ctx.write(f"Installing missing SDK component: {component}")
        run_cmd(
            ctx,
            [str(sdkmanager), f"--sdk_root={sdk_root}", "--install", component],
            ctx.build_root / "sdkmanager.log",
            input_text=("y\n" * 30),
            check=True,
        )


def run_adb(
    ctx: RunContext,
    sdk_root: Path,
    serial: str,
    args: list[str],
    log_name: str,
    *,
    check: bool = True,
    timeout: Optional[int] = None,
) -> ProcResult:
    cmd = [str(adb_path(sdk_root)), "-s", serial] + args
    return run_cmd(ctx, cmd, ctx.build_root / log_name, check=check, timeout=timeout)


def list_devices(ctx: RunContext, sdk_root: Path) -> list[str]:
    result = run_cmd(
        ctx,
        [str(adb_path(sdk_root)), "devices", "-l"],
        ctx.build_root / "adb_devices.log",
    )
    devices: list[str] = []
    for line in result.output.splitlines():
        if re.match(r"^\S+\s+device\b", line):
            devices.append(line.split()[0])
    return devices


def start_logcat(ctx: RunContext, sdk_root: Path, serial: str) -> LogCapture:
    log_path = ctx.logs_root / f"{serial}.logcat.txt"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen(
        [str(adb_path(sdk_root)), "-s", serial, "logcat", "-v", "time"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    stop_event = threading.Event()

    def _pump() -> None:
        assert proc.stdout is not None
        with log_path.open("a", encoding="utf-8") as log:
            while not stop_event.is_set():
                line = proc.stdout.readline()
                if line:
                    log.write(line)
                elif proc.poll() is not None:
                    break
                else:
                    time.sleep(0.1)

    thread = threading.Thread(target=_pump, name=f"logcat-{serial}", daemon=True)
    thread.start()
    return LogCapture(process=proc, thread=thread, stop_event=stop_event, log_path=log_path)


def stop_logcat(capture: LogCapture) -> None:
    capture.stop_event.set()
    if capture.process.poll() is None:
        capture.process.terminate()
        try:
            capture.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            capture.process.kill()
    capture.thread.join(timeout=5)


def capture_screenshot(ctx: RunContext, sdk_root: Path, serial: str, label: str) -> Path:
    safe = re.sub(r"[^a-zA-Z0-9._-]", "_", label)
    remote = f"/sdcard/{safe}.png"
    local = ctx.screens_root / f"{serial}-{safe}.png"
    run_adb(ctx, sdk_root, serial, ["shell", "screencap", "-p", remote], f"{serial}-screencap.log", check=False)
    run_adb(ctx, sdk_root, serial, ["pull", remote, str(local)], f"{serial}-screencap-pull.log")
    return local


def capture_video(ctx: RunContext, sdk_root: Path, serial: str, label: str, seconds: int = 30) -> Path:
    safe = re.sub(r"[^a-zA-Z0-9._-]", "_", label)
    remote = f"/sdcard/{safe}.mp4"
    local = ctx.videos_root / f"{serial}-{safe}.mp4"
    run_cmd(
        ctx,
        [str(adb_path(sdk_root)), "-s", serial, "shell", "screenrecord", "--time-limit", str(seconds), remote],
        ctx.build_root / f"{serial}-screenrecord.log",
        timeout=seconds + 20,
        check=False,
    )
    run_adb(ctx, sdk_root, serial, ["pull", remote, str(local)], f"{serial}-screenrecord-pull.log")
    return local


def capture_ui_dump(ctx: RunContext, sdk_root: Path, serial: str, label: str) -> Path:
    safe = re.sub(r"[^a-zA-Z0-9._-]", "_", label)
    remote = f"/sdcard/{safe}.xml"
    local = ctx.uia_root / f"{serial}-{safe}.xml"
    run_adb(ctx, sdk_root, serial, ["shell", "uiautomator", "dump", remote], f"{serial}-uia-dump.log", check=False)
    run_adb(ctx, sdk_root, serial, ["pull", remote, str(local)], f"{serial}-uia-pull.log")
    return local


def load_nodes(xml_path: Path) -> list[ET.Element]:
    tree = ET.parse(xml_path)
    return list(tree.iter("node"))


def node_center(node: ET.Element) -> tuple[int, int]:
    bounds = node.attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        raise RuntimeError(f"Cannot parse bounds: {bounds}")
    left, top, right, bottom = map(int, m.groups())
    return (left + right) // 2, (top + bottom) // 2


def find_node_by_text(xml_path: Path, texts: Iterable[str]) -> Optional[ET.Element]:
    nodes = load_nodes(xml_path)
    text_list = list(texts)
    for expected in text_list:
        for node in nodes:
            text = (node.attrib.get("text") or "").strip()
            desc = (node.attrib.get("content-desc") or "").strip()
            if text == expected or desc == expected or expected in text or expected in desc:
                return node
    return None


def find_node_by_regex(xml_path: Path, pattern: str) -> Optional[ET.Element]:
    rx = re.compile(pattern)
    for node in load_nodes(xml_path):
        text = (node.attrib.get("text") or "").strip()
        desc = (node.attrib.get("content-desc") or "").strip()
        if rx.search(text) or rx.search(desc):
            return node
    return None


def find_node_by_class(xml_path: Path, class_name: str) -> Optional[ET.Element]:
    for node in load_nodes(xml_path):
        if node.attrib.get("class") == class_name:
            return node
    return None


def tap_node(ctx: RunContext, sdk_root: Path, serial: str, node: ET.Element, log_name: str) -> None:
    x, y = node_center(node)
    run_adb(ctx, sdk_root, serial, ["shell", "input", "tap", str(x), str(y)], log_name)


def wait_for_text(
    ctx: RunContext,
    sdk_root: Path,
    serial: str,
    texts: Iterable[str],
    label: str,
    timeout: int,
) -> Path:
    deadline = time.time() + timeout
    text_list = list(texts)
    while time.time() < deadline:
        xml_path = capture_ui_dump(ctx, sdk_root, serial, label)
        if find_node_by_text(xml_path, text_list) is not None:
            return xml_path
        time.sleep(2)
    shot = capture_screenshot(ctx, sdk_root, serial, f"{label}-timeout")
    video = capture_video(ctx, sdk_root, serial, f"{label}-timeout", seconds=30)
    raise RuntimeError(
        f"Timed out waiting for UI text {text_list} on {serial}. Screenshot={shot} Video={video}"
    )


def wait_for_regex(
    ctx: RunContext,
    sdk_root: Path,
    serial: str,
    pattern: str,
    label: str,
    timeout: int,
) -> tuple[Path, ET.Element]:
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml_path = capture_ui_dump(ctx, sdk_root, serial, label)
        node = find_node_by_regex(xml_path, pattern)
        if node is not None:
            return xml_path, node
        time.sleep(2)
    shot = capture_screenshot(ctx, sdk_root, serial, f"{label}-timeout")
    video = capture_video(ctx, sdk_root, serial, f"{label}-timeout", seconds=30)
    raise RuntimeError(
        f"Timed out waiting for UI regex {pattern!r} on {serial}. Screenshot={shot} Video={video}"
    )


def wait_for_app_process(ctx: RunContext, sdk_root: Path, serial: str, timeout: int = 60) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = run_adb(
            ctx,
            sdk_root,
            serial,
            ["shell", "pidof", APP_PACKAGE],
            f"{serial}-pidof.log",
            check=False,
        )
        pid = result.output.strip()
        if pid:
            return pid
        time.sleep(2)
    raise RuntimeError(f"App process did not start on {serial}")


def ensure_package_available(ctx: RunContext, sdk_root: Path, serial: str, timeout: int = 120) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = run_adb(
            ctx,
            sdk_root,
            serial,
            ["shell", "pm", "list", "packages", APP_PACKAGE],
            f"{serial}-pm-list-packages.log",
            check=False,
        )
        if APP_PACKAGE in result.output:
            return
        time.sleep(2)
    raise RuntimeError(f"Package {APP_PACKAGE} was not available on {serial} after installation wait.")


def install_apk(ctx: RunContext, sdk_root: Path, serial: str, apk: Path, log_name: str) -> None:
    try:
        run_adb(ctx, sdk_root, serial, ["install", "-r", "-g", str(apk)], log_name, timeout=INSTALL_TIMEOUT)
    except RuntimeError as exc:
        if "timed out" not in str(exc):
            raise
        ctx.warn(f"{serial} install command timed out, checking whether the package became available anyway.")
        ensure_package_available(ctx, sdk_root, serial, timeout=180)


def install_and_prepare_device(ctx: RunContext, sdk_root: Path, serial: str) -> None:
    apk = WORKSPACE / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    ctx.write(f"Preparing device {serial}")
    run_adb(ctx, sdk_root, serial, ["wait-for-device"], f"{serial}-wait.log", timeout=120)
    run_adb(ctx, sdk_root, serial, ["shell", "am", "clear-debug-app"], f"{serial}-clear-debug-app.log", check=False)
    run_adb(ctx, sdk_root, serial, ["shell", "settings", "put", "global", "wait_for_debugger", "0"], f"{serial}-wait-for-debugger.log", check=False)
    install_apk(ctx, sdk_root, serial, apk, f"{serial}-install.log")
    clear_result = run_adb(
        ctx,
        sdk_root,
        serial,
        ["shell", "pm", "clear", APP_PACKAGE],
        f"{serial}-pm-clear.log",
        check=False,
        timeout=120,
    )
    if clear_result.returncode != 0:
        ctx.warn(f"{serial} pm clear failed, falling back to uninstall + fresh install.")
        run_adb(ctx, sdk_root, serial, ["uninstall", APP_PACKAGE], f"{serial}-uninstall.log", check=False, timeout=120)
        install_apk(ctx, sdk_root, serial, apk, f"{serial}-reinstall.log")

    runtime_permissions = [
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.NEARBY_WIFI_DEVICES",
    ]
    for permission in runtime_permissions:
        run_adb(
            ctx,
            sdk_root,
            serial,
            ["shell", "pm", "grant", APP_PACKAGE, permission],
            f"{serial}-pm-grant.log",
            check=False,
        )

    run_adb(
        ctx,
        sdk_root,
        serial,
        ["shell", "settings", "put", "secure", "enabled_notification_listeners", LISTENER_COMPONENT],
        f"{serial}-listener.log",
        check=False,
    )
    run_adb(
        ctx,
        sdk_root,
        serial,
        ["shell", "settings", "put", "global", "stay_on_while_plugged_in", "7"],
        f"{serial}-stayon.log",
        check=False,
    )

    info = run_adb(ctx, sdk_root, serial, ["shell", "getprop", "ro.product.model"], f"{serial}-model.log", check=False)
    wm_size = run_adb(ctx, sdk_root, serial, ["shell", "wm", "size"], f"{serial}-wmsize.log", check=False)
    ctx.write(f"{serial} model: {info.output.strip()}")
    ctx.write(f"{serial} wm size: {wm_size.output.strip()}")


def start_app(ctx: RunContext, sdk_root: Path, serial: str) -> None:
    run_adb(
        ctx,
        sdk_root,
        serial,
        ["shell", "am", "start", "-W", "-n", f"{APP_PACKAGE}/.MainActivity"],
        f"{serial}-start.log",
        timeout=120,
    )
    wait_for_app_process(ctx, sdk_root, serial)


def tap_text(ctx: RunContext, sdk_root: Path, serial: str, xml_path: Path, text: str, log_name: str) -> None:
    node = find_node_by_text(xml_path, [text])
    if node is None:
        raise RuntimeError(f"Text {text!r} not found on {serial}")
    tap_node(ctx, sdk_root, serial, node, log_name)


def onboarding_flow(ctx: RunContext, sdk_root: Path, serial: str, role_text: str) -> None:
    deadline = time.time() + STEP_TIMEOUT
    while time.time() < deadline:
        xml = capture_ui_dump(ctx, sdk_root, serial, "onboarding-state")
        if find_node_by_text(xml, ["添加设备", "生成配对码", "输入配对码"]) is not None:
            return
        if find_node_by_text(xml, ["开始使用"]) is not None:
            tap_text(ctx, sdk_root, serial, xml, "开始使用", f"{serial}-tap-start-using.log")
            time.sleep(1)
            continue
        if find_node_by_text(xml, ["选择设备角色"]) is not None:
            tap_text(ctx, sdk_root, serial, xml, role_text, f"{serial}-tap-role.log")
            time.sleep(1)
            continue
        if find_node_by_text(xml, ["授予权限"]) is not None:
            tap_text(ctx, sdk_root, serial, xml, "授予权限", f"{serial}-tap-permissions.log")
            time.sleep(1)
            continue
        if find_node_by_text(xml, ["开始配对"]) is not None:
            tap_text(ctx, sdk_root, serial, xml, "开始配对", f"{serial}-tap-start-pairing.log")
            time.sleep(1)
            continue
        time.sleep(2)

    shot = capture_screenshot(ctx, sdk_root, serial, "onboarding-timeout")
    video = capture_video(ctx, sdk_root, serial, "onboarding-timeout", seconds=30)
    raise RuntimeError(f"Timed out advancing onboarding on {serial}. Screenshot={shot} Video={video}")


def generate_pairing_code(ctx: RunContext, sdk_root: Path, serial: str) -> str:
    xml = capture_ui_dump(ctx, sdk_root, serial, "pairing-before-generate")
    tap_text(ctx, sdk_root, serial, xml, "生成配对码", f"{serial}-tap-generate-code.log")
    xml, node = wait_for_regex(ctx, sdk_root, serial, r"\b\d{6}\b", "pairing-code", STEP_TIMEOUT)
    code = (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()
    if not re.fullmatch(r"\d{6}", code):
        raise RuntimeError(f"Unable to extract pairing code on {serial}")
    ctx.write(f"Pairing code on {serial}: {code}")
    return code


def enter_pairing_code(ctx: RunContext, sdk_root: Path, serial: str, code: str) -> None:
    xml = capture_ui_dump(ctx, sdk_root, serial, "pairing-enter-code")
    tap_text(ctx, sdk_root, serial, xml, "输入配对码", f"{serial}-tap-enter-code.log")
    time.sleep(1)

    dialog_xml = wait_for_text(ctx, sdk_root, serial, ["6位数字"], "pairing-code-dialog", STEP_TIMEOUT)
    input_node = find_node_by_class(dialog_xml, "android.widget.EditText")
    if input_node is None:
        input_node = find_node_by_text(dialog_xml, ["6位数字"])
    if input_node is None:
        raise RuntimeError(f"Pairing code input field not found on {serial}")
    tap_node(ctx, sdk_root, serial, input_node, f"{serial}-tap-code-input.log")
    run_adb(
        ctx,
        sdk_root,
        serial,
        ["shell", "input", "text", code],
        f"{serial}-input-code.log",
    )
    dialog_xml = capture_ui_dump(ctx, sdk_root, serial, "pairing-code-confirm")
    tap_text(ctx, sdk_root, serial, dialog_xml, "确认", f"{serial}-tap-confirm-code.log")


def select_discovered_device(ctx: RunContext, sdk_root: Path, serial: str, preferred_name: str) -> None:
    deadline = time.time() + STEP_TIMEOUT
    while time.time() < deadline:
        xml = capture_ui_dump(ctx, sdk_root, serial, "pairing-discovered-devices")
        if find_node_by_text(xml, ["配对成功"]) is not None:
            return
        if find_node_by_text(xml, ["发现的设备"]) is not None:
            preferred = find_node_by_text(xml, [preferred_name])
            if preferred is not None:
                tap_node(ctx, sdk_root, serial, preferred, f"{serial}-tap-discovered-device.log")
                return

            excluded = {"发现的设备", "添加设备", "返回", "刚刚发现", "PHONE", "TABLET", "PC"}
            for node in load_nodes(xml):
                text = (node.attrib.get("text") or "").strip()
                if text and text not in excluded:
                    tap_node(ctx, sdk_root, serial, node, f"{serial}-tap-discovered-device.log")
                    return
        time.sleep(2)

    shot = capture_screenshot(ctx, sdk_root, serial, "pairing-discovered-devices-timeout")
    video = capture_video(ctx, sdk_root, serial, "pairing-discovered-devices-timeout", seconds=30)
    raise RuntimeError(f"Timed out selecting discovered device on {serial}. Screenshot={shot} Video={video}")


def finish_pairing(ctx: RunContext, sdk_root: Path, serial: str) -> None:
    wait_for_text(ctx, sdk_root, serial, ["配对成功"], "pairing-success", STEP_TIMEOUT)
    xml = capture_ui_dump(ctx, sdk_root, serial, "pairing-success")
    node = find_node_by_text(xml, ["完成"])
    if node is not None:
        tap_node(ctx, sdk_root, serial, node, f"{serial}-tap-finish-pairing.log")


def goto_bottom_tab(ctx: RunContext, sdk_root: Path, serial: str, tab_text: str, label: str) -> Path:
    xml = capture_ui_dump(ctx, sdk_root, serial, label)
    tap_text(ctx, sdk_root, serial, xml, tab_text, f"{serial}-tap-{label}.log")
    return xml


def run_home_smoke(ctx: RunContext, sdk_root: Path, serial: str) -> None:
    goto_bottom_tab(ctx, sdk_root, serial, "首页", "goto-home")
    wait_for_text(ctx, sdk_root, serial, ["SMS-Link", "连接状态"], "home-page", STEP_TIMEOUT)
    capture_screenshot(ctx, sdk_root, serial, "home-page")


def run_transfer_smoke(ctx: RunContext, sdk_root: Path, serial: str) -> None:
    goto_bottom_tab(ctx, sdk_root, serial, "传输", "goto-transfer")
    wait_for_text(ctx, sdk_root, serial, ["传输"], "transfer-page", STEP_TIMEOUT)
    capture_screenshot(ctx, sdk_root, serial, "transfer-page")


def run_settings_smoke(ctx: RunContext, sdk_root: Path, serial: str) -> None:
    goto_bottom_tab(ctx, sdk_root, serial, "设置", "goto-settings")
    wait_for_text(ctx, sdk_root, serial, ["设置"], "settings-page", STEP_TIMEOUT)
    xml = capture_ui_dump(ctx, sdk_root, serial, "settings-switch")
    switch_node = find_node_by_class(xml, "android.widget.Switch")
    if switch_node is None:
        raise RuntimeError(f"Dark mode switch not found on {serial}")
    tap_node(ctx, sdk_root, serial, switch_node, f"{serial}-tap-dark-mode.log")
    time.sleep(1)
    run_adb(ctx, sdk_root, serial, ["shell", "am", "force-stop", APP_PACKAGE], f"{serial}-force-stop.log", check=False)
    start_app(ctx, sdk_root, serial)
    goto_bottom_tab(ctx, sdk_root, serial, "设置", "goto-settings-after-restart")


def run_notifications_smoke(ctx: RunContext, sdk_root: Path, primary: str, secondary: str) -> None:
    run_adb(
        ctx,
        sdk_root,
        primary,
        [
            "shell",
            "cmd",
            "notification",
            "post",
            "-S",
            "bigtext",
            "-t",
            "SmsLinkTest",
            "SmsLinkTest",
            "Automation notification from primary",
        ],
        f"{primary}-notification-post.log",
    )
    time.sleep(3)

    goto_bottom_tab(ctx, sdk_root, primary, "通知", "goto-notifications-primary")
    goto_bottom_tab(ctx, sdk_root, secondary, "通知", "goto-notifications-secondary")
    wait_for_text(ctx, sdk_root, primary, ["通知历史"], "notifications-primary", STEP_TIMEOUT)
    wait_for_text(ctx, sdk_root, secondary, ["通知历史"], "notifications-secondary", STEP_TIMEOUT)
    wait_for_text(
        ctx,
        sdk_root,
        primary,
        ["SmsLinkTest", "Automation notification from primary"],
        "notification-primary-item",
        STEP_TIMEOUT,
    )
    wait_for_text(
        ctx,
        sdk_root,
        secondary,
        ["SmsLinkTest", "Automation notification from primary"],
        "notification-secondary-item",
        STEP_TIMEOUT,
    )
    capture_screenshot(ctx, sdk_root, primary, "notifications-primary")
    capture_screenshot(ctx, sdk_root, secondary, "notifications-secondary")


def discover_test_tasks() -> list[str]:
    test_tasks: list[str] = []
    for module in MODULES:
        module_dir = WORKSPACE / Path(module.lstrip(":").replace(":", "\\"))
        if (module_dir / "src" / "test").exists():
            test_tasks.append(f"{module}:testDebugUnitTest")
    return test_tasks


def run_gradle_coverage(ctx: RunContext, *, with_unit_tests: bool, full_module_assemble: bool) -> None:
    ctx.section("Gradle build and unit tests")
    gradlew = WORKSPACE / "gradlew.bat"
    assemble_tasks = [DEFAULT_BUILD_TASK]
    if full_module_assemble:
        assemble_tasks = [f"{module}:assembleDebug" for module in MODULES]

    ctx.write(f"Assemble tasks: {', '.join(assemble_tasks)}")
    assemble_started = time.perf_counter()
    run_cmd(
        ctx,
        [str(gradlew), "--daemon", "--build-cache", "--parallel", "--console=plain", *assemble_tasks],
        ctx.build_root / "assemble.log",
        cwd=WORKSPACE,
        check=True,
    )
    ctx.write(f"Assemble duration seconds: {time.perf_counter() - assemble_started:.1f}")

    if not with_unit_tests:
        ctx.write("Unit tests skipped. Pass --with-unit-tests to enable them.")
        return

    test_tasks = discover_test_tasks()
    if test_tasks:
        ctx.write(f"Unit test tasks: {', '.join(test_tasks)}")
        tests_started = time.perf_counter()
        run_cmd(
            ctx,
            [str(gradlew), "--daemon", "--build-cache", "--parallel", "--console=plain", *test_tasks],
            ctx.build_root / "unit-tests.log",
            cwd=WORKSPACE,
            check=True,
        )
        ctx.write(f"Unit test duration seconds: {time.perf_counter() - tests_started:.1f}")
    else:
        ctx.warn("No unit test directories discovered.")


def run_device_coverage(ctx: RunContext, sdk_root: Path, primary: str, secondary: str) -> None:
    ctx.section("Device preparation")
    install_and_prepare_device(ctx, sdk_root, primary)
    install_and_prepare_device(ctx, sdk_root, secondary)

    logcat_primary = start_logcat(ctx, sdk_root, primary)
    logcat_secondary = start_logcat(ctx, sdk_root, secondary)
    try:
        ctx.section("App launch and onboarding")
        start_app(ctx, sdk_root, primary)
        start_app(ctx, sdk_root, secondary)

        onboarding_flow(ctx, sdk_root, primary, "主设备（手机）")
        onboarding_flow(ctx, sdk_root, secondary, "副设备（电脑）")

        ctx.section("Pairing flow")
        code = generate_pairing_code(ctx, sdk_root, primary)
        enter_pairing_code(ctx, sdk_root, secondary, code)
        primary_name = run_adb(ctx, sdk_root, primary, ["shell", "getprop", "ro.product.model"], f"{primary}-pairing-model.log", check=False).output.strip()
        select_discovered_device(ctx, sdk_root, secondary, primary_name)
        finish_pairing(ctx, sdk_root, secondary)
        finish_pairing(ctx, sdk_root, primary)

        ctx.section("Post-pairing screens")
        run_home_smoke(ctx, sdk_root, primary)
        run_home_smoke(ctx, sdk_root, secondary)
        run_transfer_smoke(ctx, sdk_root, primary)
        run_transfer_smoke(ctx, sdk_root, secondary)
        run_settings_smoke(ctx, sdk_root, primary)
        run_settings_smoke(ctx, sdk_root, secondary)

        ctx.section("Notification smoke")
        run_notifications_smoke(ctx, sdk_root, primary, secondary)
    finally:
        stop_logcat(logcat_primary)
        stop_logcat(logcat_secondary)


def main() -> int:
    global STEP_TIMEOUT
    parser = argparse.ArgumentParser(description="Run full Android build + device smoke automation for SMS-Link.")
    parser.add_argument("--step-timeout", type=int, default=300, help="UI wait timeout in seconds.")
    parser.add_argument(
        "--phase",
        choices=["build", "device", "all"],
        default="build",
        help="Which stage to run. Default is build only so installation can be paused separately.",
    )
    parser.add_argument(
        "--with-unit-tests",
        action="store_true",
        help="Include testDebugUnitTest tasks during the build phase.",
    )
    parser.add_argument(
        "--full-module-assemble",
        action="store_true",
        help="Assemble every module explicitly instead of only :app:assembleDebug.",
    )
    args = parser.parse_args()
    STEP_TIMEOUT = args.step_timeout

    ctx_root = WORKSPACE / "test_output" / time.strftime("%Y%m%d_%H%M%S")
    ctx = RunContext(ctx_root)

    try:
        ctx.section("Environment")
        sdk_root = get_sdk_root()
        ctx.write(f"Workspace root: {WORKSPACE}")
        ctx.write(f"Run root: {ctx_root}")
        ctx.write(f"Android SDK root: {sdk_root}")
        ctx.write(f"Step timeout seconds: {args.step_timeout}")
        ctx.write(f"Build task mode: {'full-module-assemble' if args.full_module_assemble else 'app-only'}")
        ctx.write(f"Unit tests enabled: {args.with_unit_tests}")
        ensure_sdk_components(ctx, sdk_root)

        if args.phase in {"build", "all"}:
            run_gradle_coverage(
                ctx,
                with_unit_tests=args.with_unit_tests,
                full_module_assemble=args.full_module_assemble,
            )

        if args.phase == "build":
            ctx.section("Checkpoint")
            ctx.write("Build finished. No device work was started in build-only mode.")
            ctx.write("When you want the install/device phase, connect or verify two devices and rerun with --phase device or --phase all.")
            return 0

        devices = list_devices(ctx, sdk_root)
        if len(devices) < 2:
            raise RuntimeError(f"Need at least 2 connected devices. Found: {devices}")
        primary, secondary = devices[0], devices[1]
        ctx.write(f"Primary device: {primary}")
        ctx.write(f"Secondary device: {secondary}")

        run_device_coverage(ctx, sdk_root, primary, secondary)

        ctx.section("Result")
        ctx.write("All automated build, unit test, and device smoke checks completed.")
        return 0
    except Exception as exc:
        ctx.fail(str(exc))
        (ctx.build_root / "error.txt").write_text(str(exc) + "\n", encoding="utf-8")
        return 1
    finally:
        ctx.flush_summary()


if __name__ == "__main__":
    raise SystemExit(main())
