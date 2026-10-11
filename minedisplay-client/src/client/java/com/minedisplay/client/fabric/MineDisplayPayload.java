package com.minedisplay.client.fabric;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 唯一载荷：内部承载 minedisplay-protocol 的 UTF-8 JSON 字节（沿 MineAudio）。 */
public record MineDisplayPayload(byte[] data) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("minedisplay", "main");

    public static final CustomPacketPayload.Type<MineDisplayPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, MineDisplayPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data()),
            buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new MineDisplayPayload(data);
            }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
