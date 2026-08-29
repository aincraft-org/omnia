package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class KickCommand extends AdminCommand {
    private final boolean all;

    public KickCommand(ProxyServer proxy, Logger logger, boolean all) {
        super(proxy, logger, all ? "proxyins.command.kickall" : "proxyins.command.kick");
        this.all = all;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        if (all) {
            executeAll(invocation);
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            CommandSupport.usage(invocation.source(), "/kick <player> [reason]");
            return;
        }

        Player target = proxy.getPlayer(args[0]).orElse(null);
        if (target == null) {
            CommandSupport.error(invocation.source(), "That player is not online.");
            audit(invocation, "kick", args[0], "failure", "player offline");
            return;
        }

        String reason = CommandSupport.joinArguments(args, 1);
        if (reason.isBlank()) {
            reason = "Kicked by an administrator";
        }
        target.disconnect(Component.text(reason, NamedTextColor.RED));
        CommandSupport.success(invocation.source(), "Kicked " + target.getUsername() + ".");
        audit(invocation, "kick", target.getUsername(), "success", reason);
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return all ? List.of() : suggestPlayers(invocation);
    }

    private void executeAll(SimpleCommand.Invocation invocation) {
        String reason = CommandSupport.joinArguments(invocation.arguments(), 0);
        if (reason.isBlank()) {
            reason = "Kicked by an administrator";
        }

        Player sourcePlayer = invocation.source() instanceof Player player ? player : null;
        List<Player> targets = new ArrayList<>(proxy.getAllPlayers());
        int kicked = 0;
        for (Player target : targets) {
            if (sourcePlayer != null && target.getUniqueId().equals(sourcePlayer.getUniqueId())) {
                continue;
            }
            target.disconnect(Component.text(reason, NamedTextColor.RED));
            kicked++;
        }

        CommandSupport.success(invocation.source(), "Kicked " + kicked + " player(s).");
        audit(invocation, "kickall", "*", "success", reason + "; count=" + kicked);
    }
}
