package com.rong.picturewall;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ImageCropScreen extends Screen {
	private static final int HANDLE_SIZE = 6;
	private static final int EDGE_HIT_DISTANCE = 8;

	private final ImageSourceScreen parent;
	private final ImageCrop initialCrop;
	private final String source;
	private TextFieldWidget leftField;
	private TextFieldWidget topField;
	private TextFieldWidget rightField;
	private TextFieldWidget bottomField;
	private String error = "";
	private PictureTextureCache.Texture previewTexture;
	private int imageX;
	private int imageY;
	private int imageWidth;
	private int imageHeight;
	private DragMode dragMode = DragMode.NONE;
	private ImageCrop dragStartCrop;
	private double dragStartX;
	private double dragStartY;

	public ImageCropScreen(ImageSourceScreen parent, ImageCrop crop, String source) {
		super(Text.translatable("picturewall.crop.title"));
		this.parent = parent;
		this.initialCrop = crop;
		this.source = source;
		PictureTextureCache.retry(source);
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		CropLayout layout = layout();
		addCropField(layout.fieldX, layout.previewY + 6, initialCrop.left(), field -> leftField = field);
		addCropField(layout.fieldX, layout.previewY + 34, initialCrop.top(), field -> topField = field);
		addCropField(layout.fieldX, layout.previewY + 62, initialCrop.right(), field -> rightField = field);
		addCropField(layout.fieldX, layout.previewY + 90, initialCrop.bottom(), field -> bottomField = field);

		int buttonX = centerX - 146;
		int buttonsY = layout.buttonsY;
		addDrawableChild(ButtonWidget.builder(Text.translatable("picturewall.crop.reset"), button -> reset())
				.dimensions(buttonX, buttonsY, 92, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> apply())
				.dimensions(buttonX + 100, buttonsY, 92, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"),
						button -> MinecraftClient.getInstance().setScreen(parent))
				.dimensions(buttonX + 200, buttonsY, 92, 20).build());
	}

	private void addCropField(int x, int y, int value,
			java.util.function.Consumer<TextFieldWidget> storeField) {
		TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, 52, 20, Text.empty());
		field.setMaxLength(3);
		field.setText(Integer.toString(value));
		field.setPlaceholder(Text.literal("0"));
		addDrawableChild(field);
		storeField.accept(field);
	}

	private void reset() {
		setFields(ImageCrop.NONE);
		error = "";
	}

	private void setFields(ImageCrop crop) {
		leftField.setText(Integer.toString(crop.left()));
		topField.setText(Integer.toString(crop.top()));
		rightField.setText(Integer.toString(crop.right()));
		bottomField.setText(Integer.toString(crop.bottom()));
	}

	private ImageCrop readCrop() {
		try {
			ImageCrop crop = new ImageCrop(
					Integer.parseInt(leftField.getText()),
					Integer.parseInt(topField.getText()),
					Integer.parseInt(rightField.getText()),
					Integer.parseInt(bottomField.getText())
			);
			return crop.isValid() ? crop : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private void apply() {
		ImageCrop crop = readCrop();
		if (crop == null) {
			error = Text.translatable("picturewall.crop.invalid").getString();
			return;
		}
		parent.setCrop(crop);
		MinecraftClient.getInstance().setScreen(parent);
	}

	private void updatePreviewBounds(int maxWidth, int maxHeight) {
		imageWidth = maxWidth;
		imageHeight = maxHeight;
		if (previewTexture == null) {
			return;
		}
		double scale = Math.min((double) maxWidth / previewTexture.width(),
				(double) maxHeight / previewTexture.height());
		imageWidth = Math.max(1, (int) Math.round(previewTexture.width() * scale));
		imageHeight = Math.max(1, (int) Math.round(previewTexture.height() * scale));
	}

	private CropLayout layout() {
		int previewWidth = Math.min(360, Math.max(100, width - 180));
		int previewHeight = Math.min(240, Math.max(80, height - 100));
		int previewX = (width - previewWidth - 150) / 2;
		int previewY = Math.max(48, 48 + (height - 110 - previewHeight) / 2);
		int labelsX = previewX + previewWidth + 20;
		int buttonY = Math.min(height - 27, previewY + previewHeight + 14);
		return new CropLayout(previewX, previewY, previewWidth, previewHeight, labelsX, labelsX + 52, buttonY);
	}

	private void renderPreview(DrawContext context, int boxX, int boxY, int boxWidth, int boxHeight,
			ImageCrop crop) {
		context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xFF202020);
		context.drawBorder(boxX, boxY, boxWidth, boxHeight, 0xFFAAAAAA);
		updatePreviewBounds(boxWidth - 2, boxHeight - 2);
		imageX = boxX + (boxWidth - imageWidth) / 2;
		imageY = boxY + (boxHeight - imageHeight) / 2;

		if (previewTexture == null) {
			String key = PictureTextureCache.hasFailed(source)
					? "picturewall.crop.preview_failed" : "picturewall.crop.preview_loading";
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable(key),
					boxX + boxWidth / 2, boxY + boxHeight / 2 - 4, 0xFFFFFF);
			return;
		}

		Identifier texture = previewTexture.id();
		context.drawTexture(texture, imageX, imageY, imageWidth, imageHeight,
				0.0F, 0.0F, previewTexture.width(), previewTexture.height(),
				previewTexture.width(), previewTexture.height());

		int left = imageX + imageWidth * crop.left() / 100;
		int top = imageY + imageHeight * crop.top() / 100;
		int right = imageX + imageWidth * (100 - crop.right()) / 100;
		int bottom = imageY + imageHeight * (100 - crop.bottom()) / 100;
		context.fill(imageX, imageY, imageX + imageWidth, top, 0x99000000);
		context.fill(imageX, bottom, imageX + imageWidth, imageY + imageHeight, 0x99000000);
		context.fill(imageX, top, left, bottom, 0x99000000);
		context.fill(right, top, imageX + imageWidth, bottom, 0x99000000);
		context.drawBorder(left, top, right - left, bottom - top, 0xFFFFFFFF);
		drawHandle(context, left, top);
		drawHandle(context, right, top);
		drawHandle(context, left, bottom);
		drawHandle(context, right, bottom);
	}

	private void drawHandle(DrawContext context, int x, int y) {
		int half = HANDLE_SIZE / 2;
		context.fill(x - half, y - half, x + half, y + half, 0xFFFFFFFF);
		context.drawBorder(x - half, y - half, HANDLE_SIZE, HANDLE_SIZE, 0xFF202020);
	}

	private ImageCrop currentCropOrInitial() {
		ImageCrop crop = readCrop();
		return crop == null ? initialCrop : crop;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.translatable("picturewall.crop.hint"), width / 2, 27, 0xBBBBBB);

		CropLayout layout = layout();
		renderPreview(context, layout.previewX, layout.previewY, layout.previewWidth,
				layout.previewHeight, currentCropOrInitial());

		context.drawText(textRenderer, Text.translatable("picturewall.crop.left"), layout.labelsX,
				layout.previewY + 12, 0xFFFFFF, false);
		context.drawText(textRenderer, Text.translatable("picturewall.crop.top"), layout.labelsX,
				layout.previewY + 40, 0xFFFFFF, false);
		context.drawText(textRenderer, Text.translatable("picturewall.crop.right"), layout.labelsX,
				layout.previewY + 68, 0xFFFFFF, false);
		context.drawText(textRenderer, Text.translatable("picturewall.crop.bottom"), layout.labelsX,
				layout.previewY + 96, 0xFFFFFF, false);
		for (int row = 0; row < 4; row++) {
			context.drawText(textRenderer, Text.literal("%"), layout.labelsX + 108,
					layout.previewY + 12 + row * 28, 0xAAAAAA, false);
		}

		PictureTextureCache.Texture texture = PictureTextureCache.get(source);
		if (texture != null) {
			previewTexture = texture;
		}
		super.render(context, mouseX, mouseY, delta);
		if (!error.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, error, width / 2, height - 42, 0xFF5555);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && previewTexture != null
				&& mouseX >= imageX && mouseX <= imageX + imageWidth
				&& mouseY >= imageY && mouseY <= imageY + imageHeight) {
			ImageCrop crop = currentCropOrInitial();
			int left = imageX + imageWidth * crop.left() / 100;
			int top = imageY + imageHeight * crop.top() / 100;
			int right = imageX + imageWidth * (100 - crop.right()) / 100;
			int bottom = imageY + imageHeight * (100 - crop.bottom()) / 100;
			dragMode = pickDragMode(mouseX, mouseY, left, top, right, bottom);
			dragStartCrop = crop;
			dragStartX = mouseX;
			dragStartY = mouseY;
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private DragMode pickDragMode(double x, double y, int left, int top, int right, int bottom) {
		boolean nearLeft = Math.abs(x - left) <= EDGE_HIT_DISTANCE;
		boolean nearRight = Math.abs(x - right) <= EDGE_HIT_DISTANCE;
		boolean nearTop = Math.abs(y - top) <= EDGE_HIT_DISTANCE;
		boolean nearBottom = Math.abs(y - bottom) <= EDGE_HIT_DISTANCE;
		if (nearLeft && nearTop) return DragMode.TOP_LEFT;
		if (nearRight && nearTop) return DragMode.TOP_RIGHT;
		if (nearLeft && nearBottom) return DragMode.BOTTOM_LEFT;
		if (nearRight && nearBottom) return DragMode.BOTTOM_RIGHT;
		if (nearLeft) return DragMode.LEFT;
		if (nearRight) return DragMode.RIGHT;
		if (nearTop) return DragMode.TOP;
		if (nearBottom) return DragMode.BOTTOM;
		if (x > left && x < right && y > top && y < bottom) return DragMode.MOVE;

		double[] distances = {Math.abs(x - left), Math.abs(y - top),
				Math.abs(x - right), Math.abs(y - bottom)};
		int closest = 0;
		for (int i = 1; i < distances.length; i++) {
			if (distances[i] < distances[closest]) closest = i;
		}
		return switch (closest) {
			case 0 -> DragMode.LEFT;
			case 1 -> DragMode.TOP;
			case 2 -> DragMode.RIGHT;
			default -> DragMode.BOTTOM;
		};
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (dragMode == DragMode.NONE || button != 0 || dragStartCrop == null) {
			return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		}
		ImageCrop crop = draggedCrop(mouseX, mouseY);
		if (crop != null) {
			setFields(crop);
			error = "";
		}
		return true;
	}

	private ImageCrop draggedCrop(double mouseX, double mouseY) {
		int left = dragStartCrop.left();
		int top = dragStartCrop.top();
		int right = dragStartCrop.right();
		int bottom = dragStartCrop.bottom();
		int dx = (int) Math.round((mouseX - dragStartX) * 100 / imageWidth);
		int dy = (int) Math.round((mouseY - dragStartY) * 100 / imageHeight);
		switch (dragMode) {
			case LEFT, TOP_LEFT, BOTTOM_LEFT -> left = clamp(left + dx, 0, 99 - right);
			case RIGHT, TOP_RIGHT, BOTTOM_RIGHT -> right = clamp(right - dx, 0, 99 - left);
			default -> {
			}
		}
		switch (dragMode) {
			case TOP, TOP_LEFT, TOP_RIGHT -> top = clamp(top + dy, 0, 99 - bottom);
			case BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT -> bottom = clamp(bottom - dy, 0, 99 - top);
			default -> {
			}
		}
		if (dragMode == DragMode.MOVE) {
			int horizontalDelta = clamp(dx, -left, right);
			left += horizontalDelta;
			right -= horizontalDelta;
			int verticalDelta = clamp(dy, -top, bottom);
			top += verticalDelta;
			bottom -= verticalDelta;
		}
		return new ImageCrop(left, top, right, bottom);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (button == 0 && dragMode != DragMode.NONE) {
			dragMode = DragMode.NONE;
			dragStartCrop = null;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private enum DragMode {
		NONE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE
	}

	private record CropLayout(int previewX, int previewY, int previewWidth, int previewHeight,
			int labelsX, int fieldX, int buttonsY) {
	}
}
