# ctcommand

A lightweight annotation-based command framework for Paper/Folia/Spigot plugins. Define commands with simple annotations instead of writing boilerplate `CommandExecutor` classes by hand.

## Features

* Class-based command registration
* Simple commands using `@DefaultCommand`
* Subcommands using `@SubCommand`
* **Automatic command argument parsing based on method parameter types**
* **Typed command parameters such as `String`, `int`, `float`, `double`, and `boolean`**
* **Automatic validation of argument types**
* **Invalid arguments automatically trigger `onInvalidUsage`**
* `CommandSender` parameters are automatically provided by the framework
* `String[]` parameters can be used to receive all raw command arguments
* Automatic tab-completion per command/subcommand
* Permission checks with `@HasPermission`
* Player-only (`@PlayerOnly`), console-only (`@ConsoleOnly`), and op-only (`@OpOnly`) restrictions
* Per-command / per-subcommand cooldowns
* Usage validation with `minArgs`
* Fully overridable messages (no permission, invalid usage, cooldown, player-only, console-only)

## Installation

### Maven

Add the JitPack repository:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Add the dependency:

```xml
<dependency>
    <groupId>com.github.ozyrox086</groupId>
    <artifactId>ctcommand</artifactId>
    <version>v1.2.2</version>
</dependency>
```

Since `ctcommand` needs to be bundled inside your plugin's final jar (the server doesn't know about it), add the Shade plugin to `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.5.1</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>shade</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Then build with:

```bash
mvn clean package
```

## Getting started

### 1. Register the manager in your plugin's `onEnable`

```java
@Override
public void onEnable() {
    CommandManager manager = new CommandManager(this);

    manager.register(new TeleportCommand());
    manager.register(new EconomyCommand());
}
```

### 2. Declare each command in `plugin.yml`

```yaml
commands:
  tp:
    description: Teleport to a player
  eco:
    description: Manage economy
```

## Command Arguments

One of the main features of `ctcommand` is automatic argument parsing.

Instead of receiving every argument as a `String[]` and manually checking and converting each value, you can declare the expected argument types directly in your command method.

For example:

```java
@Command(name = "give")
public class GiveCommand extends CommandBase {

    @DefaultCommand
    public void execute(CommandSender sender, String player, int amount) {
        sender.sendMessage(
                "Player: " + player + ", Amount: " + amount
        );
    }
}
```

When the user runs:

```text
/give Parham 100
```

`ctcommand` automatically maps the arguments to the method parameters:

```text
player -> "Parham"
amount -> 100
```

The framework effectively invokes the method as if you had written:

```java
execute(sender, "Parham", 100);
```

You no longer need to manually access:

```java
args[0]
args[1]
```

or manually parse numeric values.

### Supported parameter types

The framework currently supports:

| Type                  | Example          |
| --------------------- | ---------------- |
| `String`              | `"Parham"`       |
| `int` / `Integer`     | `100`            |
| `float` / `Float`     | `10.5`           |
| `double` / `Double`   | `10.5`           |
| `boolean` / `Boolean` | `true` / `false` |

For example:

```java
@DefaultCommand
public void execute(
        CommandSender sender,
        String player,
        int amount,
        double multiplier,
        boolean silent
) {
    // ...
}
```

A command such as:

```text
/example Parham 100 1.5 true
```

is automatically converted into:

```text
String  -> "Parham"
int     -> 100
double  -> 1.5
boolean -> true
```

### Invalid argument types

If an argument cannot be converted to the parameter's expected type, the command is rejected and `onInvalidUsage` is called.

For example:

```java
@DefaultCommand
public void execute(CommandSender sender, String player, int amount) {
    // ...
}
```

If the user enters:

```text
/give Parham abc
```

`abc` cannot be converted to an `int`, so the method is not executed.

Instead:

```java
onInvalidUsage(sender, usage);
```

is called.

The same behavior applies when a supported argument type receives an invalid value.

For booleans, only:

```text
true
false
```

are accepted.

### CommandSender parameters

`CommandSender` does not consume a command argument.

The framework automatically provides the current sender:

```java
@DefaultCommand
public void execute(CommandSender sender, String player, int amount) {
    // sender is provided automatically
}
```

For example:

```text
/give Parham 100
```

is mapped as:

```text
sender -> CommandSender
player -> "Parham"
amount -> 100
```

This also works with compatible sender types such as `Player`.

### Using `String[] args`

If you need access to the raw command arguments, you can still use `String[]`:

```java
@DefaultCommand
public void execute(CommandSender sender, String[] args) {

    if (args.length < 1) {
        return;
    }

    String firstArgument = args[0];
}
```

`String[]` receives the complete raw argument array and does not consume an argument position.

You can also mix it with typed parameters when needed:

```java
@DefaultCommand
public void execute(
        CommandSender sender,
        String player,
        String[] args
) {
    // player is parsed from the first command argument
    // args contains all raw command arguments
}
```

## Simple command (no subcommands)

Put `@Command` on the **class** and `@DefaultCommand` on the method.

Arguments can be declared directly using their expected types:

```java
@Command(name = "tp", cooldown = 3)
@PlayerOnly
@HasPermission("myplugin.tp")
public class TeleportCommand extends CommandBase {

    @DefaultCommand
    public void teleport(CommandSender sender, String player) {

        Player target = Bukkit.getPlayer(player);

        if (target == null) {
            sender.sendMessage("§cPlayer not found.");
            return;
        }

        ((Player) sender).teleport(target);
    }
}
```

Running:

```text
/tp Parham
```

automatically passes:

```java
"Parham"
```

to the `player` parameter.

No manual `args[0]` access is required.

## Command with subcommands

Put `@Command` on the **class**. Each method inside becomes a subcommand via `@SubCommand`.

Arguments are automatically parsed according to the method parameter types.

```java
@Command(name = "eco")
@HasPermission("myplugin.eco")
public class EconomyCommand extends CommandBase {

    @SubCommand(
            value = "give",
            minArgs = 2,
            usage = "/eco give <player> <amount>",
            cooldown = 10
    )
    @HasPermission("myplugin.eco.give")
    public void give(
            CommandSender sender,
            String player,
            int amount
    ) {
        sender.sendMessage(
                "§a" + amount + " coins given to " + player
        );
    }

    @Completer("give")
    public List<String> giveCompleter(
            CommandSender sender,
            String[] args
    ) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            return List.of("10", "50", "100", "1000");
        }

        return List.of();
    }

    @SubCommand("take")
    @HasPermission("myplugin.eco.take")
    @ConsoleOnly
    public void take(
            CommandSender sender,
            String player,
            int amount
    ) {
        sender.sendMessage("§c" + amount + " coins taken from " + player);
    }
}
```

For:

```text
/eco give Parham 100
```

the framework automatically resolves:

```text
CommandSender -> sender
"Parham"      -> String player
"100"         -> int amount
```

If `100` were replaced with an invalid integer, `onInvalidUsage` would be called instead of executing the method.

Running `/eco` with no arguments, or an unknown subcommand, triggers `onInvalidUsage`.

## Annotation reference

### `@Command`

| Parameter  | Default      | Description                           |
| ---------- | ------------ | ------------------------------------- |
| `name`     | — (required) | Command name, must match `plugin.yml` |
| `cooldown` | `0`          | Cooldown in seconds (players only)    |

### `@SubCommand`

| Parameter  | Default      | Description                                                                                |
| ---------- | ------------ | ------------------------------------------------------------------------------------------ |
| `value`    | — (required) | Subcommand name (e.g. `"give"`)                                                            |
| `usage`    | `""`         | Usage string passed to `onInvalidUsage` when `minArgs` isn't met or argument parsing fails |
| `minArgs`  | `0`          | Minimum number of arguments required (checked automatically)                               |
| `cooldown` | `0`          | Cooldown in seconds for this subcommand                                                    |

### `@Completer`

| Parameter | Description                                                               |
| --------- | ------------------------------------------------------------------------- |
| `value`   | Name of the command or subcommand this completer provides suggestions for |

Completer methods must have the signature:

```java
List<String> method(CommandSender sender, String[] args)
```

Example:

```java
@Completer("give")
public List<String> giveCompleter(
        CommandSender sender,
        String[] args
) {
    return List.of("10", "50", "100", "1000");
}
```

### `@DefaultCommand`

Marks the method as the main executor for a simple command.

Used for simple commands without subcommands.

Example:

```java
@Command(name = "spawn")
public class SpawnCommand extends CommandBase {

    @DefaultCommand
    public void execute(CommandSender sender) {
        sender.sendMessage("Teleporting...");
    }
}
```

Command arguments can be declared directly as method parameters:

```java
@Command(name = "give")
public class GiveCommand extends CommandBase {

    @DefaultCommand
    public void execute(
            CommandSender sender,
            String player,
            int amount
    ) {
        // ...
    }
}
```

## Access & permission annotations

These can be placed on both classes and methods.

Class-level restrictions apply to the entire command. Method-level restrictions apply to the selected command handler or subcommand.

| Annotation              | Target         | Description                                                                                                 |
| ----------------------- | -------------- | ----------------------------------------------------------------------------------------------------------- |
| `@HasPermission("...")` | Method / Class | Requires the sender to have the given permission node. Repeatable — all specified permissions must be held. |
| `@PlayerOnly`           | Method / Class | Restricts the command to players only. Console senders are rejected with `onPlayerOnly`.                    |
| `@ConsoleOnly`          | Method / Class | Restricts the command to console only. Players are rejected with `onConsoleOnly`.                           |
| `@OpOnly`               | Method / Class | Restricts the command to server operators only. Non-ops are rejected with `onNoPermission`.                 |

For subcommand-style commands (class-level `@Command`), `@HasPermission` and `@PlayerOnly` can be placed on the class itself to gate the entire command before any subcommand logic runs.

## Customizing messages

Override any of these in your command class to change the default behavior:

```java
public class EconomyCommand extends CommandBase {

    @Override
    public void onNoPermission(CommandSender sender) {
        sender.sendMessage("§4You don't have permission to use this command.");
    }

    @Override
    public void onInvalidUsage(CommandSender sender, String usage) {
        sender.sendMessage("§cUsage: " + usage);
    }

    @Override
    public void onPlayerOnly(CommandSender sender) {
        sender.sendMessage("§cThis command is for players only.");
    }

    @Override
    public void onConsoleOnly(CommandSender sender) {
        sender.sendMessage("§cThis command is for console only.");
    }

    @Override
    public void onCooldown(CommandSender sender, long secondsLeft) {
        sender.sendMessage("§6Wait " + secondsLeft + "s before using this again.");
    }
}
```

If you don't override them, sensible defaults from `CommandBase` are used automatically.

## How it works

* `@Command` is placed on command classes.
* `@DefaultCommand` marks the executor for simple commands.
* `@SubCommand` creates child commands under a root command.
* Command method parameters are automatically resolved from the command arguments.
* `CommandSender` parameters are automatically provided by the framework and do not consume command arguments.
* `String[]` parameters receive the complete raw argument array.
* Supported argument types currently include `String`, `int`, `Integer`, `float`, `Float`, `double`, `Double`, `boolean`, and `Boolean`.
* If an argument cannot be converted to the declared parameter type, `onInvalidUsage` is called and the command method is not executed.
* Class-level annotations apply to the entire command.
* Method-level annotations apply to the selected command handler or subcommand.
* Cooldowns are tracked per player UUID and command key.
* Cooldowns are not persisted and reset after server restart.
* Console senders are ignored for cooldown checks.

A command class can either contain:

* One `@DefaultCommand` method for a simple command
* One or more `@SubCommand` methods for grouped commands

## Example

A complete typed command can be as simple as:

```java
@Command(name = "example")
public class ExampleCommand extends CommandBase {

    @DefaultCommand
    public void execute(
            CommandSender sender,
            String name,
            int amount,
            double multiplier,
            boolean enabled
    ) {
        sender.sendMessage(
                "Name: " + name
                        + ", Amount: " + amount
                        + ", Multiplier: " + multiplier
                        + ", Enabled: " + enabled
        );
    }
}
```

The command:

```text
/example Parham 100 1.5 true
```

is automatically converted to:

```text
name       = "Parham"
amount     = 100
multiplier = 1.5
enabled    = true
```

No manual argument indexing or parsing is required.
