package ir.ozyrox.ctcommand;

import ir.ozyrox.ctcommand.annotation.Command;
import ir.ozyrox.ctcommand.annotation.Completer;
import ir.ozyrox.ctcommand.annotation.DefaultCommand;
import ir.ozyrox.ctcommand.annotation.SubCommand;
import ir.ozyrox.ctcommand.annotation.access.ConsoleOnly;
import ir.ozyrox.ctcommand.annotation.access.HasPermission;
import ir.ozyrox.ctcommand.annotation.access.OpOnly;
import ir.ozyrox.ctcommand.annotation.access.PlayerOnly;
import ir.ozyrox.ctcommand.models.CommandData;
import ir.ozyrox.ctcommand.models.CooldownEntry;
import ir.ozyrox.ctcommand.models.SubCommandData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class CommandManager {
    private final JavaPlugin plugin;

    private final Map<UUID, Map<String, CooldownEntry>> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, CommandData> commands = new ConcurrentHashMap<>();

    public CommandManager(JavaPlugin plugin) {
        this.plugin = plugin;
        startCooldownCleanupTask();
    }

    public void register(CommandBase instance) {

        Class<?> clazz = instance.getClass();

        if (!clazz.isAnnotationPresent(Command.class)) {
            plugin.getLogger().warning(
                    clazz.getName() + " missing @Command"
            );
            return;
        }

        Command command = clazz.getAnnotation(Command.class);


        boolean hasSubCommands = Arrays.stream(clazz.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(SubCommand.class));


        if (hasSubCommands) {
            registerWithSubCommand(instance);
        } else {
            registerSimple(instance);
        }
    }

    private void registerSimple(CommandBase instance) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(DefaultCommand.class)) continue;
            Command cmd = instance.getClass().getAnnotation(Command.class);

            PluginCommand pc = plugin.getCommand(cmd.name());
            if (pc == null) {
                plugin.getLogger().warning("Command " + cmd.name() + " is not defined in plugin.yml");
                continue;
            }

            boolean playerOnly = instance.getClass().isAnnotationPresent(PlayerOnly.class);
            boolean opOnly = instance.getClass().isAnnotationPresent(OpOnly.class);
            boolean consoleOnly = instance.getClass().isAnnotationPresent(ConsoleOnly.class);
            HasPermission[] hasPermission = instance.getClass().getAnnotationsByType(HasPermission.class);

            Method completerMethod = findCompleter(instance.getClass(), cmd.name());

            method.setAccessible(true);

            if (completerMethod != null) {
                completerMethod.setAccessible(true);
            }

            CommandData commandData = new CommandData(
                    cmd.name(),
                    method,
                    method.getParameters(),
                    completerMethod,
                    getPermissions(hasPermission),
                    playerOnly,
                    consoleOnly,
                    opOnly,
                    cmd.cooldown(),
                    Collections.emptyMap()
            );

            commands.put(cmd.name(), commandData);

            pc.setExecutor((sender, command, label, args) -> {
                CommandData data = commands.get(command.getName());
                if (data == null) return true;

                if (data.isPlayerOnly() && !(sender instanceof Player)) {
                    instance.onPlayerOnly(sender);
                    return true;
                }

                if (data.isOpOnly() && !sender.isOp()) {
                    instance.onNoPermission(sender);
                    return true;
                }

                if (data.isConsoleOnly() && !(sender instanceof ConsoleCommandSender)) {
                    instance.onConsoleOnly(sender);
                    return true;
                }

                if (!hasPermission(data.getPermissions(), sender)) {
                    instance.onNoPermission(sender);
                    return true;
                }

                if (isOnCooldown(sender, data.getName(), data.getCooldown(), instance)) {
                    return true;
                }

                invoke(instance, data.getMethod(), data.getParameters(), "", sender, args);
                return true;
            });

            if (commandData.getCompleter() != null) {
                pc.setTabCompleter((sender, command, alias, args) -> invokeCompleter(instance, commandData.getCompleter(), sender, args));
            }
        }
    }

    private void registerWithSubCommand(CommandBase instance) {
        if (!instance.getClass().isAnnotationPresent(Command.class)) return;
        Command rootCommand = instance.getClass().getAnnotation(Command.class);
        PluginCommand pc = plugin.getCommand(rootCommand.name());
        if (pc == null) {
            plugin.getLogger().warning("Command " + rootCommand.name() + " is not defined in plugin.yml");
            return;
        }

        Map<String, SubCommandData> subCommands = new ConcurrentHashMap<>();

        for (Method method : instance.getClass().getDeclaredMethods()) {
            SubCommand subCommand = method.getAnnotation(SubCommand.class);
            if (subCommand != null) {

                boolean playerOnly = method.isAnnotationPresent(PlayerOnly.class);
                boolean opOnly = method.isAnnotationPresent(OpOnly.class);
                boolean consoleOnly = method.isAnnotationPresent(ConsoleOnly.class);
                HasPermission[] hasPermission = method.getAnnotationsByType(HasPermission.class);

                Method completerMethod = findCompleter(
                        instance.getClass(),
                        subCommand.value()
                );

                method.setAccessible(true);
                if (completerMethod != null) completerMethod.setAccessible(true);

                SubCommandData subCommandData = new SubCommandData(
                        subCommand.value(),
                        method,
                        method.getParameters(),
                        completerMethod,
                        getPermissions(hasPermission),
                        playerOnly,
                        consoleOnly,
                        opOnly,
                        subCommand.cooldown(),
                        subCommand.minArgs(),
                        subCommand.usage()
                );

                subCommands.put(subCommand.value().toLowerCase(), subCommandData);
            }
        }

        boolean playerOnly = instance.getClass().isAnnotationPresent(PlayerOnly.class);
        boolean opOnly = instance.getClass().isAnnotationPresent(OpOnly.class);
        boolean consoleOnly = instance.getClass().isAnnotationPresent(ConsoleOnly.class);
        HasPermission[] hasPermission = instance.getClass().getAnnotationsByType(HasPermission.class);

        CommandData commandData = new CommandData(
                rootCommand.name(),
                null,
                null,
                null,
                getPermissions(hasPermission),
                playerOnly,
                consoleOnly,
                opOnly,
                rootCommand.cooldown(),
                subCommands
        );

        commands.put(rootCommand.name(), commandData);

        pc.setExecutor((sender, command, label, args) -> {
            CommandData data = commands.get(command.getName());
            if (data == null) return true;

            if (data.isPlayerOnly() && !(sender instanceof Player)) {
                instance.onPlayerOnly(sender);
                return true;
            }

            if (data.isOpOnly() && !sender.isOp()) {
                instance.onNoPermission(sender);
                return true;
            }

            if (data.isConsoleOnly() && !(sender instanceof ConsoleCommandSender)) {
                instance.onConsoleOnly(sender);
                return true;
            }

            if (!hasPermission(data.getPermissions(), sender)) {
                instance.onNoPermission(sender);
                return true;
            }

            if (args.length == 0) {
                instance.onInvalidUsage(sender, "");
                return true;
            }

            SubCommandData subCommandData = data.getSubCommands().get(args[0].toLowerCase());
            if (subCommandData == null) {
                instance.onInvalidUsage(sender, "");
                return true;
            }

            if (subCommandData.isPlayerOnly() && !(sender instanceof Player)) {
                instance.onPlayerOnly(sender);
                return true;
            }

            if (subCommandData.isOpOnly() && !sender.isOp()) {
                instance.onNoPermission(sender);
                return true;
            }

            if (subCommandData.isConsoleOnly() && !(sender instanceof ConsoleCommandSender)) {
                instance.onConsoleOnly(sender);
                return true;
            }

            if (!hasPermission(subCommandData.getPermissions(), sender)) {
                instance.onNoPermission(sender);
                return true;
            }

            String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);
            if (remainingArgs.length < subCommandData.getMinArgs()) {
                instance.onInvalidUsage(sender, subCommandData.getUsage());
                return true;
            }

            String cooldownKey = rootCommand.name() + ":" + subCommandData.getValue();
            if (isOnCooldown(sender, cooldownKey, subCommandData.getCooldown(), instance)) {
                return true;
            }

            invoke(instance,
                    subCommandData.getMethod(),
                    subCommandData.getParameters(),
                    subCommandData.getUsage(), sender,
                    remainingArgs
            );
            return true;
        });

        pc.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) {
                return subCommands.keySet().stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }

            SubCommandData data = subCommands.get(args[0].toLowerCase());

            if (data == null) return Collections.emptyList();
            if (data.getCompleter() == null) return Collections.emptyList();


            String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);

            return invokeCompleter(instance, data.getCompleter(), sender, remainingArgs);
        });
    }

    private boolean hasPermission(Set<String> perms, CommandSender sender) {

        for (String perm : perms) {
            if (!sender.hasPermission(perm)) {
                return false;
            }
        }

        return true;
    }

    private Set<String> getPermissions(HasPermission[] permissions) {
        Set<String> nodes = new HashSet<>();

        for (HasPermission permission : permissions) {
            nodes.add(permission.value());
        }

        return Collections.unmodifiableSet(nodes);
    }

    private Method findCompleter(Class<?> clazz, String name) {
        for (Method method : clazz.getDeclaredMethods()) {
            Completer completer = method.getAnnotation(Completer.class);
            if (completer != null && completer.value().equalsIgnoreCase(name)) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeCompleter(CommandBase instance, Method method, CommandSender sender, String[] args) {
        try {
            Object result = method.invoke(instance, sender, args);
            return result == null ? Collections.emptyList() : (List<String>) result;
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private void invoke(
            CommandBase instance,
            Method method,
            Parameter[] parameters,
            String usage,
            CommandSender sender,
            String[] args
    ) {
        try {
            Object[] invokeArgs = new Object[parameters.length];

            int argIndex = 0;

            for (int i = 0; i < parameters.length; i++) {
                Class<?> type = parameters[i].getType();

                if (type == CommandSender.class) {
                    invokeArgs[i] = sender;
                    continue;
                } else if (type == String[].class) {
                    invokeArgs[i] = args;
                    continue;
                }

                if (argIndex < args.length) {
                    invokeArgs[i] = resolveArgument(
                            type,
                            args[argIndex++]
                    );
                }
            }

            method.invoke(instance, invokeArgs);
        } catch (IllegalArgumentException e) {
            instance.onInvalidUsage(sender, usage);
        } catch (ReflectiveOperationException e) {
            sender.sendMessage("An internal error occurred.");
            plugin.getLogger().severe("Failed to execute /" + method.getName());
            e.printStackTrace();
        }
    }

    private Object resolveArgument(
            Class<?> type,
            String argument
    ) {
        if (type == String.class) {
            return argument;
        }

        if (type == boolean.class || type == Boolean.class) {
            if (!argument.equalsIgnoreCase("true")
                    && !argument.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException();
            }

            return Boolean.parseBoolean(argument);
        }

        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(argument);
        }

        if (type == float.class || type == Float.class) {
            return Float.parseFloat(argument);
        }

        if (type == double.class || type == Double.class) {
            return Double.parseDouble(argument);
        }


        throw new IllegalArgumentException(
                "Unsupported argument type: " + type.getName()
        );
    }

    private boolean isOnCooldown(CommandSender sender, String cooldownKey, int cooldownSeconds, CommandBase instance) {
        if (cooldownSeconds <= 0) {
            return false;
        }

        if (!(sender instanceof Player player)) {
            return false;
        }

        UUID uuid = player.getUniqueId();

        Map<String, CooldownEntry> playerCooldowns = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        long now = System.currentTimeMillis();

        CooldownEntry entry = playerCooldowns.get(cooldownKey);

        if (entry != null && !entry.isExpired(now)) {
            long elapsedSeconds = (now - entry.getLastUse()) / 1000;
            long secondsLeft = entry.getCooldownSeconds() - elapsedSeconds;

            instance.onCooldown(sender, secondsLeft);
            return true;
        }

        playerCooldowns.put(cooldownKey, new CooldownEntry(now, cooldownSeconds));
        return false;
    }

    public void startCooldownCleanupTask() {
        if (isFolia()) {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
                long now = System.currentTimeMillis();

                for (Map.Entry<UUID, Map<String, CooldownEntry>> playerEntry : cooldowns.entrySet()) {
                    Map<String, CooldownEntry> playerCooldowns = playerEntry.getValue();

                    playerCooldowns.entrySet().removeIf(e -> e.getValue().isExpired(now));

                    if (playerCooldowns.isEmpty()) {
                        cooldowns.remove(playerEntry.getKey(), playerCooldowns);
                    }
                }
            }, 30, 30, TimeUnit.MINUTES);
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                long now = System.currentTimeMillis();

                for (Map.Entry<UUID, Map<String, CooldownEntry>> playerEntry : cooldowns.entrySet()) {
                    Map<String, CooldownEntry> playerCooldowns = playerEntry.getValue();

                    playerCooldowns.entrySet().removeIf(e -> e.getValue().isExpired(now));

                    if (playerCooldowns.isEmpty()) {
                        cooldowns.remove(playerEntry.getKey(), playerCooldowns);
                    }
                }
            }, 20L * 60 * 30, 20L * 60 * 30);
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}