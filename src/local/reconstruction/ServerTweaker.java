package local.reconstruction;

import cpw.mods.fml.common.launcher.FMLServerTweaker;

/** Retains FML's server-side initialization with the recovered entry point. */
public final class ServerTweaker extends FMLServerTweaker {
    @Override
    public String getLaunchTarget() {
        return "LocalServerMain";
    }
}
