package com.cliprecorder;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ClipFormat
{
	AVI("AVI (large, instant)", ".avi"),
	MP4("MP4 (small, slower)", ".mp4");

	private final String label;
	private final String extension;

	@Override
	public String toString()
	{
		return label;
	}
}
