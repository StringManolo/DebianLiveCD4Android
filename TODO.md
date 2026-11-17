# TODO

- [ ] First green Actions build (fix compile errors; Termux terminal API signatures were written from memory)
- [ ] Verify proot starts Debian on a real device (seccomp / `PROOT_NO_SECCOMP` fallback)
- [ ] Verify VM mode on a real device (kernel image format, virtio modules, TCG JIT permissions)
- [x] Floating window: drag, free resize, rotate, pin, opacity, minimize to notification (needs on-device testing)
- [x] Theme settings (colour scheme, font size, bold)
- [x] Multiple systems: import / download / export / snapshots (needs on-device testing)
- [x] Encryption + compression at rest, PIN, scheduled wipe, privacy and network options, updates (needs on-device testing)
- [ ] Optional build without the INTERNET permission
- [ ] Simulated size quotas per system
- [ ] VM: propagate terminal size to the guest
- [ ] Optionally build proot from source in the workflow instead of using the Termux package
- [ ] Add a "workflow" panel in settings to run from a file, save, manage, etc. This workflows are run over the base system selected, (it's an external install script to install tools, repos, etc)
- [ ] Fork this app, to ship new apps that run the terminal in the background, hidden service, etc. (This allows to easily ship full CLI terminal apps in very small APKs, probably smaller than native. Similar to what Electron does, but for CLI apps -Can also ship with webview-, need to expose a way to create Native UI or webview UI to the hidden terminal to automate / show)
