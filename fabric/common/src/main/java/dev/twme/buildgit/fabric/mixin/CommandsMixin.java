package dev.twme.buildgit.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import dev.twme.buildgit.fabric.Hooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 指令執行前：其他模組晚註冊的指令也包上原因。 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
  @Inject(method = "performCommand", at = @At("HEAD"))
  private void buildgit$before(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
    Hooks.beforeCommand();
  }
}
