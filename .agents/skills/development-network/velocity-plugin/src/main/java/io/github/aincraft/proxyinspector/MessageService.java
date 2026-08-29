package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MessageService {
    private final ConcurrentMap<UUID, UUID> lastRecipients = new ConcurrentHashMap<>();

    public void rememberConversation(Player first, Player second) {
        lastRecipients.put(first.getUniqueId(), second.getUniqueId());
        lastRecipients.put(second.getUniqueId(), first.getUniqueId());
    }

    public Optional<Player> replyTarget(Player sender, ProxyServer proxy) {
        UUID recipientId = lastRecipients.get(sender.getUniqueId());
        if (recipientId == null) {
            return Optional.empty();
        }

        Optional<Player> recipient = proxy.getPlayer(recipientId);
        if (recipient.isEmpty()) {
            lastRecipients.remove(sender.getUniqueId(), recipientId);
        }
        return recipient;
    }
}
