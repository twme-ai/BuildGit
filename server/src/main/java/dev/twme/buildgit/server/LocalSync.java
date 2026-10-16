package dev.twme.buildgit.server;

import dev.twme.buildgit.core.model.*;
import dev.twme.buildgit.core.sync.Bundle;
import dev.twme.buildgit.core.sync.SyncClient;
import dev.twme.buildgit.server.nms.LiveReader;
import dev.twme.buildgit.server.nms.LiveWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * 與伺服器同步，單人世界這一邊（設計 §6.7）：{@code load} 把取出的範圍放進目前的世界，
 * 世界關閉時把範圍讀回來存成「改過的」範圍檔，下次連上伺服器用 {@code push} 送回。
 * 載入紀錄存在範圍檔旁邊（{@code <名稱>.yml}），記得放在哪個存檔的哪個維度。
 */
final class LocalSync {
  private final BuildGit bg;

  LocalSync(BuildGit bg) {
    this.bg = bg;
  }

  private Messages msg() {
    return bg.messages();
  }

  /** 資料夾裡取出的範圍名稱。 */
  static List<String> names(Path folder) {
    var result = new ArrayList<String>();
    try (var files = Files.list(folder)) {
      files.forEach(f -> {
        var n = f.getFileName().toString();
        if (n.endsWith(SyncClient.PULLED) && !n.endsWith(SyncClient.EDITED)) result.add(n.substring(0, n.length() - SyncClient.PULLED.length()));
      });
    } catch (IOException e) {
      // 還沒有範圍
    }
    Collections.sort(result);
    return result;
  }

  private String worldId() {
    return bg.platform().server().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
  }

  private static String dimensionId(ServerLevel level) {
    return String.valueOf(level.dimension());
  }

  /** 把範圍放到玩家所在世界的同一個位置：方塊整塊蓋掉，範圍內的實體換成範圍檔裡的。 */
  void load(ServerPlayer p, String name, Path folder) throws IOException {
    var sender = bg.platform().sender(p);
    var file = folder.resolve(name + SyncClient.PULLED);
    if (!Files.exists(file)) {
      msg().send(sender, "sync-load-missing", "name", name);
      return;
    }
    var bundle = Bundle.decode(Files.readAllBytes(file), bg.workspace().upgrader());
    var level = p.level();
    var box = bundle.box();
    if (box.minY() < level.getMinY() || box.maxY() > level.getMaxY()) throw new Workspace.Refused("範圍超出這個世界的高度");
    msg().send(sender, "sync-loading", "name", name);
    var platform = bg.platform();
    var futures = new ArrayList<CompletableFuture<?>>();
    for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
      for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++)
        futures.add(platform.loadChunk(level, cx, cz).thenCompose(c -> bg.workspace().entitiesLoaded(level, c)));
    var uuid = p.getUUID();
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> platform.sync(() -> {
      var player = platform.server().getPlayerList().getPlayer(uuid);
      try {
        if (error != null) throw new IOException("無法載入範圍內的 chunk", error);
        int failed = paste(level, bundle);
        var y = new Yaml();
        y.set("world", worldId());
        y.set("dimension", dimensionId(level));
        y.set("loaded", System.currentTimeMillis());
        y.save(folder.resolve(name + ".yml"));
        Files.deleteIfExists(folder.resolve(name + SyncClient.EDITED));
        if (player != null) msg().send(platform.sender(player), "sync-loaded", "name", name, "failed", failed);
      } catch (IOException | RuntimeException e) {
        platform.log().log(Level.WARNING, "無法載入範圍 " + name, e);
        if (player != null) msg().send(platform.sender(player), "sync-failed", "error", String.valueOf(e.getMessage()));
      }
    }));
  }

  /** @return 寫不進去的格子與實體數 */
  private int paste(ServerLevel level, Bundle bundle) {
    var box = bundle.box();
    int[] failed = {0};
    bg.tracker().systemWrite(() -> {
      for (int y = box.minY(); y <= box.maxY(); y++)
        for (int z = box.minZ(); z <= box.maxZ(); z++)
          for (int x = box.minX(); x <= box.maxX(); x++)
            try {
              LiveWriter.setCell(level, x, y, z, bundle.cell(x, y, z));
            } catch (IOException | RuntimeException e) {
              failed[0]++;
            }
      for (var e : level.getEntities((Entity) null, aabb(box), e -> !(e instanceof Player))) e.discard();
      for (var e : bundle.entities())
        try {
          LiveWriter.spawnEntity(level, e);
        } catch (IOException | RuntimeException ex) {
          failed[0]++;
        }
    });
    return failed[0];
  }

  private static AABB aabb(Box box) {
    return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
  }

  /** 世界關閉前（主執行緒）：這個存檔載入過的範圍都讀回來，存成改過的範圍檔。 */
  void packAll(Path folder) {
    String world = worldId();
    try (var files = Files.list(folder)) {
      for (var f : files.toList()) {
        var n = f.getFileName().toString();
        if (!n.endsWith(".yml")) continue;
        var y = Yaml.load(f);
        if (!world.equals(y.getString("world"))) continue;
        String name = n.substring(0, n.length() - 4);
        try {
          pack(folder, name, y.getString("dimension"));
        } catch (IOException | RuntimeException e) {
          bg.platform().log().log(Level.WARNING, "無法打包範圍 " + name, e);
        }
      }
    } catch (NoSuchFileException e) {
      // 沒有範圍
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "無法讀取範圍資料夾 " + folder, e);
    }
  }

  private void pack(Path folder, String name, String dimension) throws IOException {
    var file = folder.resolve(name + SyncClient.PULLED);
    if (!Files.exists(file)) return;
    ServerLevel level = null;
    for (var l : bg.platform().server().getAllLevels()) if (dimensionId(l).equals(dimension)) level = l;
    if (level == null) return;
    var original = Bundle.decode(Files.readAllBytes(file), bg.workspace().upgrader());
    var box = original.box();
    var world = level;
    var reader = new LiveReader();
    var cells = Bundle.read(original.dimension(), box, (key, wanted) -> reader.readSection(world, world.getChunk(key.chunkX(), key.chunkZ()), key.sectionY(), wanted));
    // 實體還沒載入的 chunk 維持取出時的實體
    var entities = new ArrayList<EntityState>();
    for (var e : original.entities()) {
      var p = e.position();
      if (p != null && !bg.platform().entitiesLoaded(level, (int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4)) entities.add(e);
    }
    for (var e : level.getEntities((Entity) null, aabb(box), e -> !(e instanceof Player) && bg.tracker().isBuildEntity(e.getType()))) {
      if (!e.isAlive() || !box.contains(e.getX(), e.getY(), e.getZ())) continue;
      entities.add(LiveReader.readEntity(e));
    }
    var edited = new Bundle(original.name(), original.dimension(), box, LiveReader.dataVersion(), original.base(), cells, entities);
    var tmp = folder.resolve(name + SyncClient.EDITED + ".tmp");
    Files.write(tmp, edited.encode());
    Files.move(tmp, folder.resolve(name + SyncClient.EDITED), StandardCopyOption.REPLACE_EXISTING);
    bg.platform().log().info("已把範圍 " + name + " 存成改過的版本，連上伺服器後用 /bg push " + name + " 送回");
  }
}
