package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

public final class PlayerListCommand extends AdminCommand {
    public PlayerListCommand(ProxyServer proxy, Logger logger) {
        super(proxy, logger, "proxyins.command.list");
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        if (invocation.arguments().length != 0) {
            CommandSupport.usage(invocation.source(), "/list");
            audit(invocation, "list", "*", "failure", "unexpected arguments");
            return;
        }

        Map<String, List<String>> playersByServer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Player player : proxy.getAllPlayers()) {
            playersByServer.computeIfAbsent(CommandSupport.currentServer(player), ignored -> new ArrayList<>())
                    .add(player.getUsername());
        }
        playersByServer.values().forEach(names -> names.sort(String.CASE_INSENSITIVE_ORDER));

        CommandSupport.info(invocation.source(), "Online players (" + proxy.getPlayerCount() + "):");
        for (Map.Entry<String, List<String>> entry : playersByServer.entrySet()) {
            invocation.source().sendMessage(
                    net.kyori.adventure.text.Component.text(
                            " - " + entry.getKey() + ": " + String.join(", ", entry.getValue())
                    )
            );
        }
        if (playersByServer.isEmpty()) {
            invocation.source().sendMessage(
                    net.kyori.adventure.text.Component.text(" - none")
            );
        }
        audit(invocation, "list", "*", "success", "count=" + proxy.getPlayerCount());
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return List.of();
    }
}
