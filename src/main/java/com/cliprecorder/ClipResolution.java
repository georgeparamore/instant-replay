package com.cliprecorder;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ClipResolution
{
	NATIVE("Native (no scaling)", 0),
	P1080("1080p", 1080),
	P720("720p", 720),
	P480("480p", 480);

	private final String label;
	private final int maxHeight;

	@Override
	public String toString()
	{
		return label;
	}
}
