package com.rong.picturewall;

import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

public class FrameLinkerItem extends Item {
	private final Map<ServerPlayerEntity, Target> selectedFrames = new WeakHashMap<>();

	public FrameLinkerItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		BlockPos pos = context.getBlockPos().toImmutable();
		if (!world.getBlockState(pos).isOf(PictureWallMod.PICTURE_FRAME)
				|| !(world.getBlockEntity(pos) instanceof PictureFrameBlockEntity)) {
			return ActionResult.PASS;
		}
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(context.getPlayer() instanceof ServerPlayerEntity player)
				|| !player.getAbilities().allowModifyWorld
				|| player.squaredDistanceTo(pos.toCenterPos()) > 64.0) {
			return ActionResult.FAIL;
		}

		Target previous = selectedFrames.remove(player);
		if (previous == null) {
			selectedFrames.put(player, new Target(world, pos, world.getTime()));
			player.sendMessage(Text.translatable("picturewall.link.first_selected"), true);
			return ActionResult.SUCCESS;
		}
		if (previous.world != world || !world.isChunkLoaded(previous.pos)
				|| world.getTime() - previous.selectedAt > 600
				|| player.squaredDistanceTo(previous.pos.toCenterPos()) > 64.0
				|| !world.getBlockState(previous.pos).isOf(PictureWallMod.PICTURE_FRAME)) {
			player.sendMessage(Text.translatable("picturewall.link.select_again"), true);
			return ActionResult.SUCCESS;
		}
		FrameConnections.connect(player, world, previous.pos, pos);
		return ActionResult.SUCCESS;
	}

	private record Target(World world, BlockPos pos, long selectedAt) {
	}
}
