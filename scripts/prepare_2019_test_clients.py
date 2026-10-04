"""Create two isolated clients for the prepared loopback server lab."""
import argparse
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab", type=Path, required=True)
    args = parser.parse_args()
    lab = args.lab.resolve()
    preparation = json.loads((lab / "preparation.json").read_text(encoding="utf-8"))
    source = Path(preparation["source"])
    if source == lab or source in lab.parents or lab in source.parents:
        raise ValueError("Not a separate lab")
    for name in ("natives", "native-exbo"):
        destination = lab / name
        if not destination.exists():
            print("Copying local client native libraries: " + name, flush=True)
            shutil.copytree(source / name, destination)
    for name in ("ProbeAlpha", "ProbeBeta"):
        destination = lab / name
        if destination.exists():
            raise ValueError("Refusing to overwrite client " + name)
        print("Copying separate game directory: " + name, flush=True)
        shutil.copytree(source / "game", destination, ignore=shutil.ignore_patterns(
            "saves", "stats", "crash-reports", "*.log", "*.log.*", "*.lck"))
        shutil.copy2(source / "metadata/modlist.txt", destination / "modlist.txt")
        shutil.copytree(source / "metadata/asmdata", destination / "mods/asmdata", dirs_exist_ok=True)
        # Fixed window size and modest render distance for simultaneous probes.
        options = destination / "options.txt"
        text = options.read_text(encoding="utf-8", errors="replace")
        text = "\n".join(line for line in text.splitlines() if not line.startswith(("fullscreen:", "renderDistance:", "maxFps:")))
        options.write_text(text + "\nfullscreen:false\nrenderDistance:3\nmaxFps:30\n", encoding="utf-8")
    print("Client copies ready; originals unchanged.", flush=True)


if __name__ == "__main__":
    main()
