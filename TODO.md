# TODO

- [ ] First green Actions build (fix compile errors; Termux terminal API signatures were written from memory)
- [ ] Verify proot starts Debian on a real device (seccomp / `PROOT_NO_SECCOMP` fallback)
- [ ] Verify VM mode on a real device (kernel image format, virtio modules, TCG JIT permissions)
- [x] Floating window: drag, free resize, rotate, pin, opacity, minimize to notification (needs on-device testing)
- [x] Theme settings (colour scheme, font size, bold)
- [ ] Install other rootfs: import any tarball / URL + small catalog (Ubuntu, Alpine, Arch ARM...)
- [ ] Simulated "partitions": soft size quotas per volume
- [ ] Compressed system (tar.zst at rest, unpack on start)
- [ ] VM: propagate terminal size to the guest
- [ ] Optionally build proot from source in the workflow instead of using the Termux package
