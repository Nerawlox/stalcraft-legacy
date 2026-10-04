# Working on STALCRAFT Legacy

Read README.md, docs/validation.md and docs/development.md before changing code. This is a separate playable project; historical recovery remains in the stalcraft-reconstruction repository. The user explicitly requested this public repository as `Nerawlox/stalcraft-legacy`, requested a Russian README, and waived preliminary README review for this project. Code and technical reports may remain English.

On the research machine, read `E:\Stalcraft project\AGENTS.md` if it exists. Originals, copied labs, compiled versions, trusted Java and full logs are outside this checkout. Never modify or automatically launch original package executables. No original binary, native library, full world, downloaded tool or runtime belongs in Git.

`src/` contains authored binary-compatible fixes, not a complete source rebuild. Preparation pins original archive hashes; compilation uses hash-pinned ECJ with annotation processing disabled. Preserve non-target class methods and verify exact audited edits with scripts/verify_2019_overlay.py. Keep failed experiments and successful versions separately. Check git status before editing.

Ordinary trusted Java 8 testing of copied 2019 labs is authorized. Networking currently binds loopback 127.0.0.1:25576 in offline mode. Never interrupt unrelated game sessions or widen network exposure silently. Probe fixtures belong only in the synthetic ReconstructionTest world. Bounded diagnostics are opt-in; normal launchers have no automatic shutdown.

The protected pre-2017 JVM and memory experiments belong to the research project and remain stopped after an unexplained Windows crash. This project does not resume them.

Keep claims tied to actual evidence: two actual clients, synchronization, map/chat, reload/ammunition and save/reload have tests. Combat damage, projectile mode, remote firearm animation, NPCs, inventory ownership, advanced movement and historical backend services are incomplete. Unknown scoreboard metadata is preserved for storage; missing runtime semantics are not implemented. Do not call this the recovered production server.

Use independent copied worlds for experiments. Preserve original manifests and SHA-256 identities. Public evidence excludes complete logs and original map records. Russian README wording should remain understandable to a non-programmer.
