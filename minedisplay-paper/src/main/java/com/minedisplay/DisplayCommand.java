package com.minedisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * {@code /minedisplay}（别名 {@code /display}）：M1 只实现静态地图墙所需的子命令。
 *
 * <p>ID 使用简单字母、数字、下划线与短横线，不接受任意路径。
 * 命令补全只列出可见 ID。风格沿 MineAudio：中文提示、无权限返回 true。
 */
public final class DisplayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOTS = List.of("media", "create", "set", "glow", "remove",
            "list", "info", "status", "reload");
    private static final List<String> MEDIA_ACTIONS = List.of("import", "list", "info");

    private final MineDisplayPlugin plugin;

    public DisplayCommand(MineDisplayPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "media" -> media(sender, args);
            case "create" -> create(sender, args);
            case "set" -> set(sender, args);
            case "glow" -> glow(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    // ---------- media ----------

    private void media(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minedisplay.admin") && !sender.hasPermission("minedisplay.media")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法: /minedisplay media <import|list|info> ...");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "import" -> {
                if (args.length < 4) {
                    sender.sendMessage("§e用法: /minedisplay media import <id> <incoming-file>");
                    sender.sendMessage("§7先把 PNG/JPEG 放到 plugins/MineDisplay/media/incoming/ 目录");
                    return;
                }
                // IO/解码不在主线程：异步导入，完成后回主线程提示。
                String id = args[1 + 1];
                String file = args[1 + 2];
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    String error = plugin.media().importMedia(id, file);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (error == null) {
                            var entry = plugin.media().find(id);
                            sender.sendMessage("§a素材已导入: " + id
                                    + "（" + entry.width() + "x" + entry.height() + "）");
                        } else {
                            sender.sendMessage("§c导入失败: " + error);
                        }
                    });
                });
                sender.sendMessage("§7正在导入…");
            }
            case "list" -> {
                var all = plugin.media().all();
                if (all.isEmpty()) {
                    sender.sendMessage("§7没有素材（先复制文件到 media/incoming/ 再 import）");
                    return;
                }
                sender.sendMessage("§6素材（" + all.size() + "）:");
                var ids = new ArrayList<>(all.keySet());
                ids.sort(String::compareTo);
                for (String id : ids) {
                    var entry = all.get(id);
                    sender.sendMessage("§7- " + entry.id() + " " + entry.width() + "x" + entry.height()
                            + " r" + entry.revision());
                }
            }
            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage("§e用法: /minedisplay media info <id>");
                    return;
                }
                var entry = plugin.media().find(args[2]);
                if (entry == null) {
                    sender.sendMessage("§c没有该素材");
                    return;
                }
                sender.sendMessage("§6素材 " + entry.id() + ": " + entry.width() + "x" + entry.height()
                        + " sha256=" + shortHash(entry.sha256()) + " revision=" + entry.revision());
            }
            default -> sender.sendMessage("§e用法: /minedisplay media <import|list|info> ...");
        }
    }

    // ---------- 屏幕 ----------

    private void create(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只能由玩家执行（需要右键选择墙面）");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§e用法: /minedisplay create <id> <width-tiles> <height-tiles> <media-id>");
            return;
        }
        int cols;
        int rows;
        try {
            cols = Integer.parseInt(args[2]);
            rows = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§c宽高必须是整数");
            return;
        }
        String mediaId = args.length >= 5 ? args[4] : null;
        if (mediaId == null) {
            sender.sendMessage("§e用法: /minedisplay create <id> <width-tiles> <height-tiles> <media-id>");
            return;
        }
        String error = plugin.displays().beginCreate(player, args[1], mediaId, cols, rows);
        if (error != null) {
            sender.sendMessage("§c" + error);
            return;
        }
        sender.sendMessage("§a请右键点击墙面左上角（面对墙时左上），右键空气取消。");
    }

    private void set(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§e用法: /minedisplay set <id> <media-id>");
            return;
        }
        String error = plugin.displays().setMedia(args[1], args[2]);
        if (error != null) {
            sender.sendMessage("§c" + error);
            return;
        }
        sender.sendMessage("§a已绑定，正在重绘（复用自身 map ID）…");
    }

    private void glow(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法: /minedisplay glow <id>（转为发光画框，夜晚更亮）");
            return;
        }
        String error = plugin.displays().setGlowing(args[1], true);
        if (error != null) {
            sender.sendMessage("§c" + error);
            return;
        }
        sender.sendMessage("§a已转为发光画框");
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法: /minedisplay remove <id>");
            return;
        }
        String error = plugin.displays().remove(args[1]);
        if (error != null) {
            sender.sendMessage("§c" + error);
            return;
        }
        sender.sendMessage("§a屏幕已删除（共享素材保留）");
    }

    private void list(CommandSender sender) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        var all = plugin.displays().list();
        if (all.isEmpty()) {
            sender.sendMessage("§7没有屏幕");
            return;
        }
        sender.sendMessage("§6屏幕（" + all.size() + "）:");
        for (var snapshot : all) {
            sender.sendMessage("§7- " + snapshot.id() + " " + snapshot.cols() + "x" + snapshot.rows()
                    + " media=" + snapshot.mediaId() + " r" + snapshot.revision());
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§e用法: /minedisplay info <id>");
            return;
        }
        var snapshot = plugin.displays().find(args[1]);
        if (snapshot == null) {
            sender.sendMessage("§c没有该屏幕");
            return;
        }
        sender.sendMessage("§6屏幕 " + snapshot.id() + ": "
                + snapshot.cols() + "x" + snapshot.rows() + " 朝向=" + snapshot.facing()
                + " 素材=" + snapshot.mediaId() + " revision=" + snapshot.revision());
    }

    private void status(CommandSender sender) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        sender.sendMessage("§6MineDisplay: " + plugin.displays().count() + " 个屏幕，"
                + plugin.displays().totalTiles() + " 个 tiles，"
                + plugin.media().all().size() + " 个素材");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("minedisplay.admin")) {
            sender.sendMessage("§c没有权限");
            return;
        }
        plugin.reloadDisplayConfig();
        sender.sendMessage("§a配置已重载（运行中的屏幕保留）");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6/minedisplay media import <id> <incoming-file> §7导入素材");
        sender.sendMessage("§6/minedisplay media list|info <id> §7查看素材");
        sender.sendMessage("§6/minedisplay create <id> <宽> <高> <media-id> §7创建地图屏幕");
        sender.sendMessage("§6/minedisplay set <id> <media-id> §7绑定素材");
        sender.sendMessage("§6/minedisplay glow <id> §7转为发光画框");
        sender.sendMessage("§6/minedisplay remove|list|info|status §7管理屏幕");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return ROOTS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("media")) {
            return MEDIA_ACTIONS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("glow")
                || args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("info"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.displays().list().stream().map(DisplayManager.DisplaySnapshot::id)
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return new ArrayList<>(plugin.media().all().keySet()).stream()
                    .filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("media")
                && args[1].equalsIgnoreCase("info")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return new ArrayList<>(plugin.media().all().keySet()).stream()
                    .filter(id -> id.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private static String shortHash(String sha256) {
        if (sha256 == null || sha256.length() < 12) {
            return sha256 == null ? "-" : sha256;
        }
        return sha256.substring(0, 12);
    }
}
