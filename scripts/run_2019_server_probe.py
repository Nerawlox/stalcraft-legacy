"""Run only the prepared server lab with a bounded lifetime and recorded output."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
from launch_2019_common import validate, server_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--name", default="server-probe")
    parser.add_argument("--seconds", type=int, default=90)
    parser.add_argument("--gameplay", action="store_true", help="Opt-in weapon/ammo fixture in the synthetic world")
    parser.add_argument("--readiness", action="store_true")
    parser.add_argument("--combat", action="store_true")
    args = parser.parse_args()
    args.java, lab, args.overlay, _ = validate(args.java, args.lab, args.overlay)
    server_command(args.java, lab, args.overlay)  # Also enforce the lab's loopback bind.
    if not 1 <= args.seconds <= 300 or Path(args.name).name != args.name:
        raise ValueError("Invalid bounded probe settings")
    logs = lab / "probes"
    logs.mkdir(exist_ok=True)
    base = logs / args.name
    if base.with_suffix(".json").exists():
        raise ValueError("Refusing to overwrite prior probe evidence")
    stop_flag = lab / "server-game/reconstruction-stop.flag"
    if stop_flag.exists():
        stop_flag.unlink()  # One known marker inside the validated copy.
    cp = lab / "classpath"
    classpath = [args.overlay.resolve(), cp / "server-bytecode-overlay.jar", cp / "offline-patches.jar",
                 cp / "classes.jar", cp / "libs.jar", lab / "server-game" / "modassets"]
    command = [str(args.java.resolve()), "-Xms256m", "-Xmx2g", "-Djava.awt.headless=true",
               "-Dfile.encoding=UTF-8", "-Dshow_globally_enabled=false", "-Dread_derived=true",
               "-DCustomNpcsSoundCache=true", "-Dload_dumped_event_classes=true", "-DusePrestitchedAtlas=true",
               "-Ddisable_item_atlas=true", "-Ddisable_mod_parsing=true", "-Duse_system_class_loader=true",
               "-Dfml.coreMods.load=codechicken.core.launch.CodeChickenCorePlugin",
               "-Dreconstruction.stopAfterSeconds=" + str(args.seconds),
               "-Dreconstruction.serverProbe=true",
               "-Dreconstruction.gameplayProbe=" + str(args.gameplay).lower(),
               "-Dreconstruction.readinessProbe=" + str(args.readiness).lower(),
               "-Dreconstruction.readinessInventoryTrace=" + str(args.readiness).lower(),
               "-Dreconstruction.combatProbe=" + str(args.combat).lower(),
               "-Dstalcraft.test.joinAdventure=" + str(args.readiness or args.combat).lower(),
               "-Dstalcraft.test.selfGamemode=" + str(args.readiness or args.combat).lower(),
               "-cp", ";".join(map(str, classpath)), "net.minecraft.launchwrapper.Launch",
               "--version", "STALCRAFT-RECONSTRUCTION-2019", "--gameDir", ".", "--assetsDir", "assets",
               "--tweakClass", "local.reconstruction.ServerTweaker"]
    record = {"command": command, "cwd": str(lab / "server-game"), "started_unix": time.time(),
              "java_sha256": hashlib.sha256(args.java.read_bytes()).hexdigest(), "timeout": False}
    with base.with_suffix(".stdout.log").open("wb") as stdout, base.with_suffix(".stderr.log").open("wb") as stderr:
        process = subprocess.Popen(command, cwd=record["cwd"], stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL)
        record["pid"] = process.pid
        base.with_suffix(".json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"pid": process.pid, "log": str(base)}, indent=2), flush=True)
        try:
            record["exit_code"] = process.wait(timeout=args.seconds + 90)
        except subprocess.TimeoutExpired:
            record["timeout"] = True
            process.terminate()  # This specific child only; never other Java sessions.
            record["exit_code"] = process.wait(timeout=30)
    record["finished_unix"] = time.time()
    base.with_suffix(".json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: record[k] for k in ("exit_code", "timeout", "pid")}), flush=True)
    for suffix in (".stdout.log", ".stderr.log"):
        print(base.with_suffix(suffix).read_text(encoding="utf-8", errors="replace")[-12000:], flush=True)


if __name__ == "__main__":
    main()
