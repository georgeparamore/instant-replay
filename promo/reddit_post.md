# Suggested subreddit(s)
r/2007scape or r/runelite

# Title options
- I made a RuneLite plugin that auto-clips your PBs, pets, and big drops — like ShadowPlay for OSRS
- Instant Replay: a RuneLite plugin that saves a clip the moment something good happens (PB, pet, big drop, etc.)

# Post body

Ever get a pet or a chat-box PB and think "man I wish I had that on video"? I built a RuneLite
plugin called **Instant Replay** that fixes that automatically.

It keeps a rolling buffer of your last ~30 seconds of gameplay in memory, always running while
you're logged in. The second something notable happens, it saves that buffer to a clip on your
PC — no need to hit record beforehand, it's already got it.

**What triggers a clip:**
- New personal best (boss/raid kill times)
- Valuable drop (configurable GP threshold, default 1M+)
- Collection log slot
- Quest completion
- Combat achievement
- Level up (off by default)
- Manual hotkey (default **F8**) to grab the last 30 seconds on demand, for anything the
  automatic triggers don't cover

Clips get filed into `~/.runelite/instant-replay/<player>/<category>/`, named by timestamp and
trigger, so your pet clips, PB clips, and drop clips don't all pile up in one folder.

**On privacy, since I know this sub is (rightfully) picky about anything that touches the
client:**
- It's not a screen recorder — it physically *can't* see your desktop, browser, or Discord.
  Frames come straight from RuneLite's own internal renderer (`DrawManager`), the exact same
  API the built-in Screenshot plugin has used for years. It only ever sees the game's rendered
  image.
- It only captures while you're logged into a world. At the login screen or with the client
  closed, nothing is captured — not even of the game.
- The buffer lives in memory only and constantly overwrites itself. Nothing touches your disk
  until a trigger fires or you hit the hotkey.
- Nothing is ever uploaded anywhere. Clips stay on your PC until you move or delete them.
- Fully open source, built within Plugin Hub constraints (pure Java, zero third-party
  dependencies, no native code).

Video is MJPEG-in-AVI (video only, no audio — capturing game audio from inside the JVM isn't
practical under Plugin Hub rules), plays fine in VLC, and you can convert to MP4 with ffmpeg if
you want a smaller file to share.

Screenshots of the side panel and settings below. It's on the Plugin Hub as **Instant Replay** —
GitHub/source: [link] — would love feedback on what other triggers people would want (clue steps?
wilderness PKs?).

*(Disclosure per rule 9: this is a coding project, some parts of which — including this write-up
and the promo screenshot — were put together with AI assistance.)*

[screenshot: promo/showcase.png]

---
*(Notes for you before posting, not part of the post itself:
- Rule 7 (self-promo): fine to link straight to the plugin's own GitHub/Plugin Hub page — that's
  the specific creation, not a general channel link. Just don't link anywhere that solicits
  donations/sales.
- Rule 9 (AI content): plugins/coding projects using AI are explicitly allowed, but disclosure is
  encouraged — the disclosure line above covers that. Swap in your actual GitHub/Plugin Hub URL
  and attach promo/showcase.png as the post image.)*
