package dev.twme.buildgit.core.model;

import java.util.Objects;
import java.util.UUID;

/** 變更的作者。系統動作使用 {@link #MERGE}。 */
public record Author(UUID uuid, String name) {
  public static final Author MERGE = new Author(new UUID(0, 1), "merge");
  public static final Author SYSTEM = new Author(new UUID(0, 0), "system");

  public Author {
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(name, "name");
  }
}
