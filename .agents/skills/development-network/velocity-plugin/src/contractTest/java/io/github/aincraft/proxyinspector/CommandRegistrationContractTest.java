package io.github.aincraft.proxyinspector;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

public final class CommandRegistrationContractTest {
    private CommandRegistrationContractTest() {
    }

    public static void main(String[] args) {
        CapturingCommandManager commandManager = new CapturingCommandManager();
        EventManager eventManager = proxy(EventManager.class, (ignored, method, arguments) -> defaultValue(method.getReturnType()));
        ProxyServer proxy = proxy(ProxyServer.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "getCommandManager" -> commandManager.value();
            case "getEventManager" -> eventManager;
            default -> defaultValue(method.getReturnType());
        });
        Logger logger = proxy(Logger.class, (ignored, method, arguments) -> defaultValue(method.getReturnType()));

        ProxyInspectorPlugin plugin = new ProxyInspectorPlugin(proxy, logger);
        plugin.onProxyInitialize(null);

        Map<String, Set<String>> expected = expectedAliases();
        require(commandManager.registrations.size() == expected.size(), "registered command count mismatch");
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            Registration registration = commandManager.registrations.get(entry.getKey());
            require(registration != null, "missing command: " + entry.getKey());
            require(Set.copyOf(registration.aliases()).equals(entry.getValue()), "aliases mismatch for " + entry.getKey());
            assertPermission(registration.command(), expectedPermission(entry.getKey()));
        }
    }

    private static String expectedPermission(String command) {
        return switch (command) {
            case "servers" -> "velocity.command.glist";
            case "plugins" -> "velocity.command.plugins";
            default -> "proxyins.command." + command;
        };
    }

    private static void assertPermission(SimpleCommand command, String expectedPermission) {
        String[] requestedPermission = {null};
        CommandSource source = proxy(CommandSource.class, (ignored, method, arguments) -> {
            if (method.getName().equals("hasPermission")) {
                requestedPermission[0] = (String) arguments[0];
                return true;
            }
            return defaultValue(method.getReturnType());
        });
        SimpleCommand.Invocation invocation = proxy(SimpleCommand.Invocation.class, (ignored, method, arguments) -> {
            if (method.getName().equals("source")) {
                return source;
            }
            return defaultValue(method.getReturnType());
        });

        require(command.hasPermission(invocation), "command permission check must delegate to source");
        require(expectedPermission.equals(requestedPermission[0]), "permission mismatch: " + expectedPermission);
    }

    private static Map<String, Set<String>> expectedAliases() {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("servers", Set.of("serverlist"));
        expected.put("plugins", Set.of("pluginlist"));
        expected.put("kick", Set.of());
        expected.put("kickall", Set.of());
        expected.put("ban", Set.of());
        expected.put("tempban", Set.of());
        expected.put("unban", Set.of());
        expected.put("banip", Set.of("ipban"));
        expected.put("tempbanip", Set.of());
        expected.put("unbanip", Set.of("unipban"));
        expected.put("warn", Set.of());
        expected.put("broadcast", Set.of("bc", "announce"));
        expected.put("find", Set.of("locate"));
        expected.put("whois", Set.of("playerinfo", "pinfo"));
        expected.put("seen", Set.of());
        expected.put("ping", Set.of());
        expected.put("list", Set.of("who", "online"));
        expected.put("msg", Set.of("tell", "w", "whisper", "pm"));
        expected.put("reply", Set.of("r"));
        return Collections.unmodifiableMap(expected);
    }

    private static final class CapturingCommandManager {
        private final Map<CommandMeta, Metadata> metadata = new IdentityHashMap<>();
        private final Map<String, Registration> registrations = new LinkedHashMap<>();
        private final CommandManager value = proxy(CommandManager.class, (ignored, method, arguments) -> handle(method, arguments));

        private Object handle(java.lang.reflect.Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "metaBuilder" -> builder((String) arguments[0]);
                case "register" -> {
                    CommandMeta commandMeta = (CommandMeta) arguments[0];
                    Metadata definition = metadata.get(commandMeta);
                    require(definition != null, "registered metadata was not built by this manager");
                    registrations.put(definition.name(), new Registration(definition.aliases(), (SimpleCommand) arguments[1]));
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        private CommandMeta.Builder builder(String name) {
            List<String> aliases = new ArrayList<>();
            CommandMeta.Builder[] holder = new CommandMeta.Builder[1];
            holder[0] = proxy(CommandMeta.Builder.class, (ignored, method, arguments) -> switch (method.getName()) {
                case "aliases" -> {
                    aliases.addAll(Arrays.asList((String[]) arguments[0]));
                    yield holder[0];
                }
                case "plugin" -> holder[0];
                case "build" -> {
                    CommandMeta commandMeta = proxy(CommandMeta.class, (metaProxy, metaMethod, metaArguments) -> switch (metaMethod.getName()) {
                        case "getAliases" -> List.copyOf(aliases);
                        case "getHints" -> List.of();
                        case "getPlugin" -> null;
                        default -> defaultValue(metaMethod.getReturnType());
                    });
                    metadata.put(commandMeta, new Metadata(name, List.copyOf(aliases)));
                    yield commandMeta;
                }
                default -> defaultValue(method.getReturnType());
            });
            return holder[0];
        }

        private CommandManager value() {
            return value;
        }
    }

    private record Metadata(String name, List<String> aliases) {
    }

    private record Registration(List<String> aliases, SimpleCommand command) {
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler
        ));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
