package dev.twme.buildgit.server;

import java.util.UUID;
import java.util.function.Function;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.minecraft.server.level.ServerPlayer;

/**
 * 向 LuckPerms 登記 BuildGit 的情境（設計 §8.1）。只有裝了 LuckPerms 才載入這個類別。
 *
 * @param <T> LuckPerms 在這個平台的玩家類別（Paper 的 {@code Player}、Fabric 的 {@code ServerPlayer}）
 */
public final class LuckPermsContexts<T> implements ContextCalculator<T> {
  private final Contexts contexts;
  private final Function<T, UUID> id;

  private LuckPermsContexts(Contexts contexts, Function<T, UUID> id) {
    this.contexts = contexts;
    this.id = id;
  }

  /**
   * @param id 平台的玩家 → UUID
   * @param subject BuildGit 的玩家 → 平台的玩家（通知 LuckPerms 用）
   */
  public static <T> void register(BuildGit bg, Function<T, UUID> id, Function<ServerPlayer, T> subject) {
    var manager = LuckPermsProvider.get().getContextManager();
    var calculator = new LuckPermsContexts<>(bg.contexts(), id);
    manager.registerCalculator(calculator);
    bg.contexts().signal(p -> manager.signalContextUpdate(subject.apply(p)));
    bg.platform().log().info("已向 LuckPerms 提供情境 " + Contexts.LOCATION + "、" + Contexts.BRANCH);
  }

  @Override
  public void calculate(T target, ContextConsumer consumer) {
    contexts.of(id.apply(target)).forEach(consumer::accept);
  }

  @Override
  public ContextSet estimatePotentialContexts() {
    var builder = ImmutableContextSet.builder();
    for (var l : Contexts.LOCATIONS) builder.add(Contexts.LOCATION, l);
    for (var b : contexts.branches()) builder.add(Contexts.BRANCH, b);
    return builder.build();
  }
}
