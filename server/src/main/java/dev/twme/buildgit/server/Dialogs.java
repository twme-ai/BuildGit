package dev.twme.buildgit.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;

/**
 * 原版的對話框（1.21.6 起）：要打字的地方用它，不必叫玩家到聊天欄打指令。照資料包的 JSON 格式組出來再解碼，
 * 各版本的建構子不同也不用分開寫。按下確定時用填的值執行一行指令（{@code $(鍵)} 換成輸入的值）。
 */
final class Dialogs {
  private Dialogs() {}

  static JsonObject text(String key, Component label, String initial, int maxLength) {
    var o = new JsonObject();
    o.addProperty("type", "minecraft:text");
    o.addProperty("key", key);
    o.add("label", json(label));
    o.addProperty("initial", initial == null ? "" : initial);
    o.addProperty("max_length", maxLength);
    o.addProperty("width", 300);
    return o;
  }

  /** 單選；{@code options} 是（值, 顯示的文字），{@code selected} 是一開始選中的值。 */
  static JsonObject choice(String key, Component label, List<Option> options, String selected) {
    var o = new JsonObject();
    o.addProperty("type", "minecraft:single_option");
    o.addProperty("key", key);
    o.add("label", json(label));
    o.addProperty("width", 300);
    var list = new JsonArray();
    for (var opt : options) {
      var e = new JsonObject();
      e.addProperty("id", opt.id());
      e.add("display", json(opt.display()));
      if (opt.id().equals(selected)) e.addProperty("initial", true);
      list.add(e);
    }
    o.add("options", list);
    return o;
  }

  record Option(String id, Component display) {}

  /** 確定／取消兩個按鈕的對話框，確定時執行 {@code command}（不含斜線）。 */
  static void open(BuildGit bg, ServerPlayer p, Component title, Component body, List<JsonObject> inputs, Component yes, Component no, String command) {
    var d = new JsonObject();
    d.addProperty("type", "minecraft:confirmation");
    d.add("title", json(title));
    if (body != null) {
      var b = new JsonObject();
      b.addProperty("type", "minecraft:plain_message");
      b.add("contents", json(body));
      b.addProperty("width", 300);
      var bodies = new JsonArray();
      bodies.add(b);
      d.add("body", bodies);
    }
    var in = new JsonArray();
    inputs.forEach(in::add);
    d.add("inputs", in);
    var action = new JsonObject();
    action.addProperty("type", "minecraft:dynamic/run_command");
    action.addProperty("template", command);
    var yesButton = new JsonObject();
    yesButton.add("label", json(yes));
    yesButton.add("action", action);
    d.add("yes", yesButton);
    var noButton = new JsonObject();
    noButton.add("label", json(no));
    d.add("no", noButton);
    var ops = RegistryOps.create(JsonOps.INSTANCE, bg.platform().server().registryAccess());
    var dialog = Dialog.DIRECT_CODEC.parse(ops, d).getOrThrow(e -> new IllegalStateException("對話框格式不對：" + e));
    p.openDialog(Holder.direct(dialog));
  }

  private static JsonElement json(Component c) {
    return GsonComponentSerializer.gson().serializeToTree(c);
  }
}
