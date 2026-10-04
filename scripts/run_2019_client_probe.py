"""Launch one isolated loopback test client; bound and log only this child."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
from launch_2019_common import validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--username", choices=("ProbeAlpha", "ProbeBeta"), required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--seconds", type=int, default=100)
    parser.add_argument("--gameplay", action="store_true")
    parser.add_argument("--chat", action="store_true")
    parser.add_argument("--combat", action="store_true")
    args = parser.parse_args()
    args.java, lab, args.overlay, preparation = validate(args.java, args.lab, args.overlay)
    game = lab / args.username
    if not game.exists() or Path(preparation["lab"]).resolve() != lab:
        raise ValueError("Client has not been prepared")
    if Path(args.name).name != args.name or not 1 <= args.seconds <= 300:
        raise ValueError("Invalid probe settings")
    source = Path(preparation["source"])
    import shutil
    shutil.copy2(source / "metadata/modlist.txt", game / "modlist.txt")
    cp = lab / "classpath"
    classpath = [args.overlay.resolve(), cp / "offline-patches.jar", cp / "classes.jar", cp / "libs.jar", game / "modassets"]
    nativepath = ";".join(map(str, (lab / "natives", args.java.resolve().parent, lab / "native-exbo", lab / "native-exbo/fmod")))
    command = [str(args.java.resolve()), "-Xms256m", "-Xmx3g", "-Dfile.encoding=UTF-8", "-Doffline.world=",
               "-Djava.library.path=" + nativepath, "-Dshow_globally_enabled=false", "-Dread_derived=true",
               "-DCustomNpcsSoundCache=true", "-Dload_dumped_event_classes=true", "-DusePrestitchedAtlas=true",
               "-Ddisable_item_atlas=true", "-Ddisable_mod_parsing=true", "-Duse_system_class_loader=true",
               "-Dfml.coreMods.load=codechicken.core.launch.CodeChickenCorePlugin", "-Dreconstruction.clientProbe=true",
               "-Dreconstruction.clientSeconds=" + str(args.seconds), "-cp", ";".join(map(str, classpath)),
               "net.minecraft.launchwrapper.Launch", "--version", "STALCRAFT-RECONSTRUCTION-2019",
               "--gameDir", ".", "--assetsDir", "assets", "--username", args.username, "--session", "0",
               "--width", "640", "--height", "480", "--server", "127.0.0.1", "--port", "25576",
               "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker"]
    command.insert(1, "-Dreconstruction.gameplayProbe=" + str(args.gameplay).lower())
    command.insert(1, "-Dreconstruction.chatProbe=" + str(args.chat).lower())
    command.insert(1, "-Dreconstruction.combatProbe=" + str(args.combat).lower())
    base = lab / "probes" / args.name
    if base.with_suffix(".json").exists():
        raise ValueError("Refusing to overwrite prior evidence")
    record = {"command": command, "cwd": str(game), "started_unix": time.time(), "timeout": False}
    environment = dict(os.environ)
    environment["PATH"] = nativepath + ";" + environment.get("PATH", "")
    with base.with_suffix(".stdout.log").open("wb") as stdout, base.with_suffix(".stderr.log").open("wb") as stderr:
        process = subprocess.Popen(command, cwd=game, stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL, env=environment)
        record["pid"] = process.pid
        base.with_suffix(".json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"pid": process.pid, "log": str(base)}), flush=True)
        try:
            record["exit_code"] = process.wait(timeout=args.seconds + 120)
        except subprocess.TimeoutExpired:
            record["timeout"] = True
            process.terminate()
            record["exit_code"] = process.wait(timeout=30)
    record["finished_unix"] = time.time()
    base.with_suffix(".json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: record[k] for k in ("pid", "exit_code", "timeout")}), flush=True)
    for suffix in (".stdout.log", ".stderr.log"):
        print(base.with_suffix(suffix).read_text(encoding="utf-8", errors="replace")[-7000:], flush=True)


if __name__ == "__main__":
    main()
