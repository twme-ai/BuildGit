package dev.twme.buildgit.paper.nms;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

/**
 * 只存在於某位玩家客戶端的發光方塊展示（外框）。伺服器上沒有這些實體：在記憶體裡建一個不加入世界的
 * {@link Display.BlockDisplay}，用它產生生成與資料封包後只送給那位玩家，所以資料欄位的編號永遠與遊戲版本一致。
 */
public final class OutlinePackets {
  private OutlinePackets() {}

  /** 一個外框：方塊座標的最小角、尺寸（格）、發光顏色（RGB）。 */
  public record Box(int x, int y, int z, float sizeX, float sizeY, float sizeZ, int rgb) {}

  /** 送出外框（主執行緒），回傳實體 id，之後交給 {@link #remove}。 */
  public static int[] show(Player player, List<Box> boxes) {
    var handle = ((CraftPlayer) player).getHandle();
    var level = handle.level();
    var packets = new ArrayList<Packet<? super ClientGamePacketListener>>(boxes.size() * 2);
    var ids = new int[boxes.size()];
    var glass = ((CraftBlockData) Material.WHITE_STAINED_GLASS.createBlockData()).getState();
    for (int i = 0; i < boxes.size(); i++) {
      var b = boxes.get(i);
      // 只拿來產生封包，不會加入世界；建構時就分配到不會和真實體重複的 id
      var d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
      d.setPos(b.x(), b.y(), b.z());
      d.setBlockState(glass);
      // 四邊各多 0.01 格，避免和真的方塊表面重疊閃爍
      d.setTransformation(new Transformation(new Vector3f(-0.01f), null, new Vector3f(b.sizeX() + 0.02f, b.sizeY() + 0.02f, b.sizeZ() + 0.02f), null));
      d.setGlowingTag(true);
      d.setGlowColorOverride(b.rgb());
      d.setBrightnessOverride(new Brightness(15, 15));
      d.setViewRange(4f);
      ids[i] = d.getId();
      packets.add(new ClientboundAddEntityPacket(d.getId(), d.getUUID(), b.x(), b.y(), b.z(), 0f, 0f, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
      packets.add(new ClientboundSetEntityDataPacket(d.getId(), d.getEntityData().packAll()));
    }
    if (!packets.isEmpty()) handle.connection.send(new ClientboundBundlePacket(packets));
    return ids;
  }

  /** 移除先前送出的外框。玩家已換世界或離線時客戶端早就丟掉了，不必送。 */
  public static void remove(Player player, int[] ids) {
    if (ids.length > 0) ((CraftPlayer) player).getHandle().connection.send(new ClientboundRemoveEntitiesPacket(ids));
  }
}
