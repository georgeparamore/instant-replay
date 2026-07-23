# Instant Replay (RuneLite plugin)

A ShadowPlay-style "clip that" button for Old School RuneScape. Keeps a rolling
buffer of the last N seconds of gameplay in memory and saves a video clip when
something notable happens:

- **New personal best** (boss/raid kill times)
- **Valuable drop** (loot above a configurable GE value, default 1M gp)
- **Collection log slot** (requires the in-game setting *Collection log — New
  addition notification* to be ON)
- **Quest completion**
- **Combat achievement**
- **Level up** (off by default)
- **Manual hotkey** (default **F8**) — "clip the last 30 seconds now"

Clips are saved to `~/.runelite/clips/<player>/<timestamp>-<reason>.avi`.

Automatic triggers keep recording for a configurable post-roll (default 10 s)
so the drop/animation is included, and triggers that fire close together are
merged into one clip.

## How it works / privacy

- **This is not a screen recorder.** The plugin never captures your screen,
  desktop, or other windows — it *can't*. Frames come from RuneLite's own
  internal renderer (`DrawManager`), an API that only ever produces the
  game's rendered image. Your browser, Discord, and second monitor are
  technically invisible to it. This is the **same capture API RuneLite's
  built-in Screenshot plugin has used for years** — this plugin just keeps
  a rolling window of frames instead of saving a single one.
- **It only runs while you're logged into the game world.** At the login
  screen, during logout, or with the client closed, no frames are captured
  at all — not even of the game. The panel shows this live: *Buffering*
  (green) while logged in, *Idle* (gray) otherwise.
- **While logged in, it keeps the last N seconds in memory only**,
  constantly overwriting itself — like ShadowPlay's instant replay.
- **Nothing is written to disk** until a trigger fires (PB, valuable drop,
  collection log, quest, combat achievement) or you press the hotkey. Only
  then is the buffered footage saved as a file on your own PC.
- **Nothing is ever uploaded anywhere.** Clips stay in your clips folder
  until you move or delete them.

## Format & limitations

- Video is MJPEG-in-AVI — pure Java, no dependencies, plays in VLC and most
  players. Files are large-ish (~1–3 MB/s); clips are short so this is fine.
  An MP4 (JCodec) option may come later.
- **Video only.** Capturing game audio from inside the JVM isn't practical
  under Plugin Hub rules.
- Capture only runs while logged in.

## Memory

Frames are JPEG-compressed as they enter the buffer on a background thread.
At the defaults (30 s, 15 fps, 1280 px wide, quality 80) the buffer uses
roughly 50–120 MB. A hard 600 MB ceiling evicts the oldest frames regardless
of configuration.

## Building

Requires JDK 11+.

```
gradlew build
```

## Running in dev mode

Run `ClipRecorderPluginTest.main()` (under `src/test/java`) — it launches the
full RuneLite client with the plugin loaded.

## Plugin Hub notes

Designed within Plugin Hub constraints: Java only, no native code, no external
processes, no runtime downloads, no reflection, zero third-party dependencies
(the AVI muxer and JPEG encoding use only the JDK). Before submitting, ask in
RuneLite Discord #plugin-hub whether a clip recorder would be accepted.
