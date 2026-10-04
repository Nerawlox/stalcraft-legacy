"""Write machine-local CMD shortcuts for the prepared standalone lab."""
import argparse
from pathlib import Path
import sys
from launch_2019_common import validate, server_command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    args = parser.parse_args()
    java, lab, overlay, _ = validate(args.java, args.lab, args.overlay)
    server_command(java, lab, overlay)
    scripts = Path(__file__).resolve().parent
    parameters = f'--java "{java}" --lab "{lab}" --overlay "{overlay}"'
    commands = {
        "start-server.cmd": f'"{sys.executable}" "{scripts / "launch_2019_server.py"}" {parameters}',
        "stop-server.cmd": f'"{sys.executable}" "{scripts / "launch_2019_server.py"}" {parameters} --stop',
        "start-client.cmd": f'"{sys.executable}" "{scripts / "launch_2019_client.py"}" {parameters} --profile ProbeAlpha --username LocalPlayer',
        "start-second-client.cmd": f'"{sys.executable}" "{scripts / "launch_2019_client.py"}" {parameters} --profile ProbeBeta --username SecondPlayer',
    }
    # Reject CMD metacharacters in the generated machine-specific arguments.
    if any(character in "\n\r%&|<>^" for command in commands.values() for character in command):
        raise ValueError("Paths cannot be represented safely by these CMD shortcuts")
    if any((lab / name).exists() for name in commands):
        raise ValueError("Refusing to replace existing shortcuts")
    for name, command in commands.items():
        (lab / name).write_text("@echo off\r\n" + command + "\r\npause\r\n", encoding="ascii", newline="")
        print(lab / name)


if __name__ == "__main__":
    main()
