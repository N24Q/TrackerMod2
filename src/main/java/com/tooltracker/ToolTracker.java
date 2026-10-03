package com.tooltracker;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Client-only mod entry point. All behaviour lives in {@link ClientEvents},
 * which is registered automatically through {@code @EventBusSubscriber}.
 */
@Mod(value = ToolTracker.MODID, dist = Dist.CLIENT)
public final class ToolTracker {
    public static final String MODID = "tooltracker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ToolTracker() {
    }
}
