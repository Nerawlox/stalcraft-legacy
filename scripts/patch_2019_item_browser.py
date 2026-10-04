#!/usr/bin/env python3
"""Prepare copied STALCRAFT 2019 client/server NEI configs for item browsing.

The script edits only explicitly supplied copied profile/config directories.
It does not change JARs, worlds, shared build scripts, or the original package.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path


ORIGINAL_ROOT = Path(r"E:\Stalcraft project\Stalcraft 2019\Stalcraft 2019")


def _inside(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def _guard_copy(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if _inside(resolved, ORIGINAL_ROOT):
        raise ValueError(f"Refusing to edit original bundle {label}: {resolved}")
    if not resolved.is_dir():
        raise ValueError(f"{label} directory does not exist: {resolved}")
    return resolved


def _find_block(lines: list[str], section: str) -> tuple[int, int] | None:
    pattern = re.compile(rf"^\s*{re.escape(section)}\s*$")
    start = None
    brace_line = None
    depth = 0
    for index, line in enumerate(lines):
        content = line.split("#", 1)[0].strip()
        if start is None:
            if pattern.match(line.rstrip("\r\n")):
                start = index
                continue
            continue
        if brace_line is None:
            if content == "{":
                brace_line = index
                depth = 1
            elif content:
                start = None
            continue
        # NEI's target blocks contain no quoted brace values; strip comments.
        depth += content.count("{") - content.count("}")
        if depth == 0:
            return start, index
    return None


def set_value(text: str, section: str, key: str, value: str) -> str:
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.splitlines(keepends=True)
    block = _find_block(lines, section)
    if block is None:
        if text and not text.endswith(("\n", "\r")):
            text += newline
        return text + f"{section}{newline}{{{newline}\t{key}={value}{newline}}}{newline}"

    start, end = block
    key_pattern = re.compile(rf"^(\s*){re.escape(key)}\s*=.*?(\r?\n)?$")
    found = []
    for index in range(start + 1, end):
        match = key_pattern.match(lines[index])
        if match:
            found.append((index, match.group(1)))
    if len(found) > 1:
        raise ValueError(f"Duplicate {section}.{key} entries")
    if found:
        index, indent = found[0]
        ending = "\r\n" if lines[index].endswith("\r\n") else "\n" if lines[index].endswith("\n") else ""
        lines[index] = f"{indent}{key}={value}{ending}"
    else:
        lines.insert(end, f"\t{key}={value}{newline}")
    return "".join(lines)


def clear_section_values(text: str, section: str) -> str:
    lines = text.splitlines(keepends=True)
    block = _find_block(lines, section)
    if block is None:
        return text
    start, end = block
    body = lines[start + 1 : end]
    # Keep explanatory comments and blank layout; remove every active child.
    lines[start + 1 : end] = [line for line in body
        if line.split("#", 1)[0].strip() == "{" or not line.split("#", 1)[0].strip()]
    return "".join(lines)


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(client_root: Path, server_root: Path, apply: bool, hooks_installed: bool = False) -> dict:
    if apply and not hooks_installed:
        raise ValueError("Refusing to grant NEI item permission until client reveal and both server creative guards are integrated")
    client_root = _guard_copy(client_root, "client")
    server_root = _guard_copy(server_root, "server")
    config_paths: dict[Path, str] = {}

    client_cfg = client_root / "config" / "NEI.cfg"
    server_cfg = server_root / "config" / "NEIServer.cfg"
    if not client_cfg.is_file():
        raise ValueError(f"Missing client NEI config: {client_cfg}")
    if not server_cfg.is_file():
        raise ValueError(f"Missing server NEI config: {server_cfg}")

    client_text = client_cfg.read_text(encoding="utf-8-sig")
    for key, value in (
        ("hidden", "false"),
        ("widgetsenabled", "true"),
        ("cheatmode", "2"),
        ("lockmode", "-1"),
    ):
        client_text = set_value(client_text, "inventory", key, value)
    config_paths[client_cfg] = client_text

    # World-specific values override the global NEI.cfg values, so prepare the
    # existing remote-save files as well. Do not create or alter save data.
    remote_root = client_root / "saves" / "NEI" / "remote"
    if remote_root.is_dir():
        for world_cfg in sorted(remote_root.rglob("NEI.cfg")):
            text = world_cfg.read_text(encoding="utf-8-sig")
            for key, value in (
                ("hidden", "false"),
                ("widgetsenabled", "true"),
                ("cheatmode", "2"),
                ("lockmode", "-1"),
            ):
                text = set_value(text, "inventory", key, value)
            config_paths[world_cfg] = text

    server_text = server_cfg.read_text(encoding="utf-8-sig")
    server_text = set_value(server_text, "permissions", "item", "ALL")
    server_text = clear_section_values(server_text, "BannedBlocks")
    server_text = set_value(server_text, "BannedBlocks", "7:0", "ALL")
    config_paths[server_cfg] = server_text

    changes = []
    for path, new_text in config_paths.items():
        old_bytes = path.read_bytes()
        old_text = old_bytes.decode("utf-8-sig")
        new_bytes = new_text.encode("utf-8")
        changes.append(
            {
                "path": str(path),
                "before_sha256": hashlib.sha256(old_bytes).hexdigest(),
                "after_sha256": hashlib.sha256(new_bytes).hexdigest(),
                "changed": old_text != new_text,
                "new_bytes": new_bytes,
            }
        )

    if apply:
        for item in changes:
            path = Path(item["path"])
            if not item["changed"]:
                continue
            backup = path.with_name(path.name + ".pre-item-browser.bak")
            if backup.exists():
                raise FileExistsError(f"Refusing to overwrite backup: {backup}")
            shutil.copy2(path, backup)
        staged: list[tuple[Path, Path]] = []
        try:
            for item in changes:
                if not item["changed"]:
                    continue
                path = Path(item["path"])
                temp = path.with_name(path.name + ".item-browser.tmp")
                if temp.exists():
                    raise FileExistsError(f"Refusing to overwrite temporary file: {temp}")
                temp.write_bytes(item["new_bytes"])
                staged.append((temp, path))
            for temp, path in staged:
                os.replace(temp, path)
        except Exception:
            for temp, _ in staged:
                temp.unlink(missing_ok=True)
            raise

    return {
        "schema": 1,
        "mode": "apply" if apply else "dry-run",
        "client_root": str(client_root),
        "server_root": str(server_root),
        "settings": {
            "client": {
                "inventory.hidden": False,
                "inventory.widgetsenabled": True,
                "inventory.cheatmode": 2,
                "inventory.lockmode": -1,
            },
            "server": {
                "permissions.item": "ALL",
                "BannedBlocks": "all items permitted; bedrock default explicitly ALL",
            },
            "server_authority": "CreativeItemBrowserHook guards NEI packet types 1/5 and native item-give method; other permissions remain unchanged.",
        },
        "files": [
            {key: value for key, value in item.items() if key != "new_bytes"}
            for item in changes
        ],
    }


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="nei-browser-test-") as temp:
        root = Path(temp)
        client = root / "client"
        server = root / "server"
        (client / "config").mkdir(parents=True)
        (client / "saves" / "NEI" / "remote" / "host" / "World").mkdir(parents=True)
        (server / "config").mkdir(parents=True)
        (client / "config" / "NEI.cfg").write_text(
            "inventory\n{\n\tcheatmode=0\n\thidden=true\n\tlockmode=0\n}\nkeys\n{\n\tgui.hide=24\n}\n",
            encoding="utf-8",
        )
        (client / "saves" / "NEI" / "remote" / "host" / "World" / "NEI.cfg").write_text(
            "# World config\n", encoding="utf-8"
        )
        (server / "config" / "NEIServer.cfg").write_text(
            "BannedBlocks\n{\n\t7:0=OfflinePlayer\n}\npermissions\n{\n\titem=OP\n\theal=OP\n}\n",
            encoding="utf-8",
        )
        dry = prepare(client, server, False)
        assert dry["mode"] == "dry-run"
        assert "cheatmode=0" in (client / "config" / "NEI.cfg").read_text(encoding="utf-8")
        applied = prepare(client, server, True, hooks_installed=True)
        assert applied["mode"] == "apply"
        global_cfg = (client / "config" / "NEI.cfg").read_text(encoding="utf-8")
        remote_cfg = (client / "saves" / "NEI" / "remote" / "host" / "World" / "NEI.cfg").read_text(encoding="utf-8")
        server_cfg = (server / "config" / "NEIServer.cfg").read_text(encoding="utf-8")
        assert "hidden=false" in global_cfg and "widgetsenabled=true" in global_cfg
        assert "cheatmode=2" in global_cfg and "lockmode=-1" in global_cfg
        assert "gui.hide=24" in global_cfg
        assert "cheatmode=2" in remote_cfg and "hidden=false" in remote_cfg
        assert "7:0=OfflinePlayer" not in server_cfg
        assert server_cfg.count("{") == server_cfg.count("}")
        assert "item=ALL" in server_cfg and "heal=OP" in server_cfg
        assert (client / "config" / "NEI.cfg.pre-item-browser.bak").is_file()
        assert (server / "config" / "NEIServer.cfg.pre-item-browser.bak").is_file()
        try:
            prepare(ORIGINAL_ROOT, server, False)
        except ValueError:
            pass
        else:
            raise AssertionError("original-path protection did not reject bundle")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-root", type=Path)
    parser.add_argument("--server-root", type=Path)
    parser.add_argument("--apply", action="store_true", help="write configs and create one-time backups; default is dry-run")
    parser.add_argument("--hooks-installed", action="store_true", help="assert the client reveal hook and both server creative guards are already integrated")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("item-browser self-test: PASS")
        return 0
    if args.client_root is None or args.server_root is None:
        parser.error("--client-root and --server-root are required unless --self-test is used")
    try:
        result = prepare(args.client_root, args.server_root, args.apply, args.hooks_installed)
    except (OSError, ValueError) as exception:
        print(f"error: {exception}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
