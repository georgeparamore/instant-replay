package com.cliprecorder;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.Brand;
import org.jcodec.containers.mp4.MP4TrackType;
import org.jcodec.containers.mp4.boxes.SampleEntry;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.containers.mp4.muxer.MP4MuxerTrack;

/**
 * H.264/MP4 clip writer that splits the clip into chunks encoded concurrently
 * (JCodec is pure Java but slow single-threaded), then stitches the chunk
 * files into one MP4 by remuxing - no re-encode on the stitch. All chunks use
 * identical encoder settings, so their SPS/PPS match and the first chunk's
 * sample entry describes the whole stream.
 */
@Slf4j
final class ParallelMp4Writer
{
	private static final int MIN_CHUNK_FRAMES = 30;

	private ParallelMp4Writer()
	{
	}

	static void write(File file, List<Frame> frames, int fps) throws IOException
	{
		// Keep the encode gentle on the JVM so gameplay stays smooth: few
		// workers and low priority. The limiter isn't CPU cores - it's the
		// garbage created by decoding every frame, which triggers GC pauses
		// that freeze the game thread too. Fewer concurrent decodes = fewer,
		// smaller GC bursts (see the per-frame pacing in encodeChunk).
		int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
		List<List<Frame>> chunks = splitAtRealFrames(frames, threads);

		List<File> chunkFiles = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(chunks.size(), r ->
		{
			Thread t = new Thread(r, "clip-recorder-mp4");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		try
		{
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < chunks.size(); i++)
			{
				File chunkFile = File.createTempFile("clip-chunk-" + i + "-", ".mp4");
				chunkFiles.add(chunkFile);
				final List<Frame> chunk = chunks.get(i);
				futures.add(pool.submit(() ->
				{
					encodeChunk(chunkFile, chunk, fps);
					return null;
				}));
			}
			for (Future<?> f : futures)
			{
				f.get();
			}
			concatenate(file, chunkFiles);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("MP4 encode interrupted", e);
		}
		catch (ExecutionException e)
		{
			throw new IOException("MP4 chunk encode failed", e.getCause());
		}
		finally
		{
			pool.shutdownNow();
			for (File f : chunkFiles)
			{
				if (!f.delete())
				{
					f.deleteOnExit();
				}
			}
		}
	}

	/**
	 * Split into ~equal chunks, but only at real (non-repeat) frames so every
	 * chunk can start with an actual image.
	 */
	private static List<List<Frame>> splitAtRealFrames(List<Frame> frames, int maxChunks)
	{
		int chunkCount = Math.max(1, Math.min(maxChunks, frames.size() / MIN_CHUNK_FRAMES));
		List<List<Frame>> chunks = new ArrayList<>();
		int idealSize = frames.size() / chunkCount;
		int start = 0;
		for (int c = 0; c < chunkCount && start < frames.size(); c++)
		{
			int end = (c == chunkCount - 1) ? frames.size() : Math.min(frames.size(), start + idealSize);
			// advance the boundary to the next real frame
			while (end < frames.size() && frames.get(end).isRepeat())
			{
				end++;
			}
			chunks.add(frames.subList(start, end));
			start = end;
		}
		return chunks;
	}

	private static void encodeChunk(File out, List<Frame> frames, int fps) throws IOException
	{
		AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(out, fps);
		BufferedImage previous = null;
		int since = 0;
		for (Frame f : frames)
		{
			BufferedImage img;
			if (f.isRepeat())
			{
				img = previous; // identical image; P-frame compresses to almost nothing
			}
			else
			{
				img = ImageIO.read(new ByteArrayInputStream(f.getJpeg()));
				previous = img;
			}
			if (img != null)
			{
				encoder.encodeImage(img);
			}
			// Pace the work: a brief pause every few frames lets the JVM
			// reclaim decoded-image garbage in small increments instead of
			// one big stall, keeping the game smooth while this runs.
			if (++since >= 8)
			{
				since = 0;
				try
				{
					Thread.sleep(6);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		encoder.finish();
	}

	/**
	 * Remux the chunk MP4s into one file: copy every video sample in order,
	 * re-stamping timestamps continuously. No re-encoding.
	 */
	private static void concatenate(File out, List<File> chunkFiles) throws IOException
	{
		try (SeekableByteChannel outCh = NIOUtils.writableChannel(out))
		{
			MP4Muxer muxer = MP4Muxer.createMP4Muxer(outCh, Brand.MP4);
			MP4MuxerTrack outTrack = null;
			long ptsOffset = 0;
			long frameNo = 0;

			for (File chunkFile : chunkFiles)
			{
				try (SeekableByteChannel inCh = NIOUtils.readableChannel(chunkFile))
				{
					// Raw demuxer: samples stay as stored MP4/AVC data, ready
					// to copy straight into the output track
					MP4Demuxer demuxer = MP4Demuxer.createRawMP4Demuxer(inCh);
					AbstractMP4DemuxerTrack videoTrack = (AbstractMP4DemuxerTrack) demuxer.getVideoTrack();
					if (outTrack == null)
					{
						outTrack = muxer.addTrack(new MP4MuxerTrack(muxer.getNextTrackId(), MP4TrackType.VIDEO));
						for (SampleEntry se : videoTrack.getSampleEntries())
						{
							outTrack.addSampleEntry(se);
						}
					}

					long chunkDuration = 0;
					Packet packet;
					while ((packet = videoTrack.nextFrame()) != null)
					{
						outTrack.addFrame(Packet.createPacket(
							packet.getData(),
							ptsOffset + packet.getPts(),
							packet.getTimescale(),
							packet.getDuration(),
							frameNo,
							packet.getFrameType(),
							null));
						chunkDuration += packet.getDuration();
						frameNo++;
					}
					ptsOffset += chunkDuration;
				}
			}
			muxer.finish();
		}
	}
}
