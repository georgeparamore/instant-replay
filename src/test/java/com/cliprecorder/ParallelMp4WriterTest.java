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

public class ParallelMp4WriterTest
{
	@Test
	public void writesValidStitchedMp4() throws IOException
	{
		final int w = 320;
		final int h = 240;
		final int fps = 30;
		// Enough frames to force several parallel chunks, with repeat markers mixed in
		List<Frame> frames = new ArrayList<>();
		for (int i = 0; i < 240; i++)
		{
			if (i > 0 && i % 5 == 0)
			{
				frames.add(new Frame(null, 1000L * i / fps, w, h));
			}
			else
			{
				frames.add(new Frame(makeJpeg(w, h, i), 1000L * i / fps, w, h));
			}
		}

		// Written into build/ (not deleted) so the output can be inspected
		File out = new File("build", "parallel-mp4-test-sample.mp4");
		ParallelMp4Writer.write(out, frames, fps);

		assertTrue(out.length() > 0);
		byte[] head = new byte[12];
		System.arraycopy(Files.readAllBytes(out.toPath()), 0, head, 0, 12);
		assertEquals("ftyp", new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
	}

	private static byte[] makeJpeg(int w, int h, int seed) throws IOException
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color((seed * 5) % 255, (seed * 11) % 255, (seed * 17) % 255));
		g.fillRect(0, 0, w, h);
		g.setColor(Color.WHITE);
		g.drawString("frame " + seed, 20, h / 2);
		g.dispose();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", bos);
		return bos.toByteArray();
	}
}
