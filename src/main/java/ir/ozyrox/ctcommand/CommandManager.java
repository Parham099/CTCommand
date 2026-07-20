package ir.ozyrox.ctcommand;

import ir.ozyrox.ctcommand.annotation.Command;
import ir.ozyrox.ctcommand.annotation.Completer;
import ir.ozyrox.ctcommand.annotation.SubCommand;
import ir.ozyrox.ctcommand.annotation.access.ConsoleOnly;
import ir.ozyrox.ctcommand.annotation.access.HasPermission;
import ir.ozyrox.ctcommand.annotation.access.OpOnly;
import ir.ozyrox.ctcommand.annotation.access.PlayerOnly;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

public class CommandManager {
    private final JavaPlugin plugin;

    private final Map<String, Long> cooldowns = new HashMap<>();

    public CommandManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(CommandBase instance) {
        Class<?> clazz = instance.getClass();

        if (clazz.isAnnotationPresent(Command.class)) {
            registerWithSubCommand(instance);
        } else {
            registerSimple(instance);
        }
    }

    private void registerSimple(CommandBase instance) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Command.class)) continue;
            Command cmd = method.getAnnotation(Command.class);

            PluginCommand pc = plugin.getCommand(cmd.name());
            if (pc == null) {
                plugin.getLogger().warning("Command " + cmd.name() + " is not defined in plugin.yml");
                continue;
            }

            Method completerMethod = findCompleter(instance.getClass(), cmd.name());

            pc.setExecutor((sender, command, label, args) -> {
                // Check command is for players only
                if (getAnnotation(method, PlayerOnly.class) != null && !(sender instanceof Player)) {
                    instance.onPlayerOnly(sender);
                    return true;
                }

                // Check command is for operator only
                if (getAnnotation(method, OpOnly.class) != null && !sender.isOp()) {
                    instance.onNoPermission(sender);
                    return true;
                }

                // Check command is for console sender only
                if (getAnnotation(method, ConsoleOnly.class) != null && !(sender instanceof ConsoleCommandSender)) {
                    instance.onConsoleOnly(sender);
                    return true;
                }

                // Check for permissions
                if (!hasPermission(instance, method, sender)) {
                    instance.onNoPermission(sender);
                    return true;
                }

                // Check player is on cooldown or not
                if (isOnCooldown(sender, cmd.name(), cmd.cooldown(), instance)) {
                    return true;
                }

                invoke(instance, method, sender, args);
                return true;
            });

            if (completerMethod != null) {
                pc.setTabCompleter((sender, command, alias, args) -> invokeCompleter(instance, completerMethod, sender, args));
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

        Map<String, Method> subCommands = new HashMap<>();
        Map<String, Method> completers = new HashMap<>();

        for (Method method : instance.getClass().getDeclaredMethods()) {
            // Put sub command in command sub commands
            SubCommand subCommand = getAnnotation(method, SubCommand.class);
            if (subCommand != null) {
                subCommands.put(subCommand.value().toLowerCase(), method);
            }

            Completer completer = getAnnotation(method, Completer.class);
            if (completer != null) {
                completers.put(completer.value().toLowerCase(), method);
            }
        }

        pc.setExecutor((sender, command, label, args) -> {
            // Check command is player only
            if (getAnnotation(instance.getClass(), PlayerOnly.class) != null && !(sender instanceof Player)) {
                instance.onPlayerOnly(sender);
                return true;
            }
            // Get required permissions
            HasPermission[] commandPermissions = getAnnotations(instance.getClass(), HasPermission.class);
            boolean hasCommandPermission = true;
            if (commandPermissions != null) {
                for (HasPermission node : commandPermissions) {
                    if (!sender.hasPermission(node.permissionNode())) {
                        hasCommandPermission = false;
                    }
                }
            }

            // If player has not permission
            if (!hasCommandPermission) {
                instance.onNoPermission(sender);
                return true;
            }

            if (args.length == 0) {
                instance.onInvalidUsage(sender, "");
                return true;
            }

            Method method = subCommands.get(args[0].toLowerCase());
            if (method == null) {
                instance.onInvalidUsage(sender, "");
                return true;
            }

            SubCommand subCommand = method.getAnnotation(SubCommand.class);

            PlayerOnly playerOnly = getAnnotation(method, PlayerOnly.class);
            if (playerOnly != null && !(sender instanceof Player)) {
                instance.onPlayerOnly(sender);
                return true;
            }

            // Check sub command is op only or not
            ConsoleOnly consoleOnly = getAnnotation(method, ConsoleOnly.class);
            if (consoleOnly != null && !(sender instanceof Player)) {
                instance.onConsoleOnly(sender);
                return true;
            }

            // Check sub command is console only or not
            OpOnly opOnly = getAnnotation(method, OpOnly.class);
            if (opOnly != null && !(sender instanceof Player)) {
                instance.onNoPermission(sender);
                return true;
            }

            if(!hasPermission(instance, method, sender)) {
                instance.onNoPermission(sender);
                return true;
            }

            String cooldownKey = rootCommand.name() + ":" + subCommand.value();
            if (isOnCooldown(sender, cooldownKey, subCommand.cooldown(), instance)) {
                return true;
            }

            String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);

            if (remainingArgs.length < subCommand.minArgs()) {
                instance.onInvalidUsage(sender, subCommand.usage());
                return true;
            }

            invoke(instance, method, sender, remainingArgs);
            return true;
        });

        pc.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) {
                return subCommands.keySet().stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }

            Method completerMethod = completers.get(args[0].toLowerCase());
            if (completerMethod == null) return Collections.emptyList();

            String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);
            return invokeCompleter(instance, completerMethod, sender, remainingArgs);
        });
    }

    private boolean hasPermission(CommandBase instance, Method method, CommandSender sender) {
        // Get required permissions
        HasPermission[] subCommandPermissions = getAnnotations(method, HasPermission.class);
        boolean hasSubCommandPermission = true;
        if (subCommandPermissions != null) {
            for (HasPermission node : subCommandPermissions) {
                if (!sender.hasPermission(node.permissionNode())) {
                    hasSubCommandPermission = false;
                }
            }
        }

        return hasSubCommandPermission;
    }

    private Method findCompleter(Class<?> clazz, String name) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getAnnotation(Completer.class).value().equalsIgnoreCase(name)) {
                return method;
            }
        }
        return null;
    }

    private List<String> invokeCompleter(CommandBase instance, Method method, CommandSender sender, String[] args) {
        try {
            Object result = method.invoke(instance, sender, args);
            return (List<String>) result;
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private void invoke(CommandBase instance, Method method, CommandSender sender, String[] args) {
        try {
            method.invoke(instance, sender, args);
        } catch (Exception e) {
            sender.sendMessage("Fail to execute command");
            e.printStackTrace();
        }
    }

    private boolean isOnCooldown(CommandSender sender, String cooldownKey, int cooldownSeconds, CommandBase instance) {
        if (cooldownSeconds <= 0) return false;
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;

        String key = player.getUniqueId() + ":" + cooldownKey;
        long now = System.currentTimeMillis();
        long lastUse = cooldowns.getOrDefault(key, 0L);
        long elapsedSeconds = (now - lastUse) / 1000;

        if (elapsedSeconds < cooldownSeconds) {
            long secondsLeft = cooldownSeconds - elapsedSeconds;
            instance.onCooldown(sender, secondsLeft);
            return true;
        }

        cooldowns.put(key, now);
        return false;

    }

    private <T extends Annotation> T getAnnotation(Method method, Class<T> annotation) {
        try {
            if (method.isAnnotationPresent(annotation)) {
                return method.getAnnotation(annotation);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private <T extends Annotation> T[] getAnnotations(Method method, Class<T> annotation) {
        try {
            if (method.isAnnotationPresent(annotation)) {
                return method.getAnnotationsByType(annotation);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private <T extends Annotation> T getAnnotation(Class<?> clazz, Class<T> annotation) {
        try {
            if (clazz.isAnnotationPresent(annotation)) {
                return clazz.getAnnotation(annotation);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private <T extends Annotation> T[] getAnnotations(Class<?> clazz, Class<T> annotation) {
        try {
            if (clazz.isAnnotationPresent(annotation)) {
                return clazz.getAnnotationsByType(annotation);
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }
}