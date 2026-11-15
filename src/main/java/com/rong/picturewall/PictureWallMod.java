package com.rong.picturewall;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PictureWallMod implements ModInitializer {
	public static final String MOD_ID = "picturewall";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final PictureFrameBlock PICTURE_FRAME = Registry.register(
			Registries.BLOCK,
			Identifier.of(MOD_ID, "picture_frame"),
			new PictureFrameBlock(AbstractBlock.Settings.copy(Blocks.GLASS).strength(1.0f))
	);
	public static final Item PICTURE_FRAME_ITEM = Registry.register(
			Registries.ITEM,
			Identifier.of(MOD_ID, "picture_frame"),
			new BlockItem(PICTURE_FRAME, new Item.Settings())
	);
	public static final Item FRAME_LINKER = Registry.register(
			Registries.ITEM,
			Identifier.of(MOD_ID, "frame_linker"),
			new FrameLinkerItem(new Item.Settings())
	);
	public static final BlockEntityType<PictureFrameBlockEntity> PICTURE_FRAME_ENTITY =
			Registry.register(
					Registries.BLOCK_ENTITY_TYPE,
					Identifier.of(MOD_ID, "picture_frame"),
					BlockEntityType.Builder.create(PictureFrameBlockEntity::new, PICTURE_FRAME).build(null)
			);

	@Override
	public void onInitialize() {
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
			entries.add(PICTURE_FRAME_ITEM);
			entries.add(FRAME_LINKER);
		});
		ServerPlayNetworking.registerGlobalReceiver(SetImagePayload.ID, (server, player, handler, buf, responseSender) -> {
			SetImagePayload payload = SetImagePayload.read(buf);
			server.execute(() -> updateFrame(
					player, payload.pos(), payload.source(), payload.crop()));
		});
	}

	private static void updateFrame(ServerPlayerEntity player, BlockPos pos, String source, ImageCrop crop) {
		World world = player.getWorld();
		if (!player.getAbilities().allowModifyWorld || player.squaredDistanceTo(pos.toCenterPos()) > 64.0
				|| !world.getBlockState(pos).isOf(PICTURE_FRAME)) {
			return;
		}
		if (!(world.getBlockEntity(pos) instanceof PictureFrameBlockEntity frame)) {
			return;
		}
		if ((!source.isEmpty() && !PictureFrameBlockEntity.isValidSource(source)) || !crop.isValid()) {
			player.sendMessage(net.minecraft.text.Text.translatable("picturewall.invalid_source"), true);
			return;
		}

		String finalSource = source;
		ImageCrop finalCrop = source.isEmpty() ? ImageCrop.NONE : crop;
		if (frame.getLinkGroup().isEmpty()) {
			frame.setImage(finalSource, finalCrop);
			return;
		}
		for (PictureFrameBlockEntity linked : FrameConnections.collectLinkedFrames(world, frame)) {
			linked.setImage(finalSource, finalCrop);
		}
	}
}
