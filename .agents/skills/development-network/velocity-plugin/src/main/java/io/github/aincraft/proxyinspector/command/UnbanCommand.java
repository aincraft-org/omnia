package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import io.github.aincraft.proxyinspector.PunishmentService;
import java.util.List;
import org.slf4j.Logger;

public final class UnbanCommand extends AdminCommand {
    private final PunishmentService punishments;

    public UnbanCommand(ProxyServer proxy, Logger logger, PunishmentService punishments) {
        super(proxy, logger, "proxyins.command.unban");
        this.punishments = punishments;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length != 1 || args[0].isBlank()) {
            CommandSupport.usage(invocation.source(), "/unban <player>");
            audit(invocation, "unban", args.length == 0 ? "*" : args[0], "failure", "invalid arguments");
            return;
        }

        if (!punishments.unbanUsername(args[0])) {
            CommandSupport.error(invocation.source(), "That player is not banned.");
            audit(invocation, "unban", args[0], "failure", "not banned");
            return;
        }

        CommandSupport.success(invocation.source(), "Unbanned " + args[0] + ".");
        audit(invocation, "unban", args[0], "success", "removed username ban");
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return invocation.arguments().length <= 1 ? suggestPlayers(invocation) : List.of();
    }
}
