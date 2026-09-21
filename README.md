# Mantra NDI Screen Share (Android)

### ▸ [Download the latest build](https://github.com/markoboskoauroville/mantra-ndi-screen-share-android/releases/latest)

Open that link on the phone, take the APK, install it. Every push to `main`
builds a new one about five minutes later and it lands there.

---

The phone's screen on the network as an NDI source. vMix, OBS, Studio Monitor
and everything else that speaks NDI sees it in its list and takes it, and it
turns the right way up when the phone does.

Part of the **Mantra NDI** family, alongside
[`mantra-ndi`](https://github.com/markoboskoauroville/mantra-ndi) (the broadcast
camera) and `mantra-ndi-sdk` (the licensed SDK, private, shared by both).

## The two ways it can go out

The mode is a radio on the one screen, and exactly one is in force.

| | **Compressed NDI** | **Full NDI** |
|---|---|---|
| What it is | NDI HX — H.264 or H.265 | High Bandwidth — SpeedHQ |
| Who compresses | the phone's hardware encoder | the NDI SDK, on the phone's CPU |
| 1080p30 costs about | 4 to 12 Mbit/s | 62 Mbit/s |
| 1080p60 costs about | 8 to 25 Mbit/s | 125 Mbit/s |
| Pixels copied | none — the display draws into the encoder | none — the reader's buffer goes straight out |
| Battery | light | heavy |
| Receivers | everything current | everything ever made |

**Compressed is the default and is right nearly always.** Full NDI is there for
the receiver that will not take HX, and for the moment when the compression
artefacts on fine text matter more than the Wi-Fi does. The screen prints the
estimate before you start, so the cost is known rather than discovered.

## Which way up

The app never forces 16:9. A portrait phone sends a portrait picture, and the
long edge is capped at whatever you chose while the shape is kept exactly —
because the letterboxing that comes of forcing landscape throws away half the
pixels of a vertical screen, which is the opposite of what a phone screen share
is for.

**Turning the phone rebuilds the stream at the new shape and keeps the source.**
The NDI sender is deliberately not recreated: a receiver watches a *source
name*, and dropping the source out of every list on the network so the operator
has to find it and click it again is not an acceptable cost for a rotation. Only
the format changes, which NDI carries natively.

The rebuild fires on a change of *geometry*, not on a change of *rotation*.
Android reports a rotation every ninety degrees, including the two that leave
the numbers exactly as they were, and rebuilding on those costs a second of
black picture for nothing.

## What it looks like on the phone

One screen, and every control on it from the first frame. In full NDI the codec
and quality chips go dim rather than leaving, because a row that leaves takes
the rows under it up the screen. The key says what the next press will **do** —
START, then STOP.

Under the controls, the state panel, which is the whole point of having built
the trace first:

    sending   "Pixel 7 Screen"
    1080 × 2400 portrait @ 30
    8.4 Mbit/s   1129 frames
    receivers: 1
    this phone is 192.168.1.47

**`receivers:` is the only honest answer to "is anybody seeing this?"** A source
advertises itself whether or not anyone is watching, so a green light that means
"sending" tells nobody anything. This number means a machine has opened the
stream. The address is there because "the source is not in my list" is nearly
always two machines on two networks, and the phone is the one nobody can check.

## Building

**The APK is built by GitHub Actions and never on a desk**
([android-app.md §1a](https://github.com/markoboskoauroville/MANTRA_MANIFEST)).
There is no local Android SDK and there is not meant to be one.

    1  edit
    2  python3 scripts/verify.py          the gates a compiler will not run
    3  bump appVersion in gradle.properties
    4  commit and push
    5  gh run watch                       about five minutes
    6  the APK is at releases/latest

`appVersion` must be **one higher than the last released version** or the build
is refused. Read the releases first; do not assume.

### What the build needs, and where it comes from

| Secret | What it is |
|---|---|
| `SIGNING_KEYSTORE_B64` | the app's permanent PKCS12 key, base64 |
| `SIGNING_PASSWORD` | its password |
| `NDI_SDK_TOKEN` | a token that can read `mantra-ndi-sdk` |

The key lives in `~/.mantra-ndi-screen-share-signing/` on the Mac and in those
two secrets, and **nowhere else, ever**. Its fingerprint is pinned in
`SIGNING_FINGERPRINT.txt` and the workflow compares against it rather than
merely printing it — a printed fingerprint nobody compares is how a swapped key
gets through, and a swapped key means no build can ever replace an installed
one.

    4d4b7d8f198ea1cd25dfaea4bfbddbbb5bedc8e7c7fa03a635c594627fa18b77

### The SDK

The **NDI Advanced SDK** is licensed and confidential, so it lives in the
private repository `markoboskoauroville/mantra-ndi-sdk` and the workflow fetches
it at build time. **It must never be committed here; this repository is public
and the licence forbids redistribution.** A gate keeps it out of the tree and a
second gate refuses to release an APK that does not have the runtime and the
bridge inside it. Keep both.

Without the SDK the project still builds: the interface, the capture, the
encoder and the trace all work, `NdiSender.available` answers false, and the
screen says plainly that nothing can be sent.

## The gates

`scripts/verify.py` runs before anything is compiled. Four of its gates exist
because what they check is invisible to every other kind of test, and each one
fails at runtime, on the phone, with no error anybody can read:

- **G6 — every `external fun` has a JNI symbol, and the other way round.** A
  name that does not match compiles cleanly on both sides and throws
  `UnsatisfiedLinkError` the first time it is called.
- **G7 — the multicast lock is taken.** NDI discovery is mDNS over UDP
  multicast and Android drops multicast packets on Wi-Fi unless something holds
  a `MulticastLock`. Without it the phone encodes, sends and reports success,
  and no receiver ever sees the source. There is no error on either machine.
- **G8 — a still screen still sends.** A virtual display draws only when
  something changes, so a phone sitting on a menu hands the encoder nothing.
  The compressed path asks the encoder to repeat the previous frame; the full
  path resends the held frame on its own tick.
- **G9 — the row stride is read, never computed.** An `ImageReader` 1080 pixels
  wide commonly hands back rows padded to 1088. Computing `width × 4` skews the
  picture into a diagonal, which reads as a broken codec rather than as
  arithmetic.

Then Test 1, the mechanism alone, 78 cases with no Android in them.

## Files

| File | What it holds |
|---|---|
| `Mechanism.kt` | the arithmetic: which way up, what size, what it costs, when to rebuild. No Android, so Test 1 runs on a desk |
| `NdiSender.kt` | the Kotlin face of the bridge, compressed and full |
| `ndi_bridge.cpp` | the JNI bridge to the Advanced SDK. Lifted from `mantra-ndi`, extended with the full path |
| `Pipelines.kt` | the two ways a captured screen becomes NDI |
| `ScreenShareService.kt` | the projection, the locks, the rotation rebuild, the readouts |
| `MainActivity.kt` | the one screen |
| `Trace.kt`, `TraceFormat.kt`, `CrashLog.kt`, `Downloads.kt` | the logging, lifted whole from `mantra-ndi` phase 0 |
| `scripts/verify.py` | the gates |

## Getting the state out of the phone

Three routes, because seeing what the app is doing is the whole difficulty of
this kind of work.

**On the screen.** The trace panel, live, newest at the bottom.

**In a file manager.** `Android/data/com.mantraproductions.ndiscreen/files/`,
one `trace-<date>-<time>.txt` per run, written as things happen rather than
buffered. The last twelve runs are kept.

**In Downloads.** Crash reports go to `Download/Mantra NDI Screen Share` through
MediaStore, which is the only route an app has to a public folder from Android
10 onward. The **Export** key puts the current trace there too, so the route is
proven without having to crash.

---

Powered by NDI®. NDI® is a registered trademark of Vizrt NDI AB.
