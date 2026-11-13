# DebianLiveCD4Android

A Termux alternative for advanced users: a real **Debian** userland on Android, built from the
[KotlinApkTemplate](https://github.com/StringManolo/KotlinApkTemplate) and compiled entirely by GitHub Actions.

- Debian runs under **proot** (the `termux/proot` fork), not inside a Termux-style prefix.
- The Debian rootfs is the official `debuerreotype` tarball (same one as the Docker image), shipped inside the APK.
- Terminal emulation reuses Termux's `terminal-emulator` / `terminal-view`, with an extra-keys bar
  (ESC, TAB, CTRL, ALT, HOME, END, PGUP, PGDN, arrows; tap a modifier = one-shot, long-press = lock).
- `targetSdk 28` on purpose: newer targets cannot exec binaries from the app's data directory (W^X).

## Environments and modes (⚙ button, top-right)

|            | **Normal**                            | **Ephemeral**                                  | **VM**                                           |
|------------|---------------------------------------|------------------------------------------------|--------------------------------------------------|
| **System** | proot on your installed Debian        | proot on a temp copy of it, wiped on exit      | QEMU, installed tree streamed into guest RAM     |
| **LiveCD** | proot on a persistent extraction      | fresh extraction every start, wiped on exit    | QEMU, shipped image streamed into guest RAM      |

- **Normal**: persistent. System and LiveCD live in separate directories.
- **Ephemeral**: runs in a temporary directory that is zero-filled and deleted on exit (and swept on the next
  launch if the app was killed). This is *disk*, not RAM: flash storage (F2FS, wear levelling) may keep remnants.
- **VM**: `qemu-system-aarch64` in TCG mode (no KVM for apps). A tiny initramfs boots, receives the rootfs over a
  virtio-console port into a tmpfs and switches to it. Nothing touches the disk; changes vanish on exit.
  Much slower than proot. Pick RAM and cores in the settings. Network is QEMU user-mode (slirp).
  VM mode is optional at build time (`with-vm` in the workflow); the settings screen disables it if absent.

## Build

Push to GitHub. `.github/actions/prepare-assets` downloads proot (and QEMU) from the Termux apt repo
(sha256-verified), builds the VM kernel/initramfs from Debian packages and fetches the Debian rootfs; then Gradle
builds the APK. Hashes and URLs are uploaded as the `provenance` artifact.

Android 12+ may kill child processes ("phantom process killer"); disable it if sessions die in the background.

## Status

Written without a local toolchain: expect to fix compile/runtime issues from the first Actions logs.
See `TODO.md`.
