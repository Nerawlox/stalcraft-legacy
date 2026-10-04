"""Copy the supplied RegionsLocal world into an isolated 2019 server lab.

This utility does not start a game or server. It refuses symlinks/reparse points,
verifies every copied file by streaming SHA-256, and never overwrites a prior copy.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat


REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
CHUNK = 1024 * 1024


def is_reparse(path):
    info = path.lstat()
    return path.is_symlink() or bool(getattr(info, "st_file_attributes", 0) & REPARSE_POINT)


def reject_reparse_components(path):
    """Reject existing reparse points anywhere in a lexical path, including parents."""
    absolute = Path(os.path.abspath(str(path)))
    current = Path(absolute.anchor)
    for part in absolute.parts[1:]:
        current = current / part
        if os.path.lexists(str(current)) and is_reparse(current):
            raise ValueError("Refusing symlink/reparse path component: " + str(current))


def inside(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def checked_tree(root):
    """Inventory regular files and directories without following links."""
    files, directories = [], []
    for current, dirnames, filenames in os.walk(str(root), topdown=True, followlinks=False):
        current_path = Path(current)
        rel_current = current_path.relative_to(root)
        for name in sorted(list(dirnames)):
            path = current_path / name
            if is_reparse(path):
                raise ValueError("Refusing symlink/reparse directory: " + str(path))
            if not path.is_dir():
                raise ValueError("Expected directory: " + str(path))
            directories.append(rel_current / name)
        for name in sorted(filenames):
            path = current_path / name
            if is_reparse(path):
                raise ValueError("Refusing symlink/reparse file: " + str(path))
            if not path.is_file():
                raise ValueError("Expected regular file: " + str(path))
            files.append(rel_current / name)
    return sorted(directories, key=lambda p: p.as_posix()), sorted(files, key=lambda p: p.as_posix())


def hash_file(path):
    digest, size = hashlib.sha256(), 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK)
            if not block:
                break
            digest.update(block)
            size += len(block)
    return digest.hexdigest(), size


def copy_stream(source, destination):
    digest, size = hashlib.sha256(), 0
    with source.open("rb") as src, destination.open("xb") as dst:
        while True:
            block = src.read(CHUNK)
            if not block:
                break
            digest.update(block)
            size += len(block)
            dst.write(block)
        dst.flush()
        os.fsync(dst.fileno())
    return digest.hexdigest(), size


def read_preparation(lab):
    manifest = lab / "preparation.json"
    data = json.loads(manifest.read_text(encoding="utf-8"))
    recorded_lab_path = Path(data["lab"])
    reject_reparse_components(recorded_lab_path)
    recorded_lab = recorded_lab_path.resolve(strict=True)
    if recorded_lab != lab:
        raise ValueError("preparation.json lab path does not match --lab")
    source_recorded = Path(data["source"])
    reject_reparse_components(source_recorded)
    source = source_recorded.resolve(strict=True)
    if not source.is_dir():
        raise ValueError("preparation.json source is not a directory")
    if inside(source, lab) or inside(lab, source):
        raise ValueError("Source package and isolated lab must be separate trees")
    return data, source


def activate_world(server_game, backup_path, world_name):
    properties = server_game / "server.properties"
    if not properties.is_file() or is_reparse(properties):
        raise ValueError("Missing or unsafe lab server.properties")
    if os.path.lexists(str(backup_path)):
        raise FileExistsError("Refusing to overwrite " + str(backup_path))
    raw = properties.read_bytes()
    lines = raw.splitlines(keepends=True)
    matches = []
    for index, line in enumerate(lines):
        body = line.rstrip(b"\r\n")
        if not body.lstrip().startswith(b"#") and b"=" in body:
            key = body.split(b"=", 1)[0].strip()
            if key == b"level-name":
                matches.append(index)
    if len(matches) != 1:
        raise ValueError("Expected exactly one level-name property, found %d" % len(matches))
    index = matches[0]
    line = lines[index]
    newline = b"\r\n" if line.endswith(b"\r\n") else b"\n" if line.endswith(b"\n") else b""
    body = line[:-len(newline)] if newline else line
    prefix = re.match(rb"^(\s*level-name\s*=\s*).*$", body)
    if not prefix:
        raise ValueError("Could not safely parse level-name line")
    updated = list(lines)
    updated[index] = prefix.group(1) + world_name.encode("ascii") + newline

    # The backup is exclusive and byte-identical. Activation only follows a verified world copy.
    with backup_path.open("xb") as backup:
        backup.write(raw)
        backup.flush()
        os.fsync(backup.fileno())
    if backup_path.read_bytes() != raw:
        raise IOError("server.properties backup verification failed")

    temporary = server_game / "server.properties.world-copy.tmp"
    if os.path.lexists(str(temporary)):
        raise FileExistsError("Refusing to overwrite " + str(temporary))
    with temporary.open("xb") as output:
        output.write(b"".join(updated))
        output.flush()
        os.fsync(output.fileno())
    os.replace(str(temporary), str(properties))
    return {"backup": str(backup_path), "server_properties": str(properties),
            "level_name": world_name, "backup_sha256": hashlib.sha256(raw).hexdigest()}


def prepare(lab_arg, activate=False, world_name="RegionsLocal-test"):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", world_name, flags=re.ASCII):
        raise ValueError("--name must match ASCII [A-Za-z0-9_-]{1,64}")
    if world_name.casefold() in {"regionslocal", "reconstructiontest"}:
        raise ValueError("--name may not alias a protected/original world name")
    reject_reparse_components(lab_arg)
    lab = lab_arg.resolve(strict=True)
    if not lab.is_dir() or is_reparse(lab):
        raise ValueError("--lab must be a real directory, not a link/reparse point")
    preparation, source = read_preparation(lab)
    source_world = source / "game" / "saves" / "RegionsLocal"
    reject_reparse_components(source_world)
    if not source_world.is_dir() or is_reparse(source_world):
        raise ValueError("Original world directory is missing or unsafe: " + str(source_world))
    source_world = source_world.resolve(strict=True)
    if not inside(source_world, source):
        raise ValueError("Resolved source world escapes the package root")

    server_game = lab / "server-game"
    reject_reparse_components(server_game)
    if not server_game.is_dir() or is_reparse(server_game):
        raise ValueError("Missing or unsafe isolated server-game directory")
    server_game_real = server_game.resolve(strict=True)
    if not inside(server_game_real, lab):
        raise ValueError("server-game resolves outside the lab")
    destination = server_game_real / world_name
    default_name = world_name == "RegionsLocal-test"
    manifest_path = lab / ("world-copy.json" if default_name else "world-copy-" + world_name + ".json")
    if os.path.lexists(str(destination)):
        raise FileExistsError("Refusing to overwrite world destination: " + str(destination))
    if os.path.lexists(str(manifest_path)):
        raise FileExistsError("Refusing to overwrite world manifest: " + str(manifest_path))
    backup_path = lab / ("server.properties-before-world.txt" if default_name
                         else "server.properties-before-" + world_name + ".txt")
    if activate and os.path.lexists(str(backup_path)):
        raise FileExistsError("Refusing to overwrite server.properties backup")

    source_dirs, source_files = checked_tree(source_world)
    destination.mkdir()
    for relative in source_dirs:
        target_dir = destination / relative
        if not inside(target_dir.parent.resolve(strict=True), destination.resolve(strict=True)):
            raise ValueError("Destination directory escapes copy root: " + str(relative))
        target_dir.mkdir()

    records = []
    for relative in source_files:
        src = source_world / relative
        dst = destination / relative
        if not inside(dst.parent.resolve(strict=True), destination.resolve(strict=True)):
            raise ValueError("Destination file escapes copy root: " + str(relative))
        source_hash, source_size = copy_stream(src, dst)
        destination_hash, destination_size = hash_file(dst)
        if source_hash != destination_hash or source_size != destination_size:
            raise IOError("Copied file verification failed: " + relative.as_posix())
        records.append({"relative_path": relative.as_posix(), "source_sha256": source_hash,
                        "destination_sha256": destination_hash, "source_size": source_size,
                        "destination_size": destination_size})

    manifest = {"schema": 1, "source": str(source), "source_world": str(source_world),
                "lab": str(lab), "destination_world": str(destination),
                "world_name": world_name,
                "file_count": len(records), "files": records,
                "hashes_verified": True, "activation": None}
    if activate:
        manifest["activation"] = activate_world(server_game_real, backup_path, world_name)
    with manifest_path.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(manifest, output, ensure_ascii=False, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    print(json.dumps({"result": "PASS", "manifest": str(manifest_path),
                      "world": str(destination), "file_count": len(records),
                      "world_name": world_name, "activated": bool(activate)}, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab", required=True, type=Path,
                        help="Exact isolated lab recorded in preparation.json")
    parser.add_argument("--activate", action="store_true",
                        help="Back up lab server.properties, then set only level-name")
    parser.add_argument("--name", default="RegionsLocal-test",
                        help="New isolated destination/level name (ASCII letters, digits, underscore, hyphen)")
    args = parser.parse_args()
    prepare(args.lab, args.activate, args.name)


if __name__ == "__main__":
    main()
