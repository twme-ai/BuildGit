package dev.twme.buildgit.server;

import dev.twme.buildgit.server.track.Tracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import net.minecraft.server.level.ServerLevel;

/**
 * 主執行緒上分刻進行的系統寫入（併回、撤銷、還原、衝突整合、形狀修補）：每刻最多花 {@link #BUDGET_NANOS}，
 * 做不完下一刻繼續，大量寫入不會讓伺服器卡住。寫入期間不標記（{@link Tracker#systemWrite}）。
 */
final class TickWrites {
  static final long BUDGET_NANOS = 20_000_000;

  /** {@code count} 個寫入；{@code world} 不為 null 時，每刻開始前確認那個 chunk 仍載入著，被卸載就先重新載入。 */
  private static final class Job {
    final ServerLevel world;
    final int chunkX, chunkZ, count;
    final IntConsumer unit;
    final CompletableFuture<Void> done = new CompletableFuture<>();
    int next;

    Job(ServerLevel world, int chunkX, int chunkZ, int count, IntConsumer unit) {
      this.world = world;
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.count = count;
      this.unit = unit;
    }
  }

  private final Platform platform;
  private final Tracker tracker;
  private final ArrayDeque<Job> jobs = new ArrayDeque<>();
  /** 已排定下一次執行，或正在執行（期間新排入的工作由這次一併處理）。 */
  private boolean scheduled, running;

  TickWrites(Platform platform, Tracker tracker) {
    this.platform = platform;
    this.tracker = tracker;
  }

  /** 排入寫入（主執行緒）。{@code world} 為 null 表示不需要確認 chunk（{@code unit} 自己處理）。 */
  CompletableFuture<Void> submit(ServerLevel world, int chunkX, int chunkZ, int count, IntConsumer unit) {
    var job = new Job(world, chunkX, chunkZ, count, unit);
    if (count == 0) job.done.complete(null);
    else {
      jobs.add(job);
      schedule(0);
    }
    return job.done;
  }

  private void schedule(long ticks) {
    if (scheduled || running) return;
    scheduled = true;
    if (ticks == 0) platform.sync(this::run);
    else platform.later(ticks, this::run);
  }

  private void run() {
    scheduled = false;
    running = true;
    long end = System.nanoTime() + BUDGET_NANOS;
    var finished = new ArrayList<Runnable>();
    try {
      tracker.systemWrite(() -> step(end, finished));
      // 寫入以外完成後才通知，接下來的動作（例如存檔）不在「系統寫入」之內；這時排入的寫入下一刻才做
      finished.forEach(Runnable::run);
    } finally {
      running = false;
    }
    if (!jobs.isEmpty()) schedule(1);
  }

  private void step(long end, List<Runnable> finished) {
    while (!jobs.isEmpty()) {
      var job = jobs.peek();
      if (job.world != null && !job.world.hasChunk(job.chunkX, job.chunkZ)) {
        // 上一刻之後被卸載了：重新載入後排回來，先做別的
        jobs.poll();
        platform.loadChunk(job.world, job.chunkX, job.chunkZ).whenComplete((c, e) -> platform.sync(() -> {
          if (e != null) job.done.completeExceptionally(e);
          else {
            jobs.addFirst(job);
            schedule(0);
          }
        }));
        continue;
      }
      try {
        while (job.next < job.count) {
          job.unit.accept(job.next++);
          if ((job.next & 63) == 0 && System.nanoTime() > end) return;
        }
      } catch (RuntimeException e) {
        jobs.poll();
        finished.add(() -> job.done.completeExceptionally(e));
        continue;
      }
      jobs.poll();
      finished.add(() -> job.done.complete(null));
      if (System.nanoTime() > end) return;
    }
  }
}
