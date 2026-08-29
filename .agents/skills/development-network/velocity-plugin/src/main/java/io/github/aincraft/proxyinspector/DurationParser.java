package io.github.aincraft.proxyinspector;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern FINITE_DURATION = Pattern.compile("(?i)^([1-9]\\d*)([smhdw])$");

    private DurationParser() {
    }

    public static Optional<DurationSpec> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }

        String candidate = token.trim();
        if (candidate.equalsIgnoreCase("permanent")) {
            return Optional.of(new DurationSpec(true, null));
        }

        Matcher matcher = FINITE_DURATION.matcher(candidate);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
                default -> throw new IllegalStateException("unreachable duration unit");
            };
            Instant.now().plus(duration);
            return Optional.of(new DurationSpec(false, duration));
        } catch (ArithmeticException | DateTimeException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public record DurationSpec(boolean permanent, Duration duration) {
        public DurationSpec {
            if (permanent == (duration != null)) {
                throw new IllegalArgumentException("permanent and finite durations are mutually exclusive");
            }
            if (!permanent && (duration.isZero() || duration.isNegative())) {
                throw new IllegalArgumentException("duration must be positive");
            }
        }
    }
}
