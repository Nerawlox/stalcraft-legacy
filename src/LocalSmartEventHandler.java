import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.smart.moving.playerapi.SmartMovingServerPlayerBase;
import net.smart.moving.SmartMovingPacketStream;

/** Compiled in the default package, then relocated to the recovered mod's name. */
public final class LocalSmartEventHandler {
    @ForgeSubscribe
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.world.field_72995_K && event.entity instanceof gsye) {
            SmartMovingServerPlayerBase base = SmartMovingServerPlayerBase.getPlayerBase(event.entity);
            if (base != null) base.onPlayerJoin();
        }
    }

    @ForgeSubscribe
    public void onStartTracking(sbrv event) {
        if (event.entity instanceof gsye) {
            SmartMovingServerPlayerBase base = SmartMovingServerPlayerBase.getPlayerBase(event.entity);
            if (base != null) {
                long state = (base.moving.isCrawling ? 1L << 13 : 0L) | (base.moving.isSmall ? 1L << 15 : 0L);
                event._a.field_71135_a.func_72567_b(SmartMovingPacketStream.genPacket(
                    SmartMovingPacketStream.getStatePacket(event.entity.field_70157_k, state)));
            }
        }
    }
}
