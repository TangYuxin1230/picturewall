package com.rong.picturewall;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record SetImagePayload(BlockPos pos, String source, ImageCrop crop) implements CustomPayload {
	public static final Id<SetImagePayload> ID = new Id<>(Identifier.of(PictureWallMod.MOD_ID, "set_image"));
	public static final PacketCodec<RegistryByteBuf, SetImagePayload> CODEC = PacketCodec.tuple(
			BlockPos.PACKET_CODEC, SetImagePayload::pos,
			PacketCodecs.string(2048), SetImagePayload::source,
			PacketCodecs.VAR_INT, payload -> payload.crop().left(),
			PacketCodecs.VAR_INT, payload -> payload.crop().top(),
			PacketCodecs.VAR_INT, payload -> payload.crop().right(),
			PacketCodecs.VAR_INT, payload -> payload.crop().bottom(),
			(pos, source, left, top, right, bottom) ->
					new SetImagePayload(pos, source, new ImageCrop(left, top, right, bottom))
	);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
