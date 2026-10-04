"""Run two actual copied-lab clients, with opt-in synthetic permission/item/shot diagnostics."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys
import time
import zipfile
from launch_2019_common import validate


def runtime_checks(text, alpha_text, beta_text):
    checks = {
        "two_players": "players=2" in text,
        "permission_inventory": "[RECONSTRUCTION READINESS] PASS" in text,
        "combat_fixture": "[RECONSTRUCTION COMBAT FIXTURE] READY" in text,
        "damage": "damaged=true" in text,
        "nei_give_guard": "[RECONSTRUCTION ITEM BROWSER] PASS" in text,
        "creative_inventory_transfer": "[RECONSTRUCTION INVENTORY MODE] PASS" in text,
        "creative_invulnerability": any(float(a) == float(b)
            for a, b in re.findall(r"damaged=false health=([\d.]+)->([\d.]+)", text)),
        "wall_blocks_damage": "traceMiss shooter=ProbeAlpha blockedOrNoTarget=true" in text,
        "native_cadence": "rejected=native weapon cadence not ready" in text,
        "empty_ammunition": "rejected=missing, incompatible, projectile, or empty ammunition" in text,
        "empty_hand": "rejected=no live shooter or held firearm" in text,
        "exact_ammo_changes": all("before=" + str(a) + " after=" + str(b) in text
            for a, b in ((30, 29), (29, 28), (28, 27)))
            and text.count("[RECONSTRUCTION COMBAT] ammo shooter=") == 3,
        "client_receives_health": any(0 < float(hp) < 20
            for hp in re.findall(r"\[RECONSTRUCTION CLIENT\].*? hp=([\d.]+)", beta_text)),
        "invalid_shots_preserve_ammo": all("sent=" + label + " magazineBefore=30" in alpha_text
            for label in ("nonfinite", "off-origin", "out-of-range", "valid-hitscan")),
        "client_catalog": "[RECONSTRUCTION ITEM CATALOG] entries=" in alpha_text and "excluded=0" in alpha_text,
    }
    return checks


def main(args):
    java, lab, overlay, _ = validate(args.java, args.lab, args.overlay)
    evidence = lab / "probes" / args.name
    if evidence.exists() or Path(args.name).name != args.name:
        raise ValueError("Use a new simple evidence name")
    evidence.mkdir()
    properties = lab / "server-game/server.properties"
    previous = properties.read_bytes()
    (evidence / "server.properties.before").write_bytes(previous)
    ops = lab / "server-game/ops.txt"
    old_ops = ops.read_bytes() if ops.exists() else None
    if old_ops is not None: (evidence / "ops.txt.before").write_bytes(old_ops)
    # Preserve the previously exercised synthetic save, not the original map.
    synthetic = lab / "server-game/ReconstructionTest"
    if synthetic.exists():
        with zipfile.ZipFile(evidence / "synthetic-before.zip", "x", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(synthetic.rglob("*")):
                if file.is_file(): archive.write(file, file.relative_to(synthetic).as_posix())
        # Reset only the two diagnostic identities after archiving their complete save.
        # A retained loaded gun otherwise lets the client reload before the new fixture.
        for username in ("ProbeAlpha", "ProbeBeta"):
            for suffix in (".dat", ".dat_old"):
                player_file = synthetic / "players" / (username + suffix)
                if player_file.exists():
                    if synthetic.resolve() not in player_file.resolve().parents or player_file.is_symlink():
                        raise ValueError("Diagnostic player save escapes synthetic world")
                    player_file.unlink()
    settings = previous.decode("latin1").splitlines()
    replacements = {"level-name": "ReconstructionTest", "gamemode": "2", "force-gamemode": "true"}
    settings = [line for line in settings if line.split("=", 1)[0] not in replacements]
    settings += [key + "=" + value for key, value in replacements.items()]
    scripts = Path(__file__).resolve().parent
    base = [sys.executable]
    common = ["--java", str(java), "--lab", str(lab), "--overlay", str(overlay)]
    jobs = []
    record = {"result": "RUNNING", "physical_two_pc": False, "processes": [], "started": time.time()}

    def save():
        (evidence / "results.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")

    def start(script, name, words):
        stream = (evidence / (name + "-runner.log")).open("wb")
        command = base + [str(scripts / script)] + common + ["--name", args.name + "-" + name] + words
        process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
        jobs.append((process, stream, name))
        record["processes"].append({"name": name, "pid": process.pid, "command": command})
        save()
        return process

    try:
        properties.write_text("\n".join(settings) + "\n", encoding="latin1")
        ops.write_text("nrwlx\n", encoding="ascii")
        server = start("run_2019_server_probe.py", "server", ["--seconds", "240", "--gameplay", "--readiness", "--combat"])
        server_log = lab / "probes" / (args.name + "-server.stdout.log")
        server_error = lab / "probes" / (args.name + "-server.stderr.log")
        def server_text():
            return "\n".join(path.read_text(encoding="utf-8", errors="replace")
                for path in (server_log, server_error) if path.exists())
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if server.poll() is not None: raise AssertionError("Server stopped before readiness")
            if "Done (" in server_text(): break
            time.sleep(1)
        else: raise AssertionError("Server did not reach Done")
        for name, username in (("alpha", "ProbeAlpha"), ("beta", "ProbeBeta")):
            start("run_2019_client_probe.py", name, ["--username", username, "--seconds", "100", "--gameplay", "--combat", "--chat"])
        for process, stream, name in jobs[1:]:
            if process.wait(timeout=220) != 0: raise AssertionError("Probe failed: " + name)
            stream.close()
        (lab / "server-game/reconstruction-stop.flag").write_text("stop\n", encoding="ascii")
        if server.wait(timeout=90) != 0: raise AssertionError("Server runner failed")
        jobs[0][1].close()
        text = server_text()
        alpha_text = (lab / "probes" / (args.name + "-alpha.stderr.log")).read_text(encoding="utf-8", errors="replace")
        beta_text = (lab / "probes" / (args.name + "-beta.stderr.log")).read_text(encoding="utf-8", errors="replace")
        checks = runtime_checks(text, alpha_text, beta_text)
        record["checks"] = checks
        record["selected_server_lines"] = [line for line in text.splitlines()
            if any(marker in line for marker in ("[RECONSTRUCTION READINESS]", "[RECONSTRUCTION COMBAT", "Weapon hit target"))]
        for name in ("server", "alpha", "beta"):
            probe = lab / "probes" / (args.name + "-" + name + ".json")
            data = json.loads(probe.read_text(encoding="utf-8"))
            if data["exit_code"] != 0 or data["timeout"]: raise AssertionError("Abnormal game exit: " + name)
        if not all(checks.values()): raise AssertionError("Missing runtime assertions: " + str(checks))
        record["result"] = "PASS"
    except Exception as failure:
        record["result"], record["failure"] = "FAIL", str(failure)
        raise
    finally:
        (lab / "server-game/reconstruction-stop.flag").write_text("stop\n", encoding="ascii")
        # Runners bound/own their exact game children; let them record and stop normally.
        for process, stream, _ in jobs:
            if process.poll() is None: process.wait(timeout=350)
            if not stream.closed: stream.close()
        properties.write_bytes(previous)
        if old_ops is None:
            if ops.exists(): ops.unlink()
        else: ops.write_bytes(old_ops)
        record["finished"] = time.time()
        record["config_restored_sha256"] = hashlib.sha256(properties.read_bytes()).hexdigest()
        save()
    print(json.dumps({"result": record["result"], "report": str(evidence / "results.json")}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--lab", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    parser.add_argument("--name", required=True)
    main(parser.parse_args())
