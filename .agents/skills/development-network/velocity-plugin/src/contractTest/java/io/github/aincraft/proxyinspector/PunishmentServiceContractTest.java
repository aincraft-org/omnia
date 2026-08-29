package io.github.aincraft.proxyinspector;

import java.time.Duration;
import java.time.Instant;

public final class PunishmentServiceContractTest {
    private PunishmentServiceContractTest() {
    }

    public static void main(String[] args) {
        storesCaseInsensitiveUsernamePunishments();
        expiresFinitePunishments();
        rejectsUnrepresentableExpiryDurations();
    }

    private static void storesCaseInsensitiveUsernamePunishments() {
        PunishmentService service = new PunishmentService();
        service.banUsername("Player", null, "test reason");

        PunishmentService.Punishment punishment = service.usernameBan("player").orElseThrow();
        require(punishment.reason().equals("test reason"), "reason mismatch");
        require(service.isUsernameBanned("PLAYER"), "username lookup must be case-insensitive");
        require(service.unbanUsername("pLaYeR"), "unban must remove the username");
        require(!service.isUsernameBanned("player"), "unban must clear the username");
    }

    private static void expiresFinitePunishments() {
        PunishmentService service = new PunishmentService();
        service.banUsername("Player", Duration.ofSeconds(1), "temporary");

        PunishmentService.Punishment punishment = service.usernameBan("player").orElseThrow();
        require(punishment.activeAt(Instant.now()), "fresh punishment must be active");
        require(!punishment.activeAt(Instant.now().plusSeconds(2)), "expired punishment must be inactive");
    }

    private static void rejectsUnrepresentableExpiryDurations() {
        boolean rejected = false;
        try {
            new PunishmentService().banUsername(
                    "Player",
                    Duration.ofSeconds(Instant.MAX.getEpochSecond()),
                    "too long"
            );
        } catch (IllegalArgumentException exception) {
            rejected = true;
        }
        require(rejected, "unrepresentable expiry duration must be rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
