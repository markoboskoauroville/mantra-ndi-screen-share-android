#!/usr/bin/env python3
"""Structural checks a compiler will not run.

android-app.md: the gates that fail the build for reasons Kotlin cannot see.
Each one prints its own line and can fail on its own.

checking-the-checks.md: a gate over what the code DOES must not be able to see
what the code SAYS, so everything below reads the source with its comments
taken out. Four of the gates here exist because the thing they check is
invisible to every other kind of test — a JNI name that does not match, a
multicast lock that is not taken, a still screen that produces no frames, a
stride computed rather than read — and each one fails at runtime, on the phone,
with no error anybody can read.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PKG = "app/src/main/java/com/mantraproductions/ndiscreen"
FAILS = []


def code_only(text):
    """The source with its comments taken out."""
    out = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = re.sub(r"<!--.*?-->", "", out, flags=re.S)
    return re.sub(r"//[^\n]*", "", out)


def read(rel):
    return (ROOT / rel).read_text()


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail and not ok else ""))
    if not ok:
        FAILS.append(name)


def main():
    # ---------------------------------------------------------------- G1
    # The version is written in exactly one place and derived everywhere else.
    props = read("gradle.properties")
    versions = re.findall(r"^appVersion=(\d+)$", props, re.M)
    check("G1 appVersion present and whole", len(versions) == 1, f"found {versions}")

    gradle = read("app/build.gradle.kts")
    check("G1 versionCode reads appVersion", "versionCode = appVersion" in gradle)
    check("G1 versionName reads appVersion", "versionName = appVersion.toString()" in gradle)
    check("G1 no hardcoded version", not re.search(r'versionName\s*=\s*"[\d.]+"', gradle))

    # ---------------------------------------------------------------- G2
    # The mechanism stays free of Android so Test 1 runs on a desk. The day
    # this stops being true, the arithmetic can only be checked on a phone,
    # which means it is not checked.
    mechanism = read(f"{PKG}/Mechanism.kt")
    android_imports = re.findall(r"^\s*import android\.", code_only(mechanism), re.M)
    check("G2 Mechanism imports no Android", not android_imports,
          f"{len(android_imports)} android imports")

    # ---------------------------------------------------------------- G3
    # The signing key is never committed. A key in the tree is a key anybody
    # who clones this public repository can sign a different app with.
    keys = [p for p in ROOT.glob("**/*")
            if p.suffix in (".p12", ".jks", ".keystore") and ".git" not in p.parts]
    check("G3 no keystore in the tree", not keys, str(keys))
    gitignore = read(".gitignore")
    check("G3 signing dir ignored", "signing/" in gitignore)

    # ---------------------------------------------------------------- G4
    # The licensed SDK is never committed. Its licence forbids redistribution
    # and this repository is public.
    # TRACKED files, not files on disk. The licence forbids redistributing the
    # SDK, and this repository is public — so what has to be true is that git
    # never carries it, which is a different statement from "it is not on this
    # machine". Checking the disk instead made the gate impossible to satisfy
    # while developing: the SDK has to BE in app/src/main/cpp/ndi for Gradle to
    # compile the bridge at all, so every local build failed its own gate and
    # the only place anything could be built was CI.
    try:
        tracked = subprocess.run(
            ["git", "ls-files", "app/src/main/jniLibs", "app/src/main/cpp/ndi"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.split()
    except Exception:
        # No git here — fall back to the disk, which is the stricter reading.
        tracked = [str(x) for x in ROOT.glob("app/src/main/jniLibs/**/*.so")] + \
            [str(x) for x in ROOT.glob("app/src/main/cpp/ndi/**/*.h")]
    check("G4 no NDI SDK committed", not tracked, f"{len(tracked)} tracked files")
    check("G4 jniLibs ignored", "app/src/main/jniLibs/" in gitignore)
    check("G4 sdk headers ignored", "app/src/main/cpp/ndi/" in gitignore)

    # ---------------------------------------------------------------- G5
    # Test 1 exists and is not a token gesture.
    test_dir = ROOT / "app/src/test/java/com/mantraproductions/ndiscreen"
    test_files = list(test_dir.glob("*Test.kt"))
    check("G5 Test 1 exists", bool(test_files))
    cases = sum(len(re.findall(r"@Test", f.read_text())) for f in test_files)
    check("G5 Test 1 has a floor of cases", cases >= 40, f"{cases} cases")

    # A test in the wrong package does not fail — it does not COMPILE, and a
    # build that stops before testDebugUnitTest never finds out. TraceFormatTest
    # sat in com.mantraproductions.ndi (no "screen") from the first commit, so
    # 45 of the 78 tests had never once run, while the README counted them.
    # Found by building on the desk, not by CI, because CI died at signing two
    # steps earlier. LESSONS: a step that never ran is not a step that passed.
    want = "package com.mantraproductions.ndiscreen"
    strays = sorted(
        f.name for f in (ROOT / "app/src/test/java/com/mantraproductions/ndiscreen").glob("*.kt")
        if not f.read_text().startswith(want)
    )
    check("G5 every test is in the package it tests", not strays, str(strays))

    # ---------------------------------------------------------------- G6
    # THE GATE NOTHING ELSE CAN CATCH.
    #
    # Every `external fun` in Kotlin must have a JNI symbol in the C++ whose
    # name matches it exactly, and the other way round. A mismatch compiles
    # cleanly on both sides and throws UnsatisfiedLinkError the first time the
    # method is called — which, for half of these, is only when somebody
    # actually starts a share on a phone.
    kotlin_side = code_only(read(f"{PKG}/NdiSender.kt"))
    declared = set(re.findall(r"external fun (\w+)\(", kotlin_side))
    cpp = code_only(read("app/src/main/cpp/ndi_bridge.cpp"))
    defined = set(re.findall(
        r"Java_com_mantraproductions_ndiscreen_NdiSender_(\w+)\s*\(", cpp))
    check("G6 every external fun has a JNI symbol", declared <= defined,
          f"missing from C++: {sorted(declared - defined)}")
    check("G6 every JNI symbol has an external fun", defined <= declared,
          f"missing from Kotlin: {sorted(defined - declared)}")
    check("G6 there are native methods at all", len(declared) >= 8, f"{len(declared)}")

    # ---------------------------------------------------------------- G7
    # THE GATE FOR THE FAILURE THAT LOOKS LIKE NOTHING.
    #
    # NDI discovery is mDNS over UDP multicast and Android drops multicast
    # packets on Wi-Fi unless a MulticastLock is held. Without it the phone
    # encodes, sends and reports success, and no receiver on the network ever
    # sees the source. There is no error, anywhere, on either machine.
    service = code_only(read(f"{PKG}/ScreenShareService.kt"))
    check("G7 a multicast lock is created", "createMulticastLock" in service)
    check("G7 the multicast lock is acquired", "acquire()" in service)
    check("G7 the multicast lock is released", "isHeld" in service and "release()" in service)
    manifest = code_only(read("app/src/main/AndroidManifest.xml"))
    check("G7 CHANGE_WIFI_MULTICAST_STATE declared",
          "CHANGE_WIFI_MULTICAST_STATE" in manifest)

    # ---------------------------------------------------------------- G8
    # A still screen produces no frames. A virtual display only draws when
    # something changes, so a phone sitting on a menu hands the encoder nothing
    # and the receiver's picture freezes and then times out. The compressed
    # path asks the encoder to repeat; the full path repeats the held frame on
    # its own tick. Both are needed and neither is visible in a unit test.
    pipelines = code_only(read(f"{PKG}/Pipelines.kt"))
    check("G8 compressed repeats the previous frame",
          "KEY_REPEAT_PREVIOUS_FRAME_AFTER" in pipelines)
    check("G8 full NDI repeats the held frame", "postDelayed(this, intervalMs)" in pipelines)

    # ---------------------------------------------------------------- G9
    # The full path must use the stride the reader reports. A reader 1080
    # pixels wide commonly hands back rows padded to 1088 or 1152, and
    # computing the stride as width x 4 skews the picture into a diagonal —
    # which reads as a broken codec rather than as arithmetic.
    # The check is on the ARGUMENT at the call site, not on the file containing
    # the word somewhere. The first version of this gate asked whether
    # "plane.rowStride" appeared anywhere in the file and passed happily while
    # the send was handed width x 4, because the bytes counter still mentioned
    # the stride two lines lower. A gate that has never been seen to fail is a
    # rumour; this one was, and was caught by making it fail on purpose.
    check("G9 full NDI passes the reported row stride to the send",
          re.search(r"stride\s*=\s*[\w.]*rowStride", pipelines) is not None)
    check("G9 full NDI never computes its own stride",
          not re.search(r"stride\s*=\s*[\w.]*[Ww]idth\s*\*\s*4", pipelines))

    # ---------------------------------------------------------------- G10
    # Both modes are reachable from the screen and both are built by the
    # service. A mode with no way to choose it is a mode that does not exist.
    layout = code_only(read("app/src/main/res/layout/activity_main.xml"))
    check("G10 both modes are on the screen",
          "@+id/modeCompressed" in layout and "@+id/modeFull" in layout)
    check("G10 the service builds both",
          "CompressedPipeline(" in service and "FullPipeline(" in service)

    # ---------------------------------------------------------------- G11
    # system-bars.md: nothing that can be pressed or read may sit under the
    # status bar or the gesture bar. From targetSdk 35 Android stops insetting
    # for you, and it is a layout failure no test on a desk can see.
    activity = code_only(read(f"{PKG}/MainActivity.kt"))
    check("G11 the window is taken edge to edge", "setDecorFitsSystemWindows" in activity)
    check("G11 the insets are applied back", "setOnApplyWindowInsetsListener" in activity)
    check("G11 the cutout is accounted for", "displayCutout" in activity)

    # ---------------------------------------------------------------- G12
    # Every permission declared is used by code here. A permission no code uses
    # is a claim the app cannot back.
    #
    # <uses-permission> only, and that is the whole point of the gate: it is
    # about what the app ASKS FOR. A permission named in a component's own
    # android:permission attribute is the opposite — a requirement placed on
    # whoever wants to bind it, which is how the tile keeps anything but
    # SystemUI out. Counting those as requests reads a lock as a key.
    permissions = set(re.findall(
        r"<uses-permission[^>]*android:name=\"android\.permission\.(\w+)\"", manifest))
    sources = "\n".join(p.read_text() for p in (ROOT / PKG).glob("*.kt"))
    used = {
        "INTERNET": "NdiSender",
        "ACCESS_WIFI_STATE": "WifiManager",
        "CHANGE_WIFI_MULTICAST_STATE": "createMulticastLock",
        "FOREGROUND_SERVICE": "startForeground",
        "FOREGROUND_SERVICE_MEDIA_PROJECTION": "FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION",
        "POST_NOTIFICATIONS": "POST_NOTIFICATIONS",
        "WAKE_LOCK": "newWakeLock",
    }
    unbacked = sorted(p for p in permissions if p not in used)
    check("G12 no permission the code does not use", not unbacked, str(unbacked))
    unused = sorted(k for k, needle in used.items()
                    if k in permissions and needle not in sources)
    check("G12 every declared permission is exercised", not unused, str(unused))

    # ---------------------------------------------------------------- G12b
    # The other half: the tile is bound by SystemUI, another process, and the
    # bind permission is the only thing standing between it and any app on the
    # phone being able to drive the screen share. Dropping that one attribute
    # breaks nothing that can be seen or tested on the phone.
    check("G12 the tile is bound only by SystemUI",
          'android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"' in manifest)

    # ---------------------------------------------------------------- G13
    # The foreground service type, in both places it has to be.
    check("G13 service declares the mediaProjection type",
          'android:foregroundServiceType="mediaProjection"' in manifest)
    check("G13 startForeground passes the type on 34+",
          "FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION" in service)

    # ---------------------------------------------------------------- G16
    # The Quick Settings tile. Every one of these fails silently on the phone:
    # the tile simply never appears in the shade's list, or appears and does
    # nothing, and nothing anywhere says why.
    tile = code_only(read(f"{PKG}/NdiTileService.kt"))
    check("G16 the tile is declared to the shade",
          "android.service.quicksettings.action.QS_TILE" in manifest)
    check("G16 the tile service is exported",
          re.search(r"<service[^>]*NdiTileService(.|\n)*?</service>", manifest) is not None
          and 'android:name=".NdiTileService"' in manifest)
    check("G16 the tile has an icon and a label",
          "@drawable/ic_tile_ndi" in manifest and "@string/tile_label" in manifest)
    # requestListeningState() is honoured for ACTIVE_TILE services and ignored
    # for every other kind, with no error. Set this back to false and the tile
    # stops being redrawn the moment it is tapped - it goes on saying "not
    # sending" over a live share, and nothing anywhere complains.
    active = re.search(
        r'ACTIVE_TILE"\s*\n?\s*android:value="(\w+)"', manifest)
    check("G16 the tile is an ACTIVE_TILE, or it can never be redrawn",
          active is not None and active.group(1) == "true",
          active.group(1) if active else "the meta-data is missing")

    # A tile cannot show the capture dialog itself; it must hand over to an
    # activity, and the shade must be told to close or the dialog opens behind
    # it. Both halves, or the tap looks ignored.
    check("G16 the tile hands the start to an activity",
          "TileStartActivity" in tile)
    check("G16 the tile closes the shade", "startActivityAndCollapse" in tile)
    check("G16 the tile takes the 34+ PendingIntent branch",
          "PendingIntent.getActivity" in tile and "SDK_INT >= 34" in tile)

    # Stopping must not wait for a lock screen, and starting must. The two are
    # easy to write the same way and the wrong one is only found on a phone
    # with a fingerprint on it, at the moment something is wrongly on air.
    click = tile[tile.index("fun onClick"):tile.index("fun onClick") + 600]
    check("G16 starting waits for the lock screen", "unlockAndRun" in click)
    check("G16 stopping does not wait for the lock screen",
          "unlockAndRun" not in tile[tile.index("fun stopShare"):
                                     tile.index("fun stopShare") + 600])

    # The tile is drawn from the service's status and nothing else, and the
    # service tells it on both transitions. Miss the stop one and the tile sits
    # lit over a share that ended.
    check("G16 the tile reads the service's status",
          "ScreenShareService.status.running" in tile)
    starts = service.count("NdiTileService.refresh")
    check("G16 the service tells the tile it started and that it stopped",
          starts >= 2, f"found {starts} refresh call(s)")
    check("G16 the tile is not redrawn on every poll",
          "NdiTileService.refresh" not in
          service[service.index("private fun publishStatus"):
                  service.index("private fun publishStatus") + 800])

    # The transparent activity must finish itself on both answers, or a refusal
    # leaves an invisible window in front of whatever he is streaming.
    tile_activity = code_only(read(f"{PKG}/TileStartActivity.kt"))
    check("G16 the dialog holder finishes itself",
          tile_activity.count("finish()") >= 1 and "Theme.NdiScreenShare.Invisible" in
          read("app/src/main/AndroidManifest.xml"))
    check("G16 the dialog holder asks once", "savedInstanceState == null" in tile_activity)

    # ---------------------------------------------------------------- G14
    # The APK is not delivered until it is downloadable, and the link is the
    # first thing in the README, not the last.
    readme = read("README.md")
    head = "\n".join(readme.splitlines()[:6])
    check("G14 the download link is at the top of the README",
          "releases/latest" in head, head.strip()[:120])

    # ---------------------------------------------------------------- G15
    # The workflow is the only thing that builds an APK, and it must refuse to
    # release one without the SDK in it.
    workflow = read(".github/workflows/build.yml")
    check("G15 the artefact is gated on the NDI runtime",
          "libndi_advanced.so" in workflow and "::error::" in workflow)
    check("G15 the artefact is gated on the bridge", "libndi_bridge.so" in workflow)
    check("G15 the signing fingerprint is pinned, not printed",
          "SIGNING_FINGERPRINT.txt" in workflow)

    print()
    if FAILS:
        print(f"{len(FAILS)} gate(s) failed: " + ", ".join(FAILS))
        return 1
    print("all gates passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
