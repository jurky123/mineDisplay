package com.minedisplay;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import javax.imageio.ImageIO;

/**
 * 素材库：管理员复制 PNG/JPEG 到 {@code plugins/MineDisplay/media/incoming/}，
 * {@code media import} 校验后发布为不可变素材。
 *
 * <p>M1 只接受本地文件；HTTPS 导入默认关闭（M2 才开放）。
 * 校验规则沿 DESIGN §7：拒绝路径逃逸、符号链接越界、伪造扩展名、
 * 过大像素与解码炸弹。主线程不做 IO/解码，由调用方保证异步。
 */
public final class MediaStore {

    /** 地图单 tile 边长。 */
    public static final int TILE = 128;

    private final MineDisplayPlugin plugin;
    private final File incomingDir;
    private final File mediaDir;
    private final Map<String, MediaEntry> index = new ConcurrentHashMap<>();

    /** 不可变素材条目（替换产生新 revision，不原地改）。 */
    public record MediaEntry(String id, String file, int width, int height, String sha256, long revision) {
    }

    public MediaStore(MineDisplayPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        this.incomingDir = new File(dataFolder, "media/incoming");
        this.mediaDir = new File(dataFolder, "media");
        if (!incomingDir.isDirectory() && !incomingDir.mkdirs()) {
            plugin.getLogger().warning("无法创建素材 incoming 目录: " + incomingDir);
        }
        loadIndex();
    }

    public File incomingDir() {
        return incomingDir;
    }

    public MediaEntry find(String id) {
        return id == null ? null : index.get(id.toLowerCase(Locale.ROOT));
    }

    public Map<String, MediaEntry> all() {
        return Map.copyOf(index);
    }

    /**
     * 导入素材：校验 incoming 目录下的文件，发布为 {@code media/<id>.png}。
     *
     * @return 失败原因，成功返回 null
     */
    public String importMedia(String id, String incomingFile) {
        String idError = checkId(id);
        if (idError != null) {
            return idError;
        }
        if (incomingFile == null || incomingFile.isBlank()
                || incomingFile.contains("/") || incomingFile.contains("\\")
                || incomingFile.contains(":") || incomingFile.equals(".") || incomingFile.equals("..")) {
            return "文件名非法（不接受路径）";
        }
        String lower = incomingFile.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
            return "只接受 PNG/JPEG（扩展名）";
        }
        File src;
        try {
            src = incomingDir.getCanonicalFile().toPath().resolve(incomingFile).toFile();
            if (!src.getCanonicalPath().startsWith(incomingDir.getCanonicalPath() + File.separator)) {
                return "拒绝路径逃逸";
            }
            if (java.nio.file.Files.isSymbolicLink(src.toPath())) {
                return "拒绝符号链接";
            }
        } catch (IOException e) {
            return "文件不可读: " + e.getMessage();
        }
        if (!src.isFile()) {
            return "incoming 目录下没有该文件: " + incomingFile;
        }
        long maxMib = plugin.getConfig().getLong("media.max-source-mib", 32);
        if (src.length() > maxMib * 1024 * 1024) {
            return "文件超过上限 " + maxMib + " MiB";
        }
        BufferedImage image;
        try {
            image = ImageIO.read(src);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "解码素材失败: " + incomingFile, e);
            return "解码失败（不是有效图片）";
        }
        if (image == null) {
            return "解码失败（不是有效图片）";
        }
        long maxPixels = plugin.getConfig().getLong("media.max-image-pixels", 16777216L);
        if ((long) image.getWidth() * image.getHeight() > maxPixels) {
            return "像素超过上限 " + maxPixels + "（解码炸弹防护）";
        }
        // 实际解码尺寸同样受限：PNG 文件头通过、内容超大的情况在这里拦截。
        File dest = new File(mediaDir, id.toLowerCase(Locale.ROOT) + ".png");
        try {
            ImageIO.write(image, "PNG", dest);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "发布素材失败: " + id, e);
            return "发布失败: " + e.getMessage();
        }
        String sha256 = sha256Hex(dest);
        MediaEntry old = index.get(id.toLowerCase(Locale.ROOT));
        long revision = old == null ? 1 : old.revision() + 1;
        index.put(id.toLowerCase(Locale.ROOT),
                new MediaEntry(id, dest.getName(), image.getWidth(), image.getHeight(), sha256, revision));
        saveIndex();
        // 替换素材后，已绑定该素材的地图需要重绘（复用自身 map ID，不增长）。
        plugin.displays().refreshMedia(id);
        return null;
    }

    /** 读取已发布的素材图片（调用方保证不在主线程做 IO）。 */
    public BufferedImage loadImage(MediaEntry entry) {
        if (entry == null) {
            return null;
        }
        try {
            return ImageIO.read(new File(mediaDir, entry.file()));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "读取素材失败: " + entry.id(), e);
            return null;
        }
    }

    /** 删除素材：仍被屏幕引用时拒绝。 */
    public String deleteMedia(String id) {
        MediaEntry entry = find(id);
        if (entry == null) {
            return "没有该素材";
        }
        if (plugin.displays().isMediaReferenced(entry.id())) {
            return "仍被屏幕引用，先解绑再删除";
        }
        index.remove(entry.id().toLowerCase(Locale.ROOT));
        new File(mediaDir, entry.file()).delete();
        saveIndex();
        return null;
    }

    static String checkId(String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,32}")) {
            return "ID 只允许小写字母、数字、下划线、短横线（1-32 字符）";
        }
        return null;
    }

    private static String sha256Hex(File file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void loadIndex() {
        File file = new File(mediaDir, "index.yml");
        if (!file.isFile()) {
            return;
        }
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        var section = config.getConfigurationSection("media");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            var entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            index.put(key.toLowerCase(Locale.ROOT), new MediaEntry(
                    entry.getString("id", key),
                    entry.getString("file", key + ".png"),
                    entry.getInt("width", 0),
                    entry.getInt("height", 0),
                    entry.getString("sha256", ""),
                    entry.getLong("revision", 1)));
        }
    }

    private void saveIndex() {
        File file = new File(mediaDir, "index.yml");
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("schema-version", 1);
        for (MediaEntry entry : index.values()) {
            String base = "media." + entry.id().toLowerCase(Locale.ROOT);
            config.set(base + ".id", entry.id());
            config.set(base + ".file", entry.file());
            config.set(base + ".width", entry.width());
            config.set(base + ".height", entry.height());
            config.set(base + ".sha256", entry.sha256());
            config.set(base + ".revision", entry.revision());
        }
        // 原子写：先写临时文件再 rename，避免崩溃留下半截索引。
        try {
            File tmp = new File(mediaDir, "index.yml.tmp");
            config.save(tmp);
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "保存素材索引失败", e);
        }
    }
}
