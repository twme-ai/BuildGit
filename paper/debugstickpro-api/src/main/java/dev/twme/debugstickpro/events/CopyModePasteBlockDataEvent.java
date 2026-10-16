// Apache-2.0; TWME. Compile-only API, see paper/debugstickpro-api/README.md.
package dev.twme.debugstickpro.events;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class CopyModePasteBlockDataEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUUID;
    private final Block block;
    private boolean isCancelled = false;

    public CopyModePasteBlockDataEvent(UUID playerUUID, Block block) {
        this.playerUUID = playerUUID;
        this.block = block;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public void setCancelled(boolean b) {
        this.isCancelled = b;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public Player getPlayer() {
        return Bukkit.getPlayer(playerUUID);
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public Block getBlock() {
        return block;
    }

    public Location getLocation() {
        return block.getLocation();
    }
}
