package io.github.aincraft.proxyinspector.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.aincraft.proxyinspector.CommandSupport;
import io.github.aincraft.proxyinspector.MessageService;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

public final class MessageCommand extends AdminCommand {
    private final MessageService messages;

    public MessageCommand(ProxyServer proxy, Logger logger, MessageService messages) {
        super(proxy, logger, "proxyins.command.msg");
        this.messages = messages;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String[] args = invocation.arguments();
        String message = CommandSupport.joinArguments(args, 1);
        if (args.length < 2 || message.isBlank()) {
            CommandSupport.usage(invocation.source(), "/msg <player> <message>");
            audit(invocation, "msg", args.length == 0 ? "*" : args[0], "failure", "missing message");
            return;
        }

        Player target = proxy.getPlayer(args[0]).orElse(null);
        if (target == null) {
            CommandSupport.error(invocation.source(), "That player is not online.");
            audit(invocation, "msg", args[0], "failure", "player offline");
            return;
        }

        String sender = CommandSupport.sourceName(invocation.source());
        target.sendMessage(Component.text("[PM] " + sender + " -> you: " + message, NamedTextColor.LIGHT_PURPLE));
        invocation.source().sendMessage(Component.text("[PM] you -> " + target.getUsername() + ": " + message, NamedTextColor.LIGHT_PURPLE));
        if (invocation.source() instanceof Player sourcePlayer) {
            messages.rememberConversation(sourcePlayer, target);
        }
        audit(invocation, "msg", target.getUsername(), "success", message);
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return invocation.arguments().length <= 1 ? suggestPlayers(invocation) : List.of();
    }
}
