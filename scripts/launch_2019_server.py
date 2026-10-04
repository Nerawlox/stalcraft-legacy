"""Launch the standalone loopback server with a normal interactive console."""
import argparse
import json
from pathlib import Path
import subprocess
from launch_2019_common import validate, server_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--stop", action="store_true", help="Request orderly shutdown via this lab's stop flag")
    parser.add_argument("--diagnostics", action="store_true", help="Log players and world state for local validation")
    args = parser.parse_args()
    java, lab, overlay, _ = validate(args.java, args.lab, args.overlay)
    flag = lab / "server-game/reconstruction-stop.flag"
    if args.stop:
        flag.write_text("Orderly stop requested\n", encoding="ascii")
        print("Stop requested for this lab; the server will save before exiting.")
        return
    command, game = server_command(java, lab, overlay)
    if args.diagnostics:
        command.insert(1, "-Dreconstruction.serverProbe=true")
    if flag.exists():
        flag.unlink()  # One known marker in the copied lab, never a world file.
    print("Local server: 127.0.0.1:25576. Type 'stop' in this console to save and exit.", flush=True)
    process = subprocess.Popen(command, cwd=game)
    print(json.dumps({"pid": process.pid, "command": command, "cwd": str(game)}), flush=True)
    raise SystemExit(process.wait())


if __name__ == "__main__":
    main()
