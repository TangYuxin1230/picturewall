package com.rong.picturewall;

public record ImageCrop(int left, int top, int right, int bottom) {
	public static final ImageCrop NONE = new ImageCrop(0, 0, 0, 0);

	public boolean isValid() {
		return left >= 0 && top >= 0 && right >= 0 && bottom >= 0
				&& left < 100 && right < 100 && top < 100 && bottom < 100
				&& left + right < 100 && top + bottom < 100;
	}
}
