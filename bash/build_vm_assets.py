#!/usr/bin/env python3
"""Build the VM boot assets: a Debian arm64 kernel and a tiny initramfs.

Outputs (packaged as APK assets):
  app/src/main/assets/vm/Image             kernel (from Debian's linux-image-arm64)
  app/src/main/assets/vm/initramfs.cpio.gz busybox + virtio modules + bash/vm/init

The initramfs does NOT contain the rootfs: the app streams it into the guest at boot (see VmFeeder.kt),
so the same VM image serves both the shipped LiveCD and the installed System.
"""
import gzip, hashlib, lzma, os, re, shutil, subprocess, sys, tempfile, urllib.request
from pathlib import Path

MIRROR = "https://deb.debian.org/debian"
SUITE = os.environ.get("DEBIAN_SUITE", "trixie")
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/vm"
INFO = ROOT / "build-info"
MODULES = ["failover", "net_failover", "virtio_mmio", "virtio_console", "virtio_net"]


def fetch(url):
    with urllib.request.urlopen(url, timeout=600) as r:
        return r.read()


def index():
    raw = lzma.decompress(fetch(f"{MIRROR}/dists/{SUITE}/main/binary-arm64/Packages.xz")).decode()
    pk = {}
    for block in raw.split("\n\n"):
        f = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                k, v = line.split(":", 1)
                f[k.strip()] = v.strip()
        if "Package" in f:
            pk[f["Package"]] = f
    return pk


def get_deb(pk, name, work, x):
    f = pk[name]
    data = fetch(f"{MIRROR}/{f['Filename']}")
    got = hashlib.sha256(data).hexdigest()
    if got != f["SHA256"]:
        sys.exit(f"sha256 mismatch for {name}")
    deb = work / f"{name}.deb"
    deb.write_bytes(data)
    subprocess.check_call(["dpkg-deb", "-x", str(deb), str(x)])
    return f"{name} {f['Version']} sha256={got} {MIRROR}/{f['Filename']}"


def main():
    pk = index()
    work = Path(tempfile.mkdtemp())
    kx, bx = work / "kernel", work / "busybox"
    kx.mkdir(); bx.mkdir()

    meta = pk["linux-image-arm64"]
    kname = next(m for m in re.findall(r"linux-image-[\d.]+-arm64\b", meta["Depends"]))
    prov = [get_deb(pk, kname, work, kx), get_deb(pk, "busybox-static", work, bx)]

    # kernel
    vmlinuz = next(iter(sorted((kx / "boot").glob("vmlinuz-*"))))
    blob = vmlinuz.read_bytes()
    if blob[:2] == b"\x1f\x8b":
        blob = gzip.decompress(blob)
    print("kernel:", vmlinuz.name, "magic:", blob[:4], "arm64 Image magic ok:", blob[56:60] == b"ARM\x64")
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "Image").write_bytes(blob)

    # initramfs tree
    ir = work / "ir"
    for d in ("bin", "dev", "proc", "sys", "lib/modules", "newroot", "etc"):
        (ir / d).mkdir(parents=True, exist_ok=True)
    shutil.copy2(bx / "bin/busybox", ir / "bin/busybox")
    shutil.copy2(ROOT / "bash/vm/init", ir / "init")
    os.chmod(ir / "init", 0o755)
    os.chmod(ir / "bin/busybox", 0o755)

    found = []
    for mod in MODULES:
        for pat in (f"**/{mod}.ko.xz", f"**/{mod}.ko.zst", f"**/{mod}.ko"):
            hit = next(iter((kx / "lib/modules").glob(pat)), None)
            if not hit:
                continue
            dst = ir / "lib/modules" / f"{mod}.ko"
            if hit.suffix == ".xz":
                dst.write_bytes(lzma.decompress(hit.read_bytes()))
            elif hit.suffix == ".zst":
                subprocess.check_call(["zstd", "-d", "-q", "-f", str(hit), "-o", str(dst)])
            else:
                shutil.copy2(hit, dst)
            found.append(mod)
            break
    print("modules included:", found, "(missing ones are assumed built into the kernel)")

    subprocess.check_call(["sudo", "mknod", "-m", "600", str(ir / "dev/console"), "c", "5", "1"])
    subprocess.check_call(["sudo", "mknod", "-m", "666", str(ir / "dev/null"), "c", "1", "3"])
    cpio = subprocess.run(
        ["sudo", "sh", "-c", f"cd '{ir}' && find . | cpio -o -H newc -R 0:0 --quiet | gzip -9"],
        check=True, stdout=subprocess.PIPE).stdout
    (ASSETS / "initramfs.cpio.gz").write_bytes(cpio)

    INFO.mkdir(exist_ok=True)
    with open(INFO / "vm-provenance.txt", "w") as fh:
        fh.write("# VM boot assets (Debian packages, GPL)\n" + "\n".join(prov) + f"\nmodules: {found}\n")
    subprocess.check_call(["ls", "-lh", str(ASSETS)])


if __name__ == "__main__":
    main()
