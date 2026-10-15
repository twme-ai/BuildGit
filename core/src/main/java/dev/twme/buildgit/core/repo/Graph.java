package dev.twme.buildgit.core.repo;

import dev.twme.buildgit.core.store.ObjectId;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

/**
 * 類似 {@code git log --graph} 的分支歷史圖：每一列一個存檔，左邊用方框字元畫出分支線。
 *
 * <p>每條分支線占兩個字元（線本身與右側的間隔），所以畫面要用等寬字型才會對齊。隱藏的存檔（例如自動存檔）
 * 不佔列，它的子存檔直接連到最近的可見祖先。
 */
public final class Graph {
  private Graph() {}

  /** 一列：圖形前綴與存檔。 */
  public record Row(String graph, ObjectId id, Commit commit) {}

  /**
   * @param heads 起點（各分支的最新存檔）
   * @param visible 要顯示的存檔；其他的不佔列
   * @param limit 最多幾列
   */
  public static List<Row> build(Repository repo, Collection<ObjectId> heads, Predicate<Commit> visible, int limit) throws IOException {
    var commits = new HashMap<ObjectId, Commit>();
    var rows = new ArrayList<ObjectId>();
    // 依時間由新到舊走訪所有分支
    var queue = new PriorityQueue<ObjectId>(Comparator.comparingLong((ObjectId id) -> commits.get(id).time()).reversed());
    var seen = new HashSet<ObjectId>();
    for (var h : heads) if (seen.add(h)) {
      commits.put(h, repo.commit(h));
      queue.add(h);
    }
    int walked = 0;
    while (!queue.isEmpty() && rows.size() < limit && walked++ < 20_000) {
      var id = queue.poll();
      var c = commits.get(id);
      if (visible.test(c)) rows.add(id);
      for (var p : c.parents())
        if (seen.add(p)) {
          commits.put(p, repo.commit(p));
          queue.add(p);
        }
    }
    var result = new ArrayList<Row>();
    var lanes = new ArrayList<ObjectId>();
    for (var id : rows) {
      var parents = visibleParents(repo, commits, id, visible);
      result.add(new Row(row(lanes, id, parents), id, commits.get(id)));
    }
    return result;
  }

  /** 父存檔，略過隱藏的存檔直到可見的為止。 */
  private static List<ObjectId> visibleParents(
      Repository repo, Map<ObjectId, Commit> commits, ObjectId id, Predicate<Commit> visible) throws IOException {
    var result = new LinkedHashSet<ObjectId>();
    var stack = new ArrayDeque<ObjectId>(commits.get(id).parents().reversed());
    var seen = new HashSet<ObjectId>();
    int steps = 0;
    while (!stack.isEmpty() && steps++ < 5_000) {
      var p = stack.pop();
      if (!seen.add(p)) continue;
      var c = commits.get(p);
      if (c == null) commits.put(p, c = repo.commit(p));
      if (visible.test(c)) result.add(p);
      else for (var q : c.parents().reversed()) stack.push(q);
    }
    return List.copyOf(result);
  }

  /** 畫出一列並更新分支線（存檔是 *）：{@code lanes} 的每一格是那條線接下來等待的存檔（null 表示空著）。 */
  static String row(List<ObjectId> lanes, ObjectId id, List<ObjectId> parents) {
    int col = lanes.indexOf(id);
    if (col < 0) {
      col = lanes.indexOf(null);
      if (col < 0) {
        col = lanes.size();
        lanes.add(id);
      } else lanes.set(col, id);
    }
    int width = lanes.size();
    // 先決定新的父存檔要放哪條線，畫面寬度才確定
    var targets = new ArrayList<Integer>();
    var joins = new ArrayList<Integer>();
    for (int i = 1; i < parents.size(); i++) {
      var p = parents.get(i);
      int at = lanes.indexOf(p);
      if (at >= 0 && at != col) {
        joins.add(at);
        continue;
      }
      int free = -1;
      for (int k = col + 1; k < lanes.size(); k++)
        if (lanes.get(k) == null) {
          free = k;
          break;
        }
      if (free < 0) {
        free = lanes.size();
        lanes.add(null);
      }
      lanes.set(free, p);
      targets.add(free);
    }
    width = Math.max(width, lanes.size());
    var cells = new char[width * 2];
    Arrays.fill(cells, ' ');
    for (int k = 0; k < width; k++) if (k != col && k < lanes.size() && lanes.get(k) != null && !targets.contains(k)) cells[k * 2] = '│';
    // 從上面接進來的分支線（這個存檔是它們的分岔點）
    var ends = new ArrayList<Integer>();
    for (int k = 0; k < width; k++) if (k != col && id.equals(lanes.get(k))) ends.add(k);
    for (int k : ends) cells[k * 2] = k > col ? '┘' : '└';
    for (int k : targets) cells[k * 2] = k > col ? '┐' : '┌';
    for (int k : joins) cells[k * 2] = k > col ? '┤' : '├';
    // 轉角都放好之後才畫橫線：經過比較近的轉角時要把它改成 T 字
    for (var group : List.of(ends, targets, joins)) for (int k : group) horizontal(cells, col, k);
    cells[col * 2] = '*';
    // 更新：這條線改等第一個父存檔；匯入的分支線結束
    for (int k : ends) lanes.set(k, null);
    lanes.set(col, parents.isEmpty() ? null : parents.getFirst());
    while (!lanes.isEmpty() && lanes.getLast() == null) lanes.removeLast();
    int end = cells.length;
    while (end > 0 && cells[end - 1] == ' ') end--;
    return new String(cells, 0, end);
  }

  /** 在兩條線之間畫橫線；經過的直線改成交叉，經過的轉角改成 T 字。 */
  private static void horizontal(char[] cells, int from, int to) {
    int a = Math.min(from, to) * 2 + 1, b = Math.max(from, to) * 2 - 1;
    for (int i = a; i <= b; i++)
      cells[i] = switch (cells[i]) {
        case ' ' -> '─';
        case '│', '┤', '├' -> '┼';
        case '┘', '└' -> '┴';
        case '┐', '┌' -> '┬';
        default -> cells[i];
      };
  }
}
