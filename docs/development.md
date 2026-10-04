# Standalone server working overlay

This is a narrow binary-compatible overlay, **not a complete rebuild of the recovered source**. It requires the hash-pinned community package identified in [input-identity.md](input-identity.md). Authored source is in the repository's `src/` directory. Use separate server/client game directories and an ordinary trusted Java 8 runtime. Do not invoke the distributor's launcher against the original files.

## Changes

- `LocalServerMain` and `local.reconstruction.ServerTweaker` provide the missing server launch entry while preserving FML server-side initialization. Statistics are initialized before constructing the server to avoid circular vanilla static initialization. The custom packet registry and CustomNPCs player-data controller are initialized explicitly.
- `prepare_2019_server_lab.py` changes the dedicated server's missing frontend constructor to the retained `buzv` listener and its parameter type to `jlrg`. Instruction arrays and string literals stay unchanged; only semantic class/descriptor references change.
- `elyf` uses the existing four-argument `oiuh` constructor, restoring ordinary TCP/FML remote connection instead of the custom frontend login transport.
- The profiler tolerates absence of a client singleton. Better Grass & Leaves walking particles run only in client worlds, retaining the original renderer method for clients.
- `NettyHooks.getServerIP` reports a real remote address rather than calling every connection `memory`. Client offline tick edits remain enabled only for integrated worlds.
- Smart Moving's event handler retains player-join initialization and late-tracker crawling/small state, without the missing historical anticheat provider. Other movement behavior still requires multiplayer testing.
- Backend envelope packets are decoded and forwarded with the original sender. Native automatic reload selection (`-1`) is translated to the supplied handler's compatible-ammo selection (`0`). Unsupported requests are counted and reported; absent backend services are not simulated.
- Player equipment/effect and weight calculations run without the client camera/HUD update. The invalid broadcast of a client-only shoot request is discarded; authoritative ammunition updates remain active. Remote firearm animations still need a proper event protocol.
- Remote chat uses the native Packet3Chat connection while retaining integrated chat. Game object initialization rejects reentry and avoids removing saved instances before their world is bound, including duplicate UUIDs shared by global and chunk storage.
- Unknown scoreboard criteria are preserved as read-only storage adapters with their original names. Existing registered criteria remain unchanged. Unknown serialized metadata is retained for matching live objectives, scores, and teams; native updates take priority and deleted entries stay deleted. This fixes serialization of the supplied map's `stat.*` objectives and prevents the older serializer from discarding newer fields. Missing automatic stat evaluators and newer metadata behavior remain unimplemented.
- Opt-in client/server probe hooks record world/player state and a server-originated block transition. Diagnostic clients request their own shutdown; the server stops and saves normally after the requested duration. Hooks do not stop unrelated game sessions.

## Local tools

These commands target Windows with Python 3.10 or newer and an ordinary Java 8 runtime. Run them from the repository. Input paths are explicit; original files are never edited. The compiler is Eclipse ECJ 3.26.0, pinned to SHA-256 `ac0ba5876eaf7ebb47749a0d1be179c51f194b9dd0b875d1c09e1b530f5a2db5`; its official artifact is [Maven Central](https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar).

1. `scripts/prepare_2019_server_lab.py --source <unpacked-package> --output <new-lab-directory>` validates input hashes, prepares a copied classpath and separate server game directory, and writes a preparation manifest. It uses a new synthetic flat world on loopback port 25576, not the original save.
2. `scripts/build_2019_overlay.py --java <trusted-java8> --ecj <ecj-3.26.0.jar> --lab <lab> --output <new-output>` compiles only authored source with annotation processing disabled. The compiler is SHA-256 pinned. The ASM tool only reads/writes game class bytes; it does not initialize game classes. Obfuscated default-package types require compiling the small Smart Moving handler in the default package, then relocating its class identity.
3. `scripts/prepare_2019_test_clients.py --lab <lab>` copies two separate client directories and native libraries. It does not launch them.
4. `scripts/run_2019_server_probe.py --java <trusted-java8> --lab <lab> --overlay <compiled-output> --name <unique-probe> --seconds 240` starts the isolated server and records its command, PID, and output.
5. `scripts/run_2019_client_probe.py --java <trusted-java8> --lab <lab> --overlay <compiled-output> --username ProbeAlpha --name <unique-probe>` launches a separate client with session `0`, automatically connecting to loopback. Repeat for `ProbeBeta` in its own directory.

The build command records compiler, bytecode patcher, and static verification outputs in `compilation.json`; a failed phase fails the build. `verify_2019_overlay.py` checks exact audited instruction changes and unchanged methods. It does not execute the game or compare opaque class attributes byte-for-byte.

## Playable local launch

After preparing and compiling the lab, `scripts/write_2019_launchers.py --java <trusted-java8> --lab <lab> --overlay <compiled-output>` creates four machine-specific CMD shortcuts in the lab. Start `start-server.cmd`, wait for `Done`, then open `start-client.cmd`. `start-second-client.cmd` uses another game directory and a distinct player name. These launchers have no timed shutdown and no gameplay fixtures. Close a client normally; type `stop` in the server console or use `stop-server.cmd` to save and exit.

To use the supplied map, run `scripts/prepare_2019_world.py --lab <lab> --activate` while the server is stopped. It copies all RegionsLocal files into `server-game/RegionsLocal-test`, verifies every size/hash, writes a manifest, and backs up server.properties before changing only `level-name`. It never overwrites a previous copy. `--name RegionsLocal-test-v2` creates a separately named fresh copy for another experiment. Unicode region extensions are preserved.

Original JAR/native inputs and runtime stay local. Both launcher and probe tools validate the prepared classpath and compiled overlay before execution. The copied native libraries remain the package's own dependencies. Use unique probe names; leave prior evidence intact.

The probe tools are bounded experiments. They record exit codes but **exit code zero is not a gameplay success criterion**: this recovered launcher can swallow startup failures. Verify server/client logs, actual joined players, synchronized state, and persistence. The script's timeout termination targets its exact child process only.

## Scope still to validate

See [validation.md](validation.md). Weapon hit/damage accuracy, projectile spawning, remote gun visuals, inventory ownership, advanced movement state, NPC interactions, full-map ID compatibility, and missing backend services need explicit tests. Missing model/collider resources in the supplied package are logged separately. The launchers bind offline-mode networking to loopback port 25576; broader network deployment has not been tested.
