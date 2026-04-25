# Podroid

<p align="center">
  <img src="https://img.shields.io/github/stars/ExTV/Podroid?style=for-the-badge" alt="Stars">
  <img src="https://img.shields.io/github/forks/ExTV/Podroid?style=for-the-badge" alt="Forks">
  <img src="https://img.shields.io/github/downloads/ExTV/Podroid/total?style=for-the-badge" alt="Downloads">
</p>

<p align="center">
  <strong>Run Linux containers on your Android phone — no root required.</strong>
</p>

<p align="center">
  <a href="https://github.com/ExTV/Podroid/releases">
    <img src="https://img.shields.io/github/v/release/ExTV/Podroid?include_prereleases&style=flat-square&label=Download" alt="Download">
  </a>
  <a href="https://github.com/ExTV/Podroid/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/ExTV/Podroid?style=flat-square" alt="License">
  </a>
</p>

---

Podroid spins up a lightweight Alpine Linux 3.23 VM using QEMU 11.0.0-rc2 and a custom Linux 6.6.87 kernel. It gives you a fully working [Podman](https://podman.io) container runtime with a built-in terminal. Install the APK, tap Start, and you're running containers in seconds.

---

## Features

### Container Runtime
- **Rootless Podman** — Full Docker-compatible container runtime with no root access required
- **Custom Kernel** — Optimized Linux 6.6.87 kernel with netfilter, bridge, and overlayfs built-in for maximum performance
- **Persistent Storage** — Packages, configurations, and images survive restarts via persistent ext4 storage and overlayfs
- **Out-of-the-box Networking** — Internet access enabled via QEMU SLIRP; proper MASQUERADE support for container networking

### Terminal
- **xterm-256color Emulation** — Powered by Termux TerminalView v0.118.1
- **Real PTY** — Proper job control, signal handling, and escape sequence support
- **114 Color Themes** — Dracula, Nord, Solarized, Tokyo Night, Catppuccin, Gruvbox, and more
- **13 Curated Fonts** — JetBrains Mono, Fira Code, Cascadia Code, Source Code Pro, Hack, and more
- **Full Mouse Support** — CSI mouse tracking for btop, htop, mc, vim, and other TUI apps
- **Extra Keys Bar** — ESC, TAB, CTRL, ALT (sticky), arrows, F1–F12, and common symbols
- **Auto-resize** — Optimized resize daemon with debounced SIGWINCH signals to stop cursor flashing during keyboard animations

### Networking
- **Port Forwarding** — Expose VM services to your Android device via runtime QMP control
- **Built-in SSH** — Dropbear server running on port 9922 (configurable)

### Performance
- **ARM64-native** — Executes directly on your device's CPU using QEMU TCG
- **Multi-core Support** — Configure 1-8 CPU cores (tcg-thread=multi)
- **Allocatable RAM** — 512MB to 4GB configurable; ZRAM swap enabled in guest for 2x effective memory
- **16KB Page Support** — Optimized for newer Android devices (Pixel 8/9/10+) with 16KB system pages

### Storage
- **2–64 GB** — Configurable persistent storage
- **Downloads Sharing** — Mount Android Downloads folder via virtio-9p with optimized msize

---

## Quick Start

1. **Download** the latest APK from [Releases](https://github.com/ExTV/Podroid/releases)
2. **Install** and open Podroid
3. **Tap Start VM** — Boot progress shows via an animated tracker and notification
4. **Wait ~10-15 seconds** for "Ready" status (as fast as 6s on high-end devices)
5. **Tap Open Terminal** to access the shell
6. **Run containers:**

```sh
# Hello world
podman run --rm alpine echo hello

# Interactive shell
podman run --rm -it alpine sh

# Run Nginx
podman run -d -p 8080:80 nginx

# List running containers
podman ps -a
```

---

## Requirements

| | |
|---|---|
| **Device** | ARM64 Android device (most phones from 2018+) |
| **Android** | Min SDK 28 (Android 9.0+), Target SDK 36. |
| **Storage** | ~200 MB app + VM disk size |

---

## How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                        Android App                          │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐      │
│  │   Service   │  │   QEMU 11    │  │   Compose UI   │      │
│  │  (WakeLock) │  │   (TCG)      │  │ Terminal View  │      │
│  └──────┬──────┘  └──────┬───────┘  └───────┬────────┘      │
└─────────┼────────────────┼──────────────────┼───────────────┘
          │                │                  │
          │         ┌──────▼───────┐   ┌──────▼────────┐
          │         │ Alpine 3.23  │   │  Podroid      │
          └────────►│  + Podman    │◄──│  Bridge       │
            Boot    │  + Kernel 6.6│   │ (virt-console)│
            Stages  └──────────────┘   └───────────────┘
```

**Boot Sequence:**
1. QEMU loads custom Linux kernel + initramfs
2. Phase 1 init mounts persistent storage.img as overlayfs
3. Phase 2 configures networking, Podman, and starts the resize daemon
4. **Three-channel I/O**:
    - `serial.sock`: Kernel/boot log stream for the monitor (ttyAMA0)
    - `terminal.sock`: Primary shell I/O for the bridge (virtio-console hvc0)
    - `ctrl.sock`: Out-of-band resize signals (virtio-console hvc1)

---

## Building from Source

### Prerequisites

- Docker (for VM initramfs, kernel, and QEMU builds)
- Android NDK r27c (for bridge/Termux native builds)
- Android SDK

### Build Steps

```bash
# Clone the repository
git clone https://github.com/ExTV/Podroid.git
cd Podroid

# 1. Build custom kernel (~10min, cached)
./build-all.sh kernel

# 2. Build VM initramfs (~5min)
./build-all.sh initramfs

# 3. Build QEMU + bridge via Docker (~30min first time)
./build-all.sh qemu

# 4. Build Termux JNI lib (local NDK)
./build-all.sh termux

# 5. Build and install the APK
./gradlew installDebug
```

---

## Architecture

### Project Structure

```
Podroid/
├── app/                          # Android application (Compose)
│   └── src/main/
│       ├── java/                 # Kotlin source (Engine, UI, Data)
│       ├── jniLibs/              # Native binaries (.so renamed ELF)
│       └── assets/               # Kernel, initramfs, fonts, themes
├── init-podroid                  # Two-phase VM bootstrap script
├── podroid-bridge.c              # PTY ↔ virtio-console socket relay
├── build-all.sh                  # Unified build-everything script
├── Dockerfile                    # Multi-stage CI/CD build pipeline
├── podroid_kernel.config         # Custom Linux kernel configuration
└── gradlew                       # Gradle wrapper
```

### Native Components

| File | Description |
|------|-------------|
| `libqemu-system-aarch64.so` | QEMU TCG engine |
| `libpodroid-bridge.so` | Stateful PTY relay with SIGWINCH debouncing |
| `libtermux.so` | Terminal emulator JNI |
| `libslirp.so` | SLIRP user-mode networking |

---

## TODO

- [x] **Better QEMU performance** — TCG optimization, and performance profiling
- [x] **Better terminal integration** — Improve TUI app support (nvim, vim, less, nano) with proper CSI escape sequence handling and resize propagation
- [ ] **User account instead of root** — Run VM processes as a non-root user for improved security
- [ ] **Docker socket compatibility** — Proper service scripts for `rc-service` and `docker` commands

---

## Contributing

Contributions are welcome! Please read the [skill.md](skill.md) for complete project context before submitting PRs.

### For AI Assistants

Give AI assistants full context on Podroid:

```markdown
Read the skill.md file at: /path/to/Podroid/skill.md
```

The skill file includes complete architecture, all source files, build commands, known issues, and settings.

---

## Credits

| Project | Purpose |
|---------|---------|
| [QEMU](https://www.qemu.org) | Machine emulation and virtualization |
| [Alpine Linux](https://alpinelinux.org) | Lightweight VM base |
| [Podman](https://podman.io) | Rootless container runtime |
| [Termux](https://github.com/termux/termux-app) | Terminal emulator for Android |
| [Limbo PC Emulator](https://github.com/limboemu/limbo) | QEMU on Android groundwork |

---

## License

[GNU General Public License v2.0](LICENSE)
c License v2.0](LICENSE)
2.0](LICENSE)

