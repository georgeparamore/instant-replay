package com.cliprecorder;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MjpegAviWriterTest
{
	@Test
	public void writesValidAviStructure() throws IOException
	{
		final int w = 320;
		final int h = 240;
		final int fps = 15;
		List<Frame> frames = new ArrayList<>();
		for (int i = 0; i < 30; i++)
		{
			// Every third frame is a zero-byte "repeat previous" marker
			if (i > 0 && i % 3 == 0)
			{
				frames.add(new Frame(null, 1000L * i / fps, w, h));
			}
			else
			{
				frames.add(new Frame(makeJpeg(w, h, i), 1000L * i / fps, w, h));
			}
		}

		// Written into build/ (not deleted) so the output can be inspected
		File out = new File("build", "aviwriter-test-sample.avi");
		MjpegAviWriter.write(out, frames, fps, w, h);

		byte[] data = Files.readAllBytes(out.toPath());
		assertEquals("RIFF", fourcc(data, 0));
		assertEquals("AVI ", fourcc(data, 8));
		// RIFF size field must equal file length - 8
		assertEquals(data.length - 8, u32(data, 4));
		assertEquals("LIST", fourcc(data, 12));
		assertEquals("hdrl", fourcc(data, 20));
		assertEquals("avih", fourcc(data, 24));
		// total frames in avih
		assertEquals(30, u32(data, 24 + 8 + 16));
		// file ends with a full idx1: 8 byte header + 16 bytes per frame
		int idxStart = data.length - (8 + 16 * 30);
		assertEquals("idx1", fourcc(data, idxStart));
		assertTrue(out.length() > 0);
	}

	private static byte[] makeJpeg(int w, int h, int seed) throws IOException
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color((seed * 40) % 255, (seed * 80) % 255, (seed * 120) % 255));
		g.fillRect(0, 0, w, h);
		g.dispose();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", bos);
		return bos.toByteArray();
	}

	private static String fourcc(byte[] data, int off)
	{
		return new String(data, off, 4, java.nio.charset.StandardCharsets.US_ASCII);
	}

	private static long u32(byte[] data, int off)
	{
		return (data[off] & 0xFFL)
			| (data[off + 1] & 0xFFL) << 8
			| (data[off + 2] & 0xFFL) << 16
			| (data[off + 3] & 0xFFL) << 24;
	}
}
