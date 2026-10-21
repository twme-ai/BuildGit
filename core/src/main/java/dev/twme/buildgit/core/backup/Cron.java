package dev.twme.buildgit.core.backup;

import java.time.ZonedDateTime;
import java.util.BitSet;

/**
 * 備份工作的排程（設計 §2.7）：五個欄位「分 時 日 月 星期」，每個欄位可以是 {@code *}、數字、範圍 {@code a-b}、
 * 清單 {@code a,b}、間隔 {@code *}{@code /n} 或 {@code a-b/n}。星期 0 和 7 都是星期日。
 * 日與星期都有指定時，符合其中一個就算（和一般的 cron 相同）。
 */
public final class Cron {
  private final BitSet minute, hour, day, month, weekday;
  private final boolean anyDay, anyWeekday;
  private final String text;

  private Cron(String text, String[] f) {
    this.text = text;
    minute = field(f[0], 0, 59);
    hour = field(f[1], 0, 23);
    day = field(f[2], 1, 31);
    month = field(f[3], 1, 12);
    weekday = field(f[4], 0, 7);
    if (weekday.get(7)) weekday.set(0);
    anyDay = f[2].equals("*");
    anyWeekday = f[4].equals("*");
  }

  /** @throws IllegalArgumentException 格式不對 */
  public static Cron parse(String text) {
    var f = text.strip().split("\\s+");
    if (f.length != 5) throw new IllegalArgumentException("排程要有五個欄位（分 時 日 月 星期）：" + text);
    return new Cron(text.strip(), f);
  }

  public boolean matches(ZonedDateTime t) {
    if (!minute.get(t.getMinute()) || !hour.get(t.getHour()) || !month.get(t.getMonthValue())) return false;
    boolean d = day.get(t.getDayOfMonth()), w = weekday.get(t.getDayOfWeek().getValue() % 7);
    if (anyDay || anyWeekday) return d && w;
    return d || w;
  }

  private static BitSet field(String text, int min, int max) {
    var bits = new BitSet();
    for (var part : text.split(",")) {
      int step = 1;
      int slash = part.indexOf('/');
      if (slash >= 0) {
        step = number(part.substring(slash + 1), 1, max);
        part = part.substring(0, slash);
      }
      int from, to;
      if (part.equals("*")) {
        from = min;
        to = max;
      } else if (part.indexOf('-') > 0) {
        from = number(part.substring(0, part.indexOf('-')), min, max);
        to = number(part.substring(part.indexOf('-') + 1), from, max);
      } else {
        from = number(part, min, max);
        to = slash >= 0 ? max : from;
      }
      for (int i = from; i <= to; i += step) bits.set(i);
    }
    return bits;
  }

  private static int number(String s, int min, int max) {
    try {
      int n = Integer.parseInt(s);
      if (n >= min && n <= max) return n;
    } catch (NumberFormatException e) {
      // 下面一起說明
    }
    throw new IllegalArgumentException("排程的值要在 " + min + "～" + max + "：" + s);
  }

  @Override
  public String toString() {
    return text;
  }
}
