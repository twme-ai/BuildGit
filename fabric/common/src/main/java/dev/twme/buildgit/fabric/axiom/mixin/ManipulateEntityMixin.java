package dev.twme.buildgit.fabric.axiom.mixin;

import com.moulberry.axiom.packets.AxiomServerboundManipulateEntity;
import dev.twme.buildgit.fabric.axiom.AxiomRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 修改實體：前後各記一次整串（被趕下來的乘客修改後就不在串裡了）。 */
@Mixin(AxiomServerboundManipulateEntity.class)
public abstract class ManipulateEntityMixin {
  @Shadow @Final private List<AxiomServerboundManipulateEntity.ManipulateEntry> entries;

  @Inject(method = "handle", at = {@At("HEAD"), @At("RETURN")})
  private void buildgit$handle(MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
    var uuids = new ArrayList<UUID>();
    for (var entry : entries) {
      uuids.add(entry.uuid());
      if (entry.passengers() != null) uuids.addAll(entry.passengers());
    }
    AxiomRecorder.entities(player, uuids);
  }
}
