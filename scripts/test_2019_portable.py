"""Extract fresh portable kits and exercise relocation and actual TCP/FML login.

Developer smoke test: starts ONLY extracted kit copies, never changes firewall.
An explicit assigned private bind address is required. Tests do not substitute
for a connection between two physical machines. Evidence stays outside Git.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import time
import zipfile

from prepare_2019_world import hash_file, reject_reparse_components


def extract(archive, destination):
    reject_reparse_components(archive)
    sidecar = archive.with_suffix(archive.suffix + ".sha256")
    deadline = time.monotonic() + 600
    last_message = 0
    while not sidecar.exists():
        if time.monotonic() > deadline:
            raise TimeoutError("Archive SHA-256 sidecar is not ready: " + str(sidecar))
        if time.monotonic() - last_message > 30:
            print("Waiting for completed archive:", archive.name, flush=True)
            last_message = time.monotonic()
        time.sleep(1)
    if hash_file(archive)[0] != sidecar.read_text(encoding="utf-8").split()[0]:
        raise ValueError("Archive does not match SHA-256 sidecar")
    destination.mkdir()
    with zipfile.ZipFile(archive) as zipped:
        roots, seen = set(), set()
        for index, item in enumerate(zipped.infolist(), 1):
            relative = PurePosixPath(item.filename)
            if (relative.is_absolute() or ".." in relative.parts or "\\" in item.filename or
                    ":" in item.filename or item.filename in seen or len(relative.parts) < 2):
                raise ValueError("Unexpected archive path")
            if (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Archive links are not allowed")
            seen.add(item.filename)
            roots.add(relative.parts[0])
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipped.open(item) as src, target.open("xb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)  # ZipFile checks CRC at EOF.
            if index % 1000 == 0:
                print("Extracted", archive.name, index, flush=True)
    if len(roots) != 1:
        raise ValueError("Expected a single kit root")
    return destination / roots.pop()


def main(args):
    reject_reparse_components(args.output)
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError("Need a new test output directory")
    output.mkdir(parents=True)
    record = {"schema": 1, "started": time.time(), "bind": args.bind, "port": args.port,
              "physical_two_pc_test": False, "firewall_changed": False, "checks": [], "processes": []}

    def save():
        (output / "results.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def command(kit, *words):
        return [str(kit / "runtime/java/bin/java.exe"), "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true",
                "-cp", str(kit / "app/launcher.jar"), "PortableLauncher", *words]

    def run(kit, label, *words, expected=0):
        started = time.monotonic()
        result = subprocess.run(command(kit, *words), cwd=output, capture_output=True,
                                encoding="utf-8", errors="replace", timeout=180)
        path = output / (label + ".log")
        path.write_text(result.stdout + result.stderr, encoding="utf-8")
        record["checks"].append({"name": label, "exit_code": result.returncode,
                                 "expected_exit": expected, "duration_seconds": round(time.monotonic() - started, 2),
                                 "log_sha256": hash_file(path)[0]})
        save()
        if result.returncode != expected:
            raise AssertionError(label + ": unexpected exit; see " + str(path))
        print(label, "PASS", flush=True)
        return result.stdout + result.stderr

    def start(kit, label, *words):
        log = output / (label + ".log")
        stream = log.open("wb")
        cmd = command(kit, *words)
        process = subprocess.Popen(cmd, cwd=output, stdout=stream, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        entry = {"name": label, "pid": process.pid, "command": cmd, "log": str(log), "timeout": False}
        record["processes"].append(entry)
        save()
        return process, stream, entry

    def finish(job, timeout=150):
        process, stream, entry = job
        try:
            entry["exit_code"] = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            entry["timeout"] = True
            # Terminate only this authored launcher's child tree, identified by PID.
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
            entry["exit_code"] = process.wait(timeout=30)
        finally:
            stream.close()
        entry["log_sha256"] = hash_file(Path(entry["log"]))[0]
        save()
        if entry["exit_code"] != 0 or entry["timeout"]:
            raise AssertionError("Diagnostic process failed: " + entry["name"])

    try:
        client = extract(args.client_zip, output / "Клиент с пробелами")
        server = extract(args.server_zip, output / "Сервер с пробелами")
        record["archives"] = {"client": hash_file(args.client_zip)[0], "server": hash_file(args.server_zip)[0]}
        run(client, "client-full-verify", "verify")
        run(server, "server-full-verify", "verify")
        watched = [client / "connection.properties", server / "connection.properties", server / "game/server.properties"]
        before = {str(p): hash_file(p)[0] for p in watched}
        dry_client = run(client, "client-relocated-dry-run", "client", "--host", args.bind,
                         "--username", "TransferTest", "--port", str(args.port), "--dry-run")
        dry_server = run(server, "server-relocated-dry-run", "server", "--bind", args.bind,
                         "--port", str(args.port), "--dry-run")
        for kit, text in [(client, dry_client), (server, dry_server)]:
            if str(kit / "game") not in text or str(kit / "runtime/java/bin/java.exe") not in text:
                raise AssertionError("Relocated commands did not use their own kit")
            if "stalcraft-decompiled" in text or "server-lab-v3" in text:
                raise AssertionError("Original machine dependencies leaked into command")
        if before != {str(p): hash_file(p)[0] for p in watched}:
            raise AssertionError("Dry run changed settings")
        run(server, "reject-wildcard-bind", "server", "--bind", "0.0.0.0", "--dry-run", expected=1)
        run(server, "reject-public-bind", "server", "--bind", "8.8.8.8", "--dry-run", expected=1)
        run(client, "reject-wrong-role", "server", "--bind", args.bind, "--dry-run", expected=1)
        payload = client / "app/overlay/LocalClientProbe.class"
        original = payload.read_bytes()
        try:
            payload.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
            rejection = run(client, "reject-corrupt-payload", "client", "--host", args.bind,
                            "--username", "TransferTest", "--dry-run", expected=1)
            if "LocalClientProbe.class" not in rejection:
                raise AssertionError("Corruption was not identified")
        finally:
            payload.write_bytes(original)
        # The server timer is diagnostic-only; the shipped Start-Server has none.
        server_job = start(server, "portable-server", "server", "--bind", args.bind,
                           "--port", str(args.port), "--probe-seconds", "180")
        deadline = time.monotonic() + 110
        while time.monotonic() < deadline:
            text = Path(server_job[2]["log"]).read_text(encoding="utf-8", errors="replace")
            if "Done (" in text:
                break
            if server_job[0].poll() is not None:
                raise AssertionError("Server exited before readiness")
            time.sleep(1)
        else:
            run(server, "startup-stop-request", "stop")
            finish(server_job)
            raise AssertionError("Server did not reach Done")
        run(client, "tcp-connection-check", "check", "--host", args.bind, "--port", str(args.port))
        client_job = start(client, "portable-client", "client", "--host", args.bind,
                           "--username", "TransferTest", "--port", str(args.port), "--probe-seconds", "40")
        try:
            finish(client_job)
        finally:
            run(server, "orderly-stop-request", "stop")
            finish(server_job)
        client_log = Path(client_job[2]["log"]).read_text(encoding="utf-8", errors="replace")
        server_log = Path(server_job[2]["log"]).read_text(encoding="utf-8", errors="replace")
        if not any("[RECONSTRUCTION CLIENT]" in line and "world=true entity=true" in line and "hp=20.0" in line
                   for line in client_log.splitlines()):
            raise AssertionError("No actual living client world was observed")
        if not any("[RECONSTRUCTION SERVER]" in line and "TransferTest#" in line and "hp=20.0" in line
                   for line in server_log.splitlines()):
            raise AssertionError("Server did not observe the joined player")
        if "Loaded 400 game objects" not in server_log:
            # Text varies by recovered logging implementation; retain the exact log for review.
            record["game_object_log_lines"] = [line for line in server_log.splitlines()
                                                if "400" in line and "object" in line.lower()]
        record["joined_player_confirmed"] = True
        record["result"] = "PASS"
    except Exception as failure:
        record["result"], record["failure"] = "FAIL", str(failure)
        raise
    finally:
        record["finished"] = time.time()
        save()
    print(json.dumps({"result": record["result"], "report": str(output / "results.json")}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-zip", type=Path, required=True)
    parser.add_argument("--server-zip", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bind", required=True)
    parser.add_argument("--port", type=int, default=25576)
    main(parser.parse_args())
