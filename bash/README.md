# Bash

Scripts to help you manage your app.

### utils-compile-apk
Push the repo to github to trigger the Github Actions workflow to compile the apk

### utils-generate-keystore
Generate the keys and files needed for github secrets.

### termux_fetch.py (CI)
Downloads binaries from the Termux apt repo (proot, QEMU) and repackages them as `jniLibs`.

### build_vm_assets.py + vm/init (CI)
Builds the VM kernel (`assets/vm/Image`) and the tiny initramfs (`assets/vm/initramfs.cpio.gz`).

### fetch-rootfs.sh (CI)
Downloads the official Debian arm64 rootfs into `assets/rootfs/`.
