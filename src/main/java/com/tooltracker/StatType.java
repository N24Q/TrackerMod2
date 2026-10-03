package com.tooltracker;

import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

/** The single statistic each kind of item tracks. */
public enum StatType {
    BLOCKS_MINED,
    BLOCKS_TILLED,
    SHEEP_SHEARED,
    MOBS_KILLED,
    FLIGHT_TIME,
    ITEMS_CAUGHT,
    ATTACKS_BLOCKED,
    FIRES_LIT,
    BLOCKS_BRUSHED;

    public String translationKey() {
        return "tooltip." + ToolTracker.MODID + "." + name().toLowerCase(Locale.ROOT);
    }

    /** Formats a stored count for display. Flight time is stored in ticks. */
    public String format(long value) {
        if (this == FLIGHT_TIME) {
            long totalSeconds = value / 20L;
            long hours = totalSeconds / 3600L;
            long minutes = (totalSeconds % 3600L) / 60L;
            long seconds = totalSeconds % 60L;
            if (hours > 0) {
                return hours + "h " + minutes + "m " + seconds + "s";
            }
            if (minutes > 0) {
                return minutes + "m " + seconds + "s";
            }
            return seconds + "s";
        }
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** Works out which statistic an item tracks, or null if it is not a tracked tool. */
    @Nullable
    public static StatType of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.has(DataComponents.GLIDER)) {
            return FLIGHT_TIME;
        }
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.AXES)) {
            return BLOCKS_MINED;
        }
        if (stack.is(ItemTags.HOES)) {
            return BLOCKS_TILLED;
        }
        if (stack.is(Tags.Items.TOOLS_SHEAR)) {
            return SHEEP_SHEARED;
        }
        if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SPEARS)
                || stack.is(Tags.Items.TOOLS_MACE) || stack.is(Tags.Items.TOOLS_TRIDENT)
                || stack.is(Tags.Items.TOOLS_BOW) || stack.is(Tags.Items.TOOLS_CROSSBOW)
                || stack.is(Tags.Items.MELEE_WEAPON_TOOLS) || stack.is(Tags.Items.RANGED_WEAPON_TOOLS)) {
            return MOBS_KILLED;
        }
        if (stack.is(Tags.Items.TOOLS_FISHING_ROD)) {
            return ITEMS_CAUGHT;
        }
        if (stack.is(Tags.Items.TOOLS_SHIELD)) {
            return ATTACKS_BLOCKED;
        }
        if (stack.is(Tags.Items.TOOLS_IGNITER)) {
            return FIRES_LIT;
        }
        if (stack.is(Tags.Items.TOOLS_BRUSH)) {
            return BLOCKS_BRUSHED;
        }
        return null;
    }
}
