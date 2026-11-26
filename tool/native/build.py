#!/usr/bin/env python3
"""Build the bundled Golden Gate transport from pinned public source.

Builds the core (libgg.so) and JNI bridge (libxp.so) for each Android ABI with
a pinned NDK. Source paths are normalized, so the output is byte-identical
across machines and checkouts.

  build.py --ndk NDK                 write android/core/src/main/jniLibs/<abi>/
  build.py --ndk NDK --check         rebuild and compare with the committed files
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

UPSTREAM = "https://github.com/Google-Health-API/golden-gate.git"
REVISION = "b8a6f2552e12aaf92c1549dc88a41c9b7e73f1aa"
SUBMODULES = ["external/lwip", "external/mbedtls"]
# Golden Gate pins Mbed TLS 2.28.0; use the final 2.28 LTS release (same API)
# for its security fixes.
MBEDTLS = "https://github.com/Mbed-TLS/mbedtls.git"
MBEDTLS_REVISION = "2fc8413bfcb51354c8e679141b17b3f1a5942561"  # v2.28.10
NDK_REVISION = "28.2.13676358"
# Flutter supports no other Android ABIs.
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]
LIBRARIES = ["libgg.so", "libxp.so"]
JNI_FAMILIES = ["GoldenGate_initModulesJNI", "RunLoop", "Stack_create", "CoapEndpoint"]

REPO = Path(__file__).resolve().parents[2]
JNI_LIBS = REPO / "android/core/src/main/jniLibs"
MANIFEST = Path(__file__).resolve().parent / "manifest.json"


def run(*args, cwd=None, env=None):
    print("+ " + " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def output(*args):
    return subprocess.check_output(list(map(str, args)), text=True)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checkout(work):
    source = work / "golden-gate"
    if not source.exists():
        run("git", "clone", "--filter=blob:none", "--no-checkout", UPSTREAM, source)
    run("git", "checkout", "--detach", REVISION, cwd=source)
    run("git", "submodule", "update", "--init", "--depth", "1", *SUBMODULES, cwd=source)
    if output("git", "-C", source, "rev-parse", "HEAD").strip() != REVISION:
        raise RuntimeError("Unexpected upstream revision")
    if output("git", "-C", source, "status", "--porcelain").strip():
        raise RuntimeError("Upstream checkout must be clean before building")
    mbedtls = source / "external/mbedtls"
    run("git", "fetch", "--depth", "1", MBEDTLS, MBEDTLS_REVISION, cwd=mbedtls)
    run("git", "checkout", "--detach", MBEDTLS_REVISION, cwd=mbedtls)
    if output("git", "-C", mbedtls, "rev-parse", "HEAD").strip() != MBEDTLS_REVISION:
        raise RuntimeError("Unexpected Mbed TLS revision")
    return source


def build_abi(ndk, source, work, abi, jobs):
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    # Strip machine-specific paths embedded through __FILE__ in log macros.
    prefix_map = f"-ffile-prefix-map={work}/=."
    common = [
        "-G", "Ninja",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-24",
        "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
        "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        f"-DCMAKE_C_FLAGS={prefix_map}", f"-DCMAKE_CXX_FLAGS={prefix_map}",
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--no-undefined",
        f"-DCMAKE_MAKE_PROGRAM={shutil.which('ninja')}",
    ]
    env = dict(os.environ)
    env["SOURCE_DATE_EPOCH"] = output("git", "-C", source, "show", "-s", "--format=%ct", "HEAD").strip()
    # The JNI bridge links the core from this fixed location inside the source tree.
    core_build = source / "xp/build/cmake/android" / abi
    run("cmake", "-S", source / "xp", "-B", core_build,
        "-C", source / f"xp/config/profiles/android/{abi}.cmake",
        *common, "-DGG_ENABLE_APPS=OFF", "-DGG_ENABLE_EXAMPLES=OFF",
        "-DGG_ENABLE_UNIT_TESTS=OFF", env=env)
    run("cmake", "--build", core_build, "--target", "gg", "--parallel", jobs, env=env)
    jni_build = work / f"jni-{abi}"
    run("cmake", "-S", source / "platform/android/goldengate/GoldenGateBindings",
        "-B", jni_build, *common, "-DGG_ENABLE_UNIT_TESTS=OFF", env=env)
    run("cmake", "--build", jni_build, "--target", "xp", "--parallel", jobs, env=env)
    return {"libgg.so": core_build / "bundle/libgg.so", "libxp.so": jni_build / "libxp.so"}


def llvm_tools(ndk):
    return next((ndk / "toolchains/llvm/prebuilt").iterdir()) / "bin"


def strip(ndk, libraries):
    # Keeps the dynamic (JNI) symbols; drops debug and local symbols.
    for path in libraries.values():
        run(llvm_tools(ndk) / "llvm-strip", "--strip-unneeded", path)


def verify(ndk, abi, libraries):
    tools = llvm_tools(ndk)
    for name, path in libraries.items():
        headers = output(tools / "llvm-readelf", "--program-headers", "--wide", path)
        loads = [line for line in headers.splitlines() if line.strip().startswith("LOAD ")]
        # Android requires 16 KB page support for 64-bit ABIs.
        if "64" in abi and (not loads or any(int(line.split()[-1], 16) < 16384 for line in loads)):
            raise RuntimeError(f"{abi}/{name} has insufficient ELF LOAD alignment")
    symbols = output(tools / "llvm-nm", "--dynamic", "--defined-only", libraries["libxp.so"])
    for family in JNI_FAMILIES:
        if family not in symbols:
            raise RuntimeError(f"{abi}/libxp.so is missing JNI family {family}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--abi", choices=ABIS, action="append", help="default: all")
    parser.add_argument("--check", action="store_true", help="compare with the committed libraries")
    parser.add_argument("--work", type=Path, default=REPO / "build/native")
    parser.add_argument("--out", type=Path, help="also copy the built libraries here")
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    if not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        parser.error("--ndk must point to an installed Android NDK")
    if f"Pkg.Revision = {NDK_REVISION}" not in (ndk / "source.properties").read_text():
        parser.error(f"use NDK {NDK_REVISION}; other versions produce different binaries")
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source = checkout(work)
    jobs = os.cpu_count() or 2
    mismatches = []
    for abi in args.abi or ABIS:
        libraries = build_abi(ndk, source, work, abi, jobs)
        strip(ndk, libraries)
        verify(ndk, abi, libraries)
        for name, path in libraries.items():
            committed = JNI_LIBS / abi / name
            if args.out:
                (args.out / abi).mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, args.out / abi / name)
            if args.check:
                actual = sha256(path)
                expected = sha256(committed) if committed.is_file() else "missing"
                print(f"{abi}/{name}: built {actual}, committed {expected}")
                if actual != expected:
                    mismatches.append(f"{abi}/{name}")
            else:
                committed.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, committed)
    if not args.check:
        MANIFEST.write_text(json.dumps({
            "upstream": UPSTREAM,
            "revision": REVISION,
            "submodules": output("git", "-C", source, "submodule", "status", *SUBMODULES).split("\n")[:-1],
            "ndk": NDK_REVISION,
            "libraries": {
                f"{abi}/{name}": sha256(JNI_LIBS / abi / name)
                for abi in ABIS for name in LIBRARIES if (JNI_LIBS / abi / name).is_file()
            },
        }, indent=2) + "\n")
    if mismatches:
        sys.exit("Built libraries differ from the committed ones: " + ", ".join(mismatches))


if __name__ == "__main__":
    main()
