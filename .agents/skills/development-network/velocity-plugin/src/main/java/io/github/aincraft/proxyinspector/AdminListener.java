package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import net.kyori.adventure.text.Component;

final class AdminListener {
    private final PunishmentService punishments;

    AdminListener(PunishmentService punishments) {
        this.punishments = punishments;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String remoteIp = remoteIp(event.getConnection().getRemoteAddress());
        if (remoteIp != null) {
            Optional<PunishmentService.Punishment> ipBan = punishments.ipBan(remoteIp);
            if (ipBan.isPresent()) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        denial("Your IP address is banned", ipBan.get())
                ));
                return;
            }
        }

        Optional<PunishmentService.Punishment> usernameBan = punishments.usernameBan(event.getUsername());
        if (usernameBan.isPresent()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    denial("Your username is banned", usernameBan.get())
            ));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (event.getLoginStatus() == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                || event.getLoginStatus() == DisconnectEvent.LoginStatus.PRE_SERVER_JOIN) {
            punishments.recordLastSeen(event.getPlayer().getUsername(), Instant.now());
        }
    }

    private static Component denial(String prefix, PunishmentService.Punishment punishment) {
        String expiry = punishment.permanent()
                ? " permanently"
                : " until " + punishment.expiresAt();
        return Component.text(prefix + expiry + ". Reason: " + punishment.reason());
    }

    private static String remoteIp(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        InetAddress address = remoteAddress.getAddress();
        String host = address == null ? remoteAddress.getHostString() : address.getHostAddress();
        return host == null || host.isBlank() ? null : host;
    }
}
