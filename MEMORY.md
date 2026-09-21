# Memory — Mantra NDI Screen Share

Pointers, so a new chat does not search.

- **The app** — the phone's screen as an NDI source, two modes: compressed
  (NDI HX, hardware H.264/H.265) and full (High Bandwidth, SpeedHQ made by the
  SDK). Package `com.mantraproductions.ndiscreen`.
- **The family** — `mantra-ndi` is the broadcast camera, `mantra-ndi-sdk` is the
  licensed SDK both share. This is the third.
- **The SDK** — private repo `markoboskoauroville/mantra-ndi-sdk`, fetched in CI
  with `NDI_SDK_TOKEN`. Never committed here; this repository is public.
- **The build** — GitHub Actions only, never a desk. `appVersion` in
  `gradle.properties`, one whole number, one higher than the last release.
- **The key** — `~/.mantra-ndi-screen-share-signing/` on the Mac, and the
  secrets `SIGNING_KEYSTORE_B64` / `SIGNING_PASSWORD`. Fingerprint pinned in
  `SIGNING_FINGERPRINT.txt` and compared by the workflow.
- **What is proven and what is not** — `HANDOVER.md`. Nothing has run on a
  phone yet.
- **What was learned** — `LESSONS.md`. The four silent failures of an Android
  NDI sender are §1; the gate that was a rumour is §2.
