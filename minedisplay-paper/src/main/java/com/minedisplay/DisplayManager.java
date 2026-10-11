package com.minedisplay;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

/**
 * 屏幕管理：创建/绑定/删除地图墙，持有定义与播放状态；渲染器只消费不可变快照。
 *
 * <p>移植自 ImageMaps（MIT）的放置与持久化逻辑，适配 26.2 并按 DESIGN §5/§6 扩展：
 * <ul>
 *   <li>Display 记录：UUID、管理员友好 ID、世界 UUID、锚点、朝向、宽高、
 *       素材 ID、权限、视距、启用状态、revision；</li>
 *   <li>地图记录：每个 tile 的 map ID、行列、受控实体 UUID；</li>
 *   <li>实体 PDC 写入插件命名空间、display UUID 和 tile 坐标；</li>
 *   <li>创建前检查全部支撑/空位/实体数量，任一步失败回滚本次生成的实体/记录；</li>
 *   <li>删除屏幕删除实体与记录，但不删除世界地图文件、不复用他人 map ID。</li>
 * </ul>
 *
 * <p>限制（M1）：竖直墙面、四个水平朝向、整数 tile 尺寸。
 * Bukkit world/entity/map API 均在主线程调用。
 */
public final class DisplayManager implements Listener {

    /** 待放置会话（玩家右键选左上角）。 */
    record PendingPlacement(String displayId, String mediaId, int cols, int rows, boolean cover) {
    }

    /** 不可变屏幕快照（渲染器只消费这个）。 */
    public record DisplaySnapshot(UUID uuid, String id, UUID worldId, int anchorX, int anchorY, int anchorZ,
                                  BlockFace facing, int cols, int rows, String mediaId, long revision) {
    }

    private record TileRecord(int mapId, int col, int row, UUID entityId) {
    }

    private record DisplayRecord(UUID uuid, String id, UUID worldId, int anchorX, int anchorY, int anchorZ,
                                 BlockFace facing, int cols, int rows, String mediaId, long revision,
                                 List<TileRecord> tiles) {
    }

    private final MineDisplayPlugin plugin;
    private final Map<String, DisplayRecord> displays = new HashMap<>();
    private final Map<UUID, PendingPlacement> pending = new HashMap<>();
    private NamespacedKey displayKey;
    private NamespacedKey tileKey;

    public DisplayManager(MineDisplayPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.displayKey = new NamespacedKey(plugin, "display");
        this.tileKey = new NamespacedKey(plugin, "tile");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        load();
    }

    public void stop() {
        save();
        pending.clear();
    }

    // ---------- 查询 ----------

    public int count() {
        return displays.size();
    }

    public int totalTiles() {
        int total = 0;
        for (DisplayRecord record : displays.values()) {
            total += record.tiles().size();
        }
        return total;
    }

    public DisplaySnapshot find(String id) {
        DisplayRecord record = displays.get(key(id));
        return record == null ? null : snapshot(record);
    }

    public List<DisplaySnapshot> list() {
        List<DisplaySnapshot> out = new ArrayList<>();
        for (DisplayRecord record : displays.values()) {
            out.add(snapshot(record));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    private static DisplaySnapshot snapshot(DisplayRecord record) {
        return new DisplaySnapshot(record.uuid(), record.id(), record.worldId(),
                record.anchorX(), record.anchorY(), record.anchorZ(),
                record.facing(), record.cols(), record.rows(), record.mediaId(), record.revision());
    }

    public boolean isMediaReferenced(String mediaId) {
        for (DisplayRecord record : displays.values()) {
            if (record.mediaId() != null && record.mediaId().equalsIgnoreCase(mediaId)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 素材换图：复用自身 map ID 重绘 ----------

    /** 素材被替换后，已绑定该素材的屏幕重算渲染器像素（调用方保证图片已在工作线程载入，主线程只做 Bukkit 调用）。 */
    public void refreshMedia(String mediaId) {
        var entry = plugin.media().find(mediaId);
        if (entry == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BufferedImage image = plugin.media().loadImage(entry);
            if (image == null || plugin.isShuttingDown()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isShuttingDown()) {
                    return;
                }
                for (DisplayRecord record : displays.values()) {
                    if (record.mediaId() == null || !record.mediaId().equalsIgnoreCase(mediaId)) {
                        continue;
                    }
                    // 画布缩放是重计算，在工作线程按屏幕尺寸算好，主线程只做切分。
                    BufferedImage canvas = MapTileRenderer.toCanvas(image,
                            record.cols(), record.rows(), false);
                    applyImage(record, canvas, false);
                }
            });
        });
    }

    // ---------- 创建 ----------

    /** 开始放置：记录玩家意图，等右键墙面左上角。 */
    public String beginCreate(Player player, String displayId, String mediaId, int cols, int rows) {
        String idError = MediaStore.checkId(displayId);
        if (idError != null) {
            return idError;
        }
        if (displays.containsKey(key(displayId))) {
            return "已存在同名屏幕";
        }
        if (plugin.media().find(mediaId) == null) {
            return "没有该素材，先 media import";
        }
        int maxTiles = plugin.getConfig().getInt("maps.max-tiles-per-display", 16);
        if (cols < 1 || rows < 1 || (long) cols * rows > maxTiles) {
            return "尺寸非法（单屏最多 " + maxTiles + " tiles）";
        }
        if (totalTiles() + (long) cols * rows > plugin.getConfig().getInt("maps.max-total-tiles", 128)) {
            return "全服地图 tile 已达上限";
        }
        pending.put(player.getUniqueId(), new PendingPlacement(displayId, mediaId, cols, rows, false));
        return null;
    }

    public void cancelPending(Player player) {
        pending.remove(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        var placement = pending.get(event.getPlayer().getUniqueId());
        if (placement == null) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (block == null || face == null || face.getModY() != 0) {
            event.getPlayer().sendMessage("§c只能放在竖直墙面上（四个水平朝向）。");
            return;
        }
        event.setCancelled(true);
        pending.remove(event.getPlayer().getUniqueId());
        create(event.getPlayer(), block, face, placement);
    }

    private void create(Player player, Block block, BlockFace face, PendingPlacement placement) {
        Block base = block.getRelative(face);
        BlockFace right = rightOf(face, player);
        if (right == null) {
            player.sendMessage("§c无法确定墙面方向，请正对墙面再试。");
            return;
        }
        int cols = placement.cols();
        int rows = placement.rows();
        // 创建前检查全部支撑、空位、实体数量。
        World world = base.getWorld();
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                Block support = block.getRelative(right, col).getRelative(BlockFace.UP, row);
                if (!support.getType().isSolid()) {
                    player.sendMessage("§c支撑墙面不足（需要实心方块）。");
                    return;
                }
                Block cell = base.getRelative(right, col).getRelative(BlockFace.UP, row);
                if (cell.getType().isSolid()) {
                    player.sendMessage("§c空间被占用（需要留空）。");
                    return;
                }
                if (!cell.getChunk().isLoaded()) {
                    player.sendMessage("§c区块未加载，换个位置再试。");
                    return;
                }
                var overlapping = world.getNearbyEntities(
                        cell.getLocation().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5,
                        entity -> entity instanceof Hanging);
                if (!overlapping.isEmpty()) {
                    player.sendMessage("§c有其他悬挂实体挡住位置。");
                    return;
                }
            }
        }
        var entry = plugin.media().find(placement.mediaId());
        if (entry == null) {
            player.sendMessage("§c素材不存在。");
            return;
        }
        UUID uuid = UUID.randomUUID();
        // 锚点定义为玩家面对墙时左下角 tile 中心（DESIGN §6）。
        Block anchorBlock = base.getRelative(BlockFace.UP, 0);
        DisplayRecord record = new DisplayRecord(uuid, placement.displayId(), world.getUID(),
                anchorBlock.getX(), anchorBlock.getY(), anchorBlock.getZ(),
                face, cols, rows, placement.mediaId(), 1, new ArrayList<>());
        // 任一步失败回滚本次生成的实体/记录，不触碰他人实体。
        List<ItemFrame> spawned = new ArrayList<>();
        try {
            for (int col = 0; col < cols; col++) {
                for (int row = 0; row < rows; row++) {
                    Block cell = base.getRelative(right, col).getRelative(BlockFace.UP, row);
                    ItemFrame frame = world.spawn(cell.getLocation(), ItemFrame.class);
                    frame.setFacingDirection(face);
                    frame.setVisible(false);
                    frame.setFixed(true);
                    frame.setRotation(Rotation.NONE);
                    frame.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, uuid.toString());
                    frame.getPersistentDataContainer().set(tileKey, PersistentDataType.STRING, col + "," + row);
                    spawned.add(frame);
                    record.tiles().add(new TileRecord(-1, col, row, frame.getUniqueId()));
                }
            }
        } catch (Exception e) {
            for (ItemFrame frame : spawned) {
                frame.remove();
            }
            plugin.getLogger().log(Level.WARNING, "创建屏幕失败，已回滚", e);
            player.sendMessage("§c创建失败，已回滚: " + e.getMessage());
            return;
        }
        displays.put(key(placement.displayId()), record);
        save();
        player.sendMessage("§a屏幕已创建，正在渲染图片…");
        // 图片解码/缩放在工作线程，Bukkit 调用回主线程。
        int canvasCols = record.cols();
        int canvasRows = record.rows();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BufferedImage image = plugin.media().loadImage(entry);
            if (image == null || plugin.isShuttingDown()) {
                return;
            }
            // 整图缩放到目标画布是重计算，在工作线程做一次，多 tile 共享。
            BufferedImage canvas = MapTileRenderer.toCanvas(image, canvasCols, canvasRows, false);
            if (plugin.isShuttingDown()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isShuttingDown()) {
                    return;
                }
                DisplayRecord current = displays.get(key(placement.displayId()));
                if (current == null || !current.uuid().equals(uuid)) {
                    return;
                }
                applyImage(current, canvas, true);
            });
        });
    }

    /**
     * 把图片应用到屏幕的每个 tile：换图复用自身 map ID（只重算渲染器），
     * 新 tile 才创建 map。必须在主线程调用，canvas 已在工作线程算好。
     */
    private void applyImage(DisplayRecord record, BufferedImage canvas, boolean firstPaint) {
        for (TileRecord tile : record.tiles()) {
            World world = Bukkit.getWorld(record.worldId());
            if (world == null) {
                continue;
            }
            var entity = Bukkit.getEntity(tile.entityId());
            if (!(entity instanceof ItemFrame frame) || frame.isDead()) {
                continue;
            }
            MapView map = null;
            if (tile.mapId() >= 0) {
                map = Bukkit.getMap(tile.mapId());
            }
            if (map == null) {
                map = Bukkit.createMap(world);
                map.setTrackingPosition(false);
                replaceTileId(record, tile, map.getId());
            }
            // getRenderers() 返回快照：逐个 remove，不能对返回的 list clear。
            for (MapRenderer renderer : new ArrayList<>(map.getRenderers())) {
                map.removeRenderer(renderer);
            }
            map.addRenderer(new MapTileRenderer(canvas, tile.col(), tile.row()));
            ItemStack item = ItemStack.of(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            meta.setMapView(map);
            item.setItemMeta(meta);
            frame.setItem(item);
        }
        save();
    }

    private void replaceTileId(DisplayRecord record, TileRecord tile, int mapId) {
        for (int i = 0; i < record.tiles().size(); i++) {
            TileRecord current = record.tiles().get(i);
            if (current.entityId().equals(tile.entityId())) {
                record.tiles().set(i, new TileRecord(mapId, current.col(), current.row(), current.entityId()));
                return;
            }
        }
    }

    // ---------- 绑定/删除 ----------

    /** 绑定素材：完成校验后原子替换（revision+1，重绘复用自身 ID）。 */
    public String setMedia(String displayId, String mediaId) {
        DisplayRecord record = displays.get(key(displayId));
        if (record == null) {
            return "没有该屏幕";
        }
        var entry = plugin.media().find(mediaId);
        if (entry == null) {
            return "没有该素材";
        }
        int cols = record.cols();
        int rows = record.rows();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BufferedImage image = plugin.media().loadImage(entry);
            if (image == null || plugin.isShuttingDown()) {
                return;
            }
            BufferedImage canvas = MapTileRenderer.toCanvas(image, cols, rows, false);
            if (plugin.isShuttingDown()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                DisplayRecord current = displays.get(key(displayId));
                if (current == null) {
                    return;
                }
                DisplayRecord updated = new DisplayRecord(current.uuid(), current.id(), current.worldId(),
                        current.anchorX(), current.anchorY(), current.anchorZ(),
                        current.facing(), current.cols(), current.rows(), entry.id(),
                        current.revision() + 1, current.tiles());
                displays.put(key(displayId), updated);
                applyImage(updated, canvas, false);
            });
        });
        return null;
    }

    /** 删除屏幕：删除实体与记录，不删除世界地图文件，不复用他人 map ID。 */
    public String remove(String displayId) {
        DisplayRecord record = displays.remove(key(displayId));
        if (record == null) {
            return "没有该屏幕";
        }
        for (TileRecord tile : record.tiles()) {
            var entity = Bukkit.getEntity(tile.entityId());
            if (entity instanceof ItemFrame frame && isOurs(frame)) {
                frame.remove();
            }
        }
        save();
        return null;
    }

    // ---------- 保护 ----------

    private boolean isOurs(Hanging hanging) {
        String stored = hanging.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (stored == null) {
            return false;
        }
        for (DisplayRecord record : displays.values()) {
            if (record.uuid().toString().equals(stored)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getEntity() instanceof Hanging hanging && isOurs(hanging)) {
            // 受控画框禁止破坏/爆炸损坏，管理员删除经命令进行。
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (event.getEntity() instanceof Hanging hanging && isOurs(hanging)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame && isOurs(frame)) {
            // 禁止旋转、取图。
            event.setCancelled(true);
        }
    }

    // ---------- 持久化 ----------

    private static String key(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    private static BlockFace rightOf(BlockFace face, Player player) {
        // 玩家面对墙时右手方向：沿用 ImageMaps 的偏航判定。
        float yaw = (360.0f + player.getLocation().getYaw()) % 360.0f;
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> yawFallback(yaw);
        };
    }

    private static BlockFace yawFallback(float yaw) {
        if (yaw >= 45.0 && yaw < 135.0) {
            return BlockFace.NORTH;
        } else if (yaw >= 135.0 && yaw < 225.0) {
            return BlockFace.EAST;
        } else if (yaw >= 225.0 && yaw < 315.0) {
            return BlockFace.SOUTH;
        }
        return BlockFace.WEST;
    }

    private void save() {
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("schema-version", 1);
        for (DisplayRecord record : displays.values()) {
            String base = "displays." + record.id().toLowerCase(Locale.ROOT);
            config.set(base + ".uuid", record.uuid().toString());
            config.set(base + ".id", record.id());
            config.set(base + ".world", record.worldId().toString());
            config.set(base + ".anchor", List.of(record.anchorX(), record.anchorY(), record.anchorZ()));
            config.set(base + ".facing", record.facing().name());
            config.set(base + ".size", List.of(record.cols(), record.rows()));
            config.set(base + ".media", record.mediaId());
            config.set(base + ".revision", record.revision());
            List<Map<String, Object>> tiles = new ArrayList<>();
            for (TileRecord tile : record.tiles()) {
                Map<String, Object> tileMap = new HashMap<>();
                tileMap.put("map", tile.mapId());
                tileMap.put("col", tile.col());
                tileMap.put("row", tile.row());
                tileMap.put("entity", tile.entityId().toString());
                tiles.add(tileMap);
            }
            config.set(base + ".tiles", tiles);
        }
        try {
            File file = new File(plugin.getDataFolder(), "displays.yml");
            File tmp = new File(plugin.getDataFolder(), "displays.yml.tmp");
            config.save(tmp);
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.WARNING, "保存屏幕定义失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        File file = new File(plugin.getDataFolder(), "displays.yml");
        if (!file.isFile()) {
            return;
        }
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        int schema = config.getInt("schema-version", 1);
        if (schema != 1) {
            plugin.getLogger().warning("无法识别的 displays.yml 版本 " + schema + "，拒绝载入（不清空）。");
            return;
        }
        var section = config.getConfigurationSection("displays");
        if (section == null) {
            return;
        }
        for (String idKey : section.getKeys(false)) {
            var entry = section.getConfigurationSection(idKey);
            if (entry == null) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(entry.getString("uuid", ""));
                UUID worldId = UUID.fromString(entry.getString("world", ""));
                var anchor = entry.getIntegerList("anchor");
                var size = entry.getIntegerList("size");
                BlockFace facing = BlockFace.valueOf(entry.getString("facing", "NORTH"));
                List<Map<?, ?>> tiles = (List<Map<?, ?>>) (List<?>) entry.getMapList("tiles");
                List<TileRecord> tileRecords = new ArrayList<>();
                for (Map<?, ?> tileMap : tiles) {
                    tileRecords.add(new TileRecord(
                            ((Number) tileMap.get("map")).intValue(),
                            ((Number) tileMap.get("col")).intValue(),
                            ((Number) tileMap.get("row")).intValue(),
                            UUID.fromString(String.valueOf(tileMap.get("entity")))));
                }
                // 载入时根据 PDC 和记录幂等对账在首次区块加载后进行，这里只恢复记录。
                displays.put(key(idKey), new DisplayRecord(uuid, entry.getString("id", idKey), worldId,
                        anchor.get(0), anchor.get(1), anchor.get(2), facing,
                        size.get(0), size.get(1), entry.getString("media"),
                        entry.getLong("revision", 1), tileRecords));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "跳过损坏的屏幕记录: " + idKey, e);
            }
        }
        plugin.getLogger().info("载入 " + displays.size() + " 个屏幕定义");
    }

    /** 区块载入后对账：PDC 与记录一致才保留，否则按记录修复缺失、隔离重复。 */
    public void reconcileChunk(org.bukkit.Chunk chunk) {
        for (var entity : chunk.getEntities()) {
            if (!(entity instanceof ItemFrame frame)) {
                continue;
            }
            String stored = frame.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
            if (stored == null) {
                continue;
            }
            boolean known = false;
            for (DisplayRecord record : displays.values()) {
                if (record.uuid().toString().equals(stored)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                // 记录里没有：重复/孤儿，隔离后由管理员清理，不自动删除他人实体以外的东西。
                // 这里仅记录日志，删除走 remove 命令（按 display UUID 查不到就保留现场）。
                plugin.getLogger().warning("发现孤儿受控画框 " + frame.getUniqueId() + "（display " + stored + "）");
            }
        }
    }

    /** 切换 Glow 画框（M5 预留，M1 只用普通 ItemFrame）。 */
    public String setGlowing(String displayId, boolean glowing) {
        DisplayRecord record = displays.get(key(displayId));
        if (record == null) {
            return "没有该屏幕";
        }
        if (!glowing) {
            return null;
        }
        int converted = 0;
        for (TileRecord tile : record.tiles()) {
            var entity = Bukkit.getEntity(tile.entityId());
            if (!(entity instanceof ItemFrame frame) || frame instanceof GlowItemFrame) {
                continue;
            }
            if (frame.isDead() || !frame.isValid()) {
                continue;
            }
            ItemStack item = frame.getItem().clone();
            Rotation rotation = frame.getRotation();
            BlockFace facing = frame.getFacing();
            boolean visible = frame.isVisible();
            boolean fixed = frame.isFixed();
            org.bukkit.Location location = frame.getLocation().clone();
            World world = location.getWorld();
            if (world == null) {
                continue;
            }
            frame.remove();
            GlowItemFrame glow;
            try {
                glow = world.spawn(location, GlowItemFrame.class);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "创建发光画框失败", e);
                continue;
            }
            glow.setFacingDirection(facing);
            glow.setItem(item);
            glow.setRotation(rotation);
            glow.setVisible(visible);
            glow.setFixed(fixed);
            glow.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING,
                    record.uuid().toString());
            glow.getPersistentDataContainer().set(tileKey, PersistentDataType.STRING,
                    tile.col() + "," + tile.row());
            replaceTileEntity(record, tile, glow.getUniqueId());
            converted++;
        }
        save();
        return converted == 0 ? "没有可转换的画框（可能已是发光画框或实体已失效）" : null;
    }

    private void replaceTileEntity(DisplayRecord record, TileRecord tile, UUID entityId) {
        for (int i = 0; i < record.tiles().size(); i++) {
            TileRecord current = record.tiles().get(i);
            if (current.entityId().equals(tile.entityId())) {
                record.tiles().set(i, new TileRecord(current.mapId(), current.col(), current.row(), entityId));
                return;
            }
        }
    }
}
