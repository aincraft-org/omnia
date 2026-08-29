package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class WarnCommand extends AdminCommand {
    public WarnCommand(ProxyServer proxy, Logger logger) {
        super(proxy, logger, "proxyins.command.warn");
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        String reason = CommandSupport.joinArguments(args, 1);
        if (args.length < 2 || reason.isBlank()) {
            CommandSupport.usage(invocation.source(), "/warn <player> <reason>");
            audit(invocation, "warn", args.length == 0 ? "*" : args[0], "failure", "missing reason");
            return;
        }

        Player target = proxy.getPlayer(args[0]).orElse(null);
        if (target == null) {
            CommandSupport.error(invocation.source(), "That player is not online.");
            audit(invocation, "warn", args[0], "failure", "player offline");
            return;
        }

        target.sendMessage(Component.text("Warning: " + reason, NamedTextColor.RED));
        CommandSupport.success(invocation.source(), "Warned " + target.getUsername() + ".");
        audit(invocation, "warn", target.getUsername(), "success", reason);
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return invocation.arguments().length <= 1 ? suggestPlayers(invocation) : List.of();
    }
}
