import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.client.qlfw;

/** Optifine's client profiling predicate must tolerate a standalone server. */
public final class LocalProfilerHooks {
    public static boolean isClientThread() {
        if (FMLCommonHandler.instance().getSide().isServer()) return false;
        qlfw client = qlfw._I();
        return client != null && Thread.currentThread() == client.__av;
    }
}
