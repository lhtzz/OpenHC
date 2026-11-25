#!/usr/bin/env python3
"""Build and run the raw IPv4 port on a POSIX host with synthetic packets only."""
import os
import re
import shlex
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
JNI = ROOT / "platform/src/main/jni"
PORT = JNI / "lwip-ipv4"
LWIP = JNI / "thirdparty/lwip"


def main():
    manifest = (PORT / "sources.mk").read_text(encoding="utf-8")
    sources = [LWIP / name for name in re.findall(r"src/core/[a-z0-9_/]+\.c", manifest)]
    if not sources or not all(p.is_file() for p in sources):
        raise SystemExit("Invalid lwIP source manifest")
    with tempfile.TemporaryDirectory(prefix="openhc-lwip-smoke-") as out:
        binary = Path(out) / "ipv4-smoke"
        subprocess.run(
            shlex.split(os.environ.get("CC", "cc"))
            + ["-std=c11", "-D_POSIX_C_SOURCE=200809L", "-I", str(PORT), "-I", str(LWIP / "src/include")]
            + [str(PORT / "ipv4_tunnel.c"), str(ROOT / "platform/src/test/native/lwip_ipv4_smoke.c")]
            + [str(path) for path in sources]
            + ["-o", str(binary)],
            check=True,
        )
        subprocess.run([str(binary)], check=True, timeout=15)


if __name__ == "__main__":
    main()
