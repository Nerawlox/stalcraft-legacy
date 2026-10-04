# Standalone server validation — 4 October 2026

The first local multiplayer milestone uses an authored binary-compatible overlay over the hash-pinned community package. This is not a recovered historical production server or a full source rebuild. Tests use ordinary Temurin Java 8, copied inputs, separate client profiles, offline sessions, and loopback TCP/FML networking on port 25576. Original package launchers and bundled Java were not used.

## Observed results

| Test | Evidence | Scope |
| --- | --- | --- |
| Dedicated startup | Server reaches `Done`, ticks, creates a fresh flat world, and stops with player/world saves. | Actual dedicated process with server FML initialization. |
| Two clients | `server-probe-8`, `client-alpha-3`, `client-beta-2` record both joined players and client worlds. | Actual separate rendered Java clients, not simulated packet senders. |
| Shared world | Server changes `(445,4,-625)` from air to gold (41), then diamond (57); both clients log matching IDs. | Server-originated state replication in the synthetic world. |
| Restart/reconnect | Following runs load block 57 and previous player positions. | Persisted world/player data, not just an in-memory change. |
| Respawn | `server-probe-12`, `client-alpha-9`, `client-beta-5`: native `vmow(1)` requests restore both saved dead players to 20 health. | Standard death/respawn networking; automatic request is opt-in diagnostic behavior only. |
| Reload/fire state | Server and shooter record magazine `0 → 30 → 29 → 30`, reserve `64 → 34 → 33`, weapon 20000 / ammunition 12700. | Real reload and shoot request classes; no target damage or visible projectile asserted. |
| Backend envelope | Wrapped `amxp(cfal(-1))` reload is decoded, handled, and synchronized to the shooter. | Local dispatch of supported requests with original sender retained. |
| Shoot observer correction | `server-probe-13` accepts `rakn` with 30 rounds; the following request sees 29. `client-beta-6` has no invalid `rakn.processClient` dispatch exception. | Retest of removal of the invalid return broadcast; remote gun visuals remain absent. |
| Original map | `map-server-3`, `map-alpha-2`, `map-beta-2`: fresh RegionsLocal copy loads and initializes all 400 global objects; both clients join with 20 health and two visible players. | Supplied geography near `(-158,65,-1985)`; prior game-object initialization exceptions are absent after correction. |
| Remote chat | Both actual GUI messages appear in `map-server-3`; each recipient logs the peer marker in actual chat storage. | Bidirectional GUI → server → peer chat storage on the copied map. |
| Object preservation | After `map-server-3` saves, `data/go_data.dat` retains all 400 original unique UUIDs and its exact compressed SHA-256. All 682 original world files still match the preparation manifest. | Data preservation for global game objects and original-input immutability, not full-map gameplay compatibility. |
| Scoreboard criterion correction | `map-server-4` / `map-alpha-3`, overlay v23: server reloads the copied map, the player reconnects at the saved position with 20 health, and the server saves/stops without the game-object or scoreboard NPEs. | Intermediate overlay: 41 classes, zero compiler warnings, static checks passed. A subsequent metadata comparison exposed fields still lost by this version. |
| Metadata roundtrip | `map-server-5` / `map-alpha-4`, then `map-server-6`, overlay v24: fresh map copy, real client join, save, reload, and save again. All 38 objectives, 91 original scores, 10 teams, unknown metadata, and DisplaySlots survive. | 43 compiled classes, zero warnings, static checks passed, 367 non-target method Code arrays unchanged. Three processes exited normally without timeouts. |

Diagnostic fixtures and block mutations are restricted to the synthetic `ReconstructionTest` world. They do not edit the map copies. Natural server ticks and gameplay can change a test copy. Original worlds remain read-only.

The tested map lab uses peaceful difficulty with monster spawning disabled. This is a controlled multiplayer test configuration, not validation of hostile NPC/mob behavior.

The final NBT comparison found only an expected new `ProbeAlpha / health = 20` score and a native encoding difference: ten empty team `Players` lists use element type `TAG_Byte` instead of `TAG_End`; they remain empty. No unknown metadata fields or existing score values changed. This is semantic preservation, not byte identity of the whole scoreboard file. After both saves, `go_data.dat` remains byte-identical and all 682 original world files still match their preparation hashes.

Selected generated log lines, log hashes, overlay source hashes, and preservation counts are recorded in [evidence.json](evidence.json). Full logs and original map data remain local.

The ordinary server launcher was also exercised without diagnostic flags or a timed shutdown: it reached `Done`, accepted the lab's explicit stop flag, saved, and exited normally. The scoreboard and game-object file hashes remained unchanged from the preceding roundtrip. After separating this playable project from the research checkout, compiling `src/` as overlay v25 produced all 43 class payloads byte-identically to tested v24, with no warnings and the same static checks. No research checkout scripts are required to build this project.

## Failures and corrections

- Dedicated startup initially failed on circular vanilla registry/statistics initialization, the missing Smart Moving event class, and client-only profiler state. The overlay initializes the native statistics bootstrap first and supplies narrowly scoped server-safe hooks.
- Initial remote login exposed a missing custom packet registration (ID 323), a falsely reported integrated connection address, server-side walking particles, and an absent CustomNPCs player-data singleton. Fixes retain the native TCP/FML path and initialize existing registries/controllers.
- The supplied offline handler called client graphics/camera code when syncing player equipment. Only those two calls are redirected to authoritative equipment/effect/weight calculations.
- Reload initially failed because the actual client uses `-1` for automatic ammunition selection, whereas the supplied handler rejects negative values. The bridge normalizes that specific request to compatible-ammo selection.
- Slimes killed the first synthetic test players. Later login correctly loaded their zero-health saves, so custom requests were dropped by the native dead-player gate. The test world now disables monsters and uses native respawn; this was not a transport failure.
- Two intermediate client diagnostic builds crashed because our probe dereferenced a disconnected player. The null guard was corrected, failed outputs retained, and subsequent clients exited normally.
- The supplied shoot handler broadcast `rakn`, a client request with no shooter ID or client receiver. The observer threw `processClient not implemented`. The overlay discards that invalid return broadcast while retaining shooter ammunition sync; it does not invent a remote firearm animation.
- The first map startup loaded 400 globally stored game objects, then tried to remove unbound instances during their initial spawn. Chunk loading reentered the same initialization pass, producing very deep traces. A per-data initialization guard prevents reentry. A second run established distinct chunk/global instances sharing UUIDs, so teardown is skipped for any previously unbound instance; normal cleanup for bound objects remains.
- Map autosave exposed unknown `stat.*` scoreboard criteria, which the supplied reader turned into null and then dereferenced during serialization. The adapter preserves exact criterion names and existing values, leaving known criteria intact. Eleven unresolved criterion names were encountered; their automatic counters are not implemented. The subsequent map run saved normally.
- Comparing the saved NBT revealed a separate compatibility issue: the old serializer discarded newer objective, score, and team metadata, including `RenderType`, `Locked`, `CollisionRule`, `DeathMessageVisibility`, `NameTagVisibility`, and `TeamColor`. The preservation hooks keep unknown tags on matching live records without restoring deleted records or replacing native updates. This preserves storage only; newer collision, visibility, locking, and rendering behavior is not thereby implemented.

Failed and successful logs, exact commands, compiled versions, SHA-256 manifests, and copied worlds are preserved in the local analysis/lab directories. Public material includes authored source, scripts, and these descriptions, without original binaries or complete worlds.

## Limits

No full-map traversal, PvP damage test, projectile-spawn test, remote firearm animation, NPC quest/trader test, or inventory ownership stress test has passed yet. Smart Moving state exchange still needs verification. Account systems, instance routing, clans, economy, voice service, and other historical backend services are absent or unsupported. Existing package warnings about assets/colliders, optional integrations, and Discord IPC are distinct from these multiplayer corrections.

The [next combat milestone](combat-next-stage.md) traces the retained damage paths and the missing server checks. A server-side health mutation in the supplied offline code is not evidence of a validated combat protocol.

An exit code of zero alone is insufficient: the supplied launch code can swallow failures. Assertions use joined player/world state and observed synchronization, followed by normal save/shutdown. Static class verification checks audited instruction changes and unchanged Code arrays; opaque class attributes and full Java verification are outside that claim.
