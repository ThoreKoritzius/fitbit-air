#!/usr/bin/env python3
"""Build a local Golden Gate XCFramework for the iOS haptics prototype."""

import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "ios"
SPEC = importlib.util.spec_from_file_location("native_build", ROOT / "tool/native/build.py")
native = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(native)
run = native.run


def build_slice(source, work, sdk, architectures):
    build = work / sdk
    run("cmake", "-S", source / "xp", "-B", build,
        "-C", source / "xp/config/profiles/iOS.cmake",
        "-DCMAKE_SYSTEM_NAME=iOS", f"-DCMAKE_OSX_SYSROOT={sdk}",
        f"-DCMAKE_OSX_ARCHITECTURES={';'.join(architectures)}",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=16.0", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DGG_BUILD_STATIC_FRAMEWORK=OFF", "-DGG_ENABLE_EXAMPLES=OFF",
        "-DGG_PORTS_ENABLE_BSD_SELECT_LOOP=OFF", "-DGG_PORTS_ENABLE_BSD_SOCKETS=OFF",
        "-DGG_PORTS_ENABLE_GENERIC_LOOP=ON",
        "-DCMAKE_BUILD_TYPE=Release")
    run("cmake", "--build", build, "--parallel", min(os.cpu_count() or 2, 8))
    # Compile the small C boundary separately. Swift only sees AirSession.h.
    objects = []
    for arch in architectures:
        obj = build / f"AirSession-{arch}.o"
        deployment = "-mios-simulator-version-min=16.0" if sdk == "iphonesimulator" else "-miphoneos-version-min=16.0"
        run("xcrun", "--sdk", sdk, "clang", "-arch", arch, deployment,
            "-Wall", "-Wextra", "-Werror", "-I", source, "-I", build / "config",
            "-c", IOS / "Classes/AirSession.c", "-o", obj)
        objects.append(obj)
    adapter = objects[0]
    if len(objects) > 1:
        adapter = build / "AirSession.o"
        run("xcrun", "lipo", "-create", *objects, "-output", adapter)
    archive = build / "libAirGoldenGate.a"
    libraries = sorted(build.rglob("libgg-*.a"))
    if not libraries:
        raise RuntimeError(f"No Golden Gate archives produced for {sdk}")
    run("xcrun", "libtool", "-static", "-o", archive, adapter, *libraries)
    return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=ROOT / "build/ios-native")
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source = native.checkout(work)
    headers = work / "headers"
    headers.mkdir(exist_ok=True)
    shutil.copyfile(IOS / "Classes/AirSession.h", headers / "AirSession.h")
    (headers / "module.modulemap").write_text(
        'module AirGoldenGate { header "AirSession.h" export * }\n'
    )
    device = build_slice(source, work, "iphoneos", ["arm64"])
    simulator = build_slice(source, work, "iphonesimulator", ["arm64", "x86_64"])
    destination = IOS / "Frameworks/AirGoldenGate.xcframework"
    destination.parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temp:
        output = Path(temp) / destination.name
        run("xcodebuild", "-create-xcframework", "-library", device, "-headers", headers,
            "-library", simulator, "-headers", headers, "-output", output)
        if destination.exists():
            shutil.rmtree(destination)
        shutil.move(output, destination)


if __name__ == "__main__":
    main()
