package com.tooltracker;

/**
 * One tool the local player has used. Stored in a JSON file on the player's PC,
 * because a client-side mod cannot write data onto items on a server.
 *
 * <p>A record is re-attached to an item by matching its item id, a fingerprint
 * (custom name + enchantments) and its current damage value.
 */
public final class ToolRecord {
    public String id = "";
    public String item = "";
    public String fingerprint = "";
    public int damage;
    public String stat = "";
    public long count;
    /** Tridents can be thrown; a thrown trident comes back with slightly more damage. */
    public boolean throwable;
    /** Set while a throwable record has left the inventory, so it can be re-matched on return. */
    public boolean inFlight;

    public ToolRecord() {
    }

    boolean matches(String item, String fingerprint, int damage) {
        return this.damage == damage && this.item.equals(item) && this.fingerprint.equals(fingerprint);
    }
}
