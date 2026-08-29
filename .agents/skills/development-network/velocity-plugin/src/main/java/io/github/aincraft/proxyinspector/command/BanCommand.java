package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import io.github.aincraft.proxyinspector.PunishmentService;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class BanCommand extends AdminCommand {
    private final PunishmentService punishments;
    private final boolean temporary;

    public BanCommand(ProxyServer proxy, Logger logger, PunishmentService punishments, boolean temporary) {
        super(proxy, logger, temporary ? "proxyins.command.tempban" : "proxyins.command.ban");
        this.punishments = punishments;
        this.temporary = temporary;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        String usage = temporary
                ? "/tempban <player> <duration> [reason]"
                : "/ban <player> [duration] [reason]";
        CommandSupport.PunishmentArguments parsed = CommandSupport.parsePunishmentArguments(
                args,
                temporary,
                "Banned by an administrator"
        ).orElse(null);
        if (parsed == null) {
            CommandSupport.usage(invocation.source(), usage);
            audit(invocation, temporary ? "tempban" : "ban", args.length == 0 ? "*" : args[0], "failure", "invalid arguments");
            return;
        }

        PunishmentService.Punishment punishment = punishments.banUsername(
                parsed.target(),
                parsed.duration(),
                parsed.reason()
        );
        Player onlineTarget = proxy.getPlayer(parsed.target()).orElse(null);
        if (onlineTarget != null) {
            onlineTarget.disconnect(Component.text(
                    "Banned " + CommandSupport.formatPunishment(punishment) + ". Reason: " + parsed.reason(),
                    NamedTextColor.RED
            ));
        }

        CommandSupport.success(invocation.source(),
                "Banned " + parsed.target() + " " + CommandSupport.formatPunishment(punishment) + ".");
        audit(invocation, temporary ? "tempban" : "ban", parsed.target(), "success", parsed.reason());
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return invocation.arguments().length <= 1 ? suggestPlayers(invocation) : List.of();
    }
}
