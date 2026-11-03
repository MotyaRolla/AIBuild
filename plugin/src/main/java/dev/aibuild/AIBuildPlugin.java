package dev.aibuild;

import dev.aibuild.ai.CommandCodeGenerateClient;
import dev.aibuild.ai.LlmClient;
import dev.aibuild.ai.LlmRouter;
import dev.aibuild.ai.OpenAiCompatClient;
import dev.aibuild.build.Build;
import dev.aibuild.build.BuildStore;
import dev.aibuild.bukkit.BukkitBlockInfo;
import dev.aibuild.command.AIBuildCommand;
import dev.aibuild.config.PluginConfig;
import dev.aibuild.pipeline.BuildPipeline;
import dev.aibuild.pipeline.Job;
import dev.aibuild.util.Msg;
import dev.aibuild.world.WorldPlacer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** AIBuild — AI generation and editing of Minecraft builds with DeepSeek V4.1 Flash via Command Code. */
public final class AIBuildPlugin extends JavaPlugin implements Listener {
    private volatile PluginConfig config;
    private BukkitBlockInfo blockInfo;
    private volatile LlmClient llm;
    private BuildStore store;
    private WorldPlacer placer;
    private BuildPipeline pipeline;
    private AIBuildCommand command;
    private String blockList;
    private volatile String cliVersion;
    private NamespacedKey wandKey;

    private final Set<UUID> chatMode = ConcurrentHashMap.newKeySet();
    /** Chosen thinking mode per player ("console" key for the console), persisted in player-modes.json. */
    private final Map<String, String> playerModes = new ConcurrentHashMap<>();
    private final Map<UUID, Location[]> selections = new ConcurrentHashMap<>();
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Long> progressMessageAt = new HashMap<>();
    private final Map<String, String> progressMessageStatus = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = new PluginConfig(getConfig());
        wandKey = new NamespacedKey(this, "wand");
        blockInfo = new BukkitBlockInfo();
        blockList = blockInfo.blockListForPrompt();
        llm = createLlm(config);
        loadModes();
        store = new BuildStore(getDataFolder(), getLogger());
        store.loadAll();
        placer = new WorldPlacer(this, config.blocksPerTick, config.foundationBlock, config.maxFoundationDepth);
        pipeline = new BuildPipeline(this, store, placer, blockInfo, () -> llm, () -> config, () -> blockList);
        command = new AIBuildCommand(this);
        registerCommand("aibuild", "AI build generation and editing", List.of("aib", "ai"), command);
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::updateBars, 20L, 10L);
        loadTextureColours();
        getLogger().info("AIBuild enabled: model " + config.model + " via " + config.baseUrl + " (" + config.transport + "), "
                + store.all().size() + " builds loaded, " + blockInfo.blockNames().size() + " block types"
                + (config.apiKey.isBlank() ? " — WARNING: api.key is empty!" : ""));
    }

    /** Derives real block colours for renders (and the vision judge) from the vanilla client, off the main thread. */
    private void loadTextureColours() {
        if (!config.renderTextures) return;
        String version = Bukkit.getMinecraftVersion();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var assets = dev.aibuild.render.TextureCache.load(getDataFolder(), version, getLogger());
                dev.aibuild.analysis.IsoRenderer.useTextureColours(assets.colours());
                dev.aibuild.render.BlockTextures.install(assets.textures());
            } catch (Exception e) {
                getLogger().warning("Render colours from client textures unavailable (" + e.getMessage() + "); using built-in colours");
            }
        });
    }

    @Override
    public void onDisable() {
        if (pipeline != null) pipeline.shutdown();
        for (BossBar b : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
        if (store != null) store.shutdown();
    }

    private LlmClient createLlm(PluginConfig c) {
        Duration ct = Duration.ofSeconds(c.connectTimeout), it = Duration.ofSeconds(c.idleTimeout);
        OpenAiCompatClient provider = new OpenAiCompatClient(c.baseUrl + "/provider/v1", c.apiKey, ct, it, Map.of());
        CommandCodeGenerateClient gen = new CommandCodeGenerateClient(c.baseUrl, c.apiKey, this::cliVersion, ct, it);
        return new LlmRouter(provider, gen, c.transport, c.maxRetries, getLogger());
    }

    private String cliVersion() {
        String v = cliVersion;
        if (v == null) {
            String cfgV = config.cliVersion;
            v = cfgV == null || cfgV.equalsIgnoreCase("auto")
                    ? CommandCodeGenerateClient.fetchLatestCliVersion(HttpClient.newHttpClient(), "1.56.0") : cfgV;
            cliVersion = v;
        }
        return v;
    }

    public void reloadAll() {
        reloadConfig();
        config = new PluginConfig(getConfig());
        llm = createLlm(config);
        cliVersion = null;
    }

    // ------------------------------------------------------------------ accessors

    public PluginConfig cfg() {
        return config;
    }

    public BukkitBlockInfo blockInfo() {
        return blockInfo;
    }

    public BuildStore store() {
        return store;
    }

    public WorldPlacer placer() {
        return placer;
    }

    public BuildPipeline pipeline() {
        return pipeline;
    }

    public String llmName() {
        return llm.name();
    }

    // ------------------------------------------------------------------ thinking modes

    /** Effective mode for a player (null = console): explicit choice, else the configured default. */
    public dev.aibuild.config.BuildMode modeOf(UUID player) {
        String id = playerModes.get(player == null ? "console" : player.toString());
        return config.mode(id == null ? config.defaultMode : id);
    }

    public void setMode(UUID player, String modeId) {
        String k = player == null ? "console" : player.toString();
        if (modeId == null) playerModes.remove(k); else playerModes.put(k, modeId);
        saveModes();
    }

    private java.io.File modesFile() {
        return new java.io.File(getDataFolder(), "player-modes.json");
    }

    private void loadModes() {
        java.io.File f = modesFile();
        if (!f.isFile()) return;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(
                    java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : o.keySet()) playerModes.put(k, o.get(k).getAsString());
        } catch (Exception e) {
            getLogger().warning("player-modes.json unreadable: " + e.getMessage());
        }
    }

    private void saveModes() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        playerModes.forEach(o::addProperty);
        String json = o.toString();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                getDataFolder().mkdirs();
                java.nio.file.Files.writeString(modesFile().toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                getLogger().warning("cannot save player-modes.json: " + e.getMessage());
            }
        });
    }

    public boolean toggleChatMode(UUID id) {
        if (chatMode.remove(id)) return false;
        chatMode.add(id);
        return true;
    }

    public void setPos(UUID id, int n, Location l) {
        selections.computeIfAbsent(id, k -> new Location[2])[n - 1] = l;
    }

    public Location getPos(UUID id, int n) {
        Location[] a = selections.get(id);
        return a == null ? null : a[n - 1];
    }

    public ItemStack wandItem() {
        ItemStack it = new ItemStack(Material.BLAZE_ROD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Msg.mm("<gradient:#7ee8fa:#80ff72>AIBuild — жезл выделения</gradient>"));
        m.lore(List.of(Msg.mm("<gray>ЛКМ — точка 1, ПКМ — точка 2</gray>"), Msg.mm("<gray>/aib import <имя></gray>")));
        m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!chatMode.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (text.isEmpty()) return;
        Bukkit.getScheduler().runTask(this, () -> {
            String low = text.toLowerCase();
            if (low.equals("выход") || low.equals("exit") || low.equals("stop") || low.equals("стоп")) {
                chatMode.remove(p.getUniqueId());
                Msg.send(p, "<yellow>Режим чата с ИИ выключен.</yellow>");
                return;
            }
            Msg.raw(p, "<dark_gray>[вы → ИИ]</dark_gray> <white><v0></white>", text);
            try {
                String active = store.getActive(p.getUniqueId());
                Build b = active == null ? null : store.get(active);
                if (b == null || b.currentModel() == null) {
                    if (b != null && pipeline.jobFor(b.id) != null) {
                        Msg.send(p, "<yellow>ИИ ещё работает над постройкой — дождитесь окончания (или /aib cancel).</yellow>");
                        return;
                    }
                    p.performCommand("aib create " + text);
                } else {
                    command.startEdit(p, b, text);
                }
            } catch (RuntimeException ex) {
                Msg.send(p, "<red><v0></red>", String.valueOf(ex.getMessage()));
            }
        });
    }

    @EventHandler(ignoreCancelled = false)
    public void onWand(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.BLAZE_ROD || !it.hasItemMeta()
                || !it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) return;
        int n = e.getAction() == Action.LEFT_CLICK_BLOCK ? 1 : e.getAction() == Action.RIGHT_CLICK_BLOCK ? 2 : 0;
        if (n == 0) return;
        e.setCancelled(true);
        Location l = e.getClickedBlock().getLocation();
        setPos(e.getPlayer().getUniqueId(), n, l);
        Msg.send(e.getPlayer(), "<gray>Точка <v0>: <white><v1> <v2> <v3></white></gray>", n, l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        chatMode.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ boss bars

    private void updateBars() {
        Map<String, Job> live = new HashMap<>();
        for (Job j : pipeline.jobs()) if (!j.finished) live.put(j.buildId, j);
        progressMessageAt.keySet().retainAll(live.keySet());
        progressMessageStatus.keySet().retainAll(live.keySet());
        // remove finished
        bars.entrySet().removeIf(en -> {
            if (live.containsKey(en.getKey())) return false;
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(en.getValue());
            return true;
        });
        for (Job j : live.values()) {
            String activity = j.streamChars > 0 ? "writing" : j.reasoningChars > 0 ? "thinking" : "working";
            String status = j.status.length()>48 ? j.status.substring(0,47)+"…" : j.status;
            Component title = Component.text("AIBuild [" + j.buildId + "] ", NamedTextColor.AQUA)
                    .append(Component.text(j.mode.id + " · ", NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text(status + " · " + activity, NamedTextColor.WHITE))
                    .append(Component.text("  " + j.elapsedSeconds() + "s", NamedTextColor.GRAY));
            BossBar bar = bars.computeIfAbsent(j.buildId, k -> BossBar.bossBar(title, 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS));
            bar.name(title);
            bar.progress((float) Math.max(0, Math.min(1, j.progress)));
            long now = System.currentTimeMillis();
            long last = progressMessageAt.getOrDefault(j.buildId,0L);
            boolean changed = !j.status.equals(progressMessageStatus.get(j.buildId));
            if (!j.quiet && (last == 0 || now-last >= 30_000 || changed && now-last >= 5_000)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (j.owner != null ? j.owner.equals(p.getUniqueId()) : p.isOp() || p.hasPermission("aibuild.admin")) {
                        Msg.send(p,"<gray>[<v0>] <white><v1></white> · <v2>s · DeepSeek: <v3></gray>",
                                j.buildId,j.status,j.elapsedSeconds(),activity);
                    }
                }
                progressMessageAt.put(j.buildId,now);
                progressMessageStatus.put(j.buildId,j.status);
            }
            if (j.owner != null) {
                Player p = Bukkit.getPlayer(j.owner);
                if (p != null) p.showBossBar(bar);
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isOp() || p.hasPermission("aibuild.admin")) p.showBossBar(bar);
                    else p.hideBossBar(bar);
                }
            }
        }
    }
}
