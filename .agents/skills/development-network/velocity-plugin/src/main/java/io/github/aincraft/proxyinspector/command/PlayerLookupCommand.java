package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import io.github.aincraft.proxyinspector.PunishmentService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class PlayerLookupCommand extends AdminCommand {
    public enum Kind {
        FIND,
        WHOIS,
        SEEN,
        PING
    }

    private final PunishmentService punishments;
    private final Kind kind;

    public PlayerLookupCommand(
            ProxyServer proxy,
            Logger logger,
            PunishmentService punishments,
            Kind kind
    ) {
        super(proxy, logger, "proxyins.command." + kind.name().toLowerCase());
        this.punishments = punishments;
        this.kind = kind;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length != 1 || args[0].isBlank()) {
            CommandSupport.usage(invocation.source(), usage());
            audit(invocation, kind.name().toLowerCase(), args.length == 0 ? "*" : args[0], "failure", "invalid arguments");
            return;
        }

        if (kind == Kind.SEEN) {
            executeSeen(invocation, args[0]);
            return;
        }

        Player target = proxy.getPlayer(args[0]).orElse(null);
        if (target == null) {
            CommandSupport.error(invocation.source(), "That player is not online.");
            audit(invocation, kind.name().toLowerCase(), args[0], "failure", "player offline");
            return;
        }

        switch (kind) {
            case FIND -> {
                String server = CommandSupport.currentServer(target);
                CommandSupport.info(invocation.source(), target.getUsername() + " is connected to " + server + ".");
                audit(invocation, "find", target.getUsername(), "success", "server=" + server);
            }
            case PING -> {
                CommandSupport.info(invocation.source(), target.getUsername() + " has " + target.getPing() + " ms ping.");
                audit(invocation, "ping", target.getUsername(), "success", "ping=" + target.getPing());
            }
            case WHOIS -> executeWhois(invocation, target);
            case SEEN -> throw new IllegalStateException("seen is handled before player lookup");
        }
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return invocation.arguments().length <= 1 ? suggestPlayers(invocation) : List.of();
    }

    private void executeSeen(SimpleCommand.Invocation invocation, String username) {
        Player online = proxy.getPlayer(username).orElse(null);
        if (online != null) {
            CommandSupport.info(invocation.source(), online.getUsername() + " is currently online.");
            audit(invocation, "seen", online.getUsername(), "success", "online");
            return;
        }

        Optional<Instant> lastSeen = punishments.lastSeen(username);
        if (lastSeen.isEmpty()) {
            CommandSupport.error(invocation.source(), "No last-seen record exists for that player.");
            audit(invocation, "seen", username, "failure", "no record");
            return;
        }
        CommandSupport.info(invocation.source(), username + " was last seen at " + lastSeen.get() + ".");
        audit(invocation, "seen", username, "success", "lastSeen=" + lastSeen.get());
    }

    private void executeWhois(SimpleCommand.Invocation invocation, Player target) {
        String ip = CommandSupport.playerIp(target).orElse("unknown");
        Component details = Component.text(
                target.getUsername()
                        + " | UUID: " + target.getUniqueId()
                        + " | Server: " + CommandSupport.currentServer(target)
                        + " | Ping: " + target.getPing() + " ms"
                        + " | IP: " + ip
        );
        invocation.source().sendMessage(details);
        audit(invocation, "whois", target.getUsername(), "success", "server=" + CommandSupport.currentServer(target));
    }

    private String usage() {
        return switch (kind) {
            case FIND -> "/find <player>";
            case WHOIS -> "/whois <player>";
            case SEEN -> "/seen <player>";
            case PING -> "/ping <player>";
        };
    }
}
