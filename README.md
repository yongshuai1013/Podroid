# Podroid

**Run Linux containers on your Android phone — no root required.**

Podroid spins up a lightweight Alpine Linux VM using QEMU and gives you a fully working [Podman](https://podman.io) container runtime with a built-in terminal. Install the APK, tap Start, and you're running containers in under a minute.

---

## Features

### Container Runtime
- **Podman** with crun, netavark, and slirp4netns — no root daemon, rootless by default
- **Persistence** — packages, configs, and container images survive restarts via overlayfs
- **Internet access** out of the box via QEMU SLIRP networking
- `podman run --rm -it alpine sh` — works immediately after first boot

### Terminal
- Full VT100/xterm emulation via [Termux](https://github.com/termux/termux-app)'s TerminalView
- **Real PTY** — proper job control, signal handling, and escape sequences
- **114 color themes** — Dracula, Nord, Solarized, Tokyo Night, Catppuccin, Gruvbox, and 108 more
- **13 terminal fonts** — JetBrains Mono, Fira Code, Cascadia Code, Source Code Pro, Hack, and more
- **Mouse support** — full CSI mouse tracking for TUI apps (btop, htop, mc, vim)
- **Extra keys** — ESC, TAB, CTRL, ALT (sticky toggles), arrows, HOME, END, PGUP, PGDN, F1–F12, and common symbols
- **Auto-resize** — TUI apps (vim, btop, nano) update dimensions on keyboard open/close
- **Bell feedback** — haptic vibration on bell character

### Networking
- **Port forwarding** — expose VM services to your Android device with one tap
- **Protocol support** — TCP, UDP, or both
- **Runtime control** — add and remove forwards while the VM is running via QMP
- **Service presets** — one-tap setup for common services:
  - Pi-hole (DNS + web), Nginx, Gitea, Grafana
- **Built-in SSH** — connect from any SSH client on port 9922

### Storage
- **Configurable size** — 2, 4, 8, 16, 32, or 64 GB
- **Downloads sharing** — mount your Android Downloads folder into the VM via virtio-9p

### Performance
- **ARM64-native** — runs as aarch64 on your device's CPU via QEMU TCG
- **Multi-core** — configurable CPU count (1–N based on your device)
- **Allocatable RAM** — 512 MB default, adjustable to your workload
- **GICv3, MTTCG** — hardware acceleration for the emulated interrupt controller and multi-threaded TCG

---

## Quick Start

1. Install the APK from [Releases](https://github.com/ExTV/Podroid/releases)
2. Open Podroid and tap **Start Podman**
3. Wait ~20 seconds — boot progress shows in the notification
4. Tap **Open Terminal**
5. Run containers:

```sh
podman run --rm alpine echo hello
podman run --rm -it alpine sh
podman run -d -p 8080:80 nginx
```

---

## How It Works

```
Android App
├── Foreground Service          ← keeps the VM alive
├── PodroidQemu engine
│   ├── libqemu-system-aarch64  ← QEMU (TCG, no KVM)
│   ├── podroid-bridge          ← PTY ↔ serial.sock relay
│   └── QMP socket              ← port forwarding + VM control
└── Alpine Linux VM
    ├── initramfs (read-only base)
    ├── ext4 disk (persistent overlay)
    ├── Dropbear SSH (port 22)
    └── Podman + crun + netavark + slirp4netns
```

**Boot:** QEMU loads a Linux kernel + initramfs. A two-phase init mounts a persistent ext4 disk as an overlayfs upper layer. Everything you install or pull persists across reboots.

**Terminal:** Termux allocates a real PTY for the terminal session. A native bridge binary relays data between the PTY and QEMU's serial socket. Mouse events and resize signals flow through the same path, so TUI apps work correctly.

**Networking:** QEMU user-mode networking (SLIRP) gives the VM `10.0.2.15`. Port forwards are managed via QEMU's `-netdev hostfwd` at startup and QMP at runtime.

---

## Building from Source

Requires Docker with multi-arch support and Android SDK.

```sh
# Build the VM initramfs and kernel (requires Docker)
./docker-build-initramfs.sh

# Build QEMU and the terminal bridge binary (requires Docker)
./build-qemu-android.sh

# Build the APK
./gradlew assembleDebug
```

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## Requirements

- **arm64** Android device (most phones from 2018 onward)
- Android **8.0+** (API 26)
- ~150 MB storage for the app, plus your chosen VM disk size

---

## Credits

- [QEMU](https://www.qemu.org) — machine emulation
- [Alpine Linux](https://alpinelinux.org) — VM base
- [Podman](https://podman.io) — container runtime
- [Termux](https://github.com/termux/termux-app) — terminal emulator
- [Limbo PC Emulator](https://github.com/limboemu/limbo) — QEMU on Android groundwork

---

## License

GNU General Public License v2.0
