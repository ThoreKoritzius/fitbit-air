#!/usr/bin/env python3
"""Generate a standalone Flutter host for testing the local iOS prototype."""

import argparse
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
    subprocess.run(["flutter", "create", "--platforms=ios", "--no-pub",
                    "--project-name=fitbit_air_probe", "--org=dev.airpulse", str(app)], check=True)
    pubspec = app / "pubspec.yaml"
    pubspec.write_text(pubspec.read_text().replace(
        "dependencies:\n", "dependencies:\n  fitbit_air:\n    path: " + repr(str(ROOT)) + "\n", 1
    ))
    shutil.copyfile(FILES / "probe.dart", app / "lib/main.dart")
    shutil.copyfile(FILES / "ProbeTests.swift", app / "ios/RunnerTests/RunnerTests.swift")
    runner = app / "ios/Runner"
    delegate = runner / "AppDelegate.swift"
    delegate.write_text(delegate.read_text() + "\n" + (FILES / "TapFitbitAirIntent.swift").read_text())
    plist = runner / "Info.plist"
    info = plistlib.loads(plist.read_bytes())
    info["NSBluetoothAlwaysUsageDescription"] = "Connect to your Fitbit Air to send a vibration."
    info["UIBackgroundModes"] = [*info.get("UIBackgroundModes", []), "bluetooth-central"]
    plist.write_bytes(plistlib.dumps(info, sort_keys=False))
    project = app / "ios/Runner.xcodeproj/project.pbxproj"
    project.write_text(re.sub(r"IPHONEOS_DEPLOYMENT_TARGET = [\d.]+;",
                              "IPHONEOS_DEPLOYMENT_TARGET = 16.0;", project.read_text()))


if __name__ == "__main__":
    main()
