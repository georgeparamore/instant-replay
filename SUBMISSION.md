# Plugin Hub submission guide

Everything needed to publish **Instant Replay** to the RuneLite Plugin Hub.

## 1. Create the GitHub repo and push

Create a new **public** repo named `instant-replay` under your account
(https://github.com/new — do NOT initialize it with a README/license, the
local repo already has them). Then, from this folder:

```bash
git remote add origin https://github.com/georgeparamore/instant-replay.git
git push -u origin master
```

After pushing, note the exact commit hash — the Hub manifest points at it:

```bash
git rev-parse HEAD
```

## 2. Fork the plugin-hub repo

Fork https://github.com/runelite/plugin-hub and clone your fork.

## 3. Add the plugin manifest

Create a file named `plugins/instant-replay` (no extension) containing:

```
repository=https://github.com/georgeparamore/instant-replay.git
commit=<the commit hash from step 1>
```

## 4. Verify the third-party dependency (JCodec)

Instant Replay's only third-party dependency is JCodec (pure-Java MP4 encoder).
The Hub verifies dependency hashes to prevent supply-chain tampering.

In your plugin-hub fork, add these to the `thirdParty` configuration in
`package/verification-template/build.gradle`:

```gradle
thirdParty 'org.jcodec:jcodec:0.2.5'
thirdParty 'org.jcodec:jcodec-javase:0.2.5'
```

Then generate the verification metadata:

```bash
./gradlew --write-verification-metadata sha256
```

This fetches the jars and records their hashes. For reference, the expected
SHA-256 hashes (verify these match what Gradle records) are:

```
org.jcodec:jcodec:0.2.5        890329dad124e8b739c1d6602a59a53c8a474daddff265c2561e21c498496c81
org.jcodec:jcodec-javase:0.2.5 8a72ff7560bce1c89c1d815816d1304383bb4048afeeb91f200b7a1e213ab6ae
```

JCodec has no further transitive runtime dependencies, so these two are all
that need verifying.

## 5. Open the pull request

Commit the manifest and the updated verification metadata to your plugin-hub
fork, push, and open a PR against `runelite/plugin-hub`. In the PR description,
briefly describe the plugin and note that it captures via `DrawManager` (the
same API the core screenshot plugin uses), stores frames in memory only, and
never uploads anything.

## Compliance checklist (all satisfied)

- Java only, no native code / JNI
- No executing external programs (folder opening uses RuneLite's `LinkBrowser`)
- No reflection
- No runtime code downloading
- One hash-verified third-party dependency (JCodec)
- `icon.png` present, 48x48 (within the 48x72 limit)
- BSD 2-Clause license
