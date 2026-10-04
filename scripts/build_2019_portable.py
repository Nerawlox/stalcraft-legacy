"""Assemble a private, relocatable Windows x64 kit from verified local inputs.

The output includes supplied game data; it is deliberately outside Git. Target
machines need neither Python nor a separately installed Java. Never overwrites
an existing kit, archive or report, and never launches the supplied game.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from launch_2019_common import validate
from patch_2019_item_browser import set_value, clear_section_values
from prepare_2019_world import checked_tree, hash_file, inside, reject_reparse_components

JAVA_SHA = "46df95bb47e2ba2b10736cee2ab543289c66021611f1ec67d0d55d5128228c20"
ECJ_SHA = "ac0ba5876eaf7ebb47749a0d1be179c51f194b9dd0b875d1c09e1b530f5a2db5"
REPO = Path(__file__).resolve().parent.parent
EXCLUDE_DIRS = {"saves", "stats", "crash-reports", "logs", "screenshots"}
EXCLUDE_NAMES = {"modlist.txt", "servers.dat", "servers.dat_old", "usercache.json",
                 "launcher_profiles.json", "launcher_accounts.json", "server.properties",
                 "banned-players.txt", "banned-ips.txt", "ops.txt", "white-list.txt",
                 "reconstruction-stop.flag", "session.lock"}
IMMUTABLE_GAME = {"assets", "modassets", "mods", "resourcepacks", "texturepacks"}


def exclusive_text(path, text, encoding="utf-8", newline="\n"):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding=encoding, newline=newline) as stream:
        stream.write(text)


class Copier:
    def __init__(self, root):
        self.root, self.records, self.bytes = root, [], 0

    def file(self, source, relative, expected=None):
        reject_reparse_components(source)
        target = self.root / relative
        if not inside(target, self.root):
            raise ValueError("Copy target escapes kit")
        target.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        size = 0
        with source.open("rb") as src, target.open("xb") as dst:
            while block := src.read(1024 * 1024):
                digest.update(block)
                dst.write(block)
                size += len(block)
        checksum = digest.hexdigest()
        if expected is not None and expected != checksum:
            raise ValueError("Pinned input changed: " + str(source))
        if hash_file(target) != (checksum, size):
            raise IOError("Copy verification failed: " + relative.as_posix())
        self.records.append({"path": relative.as_posix(), "sha256": checksum, "bytes": size})
        self.bytes += size
        if len(self.records) % 500 == 0:
            print("Copied", len(self.records), "files;", self.bytes, "bytes", flush=True)

    def tree(self, source, relative, accept=lambda p: True, expected=None):
        reject_reparse_components(source)
        _, files = checked_tree(source)
        if expected is not None and set(p.as_posix() for p in files) != set(expected):
            raise ValueError("Pinned input tree has missing or extra files: " + str(source))
        for path in files:
            if accept(path):
                self.file(source / path, relative / path,
                          expected[path.as_posix()] if expected is not None else None)


def game_file(path):
    parts = [p.casefold() for p in path.parts]
    name = parts[-1]
    if parts[0] in EXCLUDE_DIRS or name in EXCLUDE_NAMES:
        return False
    if parts[:2] == ["mods", "asmdata"]:
        return False
    return not (name.endswith((".log", ".lck")) or ".log." in name or
                name.endswith((".cmd", ".bat", ".ps1", ".exe")))


def immutable(relative):
    parts = Path(relative).parts
    if parts[0] != "game":
        return relative not in {"connection.properties", "test-server.properties", "files.sha256"}
    return len(parts) > 1 and parts[1] in IMMUTABLE_GAME


def java_cmd(command):
    return ("@echo off\nchcp 65001 >nul\n"
            '"%~dp0runtime\\java\\bin\\java.exe" -Dfile.encoding=UTF-8 '
            '-cp "%~dp0app\\launcher.jar" PortableLauncher ' + command + " %*\n"
            'set "launch_exit=%errorlevel%"\npause\nexit /b %launch_exit%\n')


def firewall_cmd(remove=False):
    return ("@echo off\nchcp 65001 >nul\n"
            'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\\Allow-Connection.ps1"' +
            (" -Remove" if remove else "") + " %*\n"
            'set "launch_exit=%errorlevel%"\npause\nexit /b %launch_exit%\n')


def build(args):
    inputs = [args.java, args.ecj, args.lab, args.overlay, REPO]
    for path in inputs + [args.output] + ([args.zip] if args.zip else []):
        reject_reparse_components(path)
    java, lab, overlay, preparation = validate(args.java, args.lab, args.overlay)
    ecj = args.ecj.resolve(strict=True)
    if hash_file(java)[0] != JAVA_SHA or hash_file(ecj)[0] != ECJ_SHA:
        raise ValueError("Use the audited Temurin 8u504 runtime and ECJ 3.26.0")
    runtime = java.parent.parent
    if not (runtime / "release").is_file():
        raise ValueError("Missing complete runtime release file")
    source = Path(preparation["source"]).resolve(strict=True)
    out = args.output.resolve()
    archive = args.zip.resolve() if args.zip else None
    report = out.with_name(out.name + "-build.json")
    classes = out.with_name(out.name + "-launcher-classes")
    compile_log = out.with_name(out.name + "-launcher-compile.txt")
    paths = [out, report, classes, compile_log] + ([archive, archive.with_suffix(archive.suffix + ".sha256")] if archive else [])
    for path in paths:
        if os.path.lexists(path):
            raise FileExistsError("Refusing to overwrite " + str(path))
        for protected in [source, lab, overlay, runtime, REPO]:
            if inside(path, protected) or inside(protected, path):
                raise ValueError("Output overlaps an input or Git checkout: " + str(path))
    if archive and inside(archive, out):
        raise ValueError("Archive must be outside kit")
    out.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(out.parent).free < 15 * 1024**3:
        raise ValueError("Need at least 15 GiB free before assembling a kit")
    out.mkdir()
    classes.mkdir()
    compile_args = [str(java), "-jar", str(ecj), "-1.8", "-encoding", "UTF-8", "-proc:none",
                    "-d", str(classes), str(REPO / "launcher/PortableLauncher.java")]
    result = subprocess.run(compile_args, capture_output=True, text=True, encoding="utf-8")
    exclusive_text(compile_log, result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError("Launcher compilation failed; see " + str(compile_log))
    (out / "app").mkdir()
    with zipfile.ZipFile(out / "app/launcher.jar", "x", zipfile.ZIP_DEFLATED) as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: PortableLauncher\r\n\r\n")
        for path in sorted(classes.rglob("*.class")):
            jar.write(path, path.relative_to(classes).as_posix())
    copier = Copier(out)
    compilation = json.loads((overlay / "compilation.json").read_text(encoding="utf-8"))
    for path, checksum in sorted(compilation["classes"].items()):
        copier.file(overlay / path, Path("app/overlay") / path, checksum)
    for name in ["offline-patches.jar", "classes.jar", "libs.jar", "server-bytecode-overlay.jar"]:
        copier.file(lab / "classpath" / name, Path("app/classpath") / name)
    print("Copying complete trusted Java runtime", flush=True)
    copier.tree(runtime, Path("runtime/java"))
    copier.tree(source / "natives", Path("natives"))
    copier.tree(source / "native-exbo", Path("native-exbo"))
    print("Copying pristine game profile (no saves, logs or test profiles)", flush=True)
    copier.tree(source / "game", Path("game"), game_file)
    copier.file(source / "metadata/modlist.txt", Path("game/modlist.txt"))
    copier.tree(source / "metadata/asmdata", Path("game/mods/asmdata"))
    nei_client = out / "game/config/NEI.cfg"
    nei_text = nei_client.read_text(encoding="utf-8-sig")
    for key, value in (("hidden", "false"), ("widgetsenabled", "true"), ("cheatmode", "2"), ("lockmode", "-1")):
        nei_text = set_value(nei_text, "inventory", key, value)
    nei_client.write_text(nei_text, encoding="utf-8")
    if args.role == "server":
        nei_server = out / "game/config/NEIServer.cfg"
        nei_text = clear_section_values(set_value(nei_server.read_text(encoding="utf-8-sig"), "permissions", "item", "ALL"), "BannedBlocks")
        nei_text = set_value(nei_text, "BannedBlocks", "7:0", "ALL")
        nei_server.write_text(nei_text, encoding="utf-8")
    world_count = 0
    if args.role == "server":
        world_manifest = args.world_manifest or lab / "world-copy.json"
        reject_reparse_components(world_manifest)
        world = json.loads(world_manifest.read_text(encoding="utf-8"))
        expected = {f["relative_path"]: f["source_sha256"] for f in world["files"]}
        if len(expected) != world["file_count"] or Path(world["source"]).resolve() != source:
            raise ValueError("Unexpected world manifest")
        original_world = source / "game/saves/RegionsLocal"
        if Path(world["source_world"]).resolve() != original_world.resolve():
            raise ValueError("World manifest identifies another source")
        print("Copying pinned RegionsLocal world", len(expected), "files", flush=True)
        copier.tree(original_world, Path("game/RegionsLocal-test"), expected=expected)
        world_count = len(expected)
        exclusive_text(out / "game/server.properties",
                       "server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\n"
                       "level-name=RegionsLocal-test\nmax-players=8\nview-distance=3\n"
                       "spawn-protection=0\nallow-flight=true\ndifficulty=0\nspawn-monsters=false\n"
                       "gamemode=2\nforce-gamemode=true\npvp=true\nop-permission-level=4\n"
                       "snooper-enabled=false\nmotd=STALCRAFT Legacy private test\n"
                       "enable-query=false\nenable-rcon=false\n", encoding="ascii")
        exclusive_text(out / "game/ops.txt", "nrwlx\n", encoding="ascii")
        exclusive_text(out / "test-server.properties", "adventure-on-join=true\nself-gamemode=true\n", encoding="ascii")
        copier.file(REPO / "scripts/portable-network.ps1", Path("tools/Allow-Connection.ps1"))
        exclusive_text(out / "Start-Server.cmd", java_cmd("server"), encoding="ascii", newline="\r\n")
        exclusive_text(out / "Stop-Server.cmd", java_cmd("stop"), encoding="ascii", newline="\r\n")
        exclusive_text(out / "Allow-Connection.cmd", firewall_cmd(), encoding="ascii", newline="\r\n")
        exclusive_text(out / "Remove-Connection.cmd", firewall_cmd(True), encoding="ascii", newline="\r\n")
        settings = "bind=127.0.0.1\nport=25576\n"
    else:
        exclusive_text(out / "Start-Client.cmd", java_cmd("client"), encoding="ascii", newline="\r\n")
        exclusive_text(out / "Check-Connection.cmd", java_cmd("check"), encoding="ascii", newline="\r\n")
        settings = "host=127.0.0.1\nport=25576\nusername=SecondPlayer\n"
    exclusive_text(out / "Verify-Files.cmd", java_cmd("verify"), encoding="ascii", newline="\r\n")
    exclusive_text(out / "kit.properties", "role=" + args.role + "\nport=25576\n", encoding="ascii")
    exclusive_text(out / "connection.properties", settings, encoding="ascii")
    copier.file(REPO / "docs/two-pc-setup.md", Path("READ-ME-RU.txt"))
    copier.file(REPO / "docs/two-pc-test-checklist.md", Path("TEST-CHECKLIST-RU.txt"))
    _, kit_files = checked_tree(out)
    manifest = []
    total_bytes = 0
    for index, path in enumerate(kit_files, 1):
        size = (out / path).stat().st_size
        total_bytes += size
        if immutable(path.as_posix()):
            checksum, size = hash_file(out / path)
            manifest.append(checksum + "  " + path.as_posix() + "\n")
        if index % 1000 == 0:
            print("Manifest", index, "/", len(kit_files), flush=True)
    exclusive_text(out / "files.sha256", "".join(manifest))
    data = {"schema": 1, "role": args.role, "source": str(source), "output": str(out),
            "java_sha256": JAVA_SHA, "ecj_sha256": ECJ_SHA, "overlay_classes": len(compilation["classes"]),
            "launcher_source_sha256": hash_file(REPO / "launcher/PortableLauncher.java")[0],
            "compile_exit": result.returncode, "compile_output": result.stdout + result.stderr,
            "copied_files": len(copier.records), "world_files_verified": world_count,
            "immutable_files": len(manifest), "payload_bytes": total_bytes,
            "copied_inputs": copier.records, "archive": None}
    if archive:
        archive.parent.mkdir(parents=True, exist_ok=True)
        _, files = checked_tree(out)
        print("Creating ZIP64 archive", str(archive), flush=True)
        with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED, compresslevel=2, allowZip64=True) as zipped:
            for index, path in enumerate(files, 1):
                zipped.write(out / path, out.name + "/" + path.as_posix())
                if index % 500 == 0:
                    print("ZIP", index, "/", len(files), flush=True)
        checksum, size = hash_file(archive)
        exclusive_text(archive.with_suffix(archive.suffix + ".sha256"), checksum + "  " + archive.name + "\n")
        data["archive"] = {"path": str(archive), "sha256": checksum, "bytes": size, "entries": len(files)}
    exclusive_text(report, json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"result": "PASS", "role": args.role, "report": str(report),
                      "payload_bytes": total_bytes, "archive": data["archive"]}, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--ecj", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--role", choices=["client", "server"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--world-manifest", type=Path)
    parser.add_argument("--zip", type=Path)
    build(parser.parse_args())
