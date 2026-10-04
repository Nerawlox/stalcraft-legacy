import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Standalone regression checks; this class does not initialize game classes. */
public final class LocalPermissionPolicyTest {
    private static int assertions;

    private LocalPermissionPolicyTest() {
    }

    public static void main(String[] args) {
        Set<String> operators = new HashSet<String>(Arrays.asList("adminone", "AdminTwo"));
        check(LocalPermissionPolicy.isOperator(operators, "AdminOne"), "case-insensitive exact admin match");
        check(!LocalPermissionPolicy.isOperator(operators, "RegularPlayer"), "ordinary player denied");
        check(!LocalPermissionPolicy.isOperator(operators, "Unknown"), "unknown name denied");
        check(!LocalPermissionPolicy.isOperator(operators, null), "null name denied");
        check(!LocalPermissionPolicy.isOperator(operators, "   "), "blank name denied");
        check(!LocalPermissionPolicy.isOperator(operators, "AdminOne "), "padded name denied");
        check(!LocalPermissionPolicy.isOperator(operators, "adminone\nregularplayer"), "line injection denied");
        check(!LocalPermissionPolicy.isOperator(operators, "LongPlayerName123"), "overlong name denied");
        check(!LocalPermissionPolicy.isOperator(operators, "Pläyer"), "non-ASCII name denied");
        check(!LocalPermissionPolicy.isOperator(Collections.<String>emptySet(), "AdminOne"), "empty roster denies all");
        check(!LocalPermissionPolicy.isOperator(null, "AdminOne"), "missing roster denies all");

        check(LocalPermissionPolicy.canUseCommand(operators, "AdminOne", 2, 4, "tp"),
                "admin within configured permission level allowed");
        check(!LocalPermissionPolicy.canUseCommand(operators, "AdminOne", 4, 2, "stop"),
                "admin above configured permission level denied");
        check(!LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 0, 4, "tp"),
                "non-admin denied even a zero-level administrative command");
        check(LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 0, 4, "help"),
                "vanilla public help command remains available");
        check(LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 0, 4, "tell"),
                "vanilla public tell command remains available");
        check(LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 0, 4, "me"),
                "vanilla public me command remains available");
        check(!LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 0, 4, "seed"),
                "dedicated server seed is not treated as a public command");
        check(!LocalPermissionPolicy.canUseCommand(operators, "AdminOne", -1, 4, "tp"),
                "invalid negative permission level denied");
        check(!LocalPermissionPolicy.canUseCommand(operators, "Unknown", 0, 4, "tp"),
                "unknown requested identity denied without first-player fallback");
        check(!LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 2, 4,
                "gamemode", false), "self gamemode command is disabled unless explicitly enabled");
        check(LocalPermissionPolicy.canUseCommand(operators, "RegularPlayer", 2, 4,
                "gamemode", true), "explicit test switch admits gamemode for argument-level checks");
        check(LocalPermissionPolicy.canUseCommand(operators, "AdminOne", 2, 4,
                "gamemode", true), "operator remains admitted to native gamemode behavior");
        check(!LocalPermissionPolicy.canUseCommand(operators, "AdminOne", 4, 2,
                "gamemode", true), "operator still obeys native permission-level ceiling");

        System.out.println("LocalPermissionPolicyTest PASS (" + assertions + " assertions)");
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
