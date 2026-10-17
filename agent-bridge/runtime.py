"""Paths and helper processes shared by source and frozen desktop builds."""
import os
import sys
from pathlib import Path

VERSION = "0.2.0"
ROOT = Path(__file__).resolve().parent


def data_directory():
    override = os.environ.get("AGENT_BRIDGE_DATA_DIR")
    if override:
        return Path(override).expanduser().resolve()
    base = Path(os.environ["APPDATA"]) if os.name == "nt" and os.environ.get("APPDATA") else Path.home() / ".local" / "share"
    return base / "AgentBridge"


def helper(mode):
    if getattr(sys, "frozen", False):
        return [str(Path(sys.executable).parent / "AgentBridge-CLI.exe"), "--" + mode]
    names = {"demo": "demo_agent.py", "batch-launch": "windows_launcher.py", "mcp": "mcp_server.py"}
    return [sys.executable, "-u", str(ROOT / names[mode])]


def default_workspace():
    path = data_directory() / "workspace"
    path.mkdir(parents=True, exist_ok=True)
    return path
