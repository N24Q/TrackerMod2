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
import net.minecraft.world.inventory.GrindstoneMenu;
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
    /** The item held on the mouse cursor while a menu is open. */
    public static final int CURSOR_KEY = 102;
    private static final long TRANSFER_WINDOW_TICKS = 400;
    /** How long a result can sit on the cursor (or wait) and still inherit the tools that made it. */
    private static final long LINK_WINDOW_TICKS = 20L * 60L * 10L;

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
    /** merged-away record id -> record it was merged into (so in-flight kills still count) */
    private final Map<String, String> aliases = new HashMap<>();

    /**
     * The tool currently shown in the output slot of an anvil, grindstone, smithing table or
     * crafting grid, and the tracked tools in the input slots that produce it.
     */
    private record ResultLink(ItemStack result, String signature, List<String> inputIds, long tick) {}

    @Nullable
    private ResultLink link;
    /** Record of the tool sitting in an enchanting table, which is enchanted in place. */
    @Nullable
    private String enchantSlotRecord;

    @Nullable
    private Path file;
    private boolean dirty;

    // ------------------------------------------------------------------ identity helpers

    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Custom name + enchantments. Damage is matched separately. */
    public static String fingerprint(ItemStack stack) {
        StringBuilder sb = new StringBuilder(namePart(stack));
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

    private static String namePart(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null ? name.getString() : "";
    }

    private static boolean isEnchanted(ItemStack stack) {
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        return enchantments != null && !enchantments.entrySet().isEmpty();
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
        if (player.containerMenu != null) {
            slots.put(CURSOR_KEY, player.containerMenu.getCarried());
        }
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

        // Pass 4: an item that just appeared, coming out of an anvil, grindstone, smithing
        // table or crafting grid. It takes over the record(s) of the tool(s) that went in;
        // when two used tools are combined, their counts are added together.
        if (link != null && now - link.tick() > LINK_WINDOW_TICKS) {
            link = null;
        }
        for (Iterator<Integer> it = unresolved.iterator(); it.hasNext(); ) {
            int key = it.next();
            ItemStack stack = slots.get(key);
            String sig = signatures.get(key);
            if (sig.equals(lastSignatures.get(key))) {
                continue; // was already sitting here last tick, not a new item
            }
            ToolRecord target = null;
            if (link != null && link.signature().equals(sig) && link.inputIds().stream().noneMatch(stillInMenu::contains)) {
                target = merge(link.inputIds(), stack, used);
                link = null;
            }
            if (target == null) {
                // Fallback: any tracked tool of the same kind that was just used up in a menu.
                StatType type = StatType.of(stack);
                List<String> candidates = new ArrayList<>();
                for (Map.Entry<String, Long> p : pendingTransfers.entrySet()) {
                    if (now - p.getValue() > TRANSFER_WINDOW_TICKS || used.contains(p.getKey()) || stillInMenu.contains(p.getKey())) {
                        continue;
                    }
                    ToolRecord r = records.get(p.getKey());
                    if (r != null && type != null && r.stat.equals(type.name())) {
                        candidates.add(r.id);
                    }
                }
                target = merge(candidates, stack, used);
            }
            if (target != null) {
                fresh.put(key, target.id);
                used.add(target.id);
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
     * Looks at an open anvil, grindstone, smithing table, crafting grid or enchanting table.
     * Notes the tracked tools in its input slots, links the output to them, and follows a
     * tool being enchanted in place. Returns the records currently sitting in the menu.
     */
    private Set<String> scanOpenMenu(Player player, long now) {
        Set<String> present = new HashSet<>();
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            enchantSlotRecord = null;
            return present;
        }

        // Enchanting table: the tool is changed in place, so follow it through the change.
        if (menu instanceof EnchantmentMenu && !menu.slots.isEmpty()) {
            ItemStack stack = menu.getSlot(0).getItem();
            ToolRecord r = null;
            if (StatType.of(stack) != null) {
                r = findExact(stack, Set.of());
                ToolRecord prev = enchantSlotRecord == null ? null : records.get(enchantSlotRecord);
                if (r == null && prev != null && prev.item.equals(itemId(stack)) && prev.damage == stack.getDamageValue()
                        && prev.fingerprint.equals(namePart(stack) + "|") && isEnchanted(stack)) {
                    refresh(prev, stack);
                    r = prev;
                }
            }
            enchantSlotRecord = r == null ? null : r.id;
            if (r != null) {
                pendingTransfers.put(r.id, now);
                present.add(r.id);
            }
            return present;
        }
        enchantSlotRecord = null;

        List<Slot> inputs = new ArrayList<>();
        List<Slot> results = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof Inventory) {
                continue;
            }
            if (slot.container instanceof ResultContainer || (menu instanceof GrindstoneMenu && i == 2)) {
                results.add(slot);
            } else {
                inputs.add(slot);
            }
        }
        if (results.isEmpty()) {
            return present; // a chest or similar, not a crafting-style menu
        }

        List<ToolRecord> inputRecords = new ArrayList<>();
        for (Slot slot : inputs) {
            ItemStack stack = slot.getItem();
            if (StatType.of(stack) == null) {
                continue;
            }
            ToolRecord r = findExact(stack, present);
            if (r != null) {
                pendingTransfers.put(r.id, now);
                present.add(r.id);
                inputRecords.add(r);
            }
        }

        for (Slot slot : results) {
            ItemStack result = slot.getItem();
            StatType type = StatType.of(result);
            if (type == null) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (ToolRecord r : inputRecords) {
                if (r.stat.equals(type.name())) {
                    ids.add(r.id);
                }
            }
            if (!ids.isEmpty()) {
                link = new ResultLink(result, signature(result), ids, now);
            }
        }
        return present;
    }

    /**
     * Moves the given records onto a new item. With more than one record (two used tools
     * combined), the counts are added together into the record with the highest count.
     */
    @Nullable
    private ToolRecord merge(List<String> ids, ItemStack stack, Set<String> used) {
        ToolRecord target = null;
        List<ToolRecord> parts = new ArrayList<>();
        for (String id : ids) {
            ToolRecord r = records.get(id);
            if (r == null || used.contains(id) || parts.contains(r)) {
                continue;
            }
            parts.add(r);
            if (target == null || r.count > target.count) {
                target = r;
            }
        }
        if (target == null) {
            return null;
        }
        for (ToolRecord r : parts) {
            pendingTransfers.remove(r.id);
            if (r != target) {
                target.count += r.count;
                records.remove(r.id);
                aliases.put(r.id, target.id);
            }
        }
        target.inFlight = false;
        refresh(target, stack);
        return target;
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
        ToolRecord r = records.get(resolve(recordId));
        if (r != null) {
            r.count += amount;
            dirty = true;
        }
    }

    private String resolve(String recordId) {
        String id = recordId;
        for (int i = 0; i < 16 && aliases.containsKey(id); i++) {
            id = aliases.get(id);
        }
        return id;
    }

    /** The count to show in a tooltip. Tools that have never been used by this player show 0. */
    public long countFor(ItemStack stack, StatType type) {
        if (link != null && link.result() == stack) {
            // Output slot preview: the combined count the tool will have once taken.
            long sum = 0;
            for (String id : link.inputIds()) {
                ToolRecord r = records.get(id);
                if (r != null && r.stat.equals(type.name())) {
                    sum += r.count;
                }
            }
            return sum;
        }
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
        aliases.clear();
        link = null;
        enchantSlotRecord = null;
        file = null;
        dirty = false;
    }

    public static Path fileFor(String worldKey) {
        String safe = worldKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return FMLPaths.GAMEDIR.get().resolve("tooltracker").resolve(safe + ".json");
    }
}
