package com.qsteam.toml_config.command;

import com.qsteam.toml_config.Tags;
import com.qsteam.toml_config.core.ConfigManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * In-game admin command for TOML config management.
 * <p>
 * Currently supports:
 * <ul>
 *     <li>{@code /toml_config reload} - reloads all initialized {@code @TOMLConfig} classes from disk.</li>
 * </ul>
 */
public class CommandTomlConfig extends CommandBase {

    @Override
    public String getName() {
        return Tags.MOD_ID;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/" + Tags.MOD_ID + " reload";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            int reloaded = ConfigManager.reloadAllConfigs();
            sender.sendMessage(new TextComponentString("[TOML Config] Reloaded " + reloaded + " config class(es) from .toml files."));
            return;
        }
        throw new CommandException("Usage: " + getUsage(sender));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, Collections.singletonList("reload"));
        }
        return Collections.emptyList();
    }
}
