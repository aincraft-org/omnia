package io.github.aincraft.proxyinspector;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class PunishmentService {
    private final Map<String, Punishment> usernameBans = new ConcurrentHashMap<>();
    private final Map<String, Punishment> ipBans = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public Punishment banUsername(String username, Duration duration, String reason) {
        Punishment punishment = new Punishment(reason, expiresAt(duration));
        usernameBans.put(normalizeUsername(username), punishment);
        return punishment;
    }

    public boolean unbanUsername(String username) {
        return usernameBans.remove(normalizeUsername(username)) != null;
    }

    public Optional<Punishment> usernameBan(String username) {
        return activePunishment(usernameBans, normalizeUsername(username));
    }

    public boolean isUsernameBanned(String username) {
        return usernameBan(username).isPresent();
    }

    public Punishment banIp(String ipAddress, Duration duration, String reason) {
        Punishment punishment = new Punishment(reason, expiresAt(duration));
        ipBans.put(normalizeIp(ipAddress), punishment);
        return punishment;
    }

    public boolean unbanIp(String ipAddress) {
        return ipBans.remove(normalizeIp(ipAddress)) != null;
    }

    public Optional<Punishment> ipBan(String ipAddress) {
        return activePunishment(ipBans, normalizeIp(ipAddress));
    }

    public boolean isIpBanned(String ipAddress) {
        return ipBan(ipAddress).isPresent();
    }

    public void recordLastSeen(String username, Instant seenAt) {
        lastSeen.put(normalizeUsername(username), Objects.requireNonNull(seenAt, "seenAt"));
    }

    public Optional<Instant> lastSeen(String username) {
        return Optional.ofNullable(lastSeen.get(normalizeUsername(username)));
    }

    private static Optional<Punishment> activePunishment(Map<String, Punishment> punishments, String key) {
        Punishment punishment = punishments.get(key);
        if (punishment == null) {
            return Optional.empty();
        }

        if (!punishment.activeAt(Instant.now())) {
            punishments.remove(key, punishment);
            return Optional.empty();
        }
        return Optional.of(punishment);
    }

    private static Instant expiresAt(Duration duration) {
        if (duration == null) {
            return null;
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        try {
            return Instant.now().plus(duration);
        } catch (ArithmeticException | DateTimeException exception) {
            throw new IllegalArgumentException("duration is too large", exception);
        }
    }

    private static String normalizeUsername(String username) {
        return normalize(username, "username");
    }

    private static String normalizeIp(String ipAddress) {
        return normalize(ipAddress, "ipAddress");
    }

    private static String normalize(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    public record Punishment(String reason, Instant expiresAt) {
        public Punishment {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }

        public boolean permanent() {
            return expiresAt == null;
        }

        public boolean activeAt(Instant instant) {
            Objects.requireNonNull(instant, "instant");
            return expiresAt == null || instant.isBefore(expiresAt);
        }
    }
}
