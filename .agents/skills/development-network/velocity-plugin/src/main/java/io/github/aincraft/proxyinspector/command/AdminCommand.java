package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import org.slf4j.Logger;
import io.github.aincraft.proxyinspector.CommandSupport;

abstract class AdminCommand implements SimpleCommand {
    protected final ProxyServer proxy;
    protected final Logger logger;
    private final String permission;

    protected AdminCommand(ProxyServer proxy, Logger logger, String permission) {
        this.proxy = proxy;
        this.logger = logger;
        this.permission = permission;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(permission);
    }

    protected void audit(Invocation invocation, String action, String target, String result, String detail) {
        CommandSupport.audit(logger, invocation, action, target, result, detail);
    }

    protected List<String> suggestPlayers(Invocation invocation) {
        String[] args = invocation.arguments();
        String prefix = args.length == 0 ? "" : args[args.length - 1];
        return CommandSupport.suggestPlayers(proxy, prefix);
    }

    protected List<String> suggestServers(Invocation invocation) {
        String[] args = invocation.arguments();
        String prefix = args.length == 0 ? "" : args[args.length - 1];
        return CommandSupport.suggestServers(proxy, prefix);
    }
}
