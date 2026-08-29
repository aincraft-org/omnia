package io.github.aincraft.proxyinspector;

import java.time.Duration;

public final class CommandSupportContractTest {
    private CommandSupportContractTest() {
    }

    public static void main(String[] args) {
        parsesReasonWithoutMistakingItForDuration();
        parsesOptionalDurationBeforeReason();
        suppliesDefaultReason();
        requiresFiniteDurationForTemporaryPunishment();
        acceptsOnlyLiteralIpAddresses();
    }

    private static void parsesReasonWithoutMistakingItForDuration() {
        CommandSupport.PunishmentArguments parsed = CommandSupport.parsePunishmentArguments(
                new String[]{"Player", "123", "spam"}, false, "default"
        ).orElseThrow();

        require(parsed.target().equals("Player"), "target mismatch");
        require(parsed.duration() == null, "numeric reason must not become duration");
        require(parsed.reason().equals("123 spam"), "reason mismatch");
    }

    private static void parsesOptionalDurationBeforeReason() {
        CommandSupport.PunishmentArguments parsed = CommandSupport.parsePunishmentArguments(
                new String[]{"Player", "7d", "spam"}, false, "default"
        ).orElseThrow();

        require(parsed.duration().equals(Duration.ofDays(7)), "duration mismatch");
        require(parsed.reason().equals("spam"), "reason mismatch");
    }

    private static void suppliesDefaultReason() {
        CommandSupport.PunishmentArguments parsed = CommandSupport.parsePunishmentArguments(
                new String[]{"Player"}, false, "default reason"
        ).orElseThrow();

        require(parsed.reason().equals("default reason"), "default reason mismatch");
    }

    private static void requiresFiniteDurationForTemporaryPunishment() {
        require(CommandSupport.parsePunishmentArguments(
                new String[]{"Player", "permanent"}, true, "default"
        ).isEmpty(), "temporary punishment must reject permanent");
        require(CommandSupport.parsePunishmentArguments(
                new String[]{"Player", "reason"}, true, "default"
        ).isEmpty(), "temporary punishment must require duration");
        require(CommandSupport.parsePunishmentArguments(
                new String[]{"Player", "30m"}, true, "default"
        ).isPresent(), "temporary punishment must accept finite duration");
    }

    private static void acceptsOnlyLiteralIpAddresses() {
        require(CommandSupport.parseIpLiteral("127.0.0.1").isPresent(), "IPv4 literal must parse");
        require(CommandSupport.parseIpLiteral("2001:db8::1").isPresent(), "IPv6 literal must parse");
        require(CommandSupport.parseIpLiteral("1.2.3").isEmpty(), "abbreviated IPv4 must be rejected");
        require(CommandSupport.parseIpLiteral("dead.beef").isEmpty(), "DNS-like value must be rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
