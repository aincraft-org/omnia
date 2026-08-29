package io.github.aincraft.proxyinspector;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.command.BanCommand;
import io.github.aincraft.proxyinspector.command.BanIpCommand;
import io.github.aincraft.proxyinspector.command.BroadcastCommand;
import io.github.aincraft.proxyinspector.command.KickCommand;
import io.github.aincraft.proxyinspector.command.MessageCommand;
import io.github.aincraft.proxyinspector.command.PlayerListCommand;
import io.github.aincraft.proxyinspector.command.PlayerLookupCommand;
import io.github.aincraft.proxyinspector.command.ReplyCommand;
import io.github.aincraft.proxyinspector.command.UnbanCommand;
import io.github.aincraft.proxyinspector.command.UnbanIpCommand;
import io.github.aincraft.proxyinspector.command.WarnCommand;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;

public final class ProxyInspectorPlugin {
    private static final Set<String> PROXY_ADMIN_PERMISSIONS = Set.of(
            "velocity.command.*",
            "velocity.command.info",
            "velocity.command.plugins",
            "velocity.command.reload",
            "velocity.command.dump",
            "velocity.command.heap",
            "velocity.command.glist",
            "velocity.command.send",
            "proxyins.command.*"
    );
    private final ProxyServer proxy;
    private final Logger logger;
    private final Set<String> adminUsers;
    private final PunishmentService punishments;
    private final MessageService messages;

    @Inject
    public ProxyInspectorPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
        this.adminUsers = configuredAdminUsers();
        this.punishments = new PunishmentService();
        this.messages = new MessageService();
    }

    @Subscribe
    public void onPermissionsSetup(PermissionsSetupEvent event) {
        if (event.getSubject() instanceof Player player && adminUsers.contains(player.getUsername())) {
            event.setProvider(subject -> permission ->
                    hasConfiguredAdminPermission(permission)
                            ? Tristate.TRUE
                            : Tristate.UNDEFINED);
        }
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        CommandManager commands = proxy.getCommandManager();
        proxy.getEventManager().register(this, new AdminListener(punishments));

        register(commands, "servers", new ServerListCommand(proxy), "serverlist");
        register(commands, "plugins", new PluginListCommand(proxy), "pluginlist");
        register(commands, "kick", new KickCommand(proxy, logger, false));
        register(commands, "kickall", new KickCommand(proxy, logger, true));
        register(commands, "ban", new BanCommand(proxy, logger, punishments, false));
        register(commands, "tempban", new BanCommand(proxy, logger, punishments, true));
        register(commands, "unban", new UnbanCommand(proxy, logger, punishments));
        register(commands, "banip", new BanIpCommand(proxy, logger, punishments, false), "ipban");
        register(commands, "tempbanip", new BanIpCommand(proxy, logger, punishments, true));
        register(commands, "unbanip", new UnbanIpCommand(proxy, logger, punishments), "unipban");
        register(commands, "warn", new WarnCommand(proxy, logger));
        register(commands, "broadcast", new BroadcastCommand(proxy, logger), "bc", "announce");
        register(commands, "find", new PlayerLookupCommand(proxy, logger, punishments, PlayerLookupCommand.Kind.FIND), "locate");
        register(commands, "whois", new PlayerLookupCommand(proxy, logger, punishments, PlayerLookupCommand.Kind.WHOIS), "playerinfo", "pinfo");
        register(commands, "seen", new PlayerLookupCommand(proxy, logger, punishments, PlayerLookupCommand.Kind.SEEN));
        register(commands, "ping", new PlayerLookupCommand(proxy, logger, punishments, PlayerLookupCommand.Kind.PING));
        register(commands, "list", new PlayerListCommand(proxy, logger), "who", "online");
        register(commands, "msg", new MessageCommand(proxy, logger, messages), "tell", "w", "whisper", "pm");
        register(commands, "reply", new ReplyCommand(proxy, logger, messages), "r");

        logger.info(
                "Proxy Inspector enabled: /servers, /plugins, proxy moderation, routing, lookup, messaging, and broadcast commands; proxy admin users: {}",
                adminUsers
        );
    }

    private void register(CommandManager commands, String name, SimpleCommand command, String... aliases) {
        commands.register(
                commands.metaBuilder(name)
                        .aliases(aliases)
                        .plugin(this)
                        .build(),
                command
        );
    }

    private static boolean hasConfiguredAdminPermission(String permission) {
        if (PROXY_ADMIN_PERMISSIONS.contains(permission)) {
            return true;
        }
        return (permission.startsWith("velocity.command.")
                        && PROXY_ADMIN_PERMISSIONS.contains("velocity.command.*"))
                || (permission.startsWith("proxyins.command.")
                        && PROXY_ADMIN_PERMISSIONS.contains("proxyins.command.*"));
    }

    private static Set<String> configuredAdminUsers() {
        String configured = System.getenv("DEV_USERS");
        if (configured == null || configured.isBlank()) {
            configured = "dev";
        }
        return Arrays.stream(configured.trim().split("\\s+"))
                .filter(name -> name.matches("[A-Za-z0-9_]{1,16}"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
