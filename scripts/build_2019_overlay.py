"""Compile only authored working-overlay sources with hash-pinned Java 8 ECJ."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from remap_named_bytecode import remap_class


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--ecj", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    preparation = json.loads((args.lab / "preparation.json").read_text(encoding="utf-8"))
    source = Path(preparation["source"]).resolve()
    if Path(preparation["lab"]).resolve() != args.lab.resolve():
        raise ValueError("Lab path does not match its preparation manifest")
    for path in (args.output.resolve(), args.java.resolve()):
        if path == source or source in path.parents:
            raise ValueError("Do not compile into or execute the original package")
    if hashlib.sha256(args.ecj.read_bytes()).hexdigest() != "ac0ba5876eaf7ebb47749a0d1be179c51f194b9dd0b875d1c09e1b530f5a2db5":
        raise ValueError("Unexpected ECJ compiler")
    if args.output.exists():
        raise ValueError("Use a new output directory to preserve probe binaries")
    sources = sorted((Path(__file__).resolve().parents[1] / "src").rglob("*.java"))
    cp = args.lab.resolve() / "classpath"
    classpath = ";".join(str(cp / name) for name in ("offline-patches.jar", "classes.jar", "libs.jar"))
    command = [str(args.java.resolve()), "-jar", str(args.ecj.resolve()), "-1.8", "-proc:none", "-classpath", classpath,
               "-d", str(args.output.resolve())] + list(map(str, sources))
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    args.output.mkdir(parents=True, exist_ok=True)
    relocations = {}
    patch_report = None
    validation_report = None
    exit_code = result.returncode
    if result.returncode == 0:
        relocations = {"LocalSmartEventHandler": "net/smart/moving/mod/SmartEventHandler"}
        for source_name, target_name in relocations.items():
            raw = (args.output / (source_name + ".class")).read_bytes()
            relocated, actual, _, _ = remap_class(raw, relocations, {})
            target = args.output / (actual + ".class")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(relocated)
        patch_command = [str(args.java.resolve()), "-cp", str(args.output.resolve()) + ";" + str(cp / "libs.jar"),
                         "BuildBytecodeOverlay", str(cp / "classes.jar"), str(args.output.resolve()), str(cp / "offline-patches.jar")]
        patch = subprocess.run(patch_command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        patch_report = {"command": patch_command, "exit_code": patch.returncode, "stdout": patch.stdout, "stderr": patch.stderr}
        exit_code = patch.returncode
        if exit_code == 0:
            validation_command = [sys.executable, str(Path(__file__).with_name("verify_2019_overlay.py")),
                                  "--overlay", str(args.output.resolve()), "--classpath", str(cp)]
            validation = subprocess.run(validation_command, capture_output=True, text=True, encoding="utf-8", errors="replace")
            validation_report = {"command": validation_command, "exit_code": validation.returncode,
                                 "stdout": validation.stdout, "stderr": validation.stderr}
            exit_code = validation.returncode
    report = {"command": command, "exit_code": exit_code, "compiler_exit_code": result.returncode,
              "stdout": result.stdout, "stderr": result.stderr, "patch": patch_report, "validation": validation_report,
              "relocations": relocations,
              "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              "classes": {str(p.relative_to(args.output)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in args.output.rglob("*.class")}}
    (args.output / "compilation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"exit_code": exit_code, "classes": len(report["classes"]), "stderr": result.stderr,
                      "patch_stderr": None if patch_report is None else patch_report["stderr"],
                      "validation_stderr": None if validation_report is None else validation_report["stderr"]}), flush=True)
    raise SystemExit(exit_code)


if __name__ == "__main__":
    main()
