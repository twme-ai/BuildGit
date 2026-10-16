package dev.twme.buildgit.core;

import static dev.twme.buildgit.core.FakeWorld.OVERWORLD;
import static org.junit.jupiter.api.Assertions.*;

import dev.twme.buildgit.core.model.*;
import dev.twme.buildgit.core.repo.Repository;
import dev.twme.buildgit.core.sync.Bundle;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleTest {
  static final Author ALICE = new Author(UUID.randomUUID(), "alice");

  @TempDir Path dir;

  /** 取出、編碼往返、改一格並移除實體後送回：存成的版本只有這兩項變化。 */
  @Test
  void pullEditPush() throws Exception {
    var stand = UUID.randomUUID();
    var genesis = new FakeWorld().set(0, 64, 0, "minecraft:stone").set(1, 64, 0, "minecraft:dirt")
        .entity(stand, 0.5, 65, 0.5, "minecraft:armor_stand");
    var repo = Repository.init(dir, Author.SYSTEM, 1);
    var base = repo.head(Repository.MAIN);
    var box = new Box(0, 64, 0, 1, 65, 0);
    var entity = genesis.readEntity(OVERWORLD, stand).orElseThrow();

    var pulled = Bundle.capture("house", OVERWORLD, box, 0, base.hex(), repo.reader(base), genesis, List.of(entity), e -> true);
    assertEquals(Cell.of("minecraft:dirt"), pulled.cell(1, 64, 0));
    assertEquals(List.of(entity), pulled.entities());
    assertEquals(pulled, Bundle.decode(pulled.encode(), Upgrader.NONE));

    var cells = pulled.cells().clone();
    cells[pulled.index(1, 65, 0)] = Cell.of("minecraft:glass");
    var edited = new Bundle("house", OVERWORLD, box, 0, base.hex(), cells, List.of());

    repo.createBranch("house", base);
    var id = repo.commit(new Repository.CommitRequest("house", base, edited.marks(ALICE, pulled.entityMap().keySet()), edited.source(),
        genesis, ALICE, 2, "sync", 0, new TreeMap<>())).orElseThrow();
    var changes = repo.changeset(repo.commit(id).changeset());
    assertEquals(1, changes.cells().size());
    assertEquals(1, changes.entities().size());
    assertEquals(Cell.of("minecraft:glass"), repo.reader(id).cell(OVERWORLD, new BlockPos(1, 65, 0)));
  }
}
