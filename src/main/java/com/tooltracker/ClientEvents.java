package com.tooltracker;

import java.nio.file.Files;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Detects the local player's tool usage from the client side only, so the mod works on
 * any server. Each detector only ever credits the local player's own actions.
 */
@EventBusSubscriber(modid = ToolTracker.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private static final TrackerStore STORE = new TrackerStore();

    /** How long to wait for the server to confirm an action (covers laggy connections). */
    private static final long CONFIRM_TICKS = 40;
    private static final long AUTOSAVE_TICKS = 1200;

    private static long tick;
    private static boolean loaded;

    private record Hit(String recordId, long tick) {}

    private record PendingBlock(StatType type, String recordId, BlockPos pos, BlockState before,
                                BlockPos firePos, BlockState fireBefore, long tick) {}

    private record PendingFish(String recordId, Vec3 hookPos, long tick) {}

    /** target entity id -> melee weapon that last hit it */
    private static final Map<Integer, Hit> MELEE_HITS = new HashMap<>();
    /** projectile entity id -> weapon that fired it */
    private static final Map<Integer, Hit> PROJECTILES = new HashMap<>();
    /** entity ids whose death has already been handled */
    private static final Map<Integer, Long> HANDLED_DEATHS = new HashMap<>();
    /** sheep entity id -> shears used on it */
    private static final Map<Integer, Hit> PENDING_SHEARS = new HashMap<>();
    private static final List<PendingBlock> PENDING_BLOCKS = new ArrayList<>();
    @Nullable
    private static PendingFish pendingFish;

    @Nullable
    private static BlockPos brushPos;
    @Nullable
    private static BlockState brushState;
    private static InteractionHand brushHand = InteractionHand.MAIN_HAND;
    private static long brushTick;

    private static long lastShieldTick = -100;

    /** The trident most recently held, so a thrown trident can be credited. */
    @Nullable
    private static String lastTridentRecord;
    private static ItemStack lastTridentStack = ItemStack.EMPTY;
    private static long lastTridentTick = -100;

    private ClientEvents() {
    }

    // ------------------------------------------------------------------ helpers

    private static int handKey(Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? TrackerStore.mainHandKey(player) : TrackerStore.OFFHAND_KEY;
    }

    private static boolean isLocal(@Nullable Entity entity) {
        return entity != null && entity == Minecraft.getInstance().player;
    }

    private static String worldKey(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.hasSingleplayerServer() && server != null) {
            // Walk up from a known world sub-folder until we reach the folder holding level.dat.
            Path p = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).toAbsolutePath().normalize();
            Path dir = p;
            while (dir != null && !Files.exists(dir.resolve("level.dat"))) {
                dir = dir.getParent();
            }
            Path world = dir != null ? dir : p.getParent();
            return "singleplayer_" + (world != null && world.getFileName() != null ? world.getFileName().toString() : "world");
        }
        ServerData data = mc.getCurrentServer();
        if (data != null) {
            return "server_" + data.ip;
        }
        return "unknown";
    }

    private static void resetSession() {
        MELEE_HITS.clear();
        PROJECTILES.clear();
        HANDLED_DEATHS.clear();
        PENDING_SHEARS.clear();
        PENDING_BLOCKS.clear();
        pendingFish = null;
        brushPos = null;
        brushState = null;
        lastTridentRecord = null;
        lastTridentStack = ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ session lifecycle

    @SubscribeEvent
    static void onLogIn(ClientPlayerNetworkEvent.LoggingIn event) {
        resetSession();
        STORE.load(TrackerStore.fileFor(worldKey(Minecraft.getInstance())));
        loaded = true;
    }

    @SubscribeEvent
    static void onLogOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (loaded) {
            STORE.save();
        }
        STORE.clear();
        resetSession();
        loaded = false;
    }

    // ------------------------------------------------------------------ tooltip

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        StatType type = StatType.of(stack);
        if (type == null) {
            return;
        }
        long value = loaded ? STORE.countFor(stack, type) : 0;
        Component line = Component.translatable(type.translationKey(), type.format(value)).withStyle(type.milestoneColor(value));
        List<Component> tooltip = event.getToolTip();
        tooltip.add(insertIndex(stack, tooltip), line);
    }

    /**
     * Where the stat line goes: directly below the last enchantment line, or directly below
     * the item name if the item has no enchantments. This keeps it above the
     * "When in Main Hand" attribute section.
     */
    private static int insertIndex(ItemStack stack, List<Component> tooltip) {
        if (tooltip.isEmpty()) {
            return 0;
        }
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) {
            Set<String> names = new HashSet<>();
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
                names.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            }
            for (int i = tooltip.size() - 1; i >= 1; i--) {
                if (names.contains(tooltip.get(i).getString())) {
                    return i + 1;
                }
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------ per-tick work

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!loaded || player == null || level == null || mc.isPaused()) {
            return;
        }
        tick++;

        STORE.update(player, tick);

        tickElytra(player);
        tickBrush(mc, player, level);
        tickPendingBlocks(level);
        tickShears(level);
        tickDeaths(player, level);
        rememberTrident(player);

        if (tick % 100 == 0) {
            MELEE_HITS.values().removeIf(h -> tick - h.tick() > 200);
            PROJECTILES.values().removeIf(h -> tick - h.tick() > 2400);
            HANDLED_DEATHS.values().removeIf(t -> tick - t > 600);
            if (pendingFish != null && tick - pendingFish.tick() > CONFIRM_TICKS) {
                pendingFish = null;
            }
        }
        if (tick % AUTOSAVE_TICKS == 0) {
            STORE.saveIfDirty();
        }
    }

    /** Elytra: one tick of flight per client tick while gliding. */
    private static void tickElytra(LocalPlayer player) {
        if (!player.isFallFlying()) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (StatType.of(chest) == StatType.FLIGHT_TIME) {
            STORE.add(STORE.ensureRecord(TrackerStore.CHEST_KEY, chest, StatType.FLIGHT_TIME).id, 1);
        }
    }

    /** Brush: counts when a suspicious block you were brushing turns into a normal block. */
    private static void tickBrush(Minecraft mc, LocalPlayer player, ClientLevel level) {
        if (brushPos != null && brushState != null) {
            if (tick - brushTick > CONFIRM_TICKS) {
                brushPos = null;
                brushState = null;
            } else if (level.getBlockState(brushPos).getBlock() != brushState.getBlock()) {
                ItemStack brush = player.getItemInHand(brushHand);
                if (StatType.of(brush) == StatType.BLOCKS_BRUSHED) {
                    STORE.add(STORE.ensureRecord(handKey(player, brushHand), brush, StatType.BLOCKS_BRUSHED).id, 1);
                }
                brushPos = null;
                brushState = null;
            }
        }

        if (player.isUsingItem() && StatType.of(player.getUseItem()) == StatType.BLOCKS_BRUSHED
                && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("suspicious_")) {
                brushPos = pos.immutable();
                brushState = state;
                brushHand = player.getUsedItemHand();
                brushTick = tick;
            }
        }
    }

    /** Hoe tilling and flint and steel: confirmed when the block actually changes. */
    private static void tickPendingBlocks(ClientLevel level) {
        for (Iterator<PendingBlock> it = PENDING_BLOCKS.iterator(); it.hasNext(); ) {
            PendingBlock p = it.next();
            if (tick - p.tick() > CONFIRM_TICKS) {
                it.remove();
                continue;
            }
            BlockState now = level.getBlockState(p.pos());
            boolean success;
            if (p.type() == StatType.BLOCKS_TILLED) {
                success = now.getBlock() != p.before().getBlock();
            } else {
                BlockState fireNow = level.getBlockState(p.firePos());
                boolean placedFire = (fireNow.getBlock() instanceof BaseFireBlock || fireNow.is(Blocks.NETHER_PORTAL))
                        && !(p.fireBefore().getBlock() instanceof BaseFireBlock) && !p.fireBefore().is(Blocks.NETHER_PORTAL);
                boolean litBlock = p.before().hasProperty(BlockStateProperties.LIT) && !p.before().getValue(BlockStateProperties.LIT)
                        && now.hasProperty(BlockStateProperties.LIT) && now.getValue(BlockStateProperties.LIT);
                boolean primedTnt = p.before().is(Blocks.TNT) && !now.is(Blocks.TNT);
                success = placedFire || litBlock || primedTnt;
            }
            if (success) {
                STORE.add(p.recordId(), 1);
                it.remove();
            }
        }
    }

    /** Shears: confirmed when the sheep is no longer shearable. */
    private static void tickShears(ClientLevel level) {
        for (Iterator<Map.Entry<Integer, Hit>> it = PENDING_SHEARS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Hit> e = it.next();
            if (tick - e.getValue().tick() > CONFIRM_TICKS) {
                it.remove();
                continue;
            }
            Entity entity = level.getEntity(e.getKey());
            if (entity instanceof Sheep sheep && sheep.isAlive() && !sheep.readyForShearing()) {
                STORE.add(e.getValue().recordId(), 1);
                it.remove();
            }
        }
    }

    /**
     * Kills: when a nearby mob dies, its last damage source (sent to every client by the
     * server) tells us whether the local player caused it, and whether directly or with a
     * projectile.
     */
    private static void tickDeaths(LocalPlayer player, ClientLevel level) {
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(160.0), e -> true);
        for (LivingEntity living : nearby) {
            if (living == player || living instanceof ArmorStand || !living.isDeadOrDying()) {
                continue;
            }
            int id = living.getId();
            if (HANDLED_DEATHS.containsKey(id)) {
                continue;
            }
            HANDLED_DEATHS.put(id, tick);

            DamageSource source = living.getLastDamageSource();
            if (source == null || source.getEntity() != player || source.is(DamageTypes.THORNS)) {
                continue;
            }
            Entity direct = source.getDirectEntity();
            String recordId = null;
            if (direct == player) {
                Hit hit = MELEE_HITS.get(id);
                if (hit != null && tick - hit.tick() <= 100) {
                    recordId = hit.recordId();
                } else {
                    // e.g. killed by a sweeping attack
                    ItemStack main = player.getMainHandItem();
                    if (StatType.of(main) == StatType.MOBS_KILLED) {
                        recordId = STORE.ensureRecord(TrackerStore.mainHandKey(player), main, StatType.MOBS_KILLED).id;
                    }
                }
            } else if (direct != null) {
                Hit shot = PROJECTILES.get(direct.getId());
                if (shot != null) {
                    recordId = shot.recordId();
                }
            }
            STORE.add(recordId, 1);
        }
    }

    private static void rememberTrident(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(Tags.Items.TOOLS_TRIDENT)) {
                lastTridentRecord = STORE.boundRecordId(handKey(player, hand));
                lastTridentStack = stack.copy();
                lastTridentTick = tick;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ action events

    /** Pickaxe / shovel / axe. Fired on the client when the local player breaks a block. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onBlockBreak(BreakBlockEvent event) {
        if (!loaded || event.isCanceled() || !event.getLevel().isClientSide() || !isLocal(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack main = player.getMainHandItem();
        if (StatType.of(main) == StatType.BLOCKS_MINED) {
            STORE.add(STORE.ensureRecord(TrackerStore.mainHandKey(player), main, StatType.BLOCKS_MINED).id, 1);
        }
    }

    /** Hoe and flint and steel: remember the click, confirm when the block changes. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!loaded || event.isCanceled() || !event.getLevel().isClientSide() || !isLocal(event.getEntity())) {
            return;
        }
        ItemStack stack = event.getItemStack();
        StatType type = StatType.of(stack);
        if (type != StatType.BLOCKS_TILLED && type != StatType.FIRES_LIT) {
            return;
        }
        Player player = event.getEntity();
        BlockPos pos = event.getPos().immutable();
        BlockPos firePos = event.getFace() != null ? pos.relative(event.getFace()) : pos;
        ToolRecord r = STORE.ensureRecord(handKey(player, event.getHand()), stack, type);
        PENDING_BLOCKS.add(new PendingBlock(type, r.id, pos, event.getLevel().getBlockState(pos),
                firePos, event.getLevel().getBlockState(firePos), tick));
    }

    /** Shears on a sheep: remember it, confirm when the sheep becomes sheared. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!loaded || event.isCanceled() || !event.getLevel().isClientSide() || !isLocal(event.getEntity())) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (StatType.of(stack) == StatType.SHEEP_SHEARED && event.getTarget() instanceof Sheep sheep && sheep.readyForShearing()) {
            ToolRecord r = STORE.ensureRecord(handKey(event.getEntity(), event.getHand()), stack, StatType.SHEEP_SHEARED);
            PENDING_SHEARS.put(sheep.getId(), new Hit(r.id, tick));
        }
    }

    /** Fishing rod: reeling in while a hook is out. Confirmed when the catch appears at the hook. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!loaded || event.isCanceled() || !event.getLevel().isClientSide() || !isLocal(event.getEntity())) {
            return;
        }
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (StatType.of(stack) == StatType.ITEMS_CAUGHT && player.fishing != null) {
            ToolRecord r = STORE.ensureRecord(handKey(player, event.getHand()), stack, StatType.ITEMS_CAUGHT);
            pendingFish = new PendingFish(r.id, player.fishing.position(), tick);
        }
    }

    /** Melee weapons: remember which weapon hit which mob. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onAttack(AttackEntityEvent event) {
        if (!loaded || event.isCanceled() || !isLocal(event.getEntity()) || !event.getEntity().level().isClientSide()) {
            return;
        }
        Player player = event.getEntity();
        ItemStack main = player.getMainHandItem();
        if (StatType.of(main) == StatType.MOBS_KILLED) {
            ToolRecord r = STORE.ensureRecord(TrackerStore.mainHandKey(player), main, StatType.MOBS_KILLED);
            MELEE_HITS.put(event.getTarget().getId(), new Hit(r.id, tick));
        }
    }

    /** Projectiles fired by the local player, and fishing catches arriving. */
    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!loaded || !event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (entity instanceof ItemEntity && pendingFish != null) {
            if (tick - pendingFish.tick() <= CONFIRM_TICKS && entity.position().distanceToSqr(pendingFish.hookPos()) < 9.0) {
                STORE.add(pendingFish.recordId(), 1);
                pendingFish = null;
            }
            return;
        }

        if (entity instanceof ThrownTrident trident) {
            if (trident.getOwner() != player || tick - lastTridentTick > CONFIRM_TICKS || lastTridentStack.isEmpty()) {
                return;
            }
            String rid = lastTridentRecord;
            if (rid == null) {
                rid = STORE.createDetached(lastTridentStack, StatType.MOBS_KILLED).id;
                lastTridentRecord = rid;
            }
            PROJECTILES.put(entity.getId(), new Hit(rid, tick));
            return;
        }

        boolean arrowOrRocket = entity instanceof AbstractArrow || entity instanceof FireworkRocketEntity;
        if (!arrowOrRocket) {
            return;
        }
        Entity owner = entity instanceof AbstractArrow arrow ? arrow.getOwner() : ((FireworkRocketEntity) entity).getOwner();
        if (owner != player) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(Tags.Items.TOOLS_BOW) || stack.is(Tags.Items.TOOLS_CROSSBOW)
                    || (stack.is(Tags.Items.RANGED_WEAPON_TOOLS) && !stack.is(Tags.Items.TOOLS_TRIDENT))) {
                if (StatType.of(stack) == StatType.MOBS_KILLED) {
                    ToolRecord r = STORE.ensureRecord(handKey(player, hand), stack, StatType.MOBS_KILLED);
                    PROJECTILES.put(entity.getId(), new Hit(r.id, tick));
                }
                return;
            }
        }
    }

    /** Shield: the block sound plays at your position while you are holding a shield up. */
    @SubscribeEvent
    static void onSound(PlaySoundEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!loaded || player == null) {
            return;
        }
        SoundInstance sound = event.getOriginalSound();
        if (sound == null || !sound.getIdentifier().getPath().endsWith(".block")) {
            return;
        }
        if (!player.isUsingItem() || StatType.of(player.getUseItem()) != StatType.ATTACKS_BLOCKED) {
            return;
        }
        if (player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) > 4.0 || tick - lastShieldTick < 3) {
            return;
        }
        lastShieldTick = tick;
        InteractionHand hand = player.getUsedItemHand();
        ItemStack shield = player.getItemInHand(hand);
        STORE.add(STORE.ensureRecord(handKey(player, hand), shield, StatType.ATTACKS_BLOCKED).id, 1);
    }
}
