package com.cliprecorder;

import java.awt.Image;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.plugins.loottracker.LootTrackerPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.Text;
import com.google.inject.Provides;

@Slf4j
@PluginDescriptor(
	name = "Instant Replay",
	description = "Keeps a rolling buffer of gameplay and saves a video clip on PBs, valuable drops, achievements, or a hotkey",
	tags = {"clip", "record", "video", "replay", "pb", "drop", "collection log"}
)
@PluginDependency(LootTrackerPlugin.class)
public class ClipRecorderPlugin extends Plugin
{
	private static final Pattern NEW_PB_PATTERN = Pattern.compile("\\(new personal best\\)");
	// Kill time in the same message, e.g. "Fight duration: 1:26.40" or "Duration: 36:04" or "1:02:31"
	private static final Pattern DURATION_PATTERN = Pattern.compile("[Dd]uration:? (?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?)");
	// Extra seconds before the parsed kill start so the pull/entry is visible
	private static final int PB_PRE_KILL_SLACK_SECONDS = 5;
	private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile("^New item added to your collection log: (.+)$");
	private static final Pattern COMBAT_ACHIEVEMENT_PATTERN = Pattern.compile("^Congratulations, you've completed an? .+ combat task:");
	private static final Pattern LEVEL_UP_PATTERN = Pattern.compile("^Congratulations, you've just advanced your .+ level\\.");
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile("^Valuable drop: ");
	// All three pet messages: follower, backpack, and "would have been followed" (duplicate)
	private static final Pattern PET_PATTERN = Pattern.compile("^You (have a funny feeling like|feel something weird sneaking)");
	// Quest completed scroll interface (core screenshot plugin uses the same group)
	private static final int QUEST_COMPLETED_GROUP_ID = 153;

	// Absolute safety net regardless of config: buffer never exceeds this
	private static final long MAX_BUFFER_BYTES = 1024L * 1024 * 1024;

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	@Inject
	private Client client;

	@Inject
	private ClipRecorderConfig config;

	@Inject
	private DrawManager drawManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Notifier notifier;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	// One encoder per pool thread; JpegEncoder is not thread-safe
	private final ThreadLocal<JpegEncoder> encoders = ThreadLocal.withInitial(JpegEncoder::new);
	private ClipRecorderPanel panel;
	private NavigationButton navButton;
	private volatile long lastManualClipMs;
	private final AtomicBoolean clipWriteInProgress = new AtomicBoolean();
	private final AtomicBoolean framePending = new AtomicBoolean();
	private final AtomicInteger encodesInFlight = new AtomicInteger();

	private ScheduledExecutorService captureExecutor;
	private ExecutorService encodeExecutor;
	private ExecutorService writeExecutor;
	private ScheduledFuture<?> captureTask;
	private volatile FrameRingBuffer buffer;

	// Still-frame detection: signature of the last fully-encoded frame.
	// Only touched from the frame-listener callback + encode completion.
	private volatile int[] lastRealSignature;
	private volatile int lastRealWidth;
	private volatile int lastRealHeight;
	private final AtomicInteger consecutiveRepeats = new AtomicInteger();

	// Pending automatic clip (post-roll window); guarded by pendingLock
	private final Object pendingLock = new Object();
	private long pendingStartMs;
	private long pendingEndMs;
	private final Set<String> pendingReasons = new LinkedHashSet<>();
	private ScheduledFuture<?> pendingFinalize;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.clipHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			saveManualClip();
		}
	};

	@Provides
	ClipRecorderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClipRecorderConfig.class);
	}

	@Override
	protected void startUp()
	{
		captureExecutor = Executors.newSingleThreadScheduledExecutor(r -> namedThread(r, "clip-recorder-capture", ENCODE_PRIORITY));
		// Parallel JPEG compression so high capture rates can keep up. Priority
		// sits just below the game's render thread (NORM): low enough to yield,
		// high enough to keep pace with capture so clips aren't choppy.
		int encodeThreads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
		encodeExecutor = Executors.newFixedThreadPool(encodeThreads, r -> namedThread(r, "clip-recorder-encode", ENCODE_PRIORITY));
		writeExecutor = Executors.newSingleThreadExecutor(r -> namedThread(r, "clip-recorder-writer", ENCODE_PRIORITY));
		keyManager.registerKeyListener(hotkeyListener);
		restartCapture();

		panel = new ClipRecorderPanel(this);
		navButton = NavigationButton.builder()
			.tooltip("Instant Replay")
			.icon(createIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		log.debug("Instant Replay started");
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			panel.dispose();
			panel = null;
		}
		keyManager.unregisterKeyListener(hotkeyListener);
		if (captureTask != null)
		{
			captureTask.cancel(false);
			captureTask = null;
		}
		synchronized (pendingLock)
		{
			if (pendingFinalize != null)
			{
				pendingFinalize.cancel(false);
				pendingFinalize = null;
			}
			pendingReasons.clear();
		}
		captureExecutor.shutdownNow();
		encodeExecutor.shutdownNow();
		writeExecutor.shutdown(); // let an in-progress clip finish writing
		captureExecutor = null;
		encodeExecutor = null;
		writeExecutor = null;
		buffer = null;
		log.debug("Instant Replay stopped");
	}

	// Just below the game's render thread (NORM_PRIORITY = 5): yields to the
	// game but still gets enough CPU to keep the capture rate up.
	private static final int ENCODE_PRIORITY = Thread.NORM_PRIORITY - 2;

	private static Thread namedThread(Runnable r, String name, int priority)
	{
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.setPriority(priority);
		return t;
	}

	private void restartCapture()
	{
		if (captureTask != null)
		{
			captureTask.cancel(false);
		}
		int fps = config.fps().getFps();
		// Buffer must cover the longest clip we might cut: the normal clip
		// length, or minutes of footage when whole-kill PB clips are on
		int retentionSeconds = config.bufferSeconds();
		if (config.clipOnPb() && config.pbFullKill())
		{
			retentionSeconds = Math.max(retentionSeconds, config.pbMemoryMinutes() * 60);
		}
		int capacity = (retentionSeconds + config.postRollSeconds() + 2) * fps;

		// Memory clamp: never take more than 40% of the heap the client
		// actually has. The stock RuneLite launcher runs at -Xmx768m, where
		// the fixed cap alone would OOM-crash the whole client.
		long heapBudget = (long) (Runtime.getRuntime().maxMemory() * 0.40);
		long byteCap = Math.min(MAX_BUFFER_BYTES, Math.max(64L * 1024 * 1024, heapBudget));
		buffer = new FrameRingBuffer(capacity, byteCap);

		long neededBytes = (long) retentionSeconds * fps * estimateFrameBytes();
		if (neededBytes > byteCap)
		{
			long coveredSeconds = byteCap / Math.max(1, estimateFrameBytes() * fps);
			log.info("Buffer clamped to {} MB of {} MB requested - about {}s of footage at current settings",
				byteCap / 1024 / 1024, neededBytes / 1024 / 1024, coveredSeconds);
			queueChat("Instant Replay: your settings need more memory than RuneLite has available, so the buffer will "
				+ "cover about " + coveredSeconds + " seconds instead of " + retentionSeconds + ". Lower the resolution "
				+ "or FPS, or give RuneLite more memory in the launcher settings, to extend it.");
		}

		captureTask = captureExecutor.scheduleAtFixedRate(this::captureTick, 0, 1000L / fps, TimeUnit.MILLISECONDS);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ClipRecorderConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		if ("fps".equals(key) || "bufferSeconds".equals(key) || "postRollSeconds".equals(key)
			|| "clipOnPb".equals(key) || "pbFullKill".equals(key) || "pbMemoryMinutes".equals(key))
		{
			captureExecutor.execute(this::restartCapture);
		}
	}

	// ---- Frame capture ----

	private void captureTick()
	{
		if (!framePending.compareAndSet(false, true))
		{
			return; // previous frame still being captured/encoded
		}

		GameState state = client.getGameState();
		if (state != GameState.LOGGED_IN && state != GameState.LOADING)
		{
			framePending.set(false);
			return;
		}

		drawManager.requestNextFrameListener(image ->
		{
			// Do nothing heavy on the client/render thread: hand the frame
			// straight to the background pool. All per-pixel work (still-frame
			// detection AND JPEG encoding) happens off this thread so capture
			// never drops the game's own frame rate.
			framePending.set(false);

			ExecutorService exec = encodeExecutor;
			if (exec == null || encodesInFlight.get() >= 4)
			{
				return; // encoders are saturated - skip this frame
			}
			long timestamp = System.currentTimeMillis();
			encodesInFlight.incrementAndGet();
			try
			{
				exec.execute(() -> processFrame(image, timestamp));
			}
			catch (Exception e)
			{
				encodesInFlight.decrementAndGet();
			}
		});
	}

	/**
	 * Runs entirely on the encode pool: detect a still frame (cheap repeat
	 * marker) or compress a real JPEG. Never touches the client thread.
	 */
	private void processFrame(Image image, long timestamp)
	{
		try
		{
			int[] signature = sampleSignature(image);
			if (signature != null && tryAddRepeatFrame(signature, timestamp))
			{
				return; // screen barely changed - stored a zero-byte repeat marker
			}
			int[] dims = new int[2];
			byte[] jpeg = encoders.get().encode(image, config.resolution().getMaxHeight(), config.jpegQuality() / 100f, dims);
			FrameRingBuffer buf = buffer;
			if (jpeg != null && buf != null)
			{
				buf.add(new Frame(jpeg, timestamp, dims[0], dims[1]));
				if (signature != null)
				{
					lastRealWidth = dims[0];
					lastRealHeight = dims[1];
					lastRealSignature = signature;
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Frame processing failed", e);
		}
		finally
		{
			encodesInFlight.decrementAndGet();
		}
	}

	/**
	 * If this frame is nearly identical to the last encoded one, store a
	 * zero-byte repeat marker instead of re-encoding ~100 KB of JPEG.
	 * Compared against the last REAL frame (not the previous marker) so slow
	 * motion can never accumulate into a visible freeze.
	 */
	private boolean tryAddRepeatFrame(int[] signature, long timestamp)
	{
		int[] last = lastRealSignature;
		FrameRingBuffer buf = buffer;
		// Strict: only collapse frames that are essentially identical (a truly
		// static screen like an open bank), never subtly-animating gameplay.
		if (last == null || buf == null || lastRealWidth <= 0
			|| signature.length != last.length
			|| changedFraction(signature, last) >= 0.002)
		{
			consecutiveRepeats.set(0);
			return false;
		}
		// Hard cap on how long a frame can be held, so the recording can never
		// look "paused" even if the scene reads as static - a real frame is
		// forced at least ~5 times a second.
		int maxRun = Math.max(2, config.fps().getFps() / 5);
		if (consecutiveRepeats.incrementAndGet() > maxRun)
		{
			consecutiveRepeats.set(0);
			return false;
		}
		buf.add(new Frame(null, timestamp, lastRealWidth, lastRealHeight));
		return true;
	}

	/**
	 * Coarse pixel-sample fingerprint of a frame (~3600 grid samples).
	 */
	private static int[] sampleSignature(Image image)
	{
		if (!(image instanceof java.awt.image.BufferedImage))
		{
			return null;
		}
		java.awt.image.BufferedImage bi = (java.awt.image.BufferedImage) image;
		int w = bi.getWidth();
		int h = bi.getHeight();
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		int sx = Math.max(1, w / 80);
		int sy = Math.max(1, h / 45);
		int cols = (w + sx - 1) / sx;
		int rows = (h + sy - 1) / sy;
		int[] sig = new int[cols * rows];
		int i = 0;
		for (int y = 0; y < h; y += sy)
		{
			for (int x = 0; x < w; x += sx)
			{
				sig[i++] = bi.getRGB(x, y);
			}
		}
		return sig;
	}

	private static double changedFraction(int[] a, int[] b)
	{
		int changed = 0;
		for (int i = 0; i < a.length; i++)
		{
			int p = a[i];
			int q = b[i];
			int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
			int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
			int db = Math.abs((p & 0xFF) - (q & 0xFF));
			if (dr + dg + db > 40)
			{
				changed++;
			}
		}
		return (double) changed / a.length;
	}

	// ---- Triggers ----

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// One-time transparency note so players know exactly what this does
		if (event.getGameState() == GameState.LOGGED_IN
			&& !Boolean.TRUE.toString().equals(configManager.getConfiguration(ClipRecorderConfig.GROUP, "howItWorksShown")))
		{
			configManager.setConfiguration(ClipRecorderConfig.GROUP, "howItWorksShown", Boolean.TRUE.toString());
			queueChat("Instant Replay: this plugin sees only the game's own rendered frames - it cannot see your"
				+ " desktop or other windows, and it captures nothing at all unless you are logged in. While logged in,"
				+ " the last " + config.bufferSeconds() + " seconds are kept in memory only; clips are saved to your PC"
				+ " only when a trigger fires or you press the hotkey. Nothing is ever uploaded. (Shown once.)");
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());

		if (config.clipOnPb() && NEW_PB_PATTERN.matcher(message).find())
		{
			triggerPb(message);
		}
		else if (config.clipOnCollectionLog() && COLLECTION_LOG_PATTERN.matcher(message).find())
		{
			trigger("collection-log");
		}
		else if (config.clipOnCombatAchievement() && COMBAT_ACHIEVEMENT_PATTERN.matcher(message).find())
		{
			trigger("combat-achievement");
		}
		else if (config.clipOnLevelUp() && LEVEL_UP_PATTERN.matcher(message).find())
		{
			trigger("level-up");
		}
		else if (config.clipOnPet() && PET_PATTERN.matcher(message).find())
		{
			trigger("pet");
		}
		else if (config.clipOnValuableDrop() && VALUABLE_DROP_PATTERN.matcher(message).find())
		{
			// Fallback for the in-game valuable drop notification; LootReceived
			// normally fires too - the debounce window merges them into one clip.
			trigger("drop");
		}
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (!config.clipOnValuableDrop())
		{
			return;
		}
		long value = 0;
		for (ItemStack stack : event.getItems())
		{
			value += (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
		}
		if (value >= config.valuableDropThreshold())
		{
			trigger("drop");
		}
	}

	@Subscribe
	public void onActorDeath(net.runelite.api.events.ActorDeath event)
	{
		if (config.clipOnDeath() && event.getActor() == client.getLocalPlayer())
		{
			trigger("death");
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (config.clipOnQuest() && event.getGroupId() == QUEST_COMPLETED_GROUP_ID)
		{
			trigger("quest");
		}
	}

	/**
	 * Automatic trigger: start (or extend) a post-roll window, then save one
	 * merged clip covering everything from bufferSeconds before the first
	 * trigger to postRollSeconds after the last one.
	 */
	private void trigger(String reason)
	{
		trigger(reason, System.currentTimeMillis() - config.bufferSeconds() * 1000L);
	}

	/**
	 * PB trigger: parse the kill time out of the PB message so the clip covers
	 * the whole kill, not just the last bufferSeconds of it.
	 */
	private void triggerPb(String message)
	{
		long now = System.currentTimeMillis();
		long startMs = now - config.bufferSeconds() * 1000L;
		if (config.pbFullKill())
		{
			java.util.regex.Matcher m = DURATION_PATTERN.matcher(message);
			if (m.find())
			{
				long hours = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
				long killSeconds = hours * 3600
					+ Long.parseLong(m.group(2)) * 60
					+ (long) Math.ceil(Double.parseDouble(m.group(3)));
				long killStart = now - (killSeconds + PB_PRE_KILL_SLACK_SECONDS) * 1000L;
				startMs = Math.min(startMs, killStart);
				log.debug("PB kill time parsed: {}s - clip starts at kill start", killSeconds);
			}
		}
		trigger("pb", startMs);
	}

	private void trigger(String reason, long startMs)
	{
		long now = System.currentTimeMillis();
		synchronized (pendingLock)
		{
			if (captureExecutor == null)
			{
				return;
			}
			long newEnd = now + config.postRollSeconds() * 1000L;
			if (pendingFinalize != null && !pendingFinalize.isDone())
			{
				// Merge into the in-flight clip
				pendingFinalize.cancel(false);
				pendingStartMs = Math.min(pendingStartMs, startMs);
				pendingEndMs = Math.max(pendingEndMs, newEnd);
				pendingReasons.add(reason);
			}
			else
			{
				pendingStartMs = startMs;
				pendingEndMs = newEnd;
				pendingReasons.clear();
				pendingReasons.add(reason);
			}
			long delay = Math.max(0, pendingEndMs - now);
			pendingFinalize = captureExecutor.schedule(this::finalizePendingClip, delay, TimeUnit.MILLISECONDS);
			log.debug("Clip trigger '{}' - saving in {} ms", reason, delay);
		}
	}

	private void finalizePendingClip()
	{
		long startMs;
		long endMs;
		String reason;
		synchronized (pendingLock)
		{
			startMs = pendingStartMs;
			endMs = pendingEndMs;
			reason = String.join("-", pendingReasons);
			pendingReasons.clear();
			pendingFinalize = null;
		}
		saveClip(startMs, endMs, reason);
	}

	void saveManualClip()
	{
		long now = System.currentTimeMillis();
		// Cooldown so a double-tap doesn't write two near-identical clips
		if (now - lastManualClipMs < 2000)
		{
			return;
		}
		// Don't pile up manual clips while one is still saving/encoding
		if (clipWriteInProgress.get())
		{
			if (config.chatMessageOnClip())
			{
				queueChat("A clip is already saving - please wait for it to finish before saving another.");
			}
			return;
		}
		lastManualClipMs = now;
		saveClip(now - config.bufferSeconds() * 1000L, now, "manual");
	}

	// ---- Clip writing ----

	private void saveClip(long fromMs, long toMs, String reason)
	{
		FrameRingBuffer buf = buffer;
		ExecutorService exec = writeExecutor;
		if (buf == null || exec == null)
		{
			return;
		}
		List<Frame> frames = buf.snapshot(fromMs, toMs);
		String playerName = getPlayerName();
		exec.execute(() -> writeClip(frames, playerName, reason));
	}

	/**
	 * Map a trigger reason (possibly a merged "pb-drop") to the subfolder its
	 * clip is filed under. Checked in priority order so a merged clip lands in
	 * its most notable category.
	 */
	private static String categoryFolder(String reason)
	{
		if (reason.contains("pet"))
		{
			return "Pets";
		}
		if (reason.contains("pb"))
		{
			return "Personal Bests";
		}
		if (reason.contains("collection-log"))
		{
			return "Collection Log";
		}
		if (reason.contains("drop"))
		{
			return "Valuable Drops";
		}
		if (reason.contains("quest") || reason.contains("combat-achievement") || reason.contains("level-up"))
		{
			return "Achievements";
		}
		if (reason.contains("death"))
		{
			return "Deaths";
		}
		return "Manual";
	}

	private String getPlayerName()
	{
		Player local = client.getLocalPlayer();
		String name = local != null ? local.getName() : null;
		return name != null && !name.isEmpty() ? name.replaceAll("[^a-zA-Z0-9 _-]", "_") : "unknown";
	}

	/**
	 * Whether the rolling buffer is actively capturing gameplay.
	 */
	boolean isBuffering()
	{
		GameState state = client.getGameState();
		return captureTask != null && (state == GameState.LOGGED_IN || state == GameState.LOADING);
	}

	private void writeClip(List<Frame> frames, String playerName, String reason)
	{
		if (frames.isEmpty())
		{
			log.warn("No frames buffered - clip '{}' not saved", reason);
			return;
		}
		clipWriteInProgress.set(true);
		ClipRecorderPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.setSaving(true));
		}
		try
		{
			writeClipInner(frames, playerName, reason);
		}
		finally
		{
			clipWriteInProgress.set(false);
			if (p != null)
			{
				SwingUtilities.invokeLater(() -> p.setSaving(false));
			}
		}
	}

	private void writeClipInner(List<Frame> frames, String playerName, String reason)
	{

		// Parallel encoding can finish frames slightly out of order
		frames.sort(Comparator.comparingLong(Frame::getTimestampMs));

		// All frames in the file must share dimensions; the client is rarely
		// resized mid-clip, so keep the run of frames matching the newest size.
		Frame last = frames.get(frames.size() - 1);
		List<Frame> matching = new ArrayList<>(frames.size());
		for (Frame f : frames)
		{
			if (f.getWidth() == last.getWidth() && f.getHeight() == last.getHeight())
			{
				matching.add(f);
			}
		}

		// Actual fps of the captured range can be lower than configured if the
		// client lagged; derive it from the frames so playback speed is right.
		long spanMs = matching.get(matching.size() - 1).getTimestampMs() - matching.get(0).getTimestampMs();
		int fps = config.fps().getFps();
		if (spanMs > 1000 && matching.size() > 1)
		{
			fps = (int) Math.max(1, Math.round((matching.size() - 1) * 1000.0 / spanMs));
		}

		// Organize into per-player, then per-category folders
		File dir = new File(new File(getClipRoot(), playerName), categoryFolder(reason));
		if (!dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create clip directory {}", dir);
			return;
		}
		// "Smaller files": drop to ~30 fps at save time - frames are already
		// compressed, so this halves the file with zero extra work.
		if (config.smallerFiles() && fps > 35)
		{
			int factor = (int) Math.ceil(fps / 30.0);
			List<Frame> decimated = new ArrayList<>((matching.size() + factor - 1) / factor);
			for (int i = 0; i < matching.size(); i += factor)
			{
				decimated.add(matching.get(i));
			}
			matching = decimated;
			fps = Math.max(1, fps / factor);
		}

		// A clip can't open on a "repeat previous frame" marker - drop any
		// leading repeats until the first real frame
		while (!matching.isEmpty() && matching.get(0).isRepeat())
		{
			matching.remove(0);
		}
		if (matching.isEmpty())
		{
			log.warn("Only repeat markers in range - clip '{}' not saved", reason);
			return;
		}

		File file = new File(dir, LocalDateTime.now().format(FILE_TIMESTAMP) + "-" + reason + ".avi");

		try
		{
			long t0 = System.nanoTime();
			MjpegAviWriter.write(file, matching, fps, last.getWidth(), last.getHeight());
			long ms = (System.nanoTime() - t0) / 1_000_000;
			log.info("Saved clip {} ({} frames, {} fps, {} KB, wrote in {} ms)",
				file.getName(), matching.size(), fps, file.length() / 1024, ms);
			if (config.chatMessageOnClip())
			{
				queueChat("Clip saved: " + file.getName() + " (" + formatSize(file.length()) + ")");
			}
			if (config.notifyOnClip())
			{
				notifier.notify("Clip saved: " + file.getName());
			}
			ClipRecorderPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(p::refresh);
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to write clip {}", file, e);
		}
	}

	/**
	 * Rough JPEG bytes per frame at current settings (~0.13 bytes/pixel for
	 * quality-70 game footage, measured on real clips).
	 */
	private long estimateFrameBytes()
	{
		int h = config.resolution().getMaxHeight();
		if (h == 0)
		{
			h = 1080;
		}
		long pixels = (long) Math.round(h * 16.0 / 9.0) * h;
		return (long) (pixels * 0.13);
	}

	private void queueChat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append(message)
				.build())
			.build());
	}

	private static String formatSize(long bytes)
	{
		if (bytes >= 1024 * 1024)
		{
			return (bytes / (1024 * 1024)) + " MB";
		}
		return (bytes / 1024) + " KB";
	}

	// ---- Panel support ----

	/**
	 * Root folder clips are saved under (custom folder if configured).
	 */
	private File getClipRoot()
	{
		String custom = config.outputFolder();
		if (custom != null && !custom.trim().isEmpty())
		{
			return new File(custom.trim());
		}
		return new File(RuneLite.RUNELITE_DIR, "clips");
	}

	/**
	 * Best folder to open for the user: their player folder if it exists,
	 * otherwise the clip root.
	 */
	void setOutputFolder(File dir)
	{
		configManager.setConfiguration(ClipRecorderConfig.GROUP, "outputFolder", dir.getAbsolutePath());
	}

	File getClipDirectory()
	{
		File playerDir = new File(getClipRoot(), getPlayerName());
		return playerDir.isDirectory() ? playerDir : getClipRoot();
	}

	List<File> listRecentClips(int limit)
	{
		List<File> clips = new ArrayList<>();
		// Clips nest as root/player/category/file, so walk a few levels deep
		collectClips(getClipRoot(), clips, 4);
		clips.sort(Comparator.comparingLong(File::lastModified).reversed());
		return clips.size() > limit ? clips.subList(0, limit) : clips;
	}

	private void collectClips(File dir, List<File> out, int depthLeft)
	{
		File[] entries = dir.listFiles();
		if (entries == null)
		{
			return;
		}
		for (File e : entries)
		{
			if (e.isFile() && isClipFile(e.getName()))
			{
				out.add(e);
			}
			else if (e.isDirectory() && depthLeft > 0)
			{
				collectClips(e, out, depthLeft - 1);
			}
		}
	}

	private static boolean isClipFile(String name)
	{
		return name.endsWith(".avi") || name.endsWith(".mp4");
	}

	private static java.awt.image.BufferedImage createIcon()
	{
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new java.awt.Color(220, 220, 220));
		g.drawOval(1, 1, 13, 13);
		g.setColor(new java.awt.Color(200, 60, 60));
		g.fillOval(4, 4, 8, 8);
		g.dispose();
		return img;
	}
}
