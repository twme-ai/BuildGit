package dev.twme.buildgit.server;

import java.util.concurrent.atomic.AtomicLong;

/** 背景工作做到哪裡，給 bossbar 顯示（{@link Hud#start}）。總量為 0 表示還不知道或算不出來。任何執行緒都可以更新。 */
final class Progress {
  private final AtomicLong done = new AtomicLong();
  private volatile long total;

  /** 開始新的一段：之後 {@link #step} {@code total} 次就是完成。 */
  void reset(long total) {
    done.set(0);
    this.total = total;
  }

  void step() {
    done.incrementAndGet();
  }

  /** 0 到 1；不知道總量時為 -1。 */
  float fraction() {
    long t = total;
    return t <= 0 ? -1 : Math.min(1f, (float) done.get() / t);
  }
}
