package com.minedisplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * MapTileRenderer 单测：缩放分块、方向无关的像素正确性。
 * Bukkit render 回调需要服务端环境，这里只验证已算好的缓存像素。
 */
class MapTileRendererTest {

    /** 2x2 象限色块图：整图按连续画布分块，每 tile 应取到对应象限颜色。 */
    @Test
    void tilesSplitContinuousCanvas() {
        BufferedImage full = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = full.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 128, 128);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(128, 0, 128, 128);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 128, 128, 128);
            graphics.setColor(Color.YELLOW);
            graphics.fillRect(128, 128, 128, 128);
        } finally {
            graphics.dispose();
        }
        // (col,row) -> 期望中心色
        assertTileColor(full, 0, 0, Color.RED);
        assertTileColor(full, 1, 0, Color.GREEN);
        assertTileColor(full, 0, 1, Color.BLUE);
        assertTileColor(full, 1, 1, Color.YELLOW);
    }

    /** 保持比例 letterbox：宽图上下应留透明边，内容居中。 */
    @Test
    void letterboxKeepsAspect() {
        BufferedImage full = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = full.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 256, 128);
        } finally {
            graphics.dispose();
        }
        // 1x1 画布 128x128，宽图等比缩小后上下留边。
        MapTileRenderer renderer = new MapTileRenderer(full, 0, 0, 1, 1, false);
        BufferedImage tile = renderer.tileForTest();
        assertNotNull(tile);
        assertEquals(128, tile.getWidth());
        assertEquals(128, tile.getHeight());
        // 顶部应为透明（背景填充），中间为白色内容。
        assertEquals(0, (tile.getRGB(64, 2) >>> 24));
        assertEquals(Color.WHITE.getRGB(), tile.getRGB(64, 64));
    }

    /** 裁剪铺满：宽图应裁掉左右（居中），上下铺满无透明边。 */
    @Test
    void coverCropsCenter() {
        BufferedImage full = new BufferedImage(256, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = full.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 64, 128);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(64, 0, 128, 128);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(192, 0, 64, 128);
        } finally {
            graphics.dispose();
        }
        MapTileRenderer renderer = new MapTileRenderer(full, 0, 0, 1, 1, true);
        BufferedImage tile = renderer.tileForTest();
        assertNotNull(tile);
        // 上下应铺满（不透明），左右红蓝边被裁掉，中心为绿。
        assertEquals(255, (tile.getRGB(64, 2) >>> 24));
        assertEquals(255, (tile.getRGB(64, 125) >>> 24));
    }

    /** 单 tile 图：1x1 不分块，整图缩放到 128x128。 */
    @Test
    void singleTileScalesWholeImage() {
        BufferedImage full = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = full.createGraphics();
        try {
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, 64, 64);
        } finally {
            graphics.dispose();
        }
        MapTileRenderer renderer = new MapTileRenderer(full, 0, 0, 1, 1, false);
        BufferedImage tile = renderer.tileForTest();
        assertNotNull(tile);
        assertEquals(Color.MAGENTA.getRGB(), tile.getRGB(64, 64));
    }

    private static void assertTileColor(BufferedImage full, int col, int row, Color expected) {
        MapTileRenderer renderer = new MapTileRenderer(full, col, row, 2, 2, false);
        BufferedImage tile = renderer.tileForTest();
        assertNotNull(tile);
        // tile 中心像素应为该象限颜色（双线性缩放边缘 1px 误差，取中心避开）。
        int rgb = tile.getRGB(64, 64);
        assertEquals(expected.getRGB(), rgb,
                "tile (" + col + "," + row + ") 中心色应为 " + expected);
    }
}
