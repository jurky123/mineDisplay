package com.minedisplay;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * 地图 tile 渲染器：把素材图的对应子区域缩放/填充到 128×128 后画到地图上。
 *
 * <p>移植自 ImageMaps（MIT）的 ImageMapRenderer，改动：
 * <ul>
 *   <li>去掉 CLCore 依赖（LambdaRunnable → BukkitScheduler；Tuple → 自有 Size）；</li>
 *   <li>首帧绘制不再随机延迟 0-60 tick（原版伪随机散布在 26.2 下无意义），
 *       改为渲染回调里直接画——回调只复制已算好的缓存像素，不做 IO；</li>
 *   <li>支持换图后原地重算（复用自身 map ID，不增长 ID）。</li>
 * </ul>
 */
public final class MapTileRenderer extends MapRenderer {

    private BufferedImage tile;
    private volatile boolean drawn;

    /**
     * 整图缩放到目标画布（宽=列数×128，高=行数×128）。这是唯一的重计算，
     * 必须在工作线程调用一次，多 tile 共享结果；主线程只做切分。
     *
     * @param cover true=裁剪铺满，false=保持比例 letterbox
     */
    public static BufferedImage toCanvas(BufferedImage full, int cols, int rows, boolean cover) {
        return scaleToCanvas(full, cols * MediaStore.TILE, rows * MediaStore.TILE, cover);
    }

    /**
     * @param canvas 已由 {@link #toCanvas} 算好的连续画布（cols×128 × rows×128）
     * @param col    tile 列（0 起）
     * @param row    tile 行（0 起）
     */
    public MapTileRenderer(BufferedImage canvas, int col, int row) {
        super(false);
        slice(canvas, col, row);
    }

    /**
     * @param full  完整素材图（已在工作线程载入）；为兼容保留，
     *              内部先缩放到画布再切分——多 tile 时请用 {@link #toCanvas} + 切分构造，
     *              避免每 tile 重复缩放整图。
     */
    public MapTileRenderer(BufferedImage full, int col, int row, int cols, int rows, boolean cover) {
        this(toCanvas(full, cols, rows, cover), col, row);
    }

    public synchronized void recalculate(BufferedImage full, int col, int row,
                                         int cols, int rows, boolean cover) {
        slice(toCanvas(full, cols, rows, cover), col, row);
    }

    private synchronized void slice(BufferedImage canvas, int col, int row) {
        if (canvas == null) {
            return;
        }
        int x = col * MediaStore.TILE;
        int y = row * MediaStore.TILE;
        int w = Math.min(MediaStore.TILE, canvas.getWidth() - x);
        int h = Math.min(MediaStore.TILE, canvas.getHeight() - y);
        if (w <= 0 || h <= 0) {
            return;
        }
        BufferedImage sub = canvas.getSubimage(x, y, w, h);
        BufferedImage padded = new BufferedImage(MediaStore.TILE, MediaStore.TILE, BufferedImage.TYPE_INT_ARGB);
        var graphics = padded.createGraphics();
        try {
            graphics.drawImage(sub, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        sub.flush();
        this.tile = padded;
        this.drawn = false;
    }

    private static BufferedImage scaleToCanvas(BufferedImage full, int canvasW, int canvasH, boolean cover) {
        double scaleX = (double) canvasW / full.getWidth();
        double scaleY = (double) canvasH / full.getHeight();
        double scale = cover ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        int scaledW = Math.max(1, (int) Math.round(full.getWidth() * scale));
        int scaledH = Math.max(1, (int) Math.round(full.getHeight() * scale));
        BufferedImage scaled;
        if (scaledW == full.getWidth() && scaledH == full.getHeight()) {
            scaled = full;
        } else {
            scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_ARGB);
            AffineTransform at = new AffineTransform();
            at.scale(scale, scale);
            AffineTransformOp op = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
            scaled = op.filter(full, scaled);
        }
        // 居中放到目标画布（cover 时裁掉多余边缘）。
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        var graphics = canvas.createGraphics();
        try {
            int dx = (canvasW - scaled.getWidth()) / 2;
            int dy = (canvasH - scaled.getHeight()) / 2;
            graphics.drawImage(scaled, dx, dy, null);
        } finally {
            graphics.dispose();
        }
        if (scaled != full) {
            scaled.flush();
        }
        // cover 且源图就是目标尺寸时，canvas 就是拷贝，直接返回。
        return canvas;
    }

    /** 已算好的 tile 像素（单测用）。 */
    synchronized BufferedImage tileForTest() {
        return tile;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        BufferedImage snapshot;
        synchronized (this) {
            if (drawn || tile == null) {
                return;
            }
            snapshot = tile;
            drawn = true;
        }
        // 只复制已算好的缓存像素，不做 IO 或图像解码。
        canvas.drawImage(0, 0, snapshot);
    }
}
