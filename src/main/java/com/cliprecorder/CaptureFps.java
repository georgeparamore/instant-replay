package com.cliprecorder;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CaptureFps
{
	FPS_15("15 FPS", 15),
	FPS_30("30 FPS", 30),
	FPS_60("60 FPS", 60);

	private final String label;
	private final int fps;

	@Override
	public String toString()
	{
		return label;
	}
}
