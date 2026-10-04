"""Validate and assemble ordinary Java 8 commands for the copied 2019 lab."""
import hashlib
import json
from pathlib import Path
import zipfile


def validate(java, lab, overlay):
    java, lab, overlay = java.resolve(), lab.resolve(), overlay.resolve()
    record = json.loads((lab / "preparation.json").read_text(encoding="utf-8"))
    source = Path(record["source"]).resolve()
    if Path(record["lab"]).resolve() != lab or source == lab or source in lab.parents or lab in source.parents:
        raise ValueError("Not an isolated prepared lab")
    for path in (java, overlay):
        if path == source or source in path.parents:
            raise ValueError("Do not execute the original package or its runtime")
    compilation = json.loads((overlay / "compilation.json").read_text(encoding="utf-8"))
    if compilation["exit_code"] != 0 or not compilation["classes"]:
        raise ValueError("Overlay compilation did not succeed")
    for name, expected in compilation["classes"].items():
        path = (overlay / name).resolve()
        if overlay not in path.parents or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("Compiled overlay changed: " + name)
    for archive in record["archives"]:
        if hashlib.sha256((lab / "classpath" / archive["name"]).read_bytes()).hexdigest() != archive["output_sha256"]:
            raise ValueError("Copied classpath changed: " + archive["name"])
    with zipfile.ZipFile(lab / "classpath/server-bytecode-overlay.jar") as archive:
        for patch in record["patches"]:
            name = patch["class"] + ".class"
            if hashlib.sha256(archive.read(name)).hexdigest() != patch["output_sha256"]:
                raise ValueError("Prepared server overlay changed: " + name)
    return java, lab, overlay, record


def common_flags():
    return ["-Dfile.encoding=UTF-8", "-Dshow_globally_enabled=false", "-Dread_derived=true",
            "-DCustomNpcsSoundCache=true", "-Dload_dumped_event_classes=true", "-DusePrestitchedAtlas=true",
            "-Ddisable_item_atlas=true", "-Ddisable_mod_parsing=true", "-Duse_system_class_loader=true",
            "-Dfml.coreMods.load=codechicken.core.launch.CodeChickenCorePlugin"]


def server_command(java, lab, overlay):
    game = lab / "server-game"
    properties = dict(line.split("=", 1) for line in (game / "server.properties").read_text().splitlines()
                      if "=" in line and not line.startswith(("#", "!")))
    if properties.get("server-ip") != "127.0.0.1" or properties.get("server-port") != "25576":
        raise ValueError("This launcher expects loopback port 25576")
    cp = lab / "classpath"
    classpath = [overlay, cp / "server-bytecode-overlay.jar", cp / "offline-patches.jar",
                 cp / "classes.jar", cp / "libs.jar", game / "modassets"]
    command = [str(java), "-Xms256m", "-Xmx2g", "-Djava.awt.headless=true"] + common_flags()
    command += ["-Dstalcraft.test.joinAdventure=true", "-Dstalcraft.test.selfGamemode=true"]
    command += ["-cp", ";".join(map(str, classpath)), "net.minecraft.launchwrapper.Launch",
                "--version", "STALCRAFT-RECONSTRUCTION-2019", "--gameDir", ".", "--assetsDir", "assets",
                "--tweakClass", "local.reconstruction.ServerTweaker"]
    return command, game
