package com.cliprecorder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded ring buffer of JPEG frames. Bounded both by frame count and by total
 * bytes so a mis-configured capture can never balloon memory.
 */
class FrameRingBuffer
{
	private final ArrayDeque<Frame> frames = new ArrayDeque<>();
	private final int maxFrames;
	private final long maxBytes;
	private long totalBytes;

	FrameRingBuffer(int maxFrames, long maxBytes)
	{
		this.maxFrames = maxFrames;
		this.maxBytes = maxBytes;
	}

	synchronized void add(Frame frame)
	{
		frames.addLast(frame);
		totalBytes += frame.byteSize();
		while (frames.size() > maxFrames || totalBytes > maxBytes)
		{
			totalBytes -= frames.removeFirst().byteSize();
		}
	}

	/**
	 * Copy out all frames whose timestamp falls within [fromMs, toMs].
	 */
	synchronized List<Frame> snapshot(long fromMs, long toMs)
	{
		List<Frame> out = new ArrayList<>();
		for (Frame f : frames)
		{
			if (f.getTimestampMs() >= fromMs && f.getTimestampMs() <= toMs)
			{
				out.add(f);
			}
		}
		return out;
	}

	synchronized void clear()
	{
		frames.clear();
		totalBytes = 0;
	}

	synchronized long getTotalBytes()
	{
		return totalBytes;
	}

	synchronized int size()
	{
		return frames.size();
	}
}
