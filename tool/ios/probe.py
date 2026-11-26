#!/usr/bin/env python3
"""Generate an iOS runner for the shared Air Relay Flutter app."""

import argparse
import json
from pathlib import Path
import plistlib
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
FILES = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    app = parser.parse_args().destination.resolve()
    if app.exists():
        parser.error("Choose a new directory for the generated probe.")
    subprocess.run(["flutter", "create", "--platforms=ios", "--no-pub", "--empty",
                    "--project-name=fitbit_air_example", "--org=dev.airpulse", str(app)], check=True)
    example = ROOT / "example"
    pubspec = (example / "pubspec.yaml").read_text()
    pubspec, count = re.subn(r"(?m)^    path: \.\./$", "    path: " + json.dumps(str(ROOT)), pubspec)
    if count != 1:
        raise RuntimeError("Could not find the plugin path in example/pubspec.yaml")
    (app / "pubspec.yaml").write_text(pubspec)
    for folder in ("lib", "assets"):
        shutil.copytree(example / folder, app / folder, dirs_exist_ok=True)
    shutil.copyfile(FILES / "ProbeTests.swift", app / "ios/RunnerTests/RunnerTests.swift")
    runner = app / "ios/Runner"
    iconset = runner / "Assets.xcassets/AppIcon.appiconset"
    subprocess.run(["xcrun", "swift", str(FILES / "RenderAppIcon.swift"),
                    str(example / "assets/brand/air_relay_icon.png"),
                    str(iconset / "AirRelay.png")], check=True)
    (iconset / "Contents.json").write_text(json.dumps({
        "images": [{"filename": "AirRelay.png", "idiom": "universal",
                    "platform": "ios", "size": "1024x1024"}],
        "info": {"author": "xcode", "version": 1},
    }, indent=2) + "\n")
    delegate = runner / "AppDelegate.swift"
    delegate.write_text(delegate.read_text() + "\n" + (FILES / "TapFitbitAirIntent.swift").read_text())
    plist = runner / "Info.plist"
    info = plistlib.loads(plist.read_bytes())
    info["CFBundleDisplayName"] = "Air Relay"
    info["NSBluetoothAlwaysUsageDescription"] = "Connect to your Fitbit Air to send a vibration."
    info["UIBackgroundModes"] = [*info.get("UIBackgroundModes", []), "bluetooth-central"]
    plist.write_bytes(plistlib.dumps(info, sort_keys=False))
    project = app / "ios/Runner.xcodeproj/project.pbxproj"
    project.write_text(re.sub(r"IPHONEOS_DEPLOYMENT_TARGET = [\d.]+;",
                              "IPHONEOS_DEPLOYMENT_TARGET = 16.0;", project.read_text()))


if __name__ == "__main__":
    main()
