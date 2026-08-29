package io.github.aincraft.proxyinspector;

import java.time.Duration;

public final class DurationParserContractTest {
    private DurationParserContractTest() {
    }

    public static void main(String[] args) {
        parsesFiniteDuration();
        parsesPermanentDuration();
        rejectsInvalidDurationTokens();
    }

    private static void parsesFiniteDuration() {
        DurationParser.DurationSpec parsed = DurationParser.parse("15M").orElseThrow();
        require(!parsed.permanent(), "15M must be finite");
        require(parsed.duration().equals(Duration.ofMinutes(15)), "15M duration mismatch");
    }

    private static void parsesPermanentDuration() {
        DurationParser.DurationSpec parsed = DurationParser.parse("PERMANENT").orElseThrow();
        require(parsed.permanent(), "PERMANENT must be permanent");
        require(parsed.duration() == null, "permanent duration must be null");
    }

    private static void rejectsInvalidDurationTokens() {
        require(DurationParser.parse("0s").isEmpty(), "zero duration must be rejected");
        require(DurationParser.parse("15x").isEmpty(), "unknown duration unit must be rejected");
        require(DurationParser.parse("15").isEmpty(), "unitless duration must be rejected");
        require(DurationParser.parse("1h30m").isEmpty(), "compound duration must be rejected");
        require(DurationParser.parse("100000000000000000s").isEmpty(), "out-of-range expiry must be rejected");
        require(DurationParser.parse("reason").isEmpty(), "reason text must not parse");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
