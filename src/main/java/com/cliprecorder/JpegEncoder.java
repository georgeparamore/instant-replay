package com.cliprecorder;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Downscales captured frames and compresses them to JPEG using javax.imageio
 * (no external dependencies). Not thread-safe; confine to one executor thread.
 */
class JpegEncoder
{
	/**
	 * @param maxHeight scale down to this height if taller; 0 = no scaling
	 * @return scaled frame as JPEG bytes, or null on encode failure
	 */
	byte[] encode(Image image, int maxHeight, float quality, int[] dimsOut) throws IOException
	{
		int srcW = image.getWidth(null);
		int srcH = image.getHeight(null);
		if (srcW <= 0 || srcH <= 0)
		{
			return null;
		}

		int w = srcW;
		int h = srcH;
		if (maxHeight > 0 && h > maxHeight)
		{
			w = Math.max(1, (int) Math.round((double) srcW * maxHeight / srcH));
			h = maxHeight;
		}
		// MJPEG decoders behave best with even dimensions
		w &= ~1;
		h &= ~1;
		if (w == 0 || h == 0)
		{
			return null;
		}

		// 3BYTE_BGR is the JPEG writer's native layout - it compresses this
		// directly instead of converting the pixels first
		BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = rgb.createGraphics();
		try
		{
			if (w == srcW && h == srcH)
			{
				g.drawImage(image, 0, 0, null); // plain copy, no scaling pass
			}
			else
			{
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(image, 0, 0, w, h, null);
			}
		}
		finally
		{
			g.dispose();
		}

		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		try
		{
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);

			ByteArrayOutputStream bos = new ByteArrayOutputStream(128 * 1024);
			try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(bos))
			{
				writer.setOutput(mos);
				writer.write(null, new IIOImage(rgb, null, null), param);
			}
			dimsOut[0] = w;
			dimsOut[1] = h;
			return bos.toByteArray();
		}
		finally
		{
			writer.dispose();
		}
	}
}
