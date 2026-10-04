# Next combat milestone

This document preserves the initial static review and work order. The subsequent overlay v36 stage now has runtime evidence for hitscan damage, ammunition, cadence, occlusion and creative invulnerability; see [validation.md](validation.md) and [the current stage](test-stage-2026-10-04.md). Projectile impact, complete gun visuals and broader PvP accuracy remain unverified. The findings below describe the supplied offline handler before these fixes.

## Retained paths

The native client weapon class `gloomyfolken.mods.weapon.zwbc` chooses between two request paths. When the ammunition's `rajh._p_()` flag is true it sends an empty `rakn`; otherwise it builds a shot record and sends `rrmj`. A normal trigger does not send both requests.

The supplied `ServerPacketHandler.handleWeaponHit(rrmj, jlas)` already traces the server world, selects an entity, calculates damage using the held `grkk` weapon, and calls `target.func_70097_a(...)`. The server owns the final health mutation, but the handler accepts important unvalidated client inputs. This retained hitscan path is a useful starting point for reconstruction.

`handleWeaponShoot(rakn, jlas)` consumes and synchronizes ammunition, then attempts an invalid client-request broadcast. The overlay discards that return broadcast. `rakn` carries no projectile data or shooter identity. The registered `gake` entity is a short-lived client tracer created by `zzfd.processClient`; its class contains no server impact/damage path. These observations do not establish a working projectile mode.

## Gaps in the supplied offline handler

- Ray origin and endpoints can come directly from the client without checks against the authoritative eye position, aim, or weapon range.
- Held weapon, loaded magazine, cadence, and firing state are not prerequisites for applying damage. The handler has a fallback damage value of 20 even when no suitable firearm supplies damage.
- Candidate entities receive some server geometry and occlusion checks, but those checks do not establish that the submitted ray could have originated from the shooter.
- The shot record parser accepts weakly bounded counts and unchecked numeric values. Its signed-byte comparison against 256 cannot enforce the stated target-count limit.
- Factions, safe zones, combat permissions, invulnerability, and lag compensation have not been established. The prototype remains restricted to loopback testing.

These are findings in the community offline overlay, not claims about the historical production server.

## Work order

1. Exercise a real `rrmj` shot between controlled participants in the synthetic copied world. Record target health, damage source, ammunition, and observer state. Include blocked line of sight, range, empty magazine, and empty hand as negative cases.
2. Require a valid loaded weapon and authoritative firing state. Validate cadence, ray origin, range, finite values, and bounded packet fields; consume ammunition exactly once per accepted shot.
3. Add a separate server-originated observer event with an explicit shooter identity and client receiver, instead of returning the request packet.
4. Implement and test projectile ammunition separately: server spawn, owner, lifetime, collision, impact, damage, and visual synchronization.

Local CFR evidence comes from `decompiled/base/gloomyfolken/mods/weapon/zwbc.java`, `rrmj.java`, `rakn.java`, `gake.java`, `zzfd.java`, the weapon `qlfw.java`, and `decompiled/offline-patches/ServerPacketHandler.java`. Those immutable local decompiler snapshots are outside this repository; the input identities are recorded in [input-identity.md](input-identity.md).
