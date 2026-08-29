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

public final class ReplyCommand extends AdminCommand {
    private final MessageService messages;

    public ReplyCommand(ProxyServer proxy, Logger logger, MessageService messages) {
        super(proxy, logger, "proxyins.command.reply");
        this.messages = messages;
    }

    @Override
    public void execute(SimpleCommand.Invocation invocation) {
        String message = CommandSupport.joinArguments(invocation.arguments(), 0);
        if (message.isBlank()) {
            CommandSupport.usage(invocation.source(), "/reply <message>");
            audit(invocation, "reply", "*", "failure", "missing message");
            return;
        }
        if (!(invocation.source() instanceof Player sender)) {
            CommandSupport.error(invocation.source(), "Only players can use /reply.");
            audit(invocation, "reply", "*", "failure", "console source");
            return;
        }

        Player target = messages.replyTarget(sender, proxy).orElse(null);
        if (target == null) {
            CommandSupport.error(invocation.source(), "You have no active conversation.");
            audit(invocation, "reply", "*", "failure", "no active conversation");
            return;
        }

        target.sendMessage(Component.text("[PM] " + sender.getUsername() + " -> you: " + message, NamedTextColor.LIGHT_PURPLE));
        sender.sendMessage(Component.text("[PM] you -> " + target.getUsername() + ": " + message, NamedTextColor.LIGHT_PURPLE));
        messages.rememberConversation(sender, target);
        audit(invocation, "reply", target.getUsername(), "success", message);
    }

    @Override
    public List<String> suggest(SimpleCommand.Invocation invocation) {
        return List.of();
    }
}
