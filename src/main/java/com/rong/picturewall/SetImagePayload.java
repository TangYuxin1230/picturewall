package com.rong.picturewall;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record SetImagePayload(BlockPos pos, String source, ImageCrop crop) {
	public static final Identifier ID = new Identifier(PictureWallMod.MOD_ID, "set_image");

	public static SetImagePayload read(PacketByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		String source = buf.readString(2048);
		int left = buf.readVarInt();
		int top = buf.readVarInt();
		int right = buf.readVarInt();
		int bottom = buf.readVarInt();
		return new SetImagePayload(pos, source, new ImageCrop(left, top, right, bottom));
	}

	public void write(PacketByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeString(source, 2048);
		buf.writeVarInt(crop.left());
		buf.writeVarInt(crop.top());
		buf.writeVarInt(crop.right());
		buf.writeVarInt(crop.bottom());
	}
}
