package com.minedisplay.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** JSON 编解码与校验：UTF-8、无压缩、未知字段忽略。 */
public final class ProtocolCodec {

    private static final Gson GSON = new Gson();

    private ProtocolCodec() {
    }

    public static byte[] encode(Envelope envelope) {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", envelope.protocol());
        root.addProperty("type", envelope.type().name());
        if (envelope.screen() != null) root.addProperty("screen", envelope.screen());
        if (envelope.revision() > 0) root.addProperty("revision", envelope.revision());
        root.add("data", envelope.data() == null ? new JsonObject() : envelope.data());
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    public static Envelope decode(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length == 0) {
            throw new ProtocolException("empty packet");
        }
        if (bytes.length > ProtocolLimits.MAX_PACKET_BYTES) {
            throw new ProtocolException("packet too large: " + bytes.length);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ProtocolException("invalid json", e);
        }
        int protocol = root.has("protocol") ? root.get("protocol").getAsInt() : -1;
        if (protocol != ProtocolVersion.PROTOCOL) {
            throw new ProtocolException("unsupported protocol: " + protocol);
        }
        PacketType type = PacketType.from(root.has("type") ? root.get("type").getAsString() : null);
        if (type == null) {
            throw new ProtocolException("unknown packet type");
        }
        String screen = root.has("screen") && root.get("screen").isJsonPrimitive()
                ? root.get("screen").getAsString() : null;
        int revision = root.has("revision") ? root.get("revision").getAsInt() : 0;
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        return new Envelope(protocol, type, screen, revision, data);
    }

    public static <T> T data(Envelope envelope, Class<T> type) throws ProtocolException {
        try {
            return GSON.fromJson(envelope.data(), type);
        } catch (RuntimeException e) {
            throw new ProtocolException("invalid " + envelope.type() + " data", e);
        }
    }

    public static JsonObject data(Object value) {
        return GSON.toJsonTree(value).getAsJsonObject();
    }

    // ---------- 各包校验 ----------

    public static void validateUpsert(Packets.Upsert upsert) throws ProtocolException {
        if (upsert.screenId() == null || upsert.screenId().isBlank()
                || upsert.screenId().length() > ProtocolLimits.MAX_ID_LENGTH) {
            throw new ProtocolException("UPSERT missing screenId");
        }
        // 只查形状（末段令牌）；基址匹配由发送端（配置）与接收端（WELCOME）各自校验。
        ProxyUrl.validateShape(upsert.url());
    }

    public static void validateAction(Packets.Action action) throws ProtocolException {
        if (action.screenId() == null || action.screenId().isBlank()
                || action.screenId().length() > ProtocolLimits.MAX_ID_LENGTH) {
            throw new ProtocolException("ACTION missing screenId");
        }
        if (action.kind() == null || action.kind().isBlank()) {
            throw new ProtocolException("ACTION missing kind");
        }
        // 玩家请求只能引用服务端已有 ID，不允许指定任意 URL/路径。
        if (action.url() != null && !action.url().isBlank()) {
            throw new ProtocolException("ACTION must not carry url");
        }
    }
}
