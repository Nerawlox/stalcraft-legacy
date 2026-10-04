# Community offline client labeled 2019

The contributor supplied a community offline package labeled **STALCRAFT 2019**, obtained from a Google Drive link shared through Telegram. Exact source links and the original release date have not been established. The contributor scanned and launched the package and confirmed that its offline game works. This is distinct from the project team's later isolated server experiments.

The package contains Minecraft 1.6.4 / Forge 9.11.1.1345 code, a decoded client, an `offline-patches.jar` overlay, assets, and the `RegionsLocal` save. It contains substantial integrated-server gameplay logic. This does not establish possession of the historical production server or its account, persistence, instance-routing, economy, and administration services.

## Local input identity

| Input | SHA-256 |
| --- | --- |
| `Stalcraft 2019.rar` | `b331d8ac4f30b20ac24fe99e0fd1e9886dcb26b3b6cb466432aa1fc6a75b0b48` |
| `classes.jar` | `d91d22576c994126ef90818fee4cd490b71cd62f3341464e630404293ab1f4cd` |
| `libs.jar` | `1738611cd0bbbaf9fdf19ff9abc4dae9040ae6db5f3c68463fa27b822eebafe0` |
| `offline-patches.jar` | `14388ea0e1a17ef054aab032588aa72ff4947d57f5532c85c94b49eb35d390f9` |

CFR 0.152 produced 6,504 base Java files and 166 offline-overlay Java files. Immutable local snapshots and their manifests are held outside Git under `builds/community-offline-2019-candidate/decompiled` and `analysis/cfr` in the shared project directory. Empty class entries are omitted only from the copied test classpath; every retained class/resource payload is preserved. The original package is untouched.

## Server reconstruction

[Working source](../src/) contains authored patches and test hooks, separate from the original binaries and decompiler output. A standalone local server has accepted two actual clients, synchronized server-originated block changes, saved the world, and loaded it again on reconnect. Native respawn requests, automatic reload, ammunition consumption, and wrapped backend requests have also been exercised. Read the [validation report](validation.md) for exact test scope, failures, and remaining work.

The original dedicated startup references `justagod.network.frontend.NettyServer` and `net.minecraft.server.MinecraftServer`, both absent from all three input archives. The recovered implementation is `jlrg` / `buzu`, and the ordinary stream listener is `buzv` / `rtjd`. The client also retains a vanilla/FML TCP constructor in `oiuh`, alongside its proprietary framed/encrypted Netty transport. The reconstruction experiments use the matching ordinary TCP/FML path.

No game binaries, native libraries, downloaded compilers, or worlds are included in this repository.
