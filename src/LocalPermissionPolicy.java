import java.util.Locale;
import java.util.Set;

/** Pure, game-independent policy for explicit server operator identities. */
public final class LocalPermissionPolicy {
    private static final int MAX_PLAYER_NAME_LENGTH = 16;

    private LocalPermissionPolicy() {
    }

    public static boolean isValidPlayerName(String name) {
        if (name == null || name.length() < 1 || name.length() > MAX_PLAYER_NAME_LENGTH) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '_')) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOperator(Set<?> operatorNames, String requestedName) {
        if (operatorNames == null || !isValidPlayerName(requestedName)) {
            return false;
        }
        String requested = normalize(requestedName);
        for (Object entry : operatorNames) {
            if (entry instanceof String && isValidPlayerName((String) entry)
                    && requested.equals(normalize((String) entry))) {
                return true;
            }
        }
        return false;
    }

    public static boolean canUseCommand(Set<?> operatorNames, String playerName,
                                        int requiredLevel, int serverOperatorLevel,
                                        String commandName) {
        return canUseCommand(operatorNames, playerName, requiredLevel,
                serverOperatorLevel, commandName, false);
    }

    public static boolean canUseCommand(Set<?> operatorNames, String playerName,
                                        int requiredLevel, int serverOperatorLevel,
                                        String commandName, boolean selfGamemodeEnabled) {
        if (requiredLevel < 0 || commandName == null) {
            return false;
        }
        String command = commandName.toLowerCase(Locale.ROOT);
        if ("tell".equals(command) || "help".equals(command) || "me".equals(command)) {
            return true;
        }
        if ("gamemode".equals(command) && selfGamemodeEnabled) {
            return !isOperator(operatorNames, playerName) || requiredLevel <= serverOperatorLevel;
        }
        return requiredLevel <= serverOperatorLevel && isOperator(operatorNames, playerName);
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
