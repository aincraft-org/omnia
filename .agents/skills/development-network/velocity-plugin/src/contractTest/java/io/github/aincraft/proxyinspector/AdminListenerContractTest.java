package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;

public final class AdminListenerContractTest {
    private AdminListenerContractTest() {
    }

    public static void main(String[] args) throws Exception {
        deniesBannedIpBeforeAuthentication();
        deniesBannedUsernameBeforeServerSelection();
        recordsLastSeenOnDisconnect();
        recordsLastSeenBeforeServerSelection();
        ignoresIncompleteLogin();
    }

    private static void deniesBannedIpBeforeAuthentication() throws Exception {
        PunishmentService service = new PunishmentService();
        service.banIp("127.0.0.1", null, "ip test");
        AdminListener listener = new AdminListener(service);
        PreLoginEvent event = new PreLoginEvent(connection("127.0.0.1"), "Player", null);

        listener.onPreLogin(event);

        require(!event.getResult().isAllowed(), "banned IP must be denied");
        require(event.getResult().getReasonComponent().isPresent(), "IP denial needs a reason");
    }

    private static void deniesBannedUsernameBeforeServerSelection() throws Exception {
        PunishmentService service = new PunishmentService();
        service.banUsername("Player", null, "name test");
        AdminListener listener = new AdminListener(service);
        PreLoginEvent event = new PreLoginEvent(connection("127.0.0.2"), "player", null);

        listener.onPreLogin(event);

        require(!event.getResult().isAllowed(), "banned username must be denied");
        require(event.getResult().getReasonComponent().isPresent(), "username denial needs a reason");
    }

    private static void recordsLastSeenOnDisconnect() {
        PunishmentService service = new PunishmentService();
        AdminListener listener = new AdminListener(service);
        Player player = player("Player");

        listener.onDisconnect(new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        Optional<Instant> lastSeen = service.lastSeen("player");
        require(lastSeen.isPresent(), "disconnect must record last seen");
    }

    private static void recordsLastSeenBeforeServerSelection() {
        PunishmentService service = new PunishmentService();
        AdminListener listener = new AdminListener(service);

        listener.onDisconnect(new DisconnectEvent(
                player("EarlyPlayer"),
                DisconnectEvent.LoginStatus.PRE_SERVER_JOIN
        ));

        require(service.lastSeen("earlyplayer").isPresent(), "pre-server disconnect must record last seen");
    }

    private static void ignoresIncompleteLogin() {
        PunishmentService service = new PunishmentService();
        AdminListener listener = new AdminListener(service);

        listener.onDisconnect(new DisconnectEvent(
                player("IncompletePlayer"),
                DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE
        ));

        require(service.lastSeen("incompleteplayer").isEmpty(), "incomplete login must not record last seen");
    }

    private static InboundConnection connection(String address) throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress(InetAddress.getByName(address), 25565);
        return proxy(InboundConnection.class, (method, returnType) -> switch (method) {
            case "getRemoteAddress" -> remoteAddress;
            default -> defaultValue(InboundConnection.class, method);
        });
    }

    private static Player player(String username) {
        return proxy(Player.class, (method, returnType) -> switch (method) {
            case "getUsername" -> username;
            default -> defaultValue(Player.class, method);
        });
    }

    private static <T> T proxy(Class<T> type, ValueProvider provider) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> provider.value(method.getName(), method.getReturnType())
        ));
    }

    private static Object defaultValue(Class<?> owner, String method) {
        throw new AssertionError("Unexpected " + owner.getSimpleName() + " method: " + method);
    }

    private interface ValueProvider {
        Object value(String method, Class<?> returnType);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
