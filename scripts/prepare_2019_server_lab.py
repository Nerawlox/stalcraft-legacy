"""Prepare copies for the 2019 candidate's server experiments; never run the game."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

from remap_named_bytecode import remap_class

EXPECTED = {
    "classes.jar": "d91d22576c994126ef90818fee4cd490b71cd62f3341464e630404293ab1f4cd",
    "libs.jar": "1738611cd0bbbaf9fdf19ff9abc4dae9040ae6db5f3c68463fa27b822eebafe0",
    "offline-patches.jar": "14388ea0e1a17ef054aab032588aa72ff4947d57f5532c85c94b49eb35d390f9",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def prepare(source, destination):
    source, destination = source.resolve(), destination.resolve()
    if source == destination or source in destination.parents or destination in source.parents:
        raise ValueError("Source and lab must be separate directory trees")
    if destination.exists():
        raise ValueError("Refusing to overwrite an existing lab")
    for name, expected in EXPECTED.items():
        if sha((source / "classes" / name).read_bytes()) != expected:
            raise ValueError("Unexpected input: " + name)
    destination.mkdir(parents=True)
    cp = destination / "classpath"
    cp.mkdir()
    report = {"source": str(source), "lab": str(destination), "archives": [], "patches": []}
    missing = {"justagod/network/frontend/NettyServer.class": [], "net/minecraft/server/MinecraftServer.class": []}
    for name, expected in EXPECTED.items():
        removed = []
        entries = []
        with zipfile.ZipFile(source / "classes" / name) as archive, zipfile.ZipFile(cp / name, "w", zipfile.ZIP_DEFLATED) as out:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                raw = archive.read(entry)
                if entry.filename in missing:
                    missing[entry.filename].append({"archive": name, "bytes": len(raw)})
                if entry.filename.endswith(".class") and not raw:
                    removed.append(entry.filename)
                    continue
                out.writestr(entry.filename, raw)
                entries.append({"name": entry.filename, "sha256": sha(raw)})
        report["archives"].append({"name": name, "input_sha256": expected,
            "output_sha256": sha((cp / name).read_bytes()), "omitted_empty_entries": removed,
            "copied_entries": entries})
    report["frontend_inventory"] = missing
    with zipfile.ZipFile(cp / "classes.jar") as archive:
        raw = archive.read("buzu.class")
    patched, _, changes, original = remap_class(raw, {
        "justagod/network/frontend/NettyServer": "buzv",
        "net/minecraft/server/MinecraftServer": "jlrg",
    }, {})
    if changes.get("class", 0) != 1 or changes.get("descriptor", 0) != 1:
        raise ValueError("Dedicated server references differ from audited candidate: " + repr(dict(changes)))
    with zipfile.ZipFile(cp / "server-bytecode-overlay.jar", "w", zipfile.ZIP_DEFLATED) as out:
        out.writestr("buzu.class", patched)
    report["patches"].append({"class": "buzu", "input_sha256": sha(raw), "output_sha256": sha(patched),
        "changes": dict(changes), "code_arrays_unchanged": True,
        "purpose": "Replace absent frontend constructor with recovered vanilla/FML server listener"})
    game = destination / "server-game"
    shutil.copytree(source / "game", game, ignore=shutil.ignore_patterns(
        "saves", "crash-reports", "stats", "*.log", "*.log.*", "*.lck"))
    shutil.copy2(source / "metadata" / "modlist.txt", game / "modlist.txt")
    shutil.copytree(source / "metadata" / "asmdata", game / "mods" / "asmdata", dirs_exist_ok=True)
    (game / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25576\nonline-mode=false\nlevel-name=ReconstructionTest\n"
        "level-seed=2019\nlevel-type=FLAT\ngenerator-settings=2;7,2x3,2;1;\n"
        "max-players=8\nview-distance=3\nspawn-protection=0\nallow-flight=true\n"
        "difficulty=0\nspawn-monsters=false\nsnooper-enabled=false\n"
        "motd=STALCRAFT reconstruction local test\nenable-query=false\nenable-rcon=false\n", encoding="ascii")
    (destination / "preparation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"lab": str(destination), "frontend_inventory": missing,
                      "patches": report["patches"]}, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.source, args.output)
