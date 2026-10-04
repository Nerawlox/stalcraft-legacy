/** Server-side identity and command permission hooks for the 2019 overlay. */
public final class LocalPermissionHooks {
    private static final String TEST_ADVENTURE_PROPERTY = "stalcraft.test.joinAdventure";
    private static final String TEST_SELF_GAMEMODE_PROPERTY = "stalcraft.test.selfGamemode";

    private LocalPermissionHooks() {
    }

    /**
     * Checks only the native ops.txt-backed operator set. This method deliberately does
     * not consult a player's game mode, an integrated-server owner, or another player.
     */
    public static boolean isAdministrator(laun permissionManager, String playerName) {
        return permissionManager != null
                && LocalPermissionPolicy.isOperator(permissionManager._h, playerName);
    }

    /** Resolve identity from a server player instance, never from client packet claims. */
    public static boolean isAdministrator(gsye player) {
        return player != null && isAdministrator(player.field_71133_b.__ag(), player.field_71092_bJ);
    }

    /** Safe adapter for entity-typed server hooks; non-server entities fail closed. */
    public static boolean isAdministrator(jlas player) {
        return player instanceof gsye && isAdministrator((gsye) player);
    }

    /** Replacement for gsye.func_70003_b(int,String). */
    public static boolean canUseCommand(gsye player, int requiredLevel, String commandName) {
        if (player == null || player.field_71133_b == null || commandName == null) {
            return false;
        }
        laun permissionManager = player.field_71133_b.__ag();
        return permissionManager != null && LocalPermissionPolicy.canUseCommand(
                permissionManager._h, player.field_71092_bJ, requiredLevel,
                player.field_71133_b._u(), commandName,
                Boolean.getBoolean(TEST_SELF_GAMEMODE_PROPERTY));
    }

    /**
     * Admins retain the native command behavior. Other players may use only numeric
     * creative/adventure modes on themselves, and only in an explicitly enabled test lab.
     */
    public static boolean isAllowedGamemodeRequest(zjad sender, String[] arguments) {
        if (sender instanceof jlrg) return true;
        if (!(sender instanceof gsye)) {
            return sender != null && sender.func_70003_b(2, "gamemode");
        }
        gsye player = (gsye) sender;
        if (isAdministrator(player)) return true;
        if (!Boolean.getBoolean(TEST_SELF_GAMEMODE_PROPERTY)
                || arguments == null || arguments.length < 1 || arguments.length > 2) {
            return denyGamemode(sender);
        }
        if (!("1".equals(arguments[0]) || "2".equals(arguments[0]))) return denyGamemode(sender);
        if (arguments.length == 1) return true;
        String target = arguments[1];
        return LocalPermissionPolicy.isValidPlayerName(target)
                && player.field_71092_bJ != null
                && player.field_71092_bJ.equalsIgnoreCase(target) || denyGamemode(sender);
    }

    private static boolean denyGamemode(zjad sender) {
        sender.func_70006_a(net.minecraft.util.zwbc._e("commands.generic.permission"));
        return false;
    }

    /** Test-only join mode override; call after login setup on the dedicated server. */
    public static void forceAdventureForTestJoin(gsye player) {
        if (player != null && Boolean.getBoolean(TEST_ADVENTURE_PROPERTY)) {
            player.func_71033_a(enhr._d);
        }
    }
}
