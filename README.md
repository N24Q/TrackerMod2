# Tool Tracker (NeoForge 26.3, client-side)

Adds one usage line to the tooltip of every tool, weapon and piece of gear.

| Item | Tooltip |
|---|---|
| Pickaxe, Shovel, Axe | Blocks Mined: X |
| Hoe | Blocks Tilled: X |
| Shears | Sheep Sheared: X |
| Sword, Spear, Mace, Trident, Bow, Crossbow | Mobs Killed: X |
| Elytra | Flight Time: 1h 2m 3s |
| Fishing Rod | Items Caught: X |
| Shield | Attacks Blocked: X |
| Flint and Steel | Fires Lit: X |
| Brush | Blocks Brushed: X |

The stat line sits directly below the item's enchantments (or below the name if it has none), above the
"When in Main Hand" section. Its colour steps up every time the number gains a digit:

| Count | Colour |
|---|---|
| 0 – 9 | Gray |
| 10+ | White |
| 100+ | Green |
| 1,000+ | Aqua |
| 10,000+ | Light Purple |
| 100,000+ | Gold |
| 1,000,000+ | Red (final) |

Elytra flight time uses seconds for these milestones.

Modded items are picked up automatically through the standard item tags (`#minecraft:pickaxes`, `#c:tools/bow`, etc.).

## Building

You need **JDK 25** installed.

```
./gradlew build          (Windows: gradlew.bat build)
```

The mod jar is written to `build/libs/tooltracker-1.2.0.jar`. Put it in your `mods` folder.
Only you need it installed; servers don't need it.

To test in a dev client: `./gradlew runClient`.

If Gradle can't find NeoForge `26.3.0.38-beta`, change `neo_version` in `gradle.properties` to the exact version shown on
https://projects.neoforged.net/neoforged/neoforge

## Updating

Replace the old jar in your `mods` folder with the new one. Your stats are stored separately in
`.minecraft/tooltracker/` and are kept across updates.

## How it works

A client-side mod can't write data onto items on a server, so stats are saved on your PC in
`.minecraft/tooltracker/<world or server>.json`, one file per singleplayer world / server address.

* Only **your** actions count. A tool someone hands you shows 0 until you use it.
* Every tool starts at 0. A tool gets an entry the first time you use it.
* Each tool is recognised by its item type, name, enchantments and durability, so it keeps its count when you move it around,
  put it in a chest, log out, or repair it with Mending.
* Stats carry through:
  * **Anvil**: combining enchantments, repairing with materials, renaming.
  * **Grindstone**: disenchanting, or combining two tools to repair.
  * **Smithing table**: diamond to netherite upgrades, armour trims.
  * **Crafting grid**: combining two tools to repair them.
  * **Enchanting table**.
* When two tools you have used are combined, the result gets **both counts added together**
  (e.g. a pickaxe with 1,200 blocks + one with 300 = 1,500). Hovering the output slot shows the combined count before you take it.

### Known limits

* Two identical, completely unused tools look the same. Once one has been used they are told apart by durability.
* Unbreakable items (no durability) of the same type and enchantments share one count.
* "Items Caught" counts every fishing catch: fish, junk and treasure.
* Kills are credited when the mob dies from your hit or your arrow/trident/firework. Kills from fire, fall damage or
  other indirect causes after your hit don't count.
* Stats from before installing the mod are not known.
