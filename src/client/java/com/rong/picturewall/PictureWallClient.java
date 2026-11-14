package com.rong.picturewall;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

public class PictureWallClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PictureTextureCache.stopAnimations());
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			BlockPos pos = hit.getBlockPos();
			if (player.getStackInHand(hand).isOf(PictureWallMod.FRAME_LINKER)) {
				return ActionResult.PASS;
			}
			if (world.isClient && world.getBlockState(pos).isOf(PictureWallMod.PICTURE_FRAME)
					&& world.getBlockEntity(pos) instanceof PictureFrameBlockEntity frame) {
				net.minecraft.client.MinecraftClient.getInstance()
						.setScreen(new ImageSourceScreen(pos, frame.getSource(), frame.getCrop()));
				return ActionResult.SUCCESS;
			}
			return ActionResult.PASS;
		});
		BlockEntityRendererRegistry.register(
				PictureWallMod.PICTURE_FRAME_ENTITY,
				PictureFrameBlockEntityRenderer::new
		);
	}
}
