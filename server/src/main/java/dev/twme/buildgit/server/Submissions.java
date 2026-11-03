package dev.twme.buildgit.server;

import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.minecraft.server.level.ServerPlayer;

/**
 * 提出併回（03-worlds §5.4）：fork 的世界把存檔送回來源，來源多一個 {@code submit-<玩家>-<編號>} 草稿，擁有者或管理者進去看、
 * 用一般的併回流程併回，或拒絕。資料記在來源 {@code branches.yml} 裡那個分支的 {@code submit} 底下。
 */
final class Submissions {
  private final BuildGit bg;

  Submissions(BuildGit bg) {
    this.bg = bg;
  }

  /** @param from fork 的世界 id；@param player 提出的人 */
  record Submission(String branch, String from, UUID player, String name, String message, long time) {}

  /** 來源世界還沒處理的提出，舊的在前。 */
  List<Submission> list(Workspace source) throws IOException {
    var y = source.branchInfo();
    var result = new ArrayList<Submission>();
    for (var branch : source.drafts().keySet()) {
      if (!branch.startsWith(Workspace.SUBMIT_PREFIX)) continue;
      try {
        result.add(new Submission(branch, y.getString(branch + ".submit.from"), UUID.fromString(y.getString(branch + ".submit.player")),
            y.getString(branch + ".submit.name"), y.getString(branch + ".submit.message", ""), y.getLong(branch + ".submit.time", 0)));
      } catch (RuntimeException e) {
        // 資料不完整（例如手動建的同名草稿）：不當成提出
      }
    }
    result.sort(Comparator.comparingLong(Submission::time));
    return result;
  }

  /**
   * 提出（主執行緒開始）：把 fork 裡 {@code branch} 的最新存檔送到來源。同一個 fork 再提出時更新同一個分支。物件位置不同時硬連結
   * 過去；fork 之後才生成的 chunk 補進來源的起點世界（否則那些 chunk 的改動在來源會被當成新增）。完成時（主執行緒）回傳分支名稱。
   */
  CompletableFuture<String> submit(ServerPlayer p, Workspace fork, String branch, String message) throws IOException {
    var c = fork.config();
    if (c == null || c.forkId == null || c.forkTemplate) throw new Workspace.Refused("error-submit-no-source");
    var source = bg.worlds().byId(c.forkId);
    if (source == null || !source.isOpen()) throw new Workspace.Refused("error-submit-source-gone");
    if (!bg.realms().canFork(p, source)) throw new Workspace.Refused("error-submit-source-no-fork");
    if (!source.config().acceptSubmissions) throw new Workspace.Refused("error-submit-source-closed");
    if (bg.worlds().busy(source)) throw new Workspace.Refused("error-submit-source-busy");
    var forkId = fork.buildWorld().id();
    Submission existing = null;
    for (var s : list(source)) {
      if (!s.player().equals(p.getUUID())) continue;
      if (!forkId.equals(s.from()))
        throw new Workspace.Refused("error-submit-pending", "label", source.label(), "branch", s.branch());
      existing = s;
    }
    var name = existing != null ? existing.branch() : nextName(source, p.getScoreboardName());
    boolean update = existing != null;
    var head = fork.repo().head(branch);
    boolean shared = fork.objectsDir().equals(source.objectsDir());
    var done = new CompletableFuture<String>();
    fork.async(() -> {
      try {
        fork.awaitGenesis();
        if (!shared) Workspace.copyTree(fork.objectsDir(), source.objectsDir(), true);
        source.copyGenesisFrom(fork.genesisDir(Workspace.OVERWORLD));
      } catch (Throwable t) {
        done.completeExceptionally(t);
        return;
      }
      // 補起點世界的寫入完成後才建立分支
      source.afterGenesisWrites(() -> source.sync(() -> {
        try {
          if (!source.isOpen()) throw new Workspace.Refused("error-submit-source-unloaded");
          var repo = source.repo();
          if (repo.isAncestor(head, repo.head(Repository.MAIN))) throw new Workspace.Refused("error-submit-nothing-new");
          if (update && repo.head(name).equals(head)) throw new Workspace.Refused("error-submit-same");
          source.submitBranch(name, head);
          var y = source.branchInfo();
          y.set(name + ".submit.from", forkId);
          y.set(name + ".submit.player", p.getUUID().toString());
          y.set(name + ".submit.name", p.getScoreboardName());
          y.set(name + ".submit.message", message);
          y.set(name + ".submit.time", System.currentTimeMillis());
          source.saveBranchInfo(y);
          bg.platform().log().info(p.getScoreboardName() + (update ? " updated submission " : " submitted ") + name + " to " + source.label() + " (" + head.abbrev() + ")");
          for (var reviewer : reviewers(source))
            bg.realms().notify(reviewer, update ? "submit-updated" : "submit-received", "player", p.getScoreboardName(), "world", source.label(),
                "branch", name, "message", message);
          done.complete(name);
        } catch (Throwable t) {
          done.completeExceptionally(t);
        }
      }));
    });
    return done;
  }

  /** {@code submit-<玩家名稱>-<編號>}，用還沒用過的最小編號。 */
  private static String nextName(Workspace source, String player) throws IOException {
    var base = Workspace.SUBMIT_PREFIX + player.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_") + "-";
    var branches = source.branches();
    int n = 1;
    while (branches.containsKey(base + n)) n++;
    return base + n;
  }

  /** 擁有者與管理者。 */
  private static List<UUID> reviewers(Workspace source) {
    var result = new ArrayList<UUID>();
    result.add(source.buildWorld().owner());
    source.config().members.forEach((uuid, m) -> {
      if (m.role() == WorldConfig.Role.MANAGER) result.add(uuid);
    });
    return result;
  }

  /** 拒絕（主執行緒）：刪掉分支（在裡面的人送回主世界），告訴提出的人。 */
  void reject(Workspace source, Submission s, String reason, String by) throws IOException {
    source.deleteBranch(s.branch(), true);
    bg.platform().log().info(by + " rejected submission " + s.branch() + " by " + s.name() + " to " + source.label());
    bg.realms().notify(s.player(), "submit-rejected", "world", source.label(), "by", by, "reason", reason.isBlank() ? bg.messages().raw("submit-no-reason") : reason);
  }

  /** 已經整份併進主世界的提出（主執行緒）：告訴提出的人並刪掉分支。併回、衝突整合完成、看清單時檢查。 */
  void settle(Workspace source) {
    if (source.buildWorld() == null || !source.isOpen()) return;
    try {
      var main = source.repo().head(Repository.MAIN);
      for (var s : list(source)) {
        ObjectId head = source.repo().head(s.branch());
        if (!source.repo().isAncestor(head, main)) continue;
        source.deleteBranch(s.branch(), true);
        bg.platform().log().info("Submission " + s.branch() + " by " + s.name() + " to " + source.label() + " was merged");
        bg.realms().notify(s.player(), "submit-merged", "world", source.label(), "branch", s.branch());
      }
    } catch (IOException e) {
      bg.platform().log().log(Level.WARNING, "Cannot tidy the submissions of " + source.label(), e);
    }
  }
}
