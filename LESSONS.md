# Lessons — Mantra NDI Screen Share

What this app learned, or lifted from something that had already paid for it.
Written so the next chat does not rediscover any of it.

---

## 1. The four failures of an Android NDI sender that make no noise

Every one of these produces a phone that says it is sending, a build that is
green, and nothing on the network. There is no error on either machine. They
are why `scripts/verify.py` has gates that read the source rather than trusting
the compiler.

**The multicast lock.** NDI discovery is mDNS over UDP multicast. Android drops
multicast packets on Wi-Fi unless something holds a `WifiManager.MulticastLock`.
Without it the phone encodes, sends and reports success, and no receiver ever
finds the source — which reads as "NDI does not work on Android" rather than as
four missing lines. Gate G7. Carried over from `mantra-ndi`, where it was
already paid for.

**A JNI name that does not match.** `external fun nativeSendRaw` in Kotlin and
`Java_..._nativeSendRawX` in C++ both compile perfectly. The failure is an
`UnsatisfiedLinkError` the first time the method is called, which for half of
these is only when somebody actually starts a share on a phone. Gate G6 compares
the two sets in both directions.

**A still screen.** A virtual display draws only when something changes. A phone
sitting on a menu hands the encoder nothing, the encoder emits nothing, and the
receiver's picture freezes and then times out. The compressed path sets
`MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER`, which exists for exactly this;
the full path holds the last `Image` open and resends it on its own tick. Gate
G8 checks both, because either alone leaves one mode broken.

**A stride computed rather than read.** An `ImageReader` 1080 pixels wide
commonly hands back rows padded to 1088 or 1152. NDI takes the stride as a
field, so the padding is *described* rather than removed — but `width × 4` skews
the picture into a diagonal, which reads as a broken codec rather than as
arithmetic. Gate G9.

## 2. A gate that has never been seen to fail is a rumour

**G9 was one, for about ten minutes.** It asked whether `plane.rowStride`
appeared anywhere in `Pipelines.kt`. It did — twice, once in the send and once
two lines below in the bytes counter — so when the send was deliberately changed
to `width × 4` the gate still passed.

It was caught only because each gate was made to fail on purpose before the
first push: rename a JNI symbol, delete the multicast permission, compute the
stride. Two of the three failed as intended and the third did not, and the third
was the one worth having.

The fix is the general form of the rule: **check the argument at the call site,
not the presence of a word in a file.** `checking-the-checks.md` §"a check that
matches its own comment" is the same failure from a different direction.

## 3. Compressed and full are not two qualities of one thing

They are two different machines, and the difference is *who compresses*.

| | Compressed (NDI HX) | Full (High Bandwidth) |
|---|---|---|
| Compresses | the phone's hardware encoder | the NDI SDK, on the CPU |
| Codec | H.264 / H.265 | SpeedHQ |
| Send call | `send_video_scatter` with a compressed packet | `send_video_v2` with a whole frame |
| Capture target | the encoder's input surface | an `ImageReader` |
| Still screen handled by | `KEY_REPEAT_PREVIOUS_FRAME_AFTER` | a held frame and a tick |
| 1080p60 | 8–25 Mbit/s | about 125 Mbit/s |

So they cannot share a pipeline, and trying to would repeat the mistake
`mantra-ndi`'s `REBUILD.md` names first: *"One camera path only. The old app had
two and nearly every bug came from that."* The answer here is not one path — the
two are genuinely different machines — but **one interface**, `Pipeline`, so the
service never branches on the mode after the share has started.

**NDI's own figure for 1080p60 High Bandwidth is about 125 Mbit/s**, which is a
shade over one bit per pixel per frame. That is where `fullNdiBitsPerSecond`
comes from, and a test asserts it stays near NDI's published number — a warning
that drifts is worse than no warning, because it would be believed.

## 4. A turn of the phone is a format change, not a new source

A receiver watches a *source name*. Destroying and recreating the NDI sender
when the phone is turned drops the source out of every list on the network and
the operator has to find it and click it again — during a show.

So the sender outlives the rebuild. Only the virtual display and the pipeline
are torn down and built again, `setVideoFormat` is called with the new numbers,
and the parameter sets are cleared because SPS and PPS carry the picture
dimensions and the old pair describes a frame that no longer exists.

**And the rebuild fires on a change of geometry, not on a change of rotation.**
Android reports a rotation every ninety degrees, including the two that leave
the numbers exactly as they were. Rebuilding on those costs a second of black
picture for nothing. `Mechanism.needsRebuild` compares the numbers; a test
covers the upside-down turn specifically.

## 5. Never force 16:9 on a phone

The shortcut is to configure everything at 1920×1080 and let the display
letterbox. On a portrait phone that is two black bars and half the pixels
thrown away, which is the opposite of what a screen share is for.

So the cap is on the **long edge** and the shape is kept exactly. Both numbers
come back even, because every H.264 and H.265 profile on this phone is 4:2:0 and
a 4:2:0 encoder handed an odd dimension either refuses the format or shifts the
chroma by half a pixel. And the cap never upscales: a cap above the screen is
the screen.

## 6. On Android 14 the foreground service comes first

`MediaProjectionManager.getMediaProjection()` throws `SecurityException` unless a
foreground service of type `mediaProjection` is **already running**. The message
does not say which of the two calls was out of place. So `startForegroundNotice()`
is the first thing the service does, before it looks at the result data at all.

The type has to be declared in three places and all three are load-bearing: the
`FOREGROUND_SERVICE_MEDIA_PROJECTION` permission, the service element's
`android:foregroundServiceType`, and the `startForeground` call on 34+. A gate
over the built APK's badging checks the first, because a manifest merge has lost
it before.

## 7. `receivers:` is the only honest readout

An NDI source advertises itself whether or not anybody is watching. A green
light that means "sending" therefore tells nobody anything, and it is the
readout somebody stares at when the picture is not arriving.

`NDIlib_send_get_no_connections` is the number that means a machine has opened
the stream. The phone's own IP address sits under it, because "the source is not
in my list" is nearly always two machines on two networks and the phone is the
one nobody can check.

## 8. Red, once, on purpose

`design-language.md` §3 says red is a fault and nothing else. The tally light
here is red when the source is on programme, and that is the one exception in
the app: it is red in every gallery Marko has ever worked in, and a tally light
that is not red is not a tally light. Nothing else on this screen is red except
a real fault.

---

## Lifted rather than written

- `Trace.kt`, `TraceFormat.kt`, `CrashLog.kt`, `Downloads.kt` — whole, from
  `mantra-ndi` phase 0. The trace is unbuffered, cannot throw, and catches
  `Throwable` rather than `Exception`; Downloads goes through MediaStore because
  a `File` path into the public folder is refused silently from Android 10, and
  that silence cost the previous app ten versions of believing it had a crash log.
- `ndi_bridge.cpp`'s compressed path — from `mantra-ndi`, extended with the raw
  path and the parameter-set clear.
- The workflow and its artefact gates — from `mantra-ndi`, plus a gate on the
  two permissions that fail silently.
