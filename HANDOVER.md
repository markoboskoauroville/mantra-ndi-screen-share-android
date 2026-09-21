# Handover — Mantra NDI Screen Share

## Where it is

**v1, written 21.9.2026, built by CI, not yet run on a phone.**

The whole app is here: both modes, the rotation rebuild, the state panel, the
trace, the gates, the workflow, the permanent key. Nothing about it has been
seen working on real glass yet, because it was written in one session and the
only build machine is GitHub Actions.

## What is proven and what is not

**Proven on the desk**

- Test 1, 78 cases, the whole of `Mechanism.kt`: sizing, orientation, bitrate,
  the cost of both modes, the rebuild decision, the source name, the readouts.
- `scripts/verify.py`, 36 gates, all passing — and three of them were made to
  fail on purpose first, because a gate that has never been seen to fail is a
  rumour. **G9 was exactly that**: it asked whether `plane.rowStride` appeared
  anywhere in the file and passed happily while the send was handed `width × 4`.
  It is now a check on the argument at the call site.
- The NDI bridge's compressed path is lifted from `mantra-ndi`, where it was
  the one part that was never in doubt.

**Not proven, and it is the important half**

- **Nothing has been run on a phone.** No frame has left, no receiver has seen
  a source, neither mode has been watched in vMix.
- **The full NDI path is new code.** `NDIlib_send_send_video_v2` with an
  `ImageReader`'s RGBA buffer has never been run here. The stride, the byte
  order and the held-frame repeat are all reasoned from the SDK headers and
  from Android's documentation, not measured.
- **The rotation rebuild has never been watched.** Whether a receiver takes a
  mid-stream resolution change cleanly, and whether the picture comes back
  within a second, is the first thing to look at.
- **The 25-minute reopen has never run.** It fires once, at 25 minutes, and it
  is the thing that keeps a long share alive past the Advanced SDK's own
  30-minute cap.
- **The layout has not been seen on glass.** The insets are applied and the
  cutout is accounted for, but system-bars.md is emphatic that a screenshot
  from the device is the only proof.

## What to do next, in order

1. **Install v1 and start a compressed share.** Watch for the source in Studio
   Monitor or vMix on the Mac. If the phone says it is sending and nothing
   appears in the list, the suspect is the multicast lock, and the trace says
   whether it was held.
2. **Turn the phone.** The picture should come back the other way up within a
   second and the source should never leave the receiver's list.
3. **Let it sit on a still screen for a minute.** The picture must not freeze
   and the receiver must not drop it.
4. **Then full NDI, at 1280 first, not 1920.** If the picture is skewed into a
   diagonal the stride is wrong; if the colours are swapped the FourCC should
   be BGRA rather than RGBA.
5. **Leave a share running for forty minutes** and watch it survive the reopen.

## Waiting for Marko

- Which receiver he wants it proven against first — vMix, OBS, or Studio
  Monitor.
- Whether the screen's audio should go with the picture. It does not today,
  and the app declares no microphone permission because no code uses one.

## The rules that hold here

- The APK is built by GitHub Actions and never on a desk.
- `appVersion` rises by one, every push. There is no 1.1.
- The signing key never changes: `~/.mantra-ndi-screen-share-signing/` on the
  Mac, two repository secrets, and nowhere else.
- The NDI SDK never enters this repository. It is public; the licence forbids
  redistribution.
