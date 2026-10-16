package dev.twme.buildgit.paper.track;

import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.core.track.MarkTable;
import dev.twme.buildgit.server.Settings;
import dev.twme.buildgit.server.track.Tracker;
import java.util.BitSet;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

/** 共用追蹤器的 Bukkit 版入口：事件監聽器、WorldEdit 與 Axiom 掛點拿到的是 Bukkit 物件，在這裡換成原版物件。 */
public final class BukkitTracker {
  private final Tracker tracker;

  public BukkitTracker(Tracker tracker) {
    this.tracker = tracker;
  }

  public static ServerLevel level(World world) {
    return ((CraftWorld) world).getHandle();
  }

  private static net.minecraft.world.entity.Entity handle(Entity entity) {
    return ((CraftEntity) entity).getHandle();
  }

  public Settings settings() {
    return tracker.settings();
  }

  public MarkTable.Dimension dimension(World world) {
    return world == null ? null : tracker.dimension(level(world));
  }

  public boolean isTracked(World world) {
    return tracker.isTracked(level(world));
  }

  public Author author(OfflinePlayer player) {
    return tracker.author(player.getUniqueId(), player.getName());
  }

  public Author author(UUID uuid, String name) {
    return tracker.author(uuid, name);
  }

  public Author cause() {
    return tracker.cause();
  }

  public Tracker.Scope push(Author author) {
    return tracker.push(author);
  }

  public void suppress(Block block) {
    tracker.suppress(level(block.getWorld()), block.getX(), block.getY(), block.getZ());
  }

  public void markBlock(World world, int x, int y, int z, Author author) {
    tracker.markBlock(level(world), x, y, z, author);
  }

  public void markBlock(Block block, Author author) {
    markBlock(block.getWorld(), block.getX(), block.getY(), block.getZ(), author);
  }

  public void markAround(Block block, Author author) {
    tracker.markAround(level(block.getWorld()), block.getX(), block.getY(), block.getZ(), author);
  }

  public void markBox(World world, Box box, Author author) {
    tracker.markBox(level(world), box, author);
  }

  public void markSection(World world, int chunkX, int sectionY, int chunkZ, BitSet cells, Author author) {
    tracker.markSection(level(world), chunkX, sectionY, chunkZ, cells, author);
  }

  public void expectSpawns(World world, int chunkX, int chunkZ, Author author) {
    tracker.expectSpawns(level(world), chunkX, chunkZ, author);
  }

  public void markEntity(Entity entity, Author author) {
    tracker.markEntity(handle(entity), author);
  }

  public boolean isBuildEntity(EntityType type) {
    return type != EntityType.UNKNOWN && tracker.isBuildEntity(CraftEntityType.bukkitToMinecraft(type));
  }

  public Author trackedAuthor(Entity entity) {
    return tracker.trackedAuthor(handle(entity));
  }

  public Author spawnAuthor(Entity entity) {
    return tracker.spawnAuthor(handle(entity));
  }

  public void adopt(Entity entity, Author author) {
    tracker.adopt(handle(entity), author);
  }

  public Author carrier(Entity entity) {
    return tracker.carrier(handle(entity));
  }

  public void forgetCarrier(UUID uuid) {
    tracker.forgetCarrier(uuid);
  }

  public void entityLoaded(Entity entity) {
    tracker.entityLoaded(handle(entity));
  }

  public void entityRemoved(Entity entity, boolean unloaded) {
    tracker.entityRemoved(handle(entity), unloaded);
  }
}
