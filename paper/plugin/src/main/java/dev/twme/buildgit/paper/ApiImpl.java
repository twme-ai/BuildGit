package dev.twme.buildgit.paper;

import dev.twme.buildgit.api.Actor;
import dev.twme.buildgit.api.BuildGitApi;
import dev.twme.buildgit.api.Recorder;
import dev.twme.buildgit.core.model.Author;
import dev.twme.buildgit.core.model.Box;
import dev.twme.buildgit.paper.track.BukkitTracker;
import java.util.BitSet;
import java.util.Optional;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

/** 公開 API 的實作：所有呼叫都直接轉給追蹤器。 */
final class ApiImpl implements BuildGitApi, Recorder {
  /** 單次 {@link #recordBox} 的上限，防止誤傳整個世界的範圍。 */
  private static final long MAX_BOX = 64L * 1024 * 1024;

  private final BukkitTracker tracker;

  ApiImpl(BukkitTracker tracker) {
    this.tracker = tracker;
  }

  @Override
  public Recorder recorder() {
    return this;
  }

  private Author author(Actor actor) {
    return tracker.author(actor.uuid(), actor.name());
  }

  @Override
  public boolean isTracked(World world) {
    return tracker.isTracked(world);
  }

  @Override
  public void recordBlock(Actor actor, Block block) {
    tracker.markBlock(block, author(actor));
  }

  @Override
  public void recordBlock(Actor actor, World world, int x, int y, int z) {
    tracker.markBlock(world, x, y, z, author(actor));
  }

  @Override
  public void recordBox(Actor actor, World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    var box = new Box(Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ), Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ));
    long volume = (long) (box.maxX() - box.minX() + 1) * (box.maxY() - box.minY() + 1) * (box.maxZ() - box.minZ() + 1);
    if (volume > MAX_BOX) throw new IllegalArgumentException("範圍太大：" + volume + " 格（上限 " + MAX_BOX + "）");
    tracker.markBox(world, box, author(actor));
  }

  @Override
  public void recordSection(Actor actor, World world, int chunkX, int sectionY, int chunkZ, BitSet cells) {
    if (cells.length() > 4096) throw new IllegalArgumentException("section 只有 4096 格");
    tracker.markSection(world, chunkX, sectionY, chunkZ, (BitSet) cells.clone(), author(actor));
  }

  @Override
  public void recordEntity(Actor actor, Entity entity) {
    tracker.markEntity(entity, author(actor));
  }

  @Override
  public Scope attribute(Actor actor) {
    var scope = tracker.push(author(actor));
    return scope::close;
  }

  @Override
  public Optional<Actor> pendingAuthor(Block block) {
    var d = tracker.dimension(block.getWorld());
    var a = d == null ? null : d.authorAt(block.getX(), block.getY(), block.getZ());
    return a == null ? Optional.empty() : Optional.of(new Actor(a.uuid(), a.name()));
  }
}
