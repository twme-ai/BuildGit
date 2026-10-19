package dev.twme.buildgit.server;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
      VIEWER, List.of("buildgit.menu", "buildgit.status", "buildgit.log", "buildgit.graph", "buildgit.show", "buildgit.diff",
          "buildgit.blame", "buildgit.inspect", "buildgit.who", "buildgit.sel", "buildgit.branch.list", "buildgit.go", "buildgit.back",
          "buildgit.view"),
      BUILDER, List.of(VIEWER, "buildgit.try", "buildgit.save", "buildgit.pull", "buildgit.push"),
      MANAGER, List.of(BUILDER, "buildgit.publish", "buildgit.commit", "buildgit.merge", "buildgit.merge.resolve", "buildgit.revert",
          "buildgit.restore", "buildgit.mark", "buildgit.branch.create", "buildgit.branch.delete"),
      ADMIN, List.of(MANAGER, "buildgit.init", "buildgit.reload", "buildgit.disk", "buildgit.genesis"));

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

  /** {@code has} 是平台的單一節點判斷。 */
  public static boolean check(Predicate<String> has, String permission) {
    var by = GRANTED_BY.get(permission);
    if (by == null) return has.test(permission);
    for (var p : by) if (has.test(p)) return true;
    return false;
  }
}
