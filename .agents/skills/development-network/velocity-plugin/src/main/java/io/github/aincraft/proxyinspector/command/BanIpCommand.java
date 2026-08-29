package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import io.github.aincraft.proxyinspector.PunishmentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class BanIpCommand extends AdminCommand {
    private final PunishmentService punishments;
    private final boolean temporary;

    public BanIpCommand(ProxyServer proxy, Logger logger, PunishmentService punishments, boolean temporary) {
        super(proxy, logger, temporary ? "proxyins.command.tempbanip" : "proxyins.command.banip");
        this.punishments = punishments;
        this.temporary = temporary;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        String usage = temporary
                ? "/tempbanip <player-or-ip> <duration> [reason]"
                : "/banip <player-or-ip> [duration] [reason]";
        CommandSupport.PunishmentArguments parsed = CommandSupport.parsePunishmentArguments(
                args,
                temporary,
                "IP-banned by an administrator"
        ).orElse(null);
        if (parsed == null) {
            CommandSupport.usage(invocation.source(), usage);
            audit(invocation, temporary ? "tempbanip" : "banip", args.length == 0 ? "*" : args[0], "failure", "invalid arguments");
            return;
        }

        Optional<String> parsedIp = CommandSupport.parseIpLiteral(parsed.target());
        String ipAddress = parsedIp.orElse(null);
        Player namedTarget = null;
        if (ipAddress == null) {
            namedTarget = proxy.getPlayer(parsed.target()).orElse(null);
            if (namedTarget == null) {
                CommandSupport.error(invocation.source(), "Use an online player or a literal IP address.");
                audit(invocation, temporary ? "tempbanip" : "banip", parsed.target(), "failure", "target unavailable");
                return;
            }
            ipAddress = CommandSupport.playerIp(namedTarget).orElse(null);
            if (ipAddress == null) {
                CommandSupport.error(invocation.source(), "That player has no usable IP address.");
                audit(invocation, temporary ? "tempbanip" : "banip", parsed.target(), "failure", "IP unavailable");
                return;
            }
        }

        PunishmentService.Punishment punishment = punishments.banIp(
                ipAddress,
                parsed.duration(),
                parsed.reason()
        );
        disconnectMatchingPlayers(ipAddress, punishment, namedTarget);

        CommandSupport.success(invocation.source(),
                "IP-banned " + ipAddress + " " + CommandSupport.formatPunishment(punishment) + ".");
        audit(invocation, temporary ? "tempbanip" : "banip", ipAddress, "success", parsed.reason());
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return invocation.arguments().length <= 1 ? suggestPlayers(invocation) : List.of();
    }

    private void disconnectMatchingPlayers(
            String ipAddress,
            PunishmentService.Punishment punishment,
            Player namedTarget
    ) {
        List<Player> matchingPlayers = new ArrayList<>();
        for (Player player : proxy.getAllPlayers()) {
            if (player == namedTarget || CommandSupport.playerIp(player).map(ipAddress::equals).orElse(false)) {
                matchingPlayers.add(player);
            }
        }
        Component message = Component.text(
                "Your IP address was banned " + CommandSupport.formatPunishment(punishment)
                        + ". Reason: " + punishment.reason(),
                NamedTextColor.RED
        );
        matchingPlayers.forEach(player -> player.disconnect(message));
    }
}
