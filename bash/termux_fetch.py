#!/usr/bin/env python3
"""Repackage binaries from Termux's apt repo (aarch64) as Android jniLibs.

  termux_fetch.py --clean --pkg proot \
      --bin usr/bin/proot=libproot.so \
      --bin 'usr/libexec/proot/loader=libproot-loader.so' \
      --bin 'usr/libexec/proot/loader32=libproot-loader32.so?'      (trailing ? = optional)

Every package listed (and its Depends closure) is downloaded, sha256-checked against the repo index and
extracted. Executables are copied to app/src/main/jniLibs/arm64-v8a/<dst>. Non-system shared libraries they
need (found by walking DT_NEEDED) are copied with flat names (libfoo.so.1 -> libfoo.so), NEEDED entries are
patched to match and RPATH is set to $ORIGIN, so everything is found in nativeLibraryDir.
Provenance is appended to build-info/termux-provenance.txt.
"""
import argparse, gzip, hashlib, lzma, os, re, shutil, subprocess, sys, tempfile, urllib.request
from pathlib import Path

REPO = "https://packages.termux.dev/apt/termux-main"
ARCH = "aarch64"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/jniLibs/arm64-v8a"
INFO = ROOT / "build-info"


def fetch(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()


def load_index():
    base = f"{REPO}/dists/stable/main/binary-{ARCH}/Packages"
    for ext, dec in ((".xz", lzma.decompress), (".gz", gzip.decompress), ("", lambda b: b)):
        try:
            return dec(fetch(base + ext)).decode()
        except Exception as e:  # noqa
            print(f"index{ext} failed: {e}")
    sys.exit("cannot fetch the Termux Packages index")


def parse(index):
    pk = {}
    for block in index.split("\n\n"):
        f = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                k, v = line.split(":", 1)
                f[k.strip()] = v.strip()
        if "Package" in f:
            pk[f["Package"]] = f
    return pk


def closure(pk, roots):
    seen, todo = [], list(roots)
    while todo:
        n = todo.pop()
        if n in seen or n not in pk:
            continue
        seen.append(n)
        for dep in pk[n].get("Depends", "").split(","):
            name = re.split(r"[ (|]", dep.strip())[0]
            if name:
                todo.append(name)
    return seen


def real(p, base):
    p = Path(p)
    for _ in range(10):
        if not p.is_symlink():
            return p
        t = os.readlink(p)
        p = (base / t.lstrip("/")) if t.startswith("/") else (p.parent / t)
    raise RuntimeError(f"symlink loop at {p}")


def readelf(elf):
    return subprocess.check_output(["readelf", "-d", str(elf)], text=True)


def needed(elf):
    return re.findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", readelf(elf))


def is_dynamic(elf):
    return "no dynamic section" not in readelf(elf)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clean", action="store_true", help="wipe jniLibs first")
    ap.add_argument("--pkg", action="append", required=True)
    ap.add_argument("--bin", action="append", required=True, help="path-in-prefix=destname[?]")
    a = ap.parse_args()

    pk = parse(load_index())
    names = closure(pk, a.pkg)
    print("packages:", names)
    work = Path(tempfile.mkdtemp())
    x = work / "x"
    x.mkdir()
    INFO.mkdir(exist_ok=True)
    prov = []
    for n in names:
        f = pk[n]
        data = fetch(f"{REPO}/{f['Filename']}")
        got = hashlib.sha256(data).hexdigest()
        if got != f.get("SHA256"):
            sys.exit(f"sha256 mismatch for {n}: {got} != {f.get('SHA256')}")
        deb = work / f"{n}.deb"
        deb.write_bytes(data)
        subprocess.check_call(["dpkg-deb", "-x", str(deb), str(x)])
        prov.append(f"{n} {f.get('Version')} sha256={got} {REPO}/{f['Filename']}")

    if a.clean:
        shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True, exist_ok=True)

    patch_list, queue = [], []
    for spec in a.bin:
        src, dst = spec.split("=", 1)
        optional = dst.endswith("?")
        dst = dst.rstrip("?")
        found = next(iter(x.glob(f"**/{src}")), None)
        if not found:
            if optional:
                print("optional binary missing:", src)
                continue
            sys.exit(f"binary not found in packages: {src}")
        shutil.copy2(real(found, x), OUT / dst)
        if is_dynamic(OUT / dst):
            patch_list.append(OUT / dst)
            queue.append(OUT / dst)

    libs = {}
    for dp, _, files in os.walk(x):
        for fn in files:
            if ".so" in fn:
                libs[fn] = Path(dp) / fn

    renames = {}
    while queue:
        elf = queue.pop()
        for so in needed(elf):
            if so in renames or so not in libs:
                continue  # not in any package => provided by Android (libc.so, libdl.so, liblog.so...)
            new = re.sub(r"\.so(\.\d+)*$", ".so", so)
            if not (OUT / new).exists():
                shutil.copy2(real(libs[so], x), OUT / new)
            renames[so] = new
            patch_list.append(OUT / new)
            queue.append(OUT / new)

    for elf in patch_list:
        for so, new in renames.items():
            if so != new:
                subprocess.check_call(["patchelf", "--replace-needed", so, new, str(elf)])
        if not elf.name.startswith(("libproot", "libqemu")):
            subprocess.check_call(["patchelf", "--set-soname", elf.name, str(elf)])
        subprocess.check_call(["patchelf", "--set-rpath", "$ORIGIN", str(elf)])
        print(elf.name, "NEEDED:", needed(elf))

    with open(INFO / "termux-provenance.txt", "a") as fh:
        fh.write("# Termux packages (GPL/LGPL components; sources: https://github.com/termux/termux-packages)\n")
        fh.write("\n".join(prov) + "\n")
    subprocess.check_call(["ls", "-l", str(OUT)])


if __name__ == "__main__":
    main()
