package com.cliprecorder;

import lombok.Value;

/**
 * A single frame in the rolling buffer: either a JPEG-compressed image, or a
 * zero-byte "repeat previous frame" marker (jpeg == null) used when the screen
 * barely changed - those cost nothing on disk or in memory.
 */
@Value
class Frame
{
	byte[] jpeg;
	long timestampMs;
	int width;
	int height;

	boolean isRepeat()
	{
		return jpeg == null;
	}

	int byteSize()
	{
		return jpeg == null ? 0 : jpeg.length;
	}
}
