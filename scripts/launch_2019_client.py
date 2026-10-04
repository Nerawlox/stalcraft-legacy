"""Launch a copied client and connect to the standalone loopback server."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
from launch_2019_common import validate, common_flags


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--profile", choices=("ProbeAlpha", "ProbeBeta"), default="ProbeAlpha")
    parser.add_argument("--username", default="LocalPlayer")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", args.username):
        raise ValueError("Use a 1–16 character ASCII player name")
    java, lab, overlay, preparation = validate(args.java, args.lab, args.overlay)
    game = lab / args.profile
    if not game.exists():
        raise ValueError("Prepare client profiles first")
    shutil.copy2(Path(preparation["source"]) / "metadata/modlist.txt", game / "modlist.txt")
    cp = lab / "classpath"
    classpath = [overlay, cp / "offline-patches.jar", cp / "classes.jar", cp / "libs.jar", game / "modassets"]
    nativepath = ";".join(map(str, (lab / "natives", java.parent, lab / "native-exbo", lab / "native-exbo/fmod")))
    command = [str(java), "-Xms256m", "-Xmx3g", "-Doffline.world=", "-Djava.library.path=" + nativepath] + common_flags()
    command += ["-cp", ";".join(map(str, classpath)), "net.minecraft.launchwrapper.Launch", "--version",
                "STALCRAFT-RECONSTRUCTION-2019", "--gameDir", ".", "--assetsDir", "assets", "--username", args.username,
                "--session", "0", "--width", "1280", "--height", "720", "--server", "127.0.0.1", "--port", "25576",
                "--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker"]
    environment = dict(os.environ)
    environment["PATH"] = nativepath + ";" + environment.get("PATH", "")
    raise SystemExit(subprocess.call(command, cwd=game, env=environment))


if __name__ == "__main__":
    main()
