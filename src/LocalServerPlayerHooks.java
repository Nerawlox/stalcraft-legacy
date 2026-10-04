/** Recompute authoritative player stats without the client HUD/camera pipeline. */
public final class LocalServerPlayerHooks {
    /**
     * Discard the client-originated rakn request on its server-to-client return path:
     * rakn carries no shooter identity and has no client processClient handler. A future
     * firearm visual protocol must carry an explicit recipient-visible event.
     */
    public static void discardInvalidShotBroadcast(izjo packet, int dimension) {
    }

    public static void refresh(gloomyfolken.mods.stalker.misc.qlfw stats) {
        stats._i();
        stats._k();
    }

    public static void armor(gloomyfolken.mods.stalker.misc.qlfw stats) {
        stats._i();
    }
}
