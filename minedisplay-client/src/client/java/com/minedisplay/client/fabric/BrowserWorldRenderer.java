package com.minedisplay.client.fabric;

import java.util.Locale;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.minedisplay.client.fabric.BrowserClient.ScreenState;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 世界里渲染浏览器屏：把 Rinku 纹理贴到服务端 {@code browser place} 摆好的几何上
 * （效果类似 M1 地图墙，但由客户端 mod 在世界里画 quad；没装 mod 的玩家看不到）。
 *
 * <p>朝向约定（与服务端摆放一致）：facing 是屏幕正面朝向（NORTH/SOUTH/EAST/WEST），
 * “右”向量按站在正面看屏幕的视角取（例如面朝南的屏，站在南边看，右 = 东）。
 *
 * <p>渲染线程回调：{@code COLLECT_SUBMITS} 阶段拿相机相对 PoseStack 提交自定义几何。
 */
public final class BrowserWorldRenderer {

    /** 渲染距离（方块）：远处不画，与浏览器实例的存活范围保持同数量级。 */
    private static final double RENDER_DISTANCE = 64.0;
    /** 全亮（0xF000F0，同信标光束）：屏幕不随环境光变暗。 */
    private static final int FULL_BRIGHT = 15728880;
    /** 几何防护：最小渲染尺寸。 */
    private static final double MIN_SIZE = 0.1;

    private BrowserWorldRenderer() {
    }

    public static void init() {
        LevelRenderEvents.COLLECT_SUBMITS.register(BrowserWorldRenderer::collect);
    }

    private static void collect(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        var states = BrowserClient.get().screens();
        if (states.isEmpty()) {
            return;
        }
        var cameraState = context.levelState().cameraRenderState;
        if (cameraState == null || !cameraState.initialized || cameraState.pos == null) {
            return;
        }
        Vec3 camera = cameraState.pos;
        Frustum frustum = cameraState.cullFrustum;
        String dimension = minecraft.level.dimension().identifier().toString();
        PoseStack poseStack = context.poseStack();
        var collector = context.submitNodeCollector();
        for (ScreenState state : states.values()) {
            if (!state.ready() || !state.placed() || !dimension.equals(state.world())) {
                continue;
            }
            if (!state.view().textureReady()) {
                continue; // 首帧未就绪先不画，避免黑块
            }
            Quad quad = Quad.of(state);
            if (quad.center().distanceTo(camera) > RENDER_DISTANCE) {
                continue;
            }
            if (frustum != null && !frustum.isVisible(quad.aabb())) {
                continue;
            }
            Identifier texture = state.view().textureId();
            poseStack.pushPose();
            poseStack.translate(quad.center().x - camera.x, quad.center().y - camera.y,
                    quad.center().z - camera.z);
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(texture),
                    (pose, vertexConsumer) -> emitQuad(vertexConsumer, pose, quad));
            poseStack.popPose();
        }
    }

    /** 从正面看：左上 → 左下 → 右下 → 右上（顺时针从背面看，正面为可见面）。 */
    private static void emitQuad(VertexConsumer vc, PoseStack.Pose pose, Quad quad) {
        double hw = Math.max(MIN_SIZE, quad.width()) / 2.0;
        double hh = Math.max(MIN_SIZE, quad.height()) / 2.0;
        Vec3 right = quad.right();
        Vec3 up = quad.up();
        Vec3 normal = quad.normal();
        float nx = (float) normal.x;
        float ny = (float) normal.y;
        float nz = (float) normal.z;
        vertex(vc, pose, right, up, -hw, hh, 0f, 0f, nx, ny, nz);
        vertex(vc, pose, right, up, -hw, -hh, 0f, 1f, nx, ny, nz);
        vertex(vc, pose, right, up, hw, -hh, 1f, 1f, nx, ny, nz);
        vertex(vc, pose, right, up, hw, hh, 1f, 0f, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 right, Vec3 up,
                               double rx, double ry, float u, float v, float nx, float ny, float nz) {
        double x = right.x * rx + up.x * ry;
        double y = right.y * rx + up.y * ry;
        double z = right.z * rx + up.z * ry;
        vc.addVertex(pose, (float) x, (float) y, (float) z)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    /** 精确命中结果：屏幕 id + 归一化坐标（左上 0,0；右下 1,1）。 */
    public record Hit(String screenId, double u, double v) {
    }

    /**
     * 指哪点哪：准星射线与屏幕平面精确求交（矩形边界内），取最近的一块屏。
     *
     * @return 命中（含 uv）；没瞄准返回 null
     */
    public static Hit hitScreen(Minecraft minecraft, double range) {
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }
        Vec3 from = minecraft.player.getEyePosition();
        Vec3 dir = minecraft.player.getViewVector(1.0f).normalize();
        String dimension = minecraft.level.dimension().identifier().toString();
        Hit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ScreenState state : BrowserClient.get().screens().values()) {
            if (!state.placed() || !dimension.equals(state.world())) {
                continue;
            }
            Quad quad = Quad.of(state);
            double denominator = dir.dot(quad.normal());
            if (Math.abs(denominator) < 1.0e-6) {
                continue; // 平行
            }
            double distance = quad.center().subtract(from).dot(quad.normal()) / denominator;
            if (distance < 0 || distance > range || distance >= bestDistance) {
                continue;
            }
            Vec3 point = from.add(dir.scale(distance));
            Vec3 local = point.subtract(quad.center());
            double u = local.dot(quad.right()) / Math.max(MIN_SIZE, quad.width()) + 0.5;
            double v = 0.5 - local.dot(quad.up()) / Math.max(MIN_SIZE, quad.height());
            if (u < 0 || u > 1 || v < 0 || v > 1) {
                continue;
            }
            best = new Hit(state.screenId(), u, v);
            bestDistance = distance;
        }
        return best;
    }

    /**
     * 准星命中的浏览器屏（交互用）：射线与屏幕 AABB 相交，取最近的一个。
     *
     * @return screenId；没瞄准返回 null
     */
    public static String aimedScreenId(Minecraft minecraft, double range) {
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }
        Vec3 from = minecraft.player.getEyePosition();
        Vec3 to = from.add(minecraft.player.getViewVector(1.0f).scale(range));
        String dimension = minecraft.level.dimension().identifier().toString();
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ScreenState state : BrowserClient.get().screens().values()) {
            if (!state.placed() || !dimension.equals(state.world())) {
                continue;
            }
            Quad quad = Quad.of(state);
            var hit = quad.aabb().clip(from, to);
            if (hit.isPresent()) {
                double distance = hit.get().distanceTo(from);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = state.screenId();
                }
            }
        }
        return best;
    }

    /** 世界几何：中心 + 右/上/法线 + 宽高（与服务端 place 的约定一致）。 */
    record Quad(Vec3 center, Vec3 right, Vec3 up, Vec3 normal, double width, double height) {

        static Quad of(ScreenState state) {
            Vec3 normal;
            Vec3 right;
            switch (state.facing().toUpperCase(Locale.ROOT)) {
                case "SOUTH" -> { // 正面朝南：站南边看，右 = 东（+X）
                    normal = new Vec3(0, 0, 1);
                    right = new Vec3(1, 0, 0);
                }
                case "EAST" -> { // 正面朝东：站东边看，右 = 北（-Z）
                    normal = new Vec3(1, 0, 0);
                    right = new Vec3(0, 0, -1);
                }
                case "WEST" -> { // 正面朝西：站西边看，右 = 南（+Z）
                    normal = new Vec3(-1, 0, 0);
                    right = new Vec3(0, 0, 1);
                }
                default -> { // NORTH：正面朝北，站北边看，右 = 西（-X）
                    normal = new Vec3(0, 0, -1);
                    right = new Vec3(-1, 0, 0);
                }
            }
            return new Quad(new Vec3(state.x(), state.y(), state.z()), right, new Vec3(0, 1, 0),
                    normal, state.width(), state.height());
        }

        AABB aabb() {
            double hw = Math.max(MIN_SIZE, width) / 2.0;
            double hh = Math.max(MIN_SIZE, height) / 2.0;
            double minX = center.x, maxX = center.x;
            double minY = center.y, maxY = center.y;
            double minZ = center.z, maxZ = center.z;
            for (double rx : new double[]{-hw, hw}) {
                for (double ry : new double[]{-hh, hh}) {
                    double x = center.x + right.x * rx + up.x * ry;
                    double y = center.y + right.y * rx + up.y * ry;
                    double z = center.z + right.z * rx + up.z * ry;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            return new AABB(minX - 0.05, minY - 0.05, minZ - 0.05,
                    maxX + 0.05, maxY + 0.05, maxZ + 0.05);
        }
    }
}
