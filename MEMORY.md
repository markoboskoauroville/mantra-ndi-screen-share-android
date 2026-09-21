# Memory — Mantra NDI Screen Share

Pointers, so a new chat does not search.

- **The app** — the phone's screen as an NDI source, two modes: compressed
  (NDI HX, hardware H.264/H.265) and full (High Bandwidth, SpeedHQ made by the
  SDK). Package `com.mantraproductions.ndiscreen`.
- **The family** — `mantra-ndi` is the broadcast camera, `mantra-ndi-sdk` is the
  licensed SDK both share. This is the third.
- **The SDK** — private repo `markoboskoauroville/mantra-ndi-sdk`, fetched in CI
  over SSH with `NDI_SDK_DEPLOY_KEY`, the private half of a **read-only deploy
  key** on that repo. Never committed here; this repository is public.
  NOT A PERSONAL ACCESS TOKEN, and that was a deliberate change on 21.9.2026
  before the first build ever ran. A token carries everything its owner can
  reach; the one on this account has push rights across every repository, and
  it would have been sitting in a public repo's CI secrets. A deploy key is one
  keypair on one repository, read-only, separately revocable. If the key ever
  needs replacing: delete it under mantra-ndi-sdk → Settings → Deploy keys,
  make a new one, set the secret again. Nothing else is affected.
  The runner takes GitHub's SSH host keys from `api.github.com/meta` over TLS
  rather than `ssh-keyscan`, which trusts whatever answers, and rather than a
  copy pasted into the workflow, which would rot on the day GitHub rotates.
- **The build** — GitHub Actions only, never a desk. `appVersion` in
  `gradle.properties`, one whole number, one higher than the last release.
- **The key** — `~/.mantra-ndi-screen-share-signing/` on the Mac, and the
  secrets `SIGNING_KEYSTORE_B64` / `SIGNING_PASSWORD`. Fingerprint pinned in
  `SIGNING_FINGERPRINT.txt` and compared by the workflow.
- **What is proven and what is not** — `HANDOVER.md`. Nothing has run on a
  phone yet.
- **What was learned** — `LESSONS.md`. The four silent failures of an Android
  NDI sender are §1; the gate that was a rumour is §2.
