import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import mods.gameobjects.world.GOInstance;
import mods.gameobjects.world.GOWorldData;

/** Bind saved objects once without removing objects that have never entered a world. */
public final class LocalGameObjectHooks {
    private static final Set<GOWorldData> initializing =
        Collections.newSetFromMap(new IdentityHashMap<GOWorldData, Boolean>());

    public static void beforeSpawnGameObject(GOWorldData data, GOInstance instance) {
        if (data == null || instance == null) return;
        GOInstance existing = data.getObjectById(instance.getUniqueId());
        // Saved instances are registered before their world/colliders are bound.
        // killGameObject would call onRemovedFromWorld on an unbound instance.
        // Chunk NBT may contain a distinct instance with the same UUID as global
        // saved data. Neither unbound instance has world membership to tear down.
        if (existing == null || existing.getWorld() == null) return;
        ServerPacketHandler.beforeSpawnGameObject(data, instance);
    }

    public static void onWorldDataSet(GOWorldData data, lrzy world) {
        if (data == null || world == null) return;
        synchronized (initializing) {
            if (!initializing.add(data)) return;
            try {
                // Spawning marks its chunk dirty; that looks up GOWorldData again.
                // A different thread waits on this monitor instead of skipping its pass.
                ServerPacketHandler.onWorldDataSet(data, world);
            } finally {
                initializing.remove(data);
            }
        }
    }
}
