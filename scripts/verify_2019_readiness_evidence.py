"""Verify retained two-client logs without starting or changing any game process."""
import argparse
import hashlib
import json
from pathlib import Path
from test_2019_readiness import runtime_checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()
    if Path(args.name).name != args.name: raise ValueError("Need a simple evidence name")
    probes = args.lab / "probes"
    output = probes / args.name / "results-rechecked.json"
    if output.exists(): raise FileExistsError("Preserve earlier evidence verification")
    logs = {name: probes / (args.name + "-" + name + ".stderr.log")
        for name in ("server", "alpha", "beta")}
    text = {name: path.read_text(encoding="utf-8", errors="replace") for name, path in logs.items()}
    checks = runtime_checks(text["server"], text["alpha"], text["beta"])
    for name in logs:
        record = json.loads((probes / (args.name + "-" + name + ".json")).read_text(encoding="utf-8"))
        checks[name + "_normal_exit"] = record["exit_code"] == 0 and not record["timeout"]
    report = {"result": "PASS" if all(checks.values()) else "FAIL", "checks": checks,
        "recheck_only": True, "physical_two_pc": False,
        "note": "Health checks allow native regeneration between sampled reports; creative hurt must preserve health at the actual hit.",
        "logs_sha256": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in logs.items()}}
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if report["result"] != "PASS": raise SystemExit(1)


if __name__ == "__main__": main()
