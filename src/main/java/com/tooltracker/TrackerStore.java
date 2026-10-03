package com.tooltracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps track of which inventory slot holds which tracked tool.
 *
 * <p>Every client tick the player's inventory is scanned and each tool is matched to a
 * {@link ToolRecord}: first by "same slot, same item, same damage", then by an exact
 * match anywhere, then by "same slot, same item" (covers damage changes from use or
 * Mending). Tools taken out of an anvil, grindstone, smithing table, crafting grid or
 * enchanting table inherit the record of the tool that went in.
 */
public final class TrackerStore {
    public static final int OFFHAND_KEY = 100;
    public static final int CHEST_KEY = 101;
    private static final long TRANSFER_WINDOW_TICKS = 400;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, ToolRecord> records = new LinkedHashMap<>();
    /** slot key -> record id */
    private Map<Integer, String> bindings = new HashMap<>();
    /** slot key -> "item|fingerprint|damage" seen last tick, to spot newly appearing items */
    private Map<Integer, String> lastSignatures = new HashMap<>();
    /** live ItemStack object -> record id, used by the tooltip */
    private final Map<ItemStack, String> identity = new IdentityHashMap<>();
    /** record id -> last tick it was seen in a crafting/anvil/etc. input slot */
    private final Map<String, Long> pendingTransfers = new HashMap<>();

    @Nullable
    private Path file;
    private boolean dirty;

    // ------------------------------------------------------------------ identity helpers

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Custom name + enchantments. Damage is matched separately. */
    public static String fingerprint(ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name != null) {
            sb.append(name.getString());
        }
        sb.append('|');
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) {
            List<String> parts = new ArrayList<>();
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
                parts.add(entry.getKey().getRegisteredName() + "=" + entry.getIntValue());
            }
            parts.sort(null);
            sb.append(String.join(",", parts));
        }
        return sb.toString();
    }

    private static String signature(ItemStack stack) {
        return itemId(stack) + "|" + fingerprint(stack) + "|" + stack.getDamageValue();
    }

    public static int mainHandKey(Player player) {
        return player.getInventory().getSelectedSlot();
    }

    /** All slots a tool can sit in, keyed by our own slot keys. */
    private static Map<Integer, ItemStack> collectSlots(Player player) {
        Map<Integer, ItemStack> slots = new LinkedHashMap<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            slots.put(i, inventory.getItem(i));
        }
        slots.put(OFFHAND_KEY, player.getItemBySlot(EquipmentSlot.OFFHAND));
        slots.put(CHEST_KEY, player.getItemBySlot(EquipmentSlot.CHEST));
        return slots;
    }

    // ------------------------------------------------------------------ per-tick matching

    public void update(Player player, long now) {
        Map<Integer, ItemStack> slots = collectSlots(player);
        Map<Integer, String> old = bindings;
        Map<Integer, String> fresh = new HashMap<>();
        Map<Integer, String> signatures = new HashMap<>();
        Set<String> used = new HashSet<>();
        List<Integer> unresolved = new ArrayList<>();

        Set<String> stillInMenu = scanOpenMenu(player, now);

        // Pass 1: same slot, exact same item.
        for (Map.Entry<Integer, ItemStack> e : slots.entrySet()) {
            ItemStack stack = e.getValue();
            if (StatType.of(stack) == null) {
                continue;
            }
            signatures.put(e.getKey(), signature(stack));
            String rid = old.get(e.getKey());
            ToolRecord r = rid == null ? null : records.get(rid);
            if (r != null && !used.contains(rid) && r.matches(itemId(stack), fingerprint(stack), stack.getDamageValue())) {
                fresh.put(e.getKey(), rid);
                used.add(rid);
            } else {
                unresolved.add(e.getKey());
            }
        }

        // Pass 2: exact match with any record not already in use (items moved between slots,
        // taken out of a chest, inventory reloaded after joining).
        for (Iterator<Integer> it = unresolved.iterator(); it.hasNext(); ) {
            int key = it.next();
            ItemStack stack = slots.get(key);
            ToolRecord r = findExact(stack, used);
            if (r != null) {
                fresh.put(key, r.id);
                used.add(r.id);
                r.inFlight = false;
                it.remove();
            }
        }

        // Pass 3: same slot, same item, but the damage/name/enchantments changed (use, Mending).
        for (Iterator<Integer> it = unresolved.iterator(); it.hasNext(); ) {
            int key = it.next();
            ItemStack stack = slots.get(key);
            String rid = old.get(key);
            ToolRecord r = rid == null ? null : records.get(rid);
            if (r != null && !used.contains(rid) && r.item.equals(itemId(stack))) {
                refresh(r, stack);
                fresh.put(key, rid);
                used.add(rid);
                it.remove();
            }
        }

        // Pass 4: an item that just appeared, coming out of an anvil/grindstone/smithing
        // table/crafting grid/enchanting table, takes over the record of the tool that went in.
        for (Iterator<Integer> it = unresolved.iterator(); it.hasNext(); ) {
            int key = it.next();
            ItemStack stack = slots.get(key);
            if (signatures.get(key).equals(lastSignatures.get(key))) {
                continue; // was already sitting here last tick, not a new item
            }
            StatType type = StatType.of(stack);
            ToolRecord best = null;
            for (Map.Entry<String, Long> p : pendingTransfers.entrySet()) {
                if (now - p.getValue() > TRANSFER_WINDOW_TICKS || used.contains(p.getKey()) || stillInMenu.contains(p.getKey())) {
                    continue;
                }
                ToolRecord r = records.get(p.getKey());
                if (r != null && type != null && r.stat.equals(type.name()) && (best == null || r.count > best.count)) {
                    best = r;
                }
            }
            if (best != null) {
                refresh(best, stack);
                fresh.put(key, best.id);
                used.add(best.id);
                pendingTransfers.remove(best.id);
                it.remove();
            }
        }

        // Pass 5: a thrown trident coming back (it takes a little damage when thrown).
        for (Iterator<Integer> it = unresolved.iterator(); it.hasNext(); ) {
            int key = it.next();
            ItemStack stack = slots.get(key);
            String item = itemId(stack);
            String fp = fingerprint(stack);
            int damage = stack.getDamageValue();
            for (ToolRecord r : records.values()) {
                if (r.inFlight && !used.contains(r.id) && r.item.equals(item) && r.fingerprint.equals(fp)
                        && damage >= r.damage && damage - r.damage <= 3) {
                    refresh(r, stack);
                    r.inFlight = false;
                    fresh.put(key, r.id);
                    used.add(r.id);
                    it.remove();
                    break;
                }
            }
        }

        // Throwable tools that left the inventory are probably in flight.
        for (String rid : old.values()) {
            if (!used.contains(rid)) {
                ToolRecord r = records.get(rid);
                if (r != null && r.throwable) {
                    r.inFlight = true;
                }
            }
        }

        for (String rid : used) {
            pendingTransfers.remove(rid);
        }
        pendingTransfers.values().removeIf(t -> now - t > TRANSFER_WINDOW_TICKS);

        bindings = fresh;
        lastSignatures = signatures;
        identity.clear();
        for (Map.Entry<Integer, String> e : fresh.entrySet()) {
            identity.put(slots.get(e.getKey()), e.getValue());
        }
    }

    /**
     * Notes tracked tools sitting in the input slots of an anvil, grindstone, smithing table,
     * crafting grid or enchanting table. Returns the records currently present there.
     */
    private Set<String> scanOpenMenu(Player player, long now) {
        Set<String> present = new HashSet<>();
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return present;
        }
        boolean transferMenu = menu instanceof EnchantmentMenu;
        if (!transferMenu) {
            for (Slot slot : menu.slots) {
                if (slot.container instanceof ResultContainer) {
                    transferMenu = true;
                    break;
                }
            }
        }
        if (!transferMenu) {
            return present;
        }
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory || slot.container instanceof ResultContainer) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (StatType.of(stack) == null) {
                continue;
            }
            ToolRecord r = findExact(stack, Set.of());
            if (r != null) {
                pendingTransfers.put(r.id, now);
                present.add(r.id);
            }
        }
        return present;
    }

    @Nullable
    private ToolRecord findExact(ItemStack stack, Set<String> exclude) {
        String item = itemId(stack);
        String fp = fingerprint(stack);
        int damage = stack.getDamageValue();
        for (ToolRecord r : records.values()) {
            if (!exclude.contains(r.id) && r.matches(item, fp, damage)) {
                return r;
            }
        }
        return null;
    }

    private void refresh(ToolRecord r, ItemStack stack) {
        r.item = itemId(stack);
        r.fingerprint = fingerprint(stack);
        r.damage = stack.getDamageValue();
        dirty = true;
    }

    // ------------------------------------------------------------------ record access

    /** Returns the record for the tool in a slot, creating one if this tool has never been used. */
    public ToolRecord ensureRecord(int slotKey, ItemStack stack, StatType type) {
        String rid = bindings.get(slotKey);
        ToolRecord r = rid == null ? null : records.get(rid);
        if (r != null && r.item.equals(itemId(stack))) {
            return r;
        }
        r = create(stack, type);
        bindings.put(slotKey, r.id);
        identity.put(stack, r.id);
        return r;
    }

    /** Creates a record that is not bound to any slot (e.g. a trident that was just thrown). */
    public ToolRecord createDetached(ItemStack stack, StatType type) {
        ToolRecord r = create(stack, type);
        r.inFlight = r.throwable;
        return r;
    }

    private ToolRecord create(ItemStack stack, StatType type) {
        ToolRecord r = new ToolRecord();
        r.id = UUID.randomUUID().toString();
        r.item = itemId(stack);
        r.fingerprint = fingerprint(stack);
        r.damage = stack.getDamageValue();
        r.stat = type.name();
        r.throwable = stack.is(Tags.Items.TOOLS_TRIDENT);
        records.put(r.id, r);
        dirty = true;
        return r;
    }

    @Nullable
    public String boundRecordId(int slotKey) {
        return bindings.get(slotKey);
    }

    public void add(@Nullable String recordId, long amount) {
        if (recordId == null) {
            return;
        }
        ToolRecord r = records.get(recordId);
        if (r != null) {
            r.count += amount;
            dirty = true;
        }
    }

    /** The count to show in a tooltip. Tools that have never been used by this player show 0. */
    public long countFor(ItemStack stack, StatType type) {
        String rid = identity.get(stack);
        ToolRecord r = rid == null ? null : records.get(rid);
        if (r == null) {
            r = findExact(stack, Set.of());
        }
        if (r == null || !r.stat.equals(type.name())) {
            return 0;
        }
        return r.count;
    }

    // ------------------------------------------------------------------ persistence

    public void load(Path path) {
        clear();
        this.file = path;
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<ToolRecord> list = GSON.fromJson(reader, new TypeToken<List<ToolRecord>>() {}.getType());
            if (list != null) {
                for (ToolRecord r : list) {
                    if (r != null && r.id != null && !r.id.isEmpty()) {
                        upgrade(r);
                        records.put(r.id, r);
                    }
                }
            }
            ToolTracker.LOGGER.info("Loaded {} tracked tools from {}", records.size(), path);
        } catch (Exception e) {
            ToolTracker.LOGGER.error("Could not read tool stats from {}", path, e);
        }
    }

    /**
     * Keeps stats saved by older versions of the mod. Fields that were missing in older
     * files get safe defaults, and renamed stats are mapped to their new names.
     */
    private static void upgrade(ToolRecord r) {
        if (r.item == null) {
            r.item = "";
        }
        if (r.fingerprint == null) {
            r.fingerprint = "";
        }
        if (r.stat == null) {
            r.stat = "";
        }
        if (r.stat.equals("FISH_CAUGHT")) {
            r.stat = StatType.ITEMS_CAUGHT.name();
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(records.values()), writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            ToolTracker.LOGGER.error("Could not save tool stats to {}", file, e);
        }
    }

    public void clear() {
        records.clear();
        bindings = new HashMap<>();
        lastSignatures = new HashMap<>();
        identity.clear();
        pendingTransfers.clear();
        file = null;
        dirty = false;
    }

    public static Path fileFor(String worldKey) {
        String safe = worldKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return FMLPaths.GAMEDIR.get().resolve("tooltracker").resolve(safe + ".json");
    }
}
