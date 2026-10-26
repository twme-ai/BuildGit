package dev.twme.buildgit.server;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;

/**
 * 權限節點之間的包含關係（設計 §8）。兩個平台都在這裡判斷，不靠 Paper 的 {@code children}：
 * Fabric 的權限模組不知道子節點，給一個權限集也要能用。
 */
public final class PermissionSets {
  public static final String VIEWER = "buildgit.role.viewer";
  public static final String BUILDER = "buildgit.role.builder";
  public static final String MANAGER = "buildgit.role.manager";
  public static final String ADMIN = "buildgit.admin";

  /** 權限集 → 它直接包含的節點。其他節點都只管一個指令，彼此不包含。 */
  private static final Map<String, List<String>> INCLUDES = Map.of(
      VIEWER, List.of("buildgit.menu", "buildgit.status", "buildgit.log", "buildgit.diff",
          "buildgit.inspect", "buildgit.sel", "buildgit.branch.list", "buildgit.go", "buildgit.back",
          "buildgit.view"),
      BUILDER, List.of(VIEWER, "buildgit.try", "buildgit.save", "buildgit.pull", "buildgit.push"),
      MANAGER, List.of(BUILDER, "buildgit.publish", "buildgit.merge", "buildgit.merge.resolve", "buildgit.revert",
          "buildgit.restore", "buildgit.mark", "buildgit.branch.delete"),
      ADMIN, List.of(MANAGER, "buildgit.init", "buildgit.reload", "buildgit.disk", "buildgit.genesis", "buildgit.backup",
          "buildgit.world.admin"));

  /** 建築世界的訪客能用的：只能看（03-worlds §4），不能進草稿、不能檢視舊版本。 */
  private static final Set<String> VISITOR = Set.of("buildgit.menu", "buildgit.status", "buildgit.log", "buildgit.diff",
      "buildgit.inspect", "buildgit.sel", "buildgit.branch.list");

  /**
   * 玩家在建築世界裡的角色（由 BuildGit 設定）；在伺服器世界時為 null。不能進去的人當成訪客（很快就會被送回去）。
   * 不能在這裡檢查權限：LuckPerms 也會透過情境問角色，會無限循環。
   */
  static volatile Function<ServerPlayer, WorldConfig.Role> roles = p -> null;
  /** 在伺服器世界裡、成員名單上的角色（03-worlds §4.2）；加在伺服器給的節點之上。不在名單上、或在建築世界時為 null。 */
  static volatile Function<ServerPlayer, WorldConfig.Role> serverRoles = p -> null;
  /** 所在的建築世界允許複製：訪客也能 {@code pull} 到單人世界（03-worlds §3）。 */
  static volatile Predicate<ServerPlayer> visitorCopy = p -> false;

  /** 節點 → 有了就算有這個節點的所有節點（含自己），先具體後寬鬆。 */
  private static final Map<String, Set<String>> GRANTED_BY = new HashMap<>();

  static {
    for (var parent : INCLUDES.keySet()) addChildren(parent, parent);
  }

  private static void addChildren(String root, String parent) {
    for (var child : INCLUDES.getOrDefault(parent, List.of())) {
      GRANTED_BY.computeIfAbsent(child, k -> new LinkedHashSet<>(List.of(k))).add(root);
      addChildren(root, child);
    }
  }

  private PermissionSets() {}

  /**
   * 玩家的權限：在建築世界裡，權限集包含的節點改由那個世界的角色決定（擁有者與管理者是 manager、建造者是 builder，
   * 訪客只能看），不看伺服器給的節點；{@code buildgit.world.admin} 都可以。其他節點與伺服器世界照 {@code has}；
   * 伺服器世界的成員另外有角色給的節點。
   */
  public static boolean check(ServerPlayer player, Predicate<String> has, String permission) {
    var role = player == null ? null : roles.apply(player);
    // 只有 manager 權限集以內的節點看角色；init、reload、backup 等伺服器管理照舊
    if (!grants(MANAGER, permission)) return check(has, permission);
    if (role == null) {
      var member = player == null ? null : serverRoles.apply(player);
      return member != null && allows(member, permission) || check(has, permission);
    }
    if (role == WorldConfig.Role.VISITOR && permission.equals("buildgit.pull") && visitorCopy.test(player)) return true;
    return allows(role, permission) || check(has, "buildgit.world.admin");
  }

  private static boolean allows(WorldConfig.Role role, String permission) {
    return switch (role) {
      case OWNER, MANAGER -> grants(MANAGER, permission);
      case BUILDER -> grants(BUILDER, permission);
      case VISITOR -> VISITOR.contains(permission);
    };
  }

  private static boolean grants(String set, String permission) {
    return set.equals(permission) || GRANTED_BY.getOrDefault(permission, Set.of()).contains(set);
  }

  /** {@code has} 是平台的單一節點判斷。 */
  public static boolean check(Predicate<String> has, String permission) {
    var by = GRANTED_BY.get(permission);
    if (by == null) return has.test(permission);
    for (var p : by) if (has.test(p)) return true;
    return false;
  }
}
