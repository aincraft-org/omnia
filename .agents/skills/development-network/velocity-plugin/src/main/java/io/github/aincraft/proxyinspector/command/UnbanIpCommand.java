package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import io.github.aincraft.proxyinspector.PunishmentService;
import java.util.List;
import org.slf4j.Logger;

public final class UnbanIpCommand extends AdminCommand {
    private final PunishmentService punishments;

    public UnbanIpCommand(ProxyServer proxy, Logger logger, PunishmentService punishments) {
        super(proxy, logger, "proxyins.command.unbanip");
        this.punishments = punishments;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length != 1) {
            CommandSupport.usage(invocation.source(), "/unbanip <ip>");
            audit(invocation, "unbanip", args.length == 0 ? "*" : args[0], "failure", "invalid arguments");
            return;
        }

        String ipAddress = CommandSupport.parseIpLiteral(args[0]).orElse(null);
        if (ipAddress == null) {
            CommandSupport.error(invocation.source(), "Enter a literal IPv4 or IPv6 address.");
            audit(invocation, "unbanip", args[0], "failure", "invalid IP address");
            return;
        }
        if (!punishments.unbanIp(ipAddress)) {
            CommandSupport.error(invocation.source(), "That IP address is not banned.");
            audit(invocation, "unbanip", ipAddress, "failure", "not banned");
            return;
        }

        CommandSupport.success(invocation.source(), "Unbanned IP address " + ipAddress + ".");
        audit(invocation, "unbanip", ipAddress, "success", "removed IP ban");
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return List.of();
    }
}
