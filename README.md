# DebianLiveCD4Android

A Termux alternative for advanced users: a real **Debian** userland on Android, built from the
[KotlinApkTemplate](https://github.com/StringManolo/KotlinApkTemplate) and compiled entirely by GitHub Actions.

- Debian runs under **proot** (the `termux/proot` fork), not inside a Termux-style prefix.
- The Debian rootfs is the official `debuerreotype` tarball (same one as the Docker image), shipped inside the APK.
- Terminal emulation reuses Termux's `terminal-emulator` / `terminal-view`, with an extra-keys bar
  (ESC, TAB, CTRL, ALT, HOME, END, PGUP, PGDN, arrows; tap a modifier = one-shot, long-press = lock).
- `targetSdk 28` on purpose: newer targets cannot exec binaries from the app's data directory (W^X).

## First run, environments and modes (⚙ button, always available)

Nothing is installed or started until you choose. The first launch opens the settings screen.

|            | **Normal**                            | **Ephemeral**                                  | **VM**                                           |
|------------|---------------------------------------|------------------------------------------------|--------------------------------------------------|
| **System** | proot on your installed Debian        | proot on a temp copy of it, wiped on exit      | QEMU, installed tree streamed into guest RAM     |
| **LiveCD** | proot on a persistent extraction      | fresh extraction every start, wiped on exit    | QEMU, shipped image streamed into guest RAM      |

**Modes never interfere with each other's storage**

- `System` (`files/rootfs/system`) is written only by System + Normal. It is installed only when you choose
  System + Normal and press Apply.
- `LiveCD persistence` (`files/rootfs/livecd-persist`) is written only by LiveCD + Normal.
- Ephemeral uses `files/eph/*` and wipes it on exit (and on the next launch if the app was killed).
- VM writes nothing to disk. System + Ephemeral/VM only *read* the installed System and refuse to run if it is
  not installed (they never install it).
- The one explicit cross-access: a LiveCD session in a proot mode may bind the installed System at `/mnt/system`
  (read-write, opt-in in the settings), like a live CD mounting the hard disk to repair or back it up.
  Not available in VM mode.

**Ephemeral is disk, not RAM**: flash storage (F2FS, wear levelling) may keep remnants. **VM** is the only mode
with no disk traces: `qemu-system-aarch64` in TCG mode (no KVM for apps) boots a tiny initramfs that receives the
rootfs over a virtio-console port into a tmpfs. Much slower than proot; pick RAM and cores in the settings.
VM mode is optional at build time (`with-vm` in the workflow); the settings screen says what is missing if it is
unavailable and shows a diagnostics block.

## Window and appearance

- **Fullscreen** or **Floating window** (needs "Display over other apps"). The floating window can be dragged
  anywhere (even partly off-screen), resized freely (◢ corner), rotated (↻: 90°, long-press 15°), pinned (PIN locks
  position/size/rotation and is remembered), made transparent (◐ slider) and minimized (—): while minimized the
  window is gone and the notification offers *Restore*, *Settings* and *Exit*. Tap the terminal to give the window
  the keyboard; tap outside to release it. Non-right-angle rotations leave transparent corners that still capture
  touches (Android limitation).
- **Appearance** (applies instantly, no restart): colour scheme (Dark, Solarized, Dracula, Nord, Gruvbox, Matrix,
  Light), font size and bold. Pinch-to-zoom also changes the font size.

## Build

Push to GitHub. `.github/actions/prepare-assets` downloads proot (and QEMU) from the Termux apt repo
(sha256-verified), builds the VM kernel/initramfs from Debian packages and fetches the Debian rootfs; then Gradle
builds the APK. Hashes and URLs are uploaded as the `provenance` artifact.

Android 12+ may kill child processes ("phantom process killer"); disable it if sessions die in the background.

## Status

Written without a local toolchain: expect to fix compile/runtime issues from the first Actions logs.
See `TODO.md`.
