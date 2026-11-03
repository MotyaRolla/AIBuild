package dev.aibuild.command;

import dev.aibuild.AIBuildPlugin;
import dev.aibuild.analysis.IsoRenderer;
import dev.aibuild.build.Build;
import dev.aibuild.build.BuildVersion;
import dev.aibuild.config.BuildMode;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.pipeline.BuildPipeline;
import dev.aibuild.pipeline.Job;
import dev.aibuild.util.Msg;
import dev.aibuild.world.PlacementMath;
import dev.aibuild.world.RegionCapture;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/** /aib … command tree (Paper BasicCommand). */
public final class AIBuildCommand implements BasicCommand {
    private static final List<String> SUBS = List.of("create", "edit", "chat", "undo", "redo", "list", "select", "info",
            "tp", "remove", "cancel", "render", "fix", "wand", "pos1", "pos2", "import", "mode", "bench", "status", "reload", "help");
    private final AIBuildPlugin plugin;

    public AIBuildCommand(AIBuildPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "aibuild.use";
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        CommandSender s = src.getSender();
        if (args.length == 0) {
            help(s);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (sub) {
                case "create", "build", "new", "c", "создать" -> create(s, rest);
                case "edit", "e", "change", "изменить" -> edit(s, rest);
                case "chat" -> chat(s);
                case "undo" -> undoRedo(s, rest, true);
                case "redo" -> undoRedo(s, rest, false);
                case "list", "ls" -> list(s, rest);
                case "select", "sel" -> select(s, rest);
                case "info" -> info(s, rest);
                case "tp" -> tp(s, rest);
                case "remove", "delete", "rm" -> remove(s, rest);
                case "cancel", "stop" -> cancel(s, rest);
                case "render" -> render(s, rest);
                case "fix", "autofix" -> fix(s, rest);
                case "wand" -> wand(s);
                case "pos1" -> pos(s, 1);
                case "pos2" -> pos(s, 2);
                case "import" -> importSel(s, rest);
                case "mode", "modes", "режим" -> mode(s, rest);
                case "bench", "benchmark" -> bench(s, rest);
                case "status" -> status(s);
                case "reload" -> reload(s);
                default -> help(s);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            Msg.send(s, "<red><v0></red>", e.getMessage());
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack src, String[] args) {
        if (args.length <= 1) {
            String p = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(x -> x.startsWith(p)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("select", "info", "tp", "remove", "render", "fix", "undo", "redo", "cancel").contains(sub)) {
            return plugin.store().all().stream().map(b -> b.id).filter(id -> id.startsWith(args[1])).toList();
        }
        List<String> modeIds = new java.util.ArrayList<>(plugin.cfg().modes.keySet());
        if (sub.equals("mode") && args.length == 2) {
            List<String> l = new java.util.ArrayList<>(modeIds);
            l.add("default");
            return l.stream().filter(x -> x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        String last = args[args.length - 1];
        String prev = args.length >= 2 ? args[args.length - 2] : "";
        if ((sub.equals("create") || sub.equals("edit")) && (prev.equals("--mode") || prev.equals("-m"))) {
            return modeIds.stream().filter(x -> x.startsWith(last.toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && sub.equals("create")) return List.of("<описание постройки>", "--mode", "--at");
        if (args.length == 2 && sub.equals("edit")) return List.of("<что изменить>", "--mode");
        return List.of();
    }

    // ------------------------------------------------------------------ create / edit

    /** Result of stripping {@code --mode X} / {@code -m X} / {@code --extra} style flags from the arguments. */
    private record ModeArgs(BuildMode mode, String[] rest) {
    }

    private ModeArgs stripMode(String[] a) {
        java.util.List<String> out = new java.util.ArrayList<>();
        BuildMode mode = null;
        for (int i = 0; i < a.length; i++) {
            String t = a[i];
            String low = t.toLowerCase(Locale.ROOT);
            if ((low.equals("--mode") || low.equals("-m") || low.equals("--режим")) && i + 1 < a.length) {
                String id = BuildMode.normalizeId(a[i + 1]);
                if (id == null || !plugin.cfg().modes.containsKey(id)) {
                    throw new IllegalArgumentException("Неизвестный режим: " + a[i + 1] + ". Доступны: " + String.join(", ", plugin.cfg().modes.keySet()));
                }
                mode = plugin.cfg().modes.get(id);
                i++;
                continue;
            }
            if (low.startsWith("--") && !low.equals("--at")) {
                String id = BuildMode.normalizeId(low.substring(2));
                if (id != null && plugin.cfg().modes.containsKey(id)) {
                    mode = plugin.cfg().modes.get(id);
                    continue;
                }
            }
            out.add(t);
        }
        return new ModeArgs(mode, out.toArray(new String[0]));
    }

    private void create(CommandSender s, String[] a0) {
        requireKey();
        ModeArgs ma = stripMode(a0);
        String[] a = ma.rest();
        BuildPipeline.Anchor anchor;
        int start = 0;
        if (a.length >= 6 && a[0].equalsIgnoreCase("--at")) {
            World w = Bukkit.getWorld(a[1]);
            if (w == null) throw new IllegalArgumentException("Мир не найден: " + a[1]);
            int x = Integer.parseInt(a[2]);
            Integer y = a[3].equals("~") ? null : Integer.parseInt(a[3]);
            int z = Integer.parseInt(a[4]);
            BlockFace f = BlockFace.valueOf(a[5].toUpperCase(Locale.ROOT));
            anchor = new BuildPipeline.Anchor(w.getName(), x, y, z, PlacementMath.rotationForPlayerFacing(f));
            start = 6;
        } else if (s instanceof Player p) {
            BlockFace f = PlacementMath.cardinal(p.getLocation().getYaw());
            Location l = p.getLocation();
            int d = plugin.cfg().distance + 1;
            anchor = new BuildPipeline.Anchor(p.getWorld().getName(), l.getBlockX() + f.getModX() * d, null,
                    l.getBlockZ() + f.getModZ() * d, PlacementMath.rotationForPlayerFacing(f));
        } else {
            throw new IllegalArgumentException("Из консоли: /aib create --at <мир> <x> <y|~> <z> <north|east|south|west> <описание>");
        }
        String prompt = String.join(" ", Arrays.copyOfRange(a, start, a.length)).trim();
        if (prompt.isEmpty()) throw new IllegalArgumentException("Опишите постройку: /aib create <описание>");
        UUID owner = s instanceof Player p ? p.getUniqueId() : null;
        checkJobLimits(owner);
        Build b = plugin.store().create(owner, s.getName(), anchor.world());
        b.originalPrompt = prompt;
        b.name = "Новая постройка";
        if (owner != null) plugin.store().setActive(owner, b.id);
        plugin.store().save(b);
        BuildMode mode = ma.mode() != null ? ma.mode() : plugin.modeOf(owner);
        plugin.pipeline().startCreate(b, prompt, anchor, mode);
        announce(s, "<gray>ИИ (<white><v0></white>, режим <light_purple><v3></light_purple>, <v4>) начал проектирование постройки <white>[<v1>]</white>: <italic><v2></italic></gray>",
                plugin.cfg().model, b.id, prompt, mode.title, mode.eta);
        announce(s, "<dark_gray>Прогресс — в боссбаре. Отменить: /aib cancel " + b.id + ". Постройка появится перед вами и будет расти по этапам.</dark_gray>");
    }

    private void announce(CommandSender sender, String message, Object... args) {
        Msg.send(sender,message,args);
        if (sender.equals(Bukkit.getConsoleSender())) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission("aibuild.admin")) Msg.send(player,message,args);
            }
        }
    }

    private void edit(CommandSender s, String[] a0) {
        requireKey();
        ModeArgs ma = stripMode(a0);
        String[] a = ma.rest();
        // optional explicit target: /aib edit <id> <request>
        String id = null;
        if (a.length > 1 && plugin.store().get(a[0]) != null) {
            id = a[0];
            a = Arrays.copyOfRange(a, 1, a.length);
        }
        String req = String.join(" ", a).trim();
        if (req.isEmpty()) throw new IllegalArgumentException("Что изменить? /aib edit [id] <запрос>");
        startEdit(s, active(s, id, true), req, ma.mode());
    }

    /** Also used by chat mode (player's chosen mode). */
    public void startEdit(CommandSender s, Build b, String req) {
        startEdit(s, b, req, null);
    }

    public void startEdit(CommandSender s, Build b, String req, BuildMode explicit) {
        UUID owner = s instanceof Player p ? p.getUniqueId() : null;
        BuildMode mode = explicit != null ? explicit : plugin.modeOf(owner);
        if (b.currentModel() == null) throw new IllegalStateException("У постройки нет готовой версии (дождитесь окончания генерации).");
        checkJobLimits(owner);
        if (plugin.pipeline().jobFor(b.id) != null) throw new IllegalStateException("С этой постройкой уже работает ИИ.");
        plugin.pipeline().startEdit(b, req, mode);
        Msg.send(s, "<gray>ИИ изменяет <white><v0> [<v1>]</white> (режим <light_purple><v3></light_purple>): <italic><v2></italic></gray>", b.name, b.id, req, mode.title);
    }

    private void checkJobLimits(UUID owner) {
        if (owner != null && plugin.pipeline().jobOf(owner) != null) {
            throw new IllegalStateException("У вас уже идёт задача ИИ. Дождитесь её окончания или /aib cancel.");
        }
        if (plugin.pipeline().jobs().size() >= plugin.cfg().maxConcurrentJobs * 3) {
            throw new IllegalStateException("Слишком много задач ИИ в очереди, попробуйте позже.");
        }
    }

    private void requireKey() {
        if (plugin.cfg().apiKey.isBlank()) {
            throw new IllegalStateException("Не задан API-ключ Command Code (config.yml → api.key).");
        }
    }

    private void chat(CommandSender s) {
        if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игроков.");
        boolean on = plugin.toggleChatMode(p.getUniqueId());
        if (on) {
            Msg.send(s, "<green>Режим чата с ИИ включён.</green> <gray>Пишите в чат, что изменить в активной постройке (или опишите новую, если активной нет). «выход» — выключить.</gray>");
        } else {
            Msg.send(s, "<yellow>Режим чата с ИИ выключен.</yellow>");
        }
    }

    // ------------------------------------------------------------------ versions

    private void undoRedo(CommandSender s, String[] a, boolean undo) {
        Build b = active(s, a.length > 0 ? a[0] : null, true);
        if (plugin.pipeline().jobFor(b.id) != null) throw new IllegalStateException("Сначала дождитесь окончания задачи ИИ.");
        if (undo ? !b.canUndo() : !b.canRedo()) throw new IllegalStateException(undo ? "Нечего отменять." : "Нечего повторять.");
        if (plugin.placer().isBusy(b.id)) throw new IllegalStateException("Постройка ещё размещается.");
        b.currentVersion += undo ? -1 : 1;
        VoxelModel m = b.currentModel();
        plugin.placer().place(b, m, p -> { }).whenComplete((r, e) -> {
            plugin.store().save(b);
            if (e != null) {
                Msg.send(s, "<red>Ошибка размещения: <v0></red>", String.valueOf(e.getMessage()));
            } else if (m == null) {
                Msg.send(s, "<yellow>Постройка <white><v0></white> убрана (вернули исходный ландшафт). /aib redo — вернуть.</yellow>", b.id);
            } else {
                BuildVersion v = b.current();
                Msg.send(s, "<gray><v0> → версия <white>v<v1></white> (<v2>), изменено блоков: <v3></gray>",
                        undo ? "Откат" : "Повтор", v.number, clip(v.request, 60), r.changed());
            }
        });
    }

    private void remove(CommandSender s, String[] a) {
        Build b = active(s, a.length > 0 ? a[0] : null, true);
        Job j = plugin.pipeline().jobFor(b.id);
        if (j != null) j.cancelled.set(true);
        plugin.placer().place(b, null, p -> { }).whenComplete((r, e) -> {
            plugin.store().delete(b.id);
            Msg.send(s, "<yellow>Постройка <white><v0></white> удалена, ландшафт восстановлен (<v1> блоков).</yellow>", b.id, r == null ? 0 : r.changed());
        });
    }

    private void cancel(CommandSender s, String[] a) {
        Job j = null;
        if (a.length > 0) j = plugin.pipeline().jobFor(a[0]);
        else if (s instanceof Player p) j = plugin.pipeline().jobOf(p.getUniqueId());
        else if (!plugin.pipeline().jobs().isEmpty()) j = plugin.pipeline().jobs().iterator().next();
        if (j == null) throw new IllegalStateException("Нет активной задачи ИИ.");
        if (s instanceof Player p && j.owner != null && !j.owner.equals(p.getUniqueId()) && !s.hasPermission("aibuild.admin")) {
            throw new IllegalStateException("Это не ваша задача.");
        }
        j.cancelled.set(true);
        Msg.send(s, "<yellow>Отмена задачи для постройки <v0>…</yellow>", j.buildId);
    }

    // ------------------------------------------------------------------ browse

    private void list(CommandSender s, String[] a) {
        List<Build> builds = new ArrayList<>(plugin.store().all());
        boolean all = a.length > 0 && a[0].equalsIgnoreCase("all") || !(s instanceof Player);
        if (!all && s instanceof Player p) builds.removeIf(b -> !p.getUniqueId().equals(b.owner));
        builds.sort(Comparator.comparingLong((Build b) -> b.updatedAt).reversed());
        if (builds.isEmpty()) {
            Msg.send(s, "<gray>Построек нет. Создайте: /aib create <описание></gray>");
            return;
        }
        Msg.send(s, "<white>Постройки (<v0>):</white>", builds.size());
        String active = s instanceof Player p ? plugin.store().getActive(p.getUniqueId()) : null;
        for (Build b : builds.subList(0, Math.min(20, builds.size()))) {
            Job j = plugin.pipeline().jobFor(b.id);
            Msg.raw(s, "<click:run_command:'/aib select " + b.id + "'><hover:show_text:'Выбрать'> <v0><aqua>[<v1>]</aqua> <white><v2></white> <gray>v<v3>/<v4> · <v5> · <v6><v7></gray></hover></click>",
                    b.id.equals(active) ? "▶ " : "  ", b.id, b.name, Math.max(0, b.currentVersion + 1), b.versions.size(),
                    b.ownerName, b.worldName + " " + b.originX + "," + b.originY + "," + b.originZ,
                    (b.current()!=null && Boolean.FALSE.equals(b.current().approved)?" · ЧЕРНОВИК":"")+(j != null ? " · ИИ: " + j.status : ""));
        }
    }

    private void select(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игроков.");
        Build b;
        if (a.length > 0 && !a[0].equalsIgnoreCase("look")) {
            b = plugin.store().get(a[0]);
            if (b == null) throw new IllegalArgumentException("Постройка не найдена: " + a[0]);
        } else {
            b = lookAt(p);
            if (b == null) throw new IllegalStateException("Посмотрите на постройку (или встаньте в неё), либо укажите id.");
        }
        plugin.store().setActive(p.getUniqueId(), b.id);
        Msg.send(s, "<gray>Активная постройка: <white><v0> [<v1>]</white></gray>", b.name, b.id);
    }

    private Build lookAt(Player p) {
        Block t = p.getTargetBlockExact(120);
        if (t != null) {
            Build b = plugin.store().findAt(t.getWorld().getName(), t.getX(), t.getY(), t.getZ());
            if (b != null) return b;
        }
        Location l = p.getLocation();
        return plugin.store().findAt(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private void info(CommandSender s, String[] a) {
        Build b = active(s, a.length > 0 ? a[0] : null, false);
        Msg.send(s, "<white><v0></white> <aqua>[<v1>]</aqua> <gray>автор <v2>, создано <v3></gray>", b.name, b.id, b.ownerName,
                new SimpleDateFormat("dd.MM HH:mm").format(new Date(b.createdAt)));
        Msg.raw(s, "<gray>  Запрос: <white><v0></white></gray>", clip(b.originalPrompt, 200));
        Msg.raw(s, "<gray>  Мир <v0> @ <v1> <v2> <v3>, поворот <v4>°</gray>", b.worldName, b.originX, b.originY, b.originZ, b.rotation * 90);
        if (b.current()!=null && b.current().approved!=null) Msg.raw(s,Boolean.TRUE.equals(b.current().approved)
                ? "<green>  Проверка пройдена.</green>" : "<yellow>  ЧЕРНОВИК: проверка не пройдена. /aib edit для исправления.</yellow>");
        for (int i = 0; i < b.versions.size(); i++) {
            BuildVersion v = b.versions.get(i);
            Msg.raw(s, "<gray>  <v0>v<v1>: <white><v2></white> · <v3> блоков · оценка <v4><v5></gray>", i == b.currentVersion ? "▶ " : "  ",
                    v.number, clip(v.request, 70), v.model == null ? 0 : v.model.nonAirCount(), v.score,
                    (v.mode == null ? "" : " · режим " + plugin.cfg().mode(v.mode).title)
                            +(Boolean.FALSE.equals(v.approved)?" · ЧЕРНОВИК":""));
        }
        Job j = plugin.pipeline().jobFor(b.id);
        if (j != null) Msg.raw(s, "<yellow>  ИИ: <v0> (<v1>%, <v2> c)</yellow>", j.status, (int) (j.progress * 100), j.elapsedSeconds());
    }

    private void tp(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игроков.");
        Build b = active(s, a.length > 0 ? a[0] : null, false);
        World w = Bukkit.getWorld(b.worldName);
        if (w == null) throw new IllegalStateException("Мир не загружен.");
        VoxelModel m = b.currentModel();
        int[] mb = m == null ? new int[]{0, 0, 0, 10, 10, 10} : m.bounds();
        int[] wb = PlacementMath.worldBounds(b, mb);
        // stand in front of the build's front (model south side), looking at it
        int[] front = PlacementMath.rotate((mb[0] + mb[3]) / 2, mb[5] + 6, b.rotation);
        Location l = new Location(w, b.originX + front[0] + 0.5, wb[1] + 2, b.originZ + front[1] + 0.5);
        l.setY(w.getHighestBlockYAt(l) + 1);
        Location c = new Location(w, (wb[0] + wb[3]) / 2.0 + 0.5, (wb[1] + wb[4]) / 2.0, (wb[2] + wb[5]) / 2.0 + 0.5);
        l.setDirection(c.toVector().subtract(l.toVector()));
        p.teleportAsync(l);
        Msg.send(s, "<gray>Телепорт к <white><v0></white></gray>", b.name);
    }

    private void render(CommandSender s, String[] a) {
        Build b = active(s, a.length > 0 ? a[0] : null, false);
        VoxelModel m = b.currentModel();
        if (m == null) throw new IllegalStateException("Нет версии для рендера.");
        VoxelModel copy = m.copy();
        int ver = b.current().number;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File dir = new File(plugin.getDataFolder(), "renders");
                dir.mkdirs();
                File f = new File(dir, b.id + "-v" + ver + ".png");
                IsoRenderer.writePng(dev.aibuild.render.ContactSheet.overview(copy, plugin.blockInfo(), 2400), f);
                Msg.send(s, "<gray>Рендер сохранён: <white><v0></white></gray>", "plugins/AIBuild/renders/" + f.getName());
            } catch (Exception e) {
                Msg.send(s, "<red>Ошибка рендера: <v0></red>", String.valueOf(e.getMessage()));
            }
        });
    }

    /** Deterministic stair/slab orientation repair of the current version (no AI call) as a new version. */
    private void fix(CommandSender s, String[] a) {
        Build b = active(s, a.length > 0 ? a[0] : null, true);
        if (plugin.pipeline().jobFor(b.id) != null) throw new IllegalStateException("С этой постройкой сейчас работает ИИ.");
        if (b.currentModel() == null) throw new IllegalStateException("Нет версии для исправления.");
        if (plugin.placer().isBusy(b.id)) throw new IllegalStateException("Постройка ещё размещается.");
        VoxelModel m = b.currentModel().copy();
        java.util.List<dev.aibuild.model.OrientationFixer.Change> changes = dev.aibuild.model.OrientationFixer.analyze(m, plugin.blockInfo());
        if (changes.isEmpty()) {
            Msg.send(s, "<green>Ступеньки и полублоки постройки <white><v0></white> уже ориентированы правильно.</green>", b.id);
            return;
        }
        dev.aibuild.model.OrientationFixer.fix(m, plugin.blockInfo());
        dev.aibuild.model.ShapeResolver.resolve(m, plugin.blockInfo());
        BuildVersion v = new BuildVersion();
        v.number = b.versions.get(b.versions.size() - 1).number + 1;
        v.request = "автоисправление ступенек и полублоков";
        v.summary = changes.size() + " блоков переориентировано";
        v.model = m;
        v.createdAt = System.currentTimeMillis();
        v.score = b.current().score;
        v.mode = b.current().mode;
        if (Boolean.FALSE.equals(b.current().approved)) v.approved=false;
        while (b.versions.size() > b.currentVersion + 1) b.versions.remove(b.versions.size() - 1);
        b.versions.add(v);
        b.currentVersion = b.versions.size() - 1;
        b.history.add(v.request);
        b.updatedAt = System.currentTimeMillis();
        java.util.Map<String, Integer> byRule = new java.util.LinkedHashMap<>();
        for (var c : changes) byRule.merge(c.rule(), 1, Integer::sum);
        plugin.placer().place(b, m, p -> { }).whenComplete((r, e) -> {
            plugin.store().save(b);
            if (e != null) {
                Msg.send(s, "<red>Ошибка размещения: <v0></red>", String.valueOf(e.getMessage()));
                return;
            }
            Msg.send(s, "<green>Исправлено <v0> ступенек/полублоков в <white><v1></white> → v<v2> (/aib undo — вернуть).</green>",
                    changes.size(), b.id, v.number);
            for (var en : byRule.entrySet()) Msg.raw(s, "<gray>  • <v0>: <v1></gray>", ruleRu(en.getKey()), en.getValue());
        });
    }

    private static String ruleRu(String rule) {
        if (rule.contains("row consensus")) return "ряды у столбов/поленниц (по соседям в ряду, спинкой к стене)";
        if (rule.startsWith("slope corner")) return "углы скатов крыши";
        if (rule.startsWith("slope")) return "скаты крыши и лестничные марши (спинкой вверх по склону)";
        if (rule.startsWith("wall-backed")) return "цоколь, крыльцо, скамьи (спинкой к стене)";
        if (rule.startsWith("upside-down")) return "перевёрнутые: карнизы, подоконники, кронштейны (спинкой к стене)";
        if (rule.startsWith("chair")) return "стулья (сиденьем к столу)";
        if (rule.startsWith("hanging slab")) return "висящие полублоки -> верхняя половина";
        if (rule.startsWith("gapped slab")) return "полублоки с зазором -> нижняя половина";
        return rule;
    }

    // ------------------------------------------------------------------ import of existing structures

    private void wand(CommandSender s) {
        if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игроков.");
        p.getInventory().addItem(plugin.wandItem());
        Msg.send(s, "<gray>Жезл выдан: ЛКМ по блоку — точка 1, ПКМ — точка 2. Затем /aib import <название></gray>");
    }

    private void pos(CommandSender s, int n) {
        if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игроков.");
        Block t = p.getTargetBlockExact(120);
        Location l = t != null ? t.getLocation() : p.getLocation().getBlock().getLocation();
        plugin.setPos(p.getUniqueId(), n, l);
        Msg.send(s, "<gray>Точка <v0>: <white><v1> <v2> <v3></white></gray>", n, l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private void importSel(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) throw new IllegalArgumentException("Только для игроков.");
        Location p1 = plugin.getPos(p.getUniqueId(), 1), p2 = plugin.getPos(p.getUniqueId(), 2);
        if (p1 == null || p2 == null) throw new IllegalStateException("Сначала выделите область: /aib wand или /aib pos1, /aib pos2");
        if (!p1.getWorld().equals(p2.getWorld())) throw new IllegalStateException("Точки в разных мирах.");
        int minX = Math.min(p1.getBlockX(), p2.getBlockX()), minY = Math.min(p1.getBlockY(), p2.getBlockY()), minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
        int maxX = Math.max(p1.getBlockX(), p2.getBlockX()), maxY = Math.max(p1.getBlockY(), p2.getBlockY()), maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());
        int ext = plugin.cfg().maxExtent;
        if (maxX - minX >= ext || maxY - minY >= ext || maxZ - minZ >= ext) throw new IllegalStateException("Область слишком большая (макс. " + ext + ").");
        VoxelModel m = RegionCapture.capture(p1.getWorld(), minX, minY, minZ, maxX, maxY, maxZ, plugin.blockInfo());
        if (m.nonAirCount() == 0) throw new IllegalStateException("В области нет блоков.");
        Build b = plugin.store().create(p.getUniqueId(), p.getName(), p1.getWorld().getName());
        b.name = a.length > 0 ? String.join(" ", a) : "Импорт " + b.id;
        b.originalPrompt = b.name + " (импортированная постройка)";
        b.originX = minX;
        b.originY = minY;
        b.originZ = minZ;
        b.rotation = 0;
        BuildVersion v = new BuildVersion();
        v.number = 1;
        v.request = "импорт из мира";
        v.summary = "исходная постройка";
        v.model = m;
        v.createdAt = System.currentTimeMillis();
        v.score = -1;
        b.versions.add(v);
        b.currentVersion = 0;
        b.placed = true;
        b.history.add("импорт");
        plugin.store().setActive(p.getUniqueId(), b.id);
        plugin.store().save(b);
        Msg.send(s, "<green>Импортировано <v0> блоков как <white><v1> [<v2>]</white>. Теперь: /aib edit <что изменить></green>", m.nonAirCount(), b.name, b.id);
    }

    // ------------------------------------------------------------------ benchmarks

    /**
     * /aib bench create [--mode m] <prompt>  — classic compiled pipeline vs build-script pipeline, renders for a blind vote.
     * /aib bench review [--mode m] <prompt>  — one shared plan+construction, then fixed vs adaptive verification.
     * /aib bench edit <id> [--mode m] <change> — old (direct+fixed) vs new (planned+adaptive) edit pipeline.
     * Nothing is placed in the world; results go to plugins/AIBuild/bench/<bench-id>/.
     */
    private void bench(CommandSender s, String[] a0) {
        if (!s.hasPermission("aibuild.admin")) throw new IllegalStateException("Нет прав.");
        requireKey();
        if (a0.length < 1 || (a0.length < 2 && !a0[0].equalsIgnoreCase("suite"))) {
            throw new IllegalArgumentException("/aib bench create [--mode m] [--a v] [--b v] <промпт> | /aib bench suite [--mode m] [--a v] [--b v] [--repeat n] "
                    + "| /aib bench review [--mode m] <промпт> | /aib bench edit <id> [--mode m] <правка>");
        }
        String kind = a0[0].toLowerCase(Locale.ROOT);
        ModeArgs ma = stripMode(Arrays.copyOfRange(a0, 1, a0.length));
        BuildMode mode = ma.mode() != null ? ma.mode() : plugin.modeOf(s instanceof Player p ? p.getUniqueId() : null);
        String[] a = ma.rest();
        String va = "script-v2", vb = "script-v3";
        int repeat = 1;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if ((a[i].equals("--a") || a[i].equals("--b") || a[i].equals("--repeat")) && i + 1 < a.length) {
                if (a[i].equals("--a")) va = a[++i].toLowerCase(Locale.ROOT);
                else if (a[i].equals("--b")) vb = a[++i].toLowerCase(Locale.ROOT);
                else repeat = Math.clamp(Integer.parseInt(a[++i]), 1, 5);
            } else {
                rest.add(a[i]);
            }
        }
        a = rest.toArray(String[]::new);
        String id;
        if (kind.equals("create")) {
            String prompt = String.join(" ", a).trim();
            if (prompt.isEmpty()) throw new IllegalArgumentException("Нужен промпт.");
            id = plugin.pipeline().startCreateBench(prompt, mode, va, vb);
            Msg.send(s, "<gray>Бенчмарк создания <white><v0></white> (режим <v1>): <v2> и <v3> параллельно, рендеры для слепого сравнения.</gray>", id, mode.title, va, vb);
        } else if (kind.equals("suite")) {
            int n = plugin.pipeline().startBenchSuite(mode, va, vb, repeat);
            Msg.send(s, "<gray>Набор бенчмарков (режим <v0>): <v1> запросов по очереди, <v2> против <v3>. Папки появятся в plugins/AIBuild/bench/.</gray>",
                    mode.title, n, va, vb);
        } else if (kind.equals("review")) {
            String prompt = String.join(" ", a).trim();
            if (prompt.isEmpty()) throw new IllegalArgumentException("Нужен промпт.");
            id = plugin.pipeline().startReviewBench(prompt, mode);
            Msg.send(s, "<gray>Бенчмарк проверки <white><v0></white> (режим <v1>): общий план и этапы, затем fixed и adaptive параллельно, затем слепой судья.</gray>", id, mode.title);
        } else if (kind.equals("edit")) {
            if (a.length < 2) throw new IllegalArgumentException("/aib bench edit <id> <правка>");
            Build b = plugin.store().get(a[0]);
            if (b == null || b.currentModel() == null) throw new IllegalArgumentException("Постройка не найдена или без версии: " + a[0]);
            String change = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).trim();
            id = plugin.pipeline().startEditBench(b, change, mode);
            Msg.send(s, "<gray>Бенчмарк правки <white><v0></white> на <v1> (режим <v2>): direct+fixed vs planned+adaptive, затем слепой судья.</gray>", id, b.id, mode.title);
        } else {
            throw new IllegalArgumentException("Тип бенчмарка: create | suite | review | edit");
        }
    }

    // ------------------------------------------------------------------ thinking modes

    private void mode(CommandSender s, String[] a) {
        UUID who = s instanceof Player p ? p.getUniqueId() : null;
        if (a.length > 0) {
            String arg = a[0].toLowerCase(Locale.ROOT);
            if (arg.equals("default") || arg.equals("reset") || arg.equals("сброс")) {
                plugin.setMode(who, null);
                BuildMode d = plugin.modeOf(who);
                Msg.send(s, "<gray>Режим сброшен на режим сервера по умолчанию: <light_purple><v0></light_purple></gray>", d.title);
                return;
            }
            String id = BuildMode.normalizeId(arg);
            if (id == null || !plugin.cfg().modes.containsKey(id)) {
                throw new IllegalArgumentException("Неизвестный режим: " + a[0] + ". Доступны: " + String.join(", ", plugin.cfg().modes.keySet()));
            }
            plugin.setMode(who, id);
            BuildMode m = plugin.cfg().modes.get(id);
            Msg.send(s, "<green>Режим ИИ: <light_purple><v0></light_purple></green> <gray>(<v1>; <v2>)</gray>", m.title, m.eta, m.describe());
            return;
        }
        BuildMode cur = plugin.modeOf(who);
        Msg.send(s, "<white>Режимы думания ИИ</white> <gray>(текущий: <light_purple><v0></light_purple>)</gray>", cur.title);
        for (BuildMode m : plugin.cfg().modes.values()) {
            boolean active = m.id.equals(cur.id);
            String color = active ? "light_purple" : "aqua";
            Msg.raw(s, "<click:run_command:'/aib mode " + m.id + "'><hover:show_text:'Выбрать режим'><" + color + ">"
                            + (active ? "▶ " : "  ") + "<v0></" + color + "> <white><v1></white> <gray>(<v2>)</gray></hover></click>",
                    m.id, m.title, m.eta);
            Msg.raw(s, "<dark_gray>      <v0></dark_gray>", m.describe());
        }
        Msg.raw(s, "<gray>Разово: <white>/aib create --mode extra <описание></white> или <white>/aib edit --mode fast <запрос></white></gray>");
    }

    // ------------------------------------------------------------------ admin

    private void status(CommandSender s) {
        Msg.send(s, "<gray>Модель <white><v0></white> через <white><v1></white> (<v2>), ключ: <v3></gray>", plugin.cfg().model,
                plugin.cfg().baseUrl, plugin.llmName(), plugin.cfg().apiKey.isBlank() ? "<red>нет</red>" : "задан");
        Msg.raw(s, "<gray>  Построек: <v0>, задач ИИ: <v1>, режим по умолчанию: <v2>, ваш режим: <v3></gray>", plugin.store().all().size(),
                plugin.pipeline().jobs().size(), plugin.cfg().mode(plugin.cfg().defaultMode).title,
                plugin.modeOf(s instanceof Player p ? p.getUniqueId() : null).title);
        for (Job j : plugin.pipeline().jobs()) {
            Msg.raw(s, "<gray>  [<v0>] <v1> «<v9>» — <v2> (<v3>%, <v4> c, токены <v5>/<v6>, сейчас пишет <v7> симв. ответа + <v8> симв. рассуждений)</gray>", j.buildId, j.kind, j.status,
                    (int) (j.progress * 100), j.elapsedSeconds(), j.promptTokens.get(), j.completionTokens.get(), j.streamChars, j.reasoningChars, j.mode.title);
        }
    }

    private void reload(CommandSender s) {
        if (!s.hasPermission("aibuild.admin")) throw new IllegalStateException("Нет прав.");
        plugin.reloadAll();
        Msg.send(s, "<green>Конфигурация перезагружена.</green>");
    }

    private void help(CommandSender s) {
        Msg.send(s, "<white>ИИ-строитель (DeepSeek V4.1 Flash · Command Code)</white>");
        String[][] lines = {
                {"/aib create [--mode extra] <описание>", "сгенерировать новую детальную постройку перед вами"},
                {"/aib edit <запрос>", "изменить активную постройку (в любой момент)"},
                {"/aib mode [fast|light|standard|extra]", "режим думания ИИ: без думания / лёгкое / стандарт / экстра"},
                {"/aib chat", "режим диалога: всё, что пишете в чат, — запросы к ИИ"},
                {"/aib undo | redo", "версии постройки"},
                {"/aib list | select [id] | info | tp", "управление постройками"},
                {"/aib render", "PNG-рендер постройки"},
                {"/aib fix", "автоисправление ориентации ступенек и полублоков (без ИИ)"},
                {"/aib wand | pos1 | pos2 | import <имя>", "взять существующую постройку под ИИ-редактирование"},
                {"/aib remove | cancel | status", "удалить / отменить задачу / статус"}};
        for (String[] l : lines) Msg.raw(s, "<aqua><v0></aqua> <dark_gray>—</dark_gray> <gray><v1></gray>", l[0], l[1]);
    }

    // ------------------------------------------------------------------ utils

    /** Resolves the build to act on: explicit id, else the player's active one, else their latest. */
    private Build active(CommandSender s, String id, boolean modify) {
        Build b;
        if (id != null) {
            b = plugin.store().get(id);
            if (b == null) throw new IllegalArgumentException("Постройка не найдена: " + id);
        } else if (s instanceof Player p) {
            String a = plugin.store().getActive(p.getUniqueId());
            b = a == null ? null : plugin.store().get(a);
            if (b == null) {
                b = plugin.store().byOwner(p.getUniqueId()).stream().max(Comparator.comparingLong(x -> x.updatedAt)).orElse(null);
            }
            if (b == null) throw new IllegalStateException("Нет активной постройки. /aib create <описание> или /aib select");
        } else {
            b = plugin.store().all().stream().max(Comparator.comparingLong(x -> x.updatedAt)).orElse(null);
            if (b == null) throw new IllegalStateException("Построек нет.");
        }
        if (modify && s instanceof Player p && b.owner != null && !b.owner.equals(p.getUniqueId()) && !s.hasPermission("aibuild.admin")) {
            throw new IllegalStateException("Это чужая постройка.");
        }
        return b;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    @SuppressWarnings("unused")
    private static String joinIds(Collection<Build> c) {
        return c.stream().map(b -> b.id).collect(Collectors.joining(", "));
    }
}
