package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class CommandSupport {
    private static final Pattern IPV4_LITERAL = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+(?:%[A-Za-z0-9_.-]+)?");
    private static final Comparator<Player> BY_USERNAME = Comparator.comparing(
            Player::getUsername,
            String.CASE_INSENSITIVE_ORDER
    );
    private static final Comparator<RegisteredServer> BY_SERVER_NAME = Comparator.comparing(
            server -> server.getServerInfo().getName(),
            String.CASE_INSENSITIVE_ORDER
    );

    private CommandSupport() {
    }

    public static Optional<PunishmentArguments> parsePunishmentArguments(
            String[] args,
            boolean durationRequired,
            String defaultReason
    ) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(defaultReason, "defaultReason");
        if (args.length == 0 || args[0].isBlank()) {
            return Optional.empty();
        }

        Duration duration = null;
        int reasonStart = 1;
        if (args.length > 1) {
            Optional<DurationParser.DurationSpec> parsedDuration = DurationParser.parse(args[1]);
            if (parsedDuration.isPresent()) {
                DurationParser.DurationSpec spec = parsedDuration.get();
                if (durationRequired && spec.permanent()) {
                    return Optional.empty();
                }
                duration = spec.duration();
                reasonStart = 2;
            } else if (durationRequired) {
                return Optional.empty();
            }
        } else if (durationRequired) {
            return Optional.empty();
        }

        String reason = joinArguments(args, reasonStart);
        if (reason.isBlank()) {
            reason = defaultReason;
        }
        return Optional.of(new PunishmentArguments(args[0], duration, reason));
    }

    public static String sourceName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : "console";
    }

    public static void audit(
            Logger logger,
            SimpleCommand.Invocation invocation,
            String action,
            String target,
            String result,
            String detail
    ) {
        logger.info(
                "Admin action {} by {} on {}: {} ({})",
                action,
                sourceName(invocation.source()),
                target,
                result,
                detail
        );
    }

    public static void error(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    public static void success(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    public static void info(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.AQUA));
    }

    public static void usage(CommandSource source, String message) {
        source.sendMessage(Component.text("Usage: " + message, NamedTextColor.YELLOW));
    }

    public static List<String> suggestPlayers(ProxyServer proxy, String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return proxy.getAllPlayers().stream()
                .sorted(BY_USERNAME)
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }

    public static List<String> suggestServers(ProxyServer proxy, String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return proxy.getAllServers().stream()
                .sorted(BY_SERVER_NAME)
                .map(server -> server.getServerInfo().getName())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }

    public static String currentServer(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("none");
    }

    public static Optional<String> playerIp(Player player) {
        return Optional.ofNullable(player.getRemoteAddress())
                .map(CommandSupport::remoteIp)
                .flatMap(CommandSupport::parseIpLiteral);
    }

    public static Optional<String> parseIpLiteral(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String value = candidate.trim();
        if (value.startsWith("[") || value.endsWith("]")) {
            if (!(value.startsWith("[") && value.endsWith("]"))) {
                return Optional.empty();
            }
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty()) {
            return Optional.empty();
        }
        boolean ipv4 = IPV4_LITERAL.matcher(value).matches();
        boolean ipv6 = value.indexOf(':') >= 0 && IPV6_LITERAL.matcher(value).matches();
        if (!ipv4 && !ipv6) {
            return Optional.empty();
        }
        if (ipv4 && Arrays.stream(value.split("\\.", -1)).mapToInt(Integer::parseInt).anyMatch(octet -> octet > 255)) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(value).getHostAddress().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static String formatPunishment(PunishmentService.Punishment punishment) {
        return punishment.permanent()
                ? "permanently"
                : "until " + punishment.expiresAt();
    }

    public static String joinArguments(String[] args, int start) {
        if (start >= args.length) {
            return "";
        }
        return Arrays.stream(args, start, args.length)
                .collect(Collectors.joining(" "))
                .trim();
    }

    private static String remoteIp(InetSocketAddress remoteAddress) {
        InetAddress address = remoteAddress.getAddress();
        return address == null ? remoteAddress.getHostString() : address.getHostAddress();
    }

    public record PunishmentArguments(String target, Duration duration, String reason) {
    }
}
