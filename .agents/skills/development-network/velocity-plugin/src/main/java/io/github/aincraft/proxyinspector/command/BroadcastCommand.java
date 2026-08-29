package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class BroadcastCommand extends AdminCommand {
    public BroadcastCommand(ProxyServer proxy, Logger logger) {
        super(proxy, logger, "proxyins.command.broadcast");
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String message = CommandSupport.joinArguments(invocation.arguments(), 0);
        if (message.isBlank()) {
            CommandSupport.usage(invocation.source(), "/broadcast <message>");
            audit(invocation, "broadcast", "*", "failure", "missing message");
            return;
        }

        Component broadcast = Component.text("[Broadcast] " + message, NamedTextColor.GOLD);
        proxy.getAllPlayers().forEach(player -> player.sendMessage(broadcast));
        CommandSupport.success(invocation.source(), "Broadcast sent to " + proxy.getPlayerCount() + " player(s).");
        audit(invocation, "broadcast", "*", "success", message);
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return List.of();
    }
}
