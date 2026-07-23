package com.cliprecorder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Minimal MJPEG-in-AVI muxer, pure Java, no dependencies.
 *
 * Every frame is already a complete JPEG, so writing a clip is just wrapping
 * the frames in RIFF/AVI chunk headers plus an idx1 index. All chunk sizes are
 * known up front, so the file is written in a single streaming pass.
 */
final class MjpegAviWriter
{
	private static final int AVIF_HASINDEX = 0x10;
	private static final int AVIIF_KEYFRAME = 0x10;

	private MjpegAviWriter()
	{
	}

	/**
	 * Write frames to an AVI file. All frames must share the dimensions of the
	 * provided width/height; callers filter beforehand.
	 */
	static void write(File file, List<Frame> frames, int fps, int width, int height) throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IOException("No frames to write");
		}

		final int n = frames.size();

		// movi chunk payload: each frame is '00dc' + int32 size + data + pad-to-even;
		// repeat markers are zero-length chunks (standard AVI "repeat last frame")
		long moviDataSize = 0;
		for (Frame f : frames)
		{
			moviDataSize += 8 + f.byteSize() + (f.byteSize() & 1);
		}
		final long moviListSize = 4 + moviDataSize; // 'movi' fourcc + chunks
		final long idxSize = 16L * n;

		// hdrl: 'hdrl' + avih chunk (8+56) + LIST strl (8 + 4 + strh(8+56) + strf(8+40))
		final int strlListSize = 4 + 64 + 48;
		final int hdrlListSize = 4 + 64 + 8 + strlListSize;

		final long riffSize = 4 // 'AVI '
			+ 8 + hdrlListSize
			+ 8 + moviListSize
			+ 8 + idxSize;

		int maxFrameBytes = 0;
		for (Frame f : frames)
		{
			maxFrameBytes = Math.max(maxFrameBytes, f.byteSize());
		}

		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16))
		{
			fourcc(out, "RIFF");
			u32(out, riffSize);
			fourcc(out, "AVI ");

			// ---- LIST hdrl ----
			fourcc(out, "LIST");
			u32(out, hdrlListSize);
			fourcc(out, "hdrl");

			// avih - main AVI header
			fourcc(out, "avih");
			u32(out, 56);
			u32(out, 1_000_000L / fps); // dwMicroSecPerFrame
			u32(out, (long) maxFrameBytes * fps); // dwMaxBytesPerSec
			u32(out, 0); // dwPaddingGranularity
			u32(out, AVIF_HASINDEX); // dwFlags
			u32(out, n); // dwTotalFrames
			u32(out, 0); // dwInitialFrames
			u32(out, 1); // dwStreams
			u32(out, maxFrameBytes); // dwSuggestedBufferSize
			u32(out, width);
			u32(out, height);
			u32(out, 0); // dwReserved[4]
			u32(out, 0);
			u32(out, 0);
			u32(out, 0);

			// ---- LIST strl ----
			fourcc(out, "LIST");
			u32(out, strlListSize);
			fourcc(out, "strl");

			// strh - stream header
			fourcc(out, "strh");
			u32(out, 56);
			fourcc(out, "vids");
			fourcc(out, "MJPG");
			u32(out, 0); // dwFlags
			u32(out, 0); // wPriority + wLanguage
			u32(out, 0); // dwInitialFrames
			u32(out, 1); // dwScale
			u32(out, fps); // dwRate -> fps = rate/scale
			u32(out, 0); // dwStart
			u32(out, n); // dwLength (frames)
			u32(out, maxFrameBytes); // dwSuggestedBufferSize
			u32(out, 0xFFFFFFFFL); // dwQuality (-1 = default)
			u32(out, 0); // dwSampleSize
			u16(out, 0); // rcFrame left
			u16(out, 0); // rcFrame top
			u16(out, width); // rcFrame right
			u16(out, height); // rcFrame bottom

			// strf - BITMAPINFOHEADER
			fourcc(out, "strf");
			u32(out, 40);
			u32(out, 40); // biSize
			u32(out, width);
			u32(out, height);
			u16(out, 1); // biPlanes
			u16(out, 24); // biBitCount
			fourcc(out, "MJPG"); // biCompression
			u32(out, (long) width * height * 3); // biSizeImage
			u32(out, 0); // biXPelsPerMeter
			u32(out, 0); // biYPelsPerMeter
			u32(out, 0); // biClrUsed
			u32(out, 0); // biClrImportant

			// ---- LIST movi ----
			fourcc(out, "LIST");
			u32(out, moviListSize);
			fourcc(out, "movi");

			for (Frame f : frames)
			{
				fourcc(out, "00dc");
				u32(out, f.byteSize());
				if (!f.isRepeat())
				{
					out.write(f.getJpeg());
					if ((f.byteSize() & 1) != 0)
					{
						out.write(0);
					}
				}
			}

			// ---- idx1 ----
			fourcc(out, "idx1");
			u32(out, idxSize);
			long offset = 4; // relative to start of 'movi' fourcc
			for (Frame f : frames)
			{
				fourcc(out, "00dc");
				u32(out, f.isRepeat() ? 0 : AVIIF_KEYFRAME);
				u32(out, offset);
				u32(out, f.byteSize());
				offset += 8 + f.byteSize() + (f.byteSize() & 1);
			}
		}
	}

	private static void fourcc(OutputStream out, String s) throws IOException
	{
		for (int i = 0; i < 4; i++)
		{
			out.write(s.charAt(i));
		}
	}

	private static void u32(OutputStream out, long v) throws IOException
	{
		out.write((int) (v & 0xFF));
		out.write((int) ((v >> 8) & 0xFF));
		out.write((int) ((v >> 16) & 0xFF));
		out.write((int) ((v >> 24) & 0xFF));
	}

	private static void u16(OutputStream out, int v) throws IOException
	{
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
	}
}
