package dev.twme.buildgit.core.backup;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CronTest {
  private static ZonedDateTime at(int day, int hour, int minute) {
    return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZoneOffset.UTC); // 2026-10-18 是星期日
  }

  @Test
  void matchesFields() {
    var daily = Cron.parse("0 4 * * *");
    assertTrue(daily.matches(at(21, 4, 0)));
    assertFalse(daily.matches(at(21, 4, 1)));
    var every15 = Cron.parse("*/15 9-17 * * 1-5");
    assertTrue(every15.matches(at(21, 9, 45)));
    assertFalse(every15.matches(at(18, 9, 45))); // 星期日
    assertTrue(Cron.parse("30 2 * * 7").matches(at(18, 2, 30)));
    assertTrue(Cron.parse("0 0 1,21 * 1").matches(at(21, 0, 0))); // 日與星期都指定時符合其一
    assertThrows(IllegalArgumentException.class, () -> Cron.parse("60 * * * *"));
    assertThrows(IllegalArgumentException.class, () -> Cron.parse("* * *"));
  }
}
