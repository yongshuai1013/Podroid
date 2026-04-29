<div align="center">

<img src="https://github.com/ExTV.png" width="96" height="96" alt="Podroid" style="border-radius: 24px" />

# Podroid

**Linux containers on Android. No root required.**

A real Alpine Linux VM, a real Linux kernel, and rootless Podman, all in a single APK.

<p>
  <a href="https://github.com/ExTV/Podroid/releases"><img src="https://img.shields.io/github/v/release/ExTV/Podroid?include_prereleases&style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="https://github.com/ExTV/Podroid/releases"><img src="https://img.shields.io/github/downloads/ExTV/Podroid/total?style=flat-square&color=brightgreen" alt="Downloads" /></a>
  <a href="https://github.com/ExTV/Podroid/stargazers"><img src="https://img.shields.io/github/stars/ExTV/Podroid?style=flat-square&color=yellow" alt="Stars" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ExTV/Podroid?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/platform-Android%209%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 9+" />
  <img src="https://img.shields.io/badge/arch-arm64-orange?style=flat-square" alt="arm64" />
</p>

<a href="https://extv.github.io/Podroid/"><strong>Website</strong></a> ·
<a href="https://github.com/ExTV/Podroid/releases/latest"><strong>Download</strong></a> ·
<a href="#-quick-start"><strong>Quick Start</strong></a> ·
<a href="#-architecture"><strong>Architecture</strong></a> ·
<a href="#-building-from-source"><strong>Build</strong></a>

</div>

---

## Why Podroid

Termux gives you a Linux shell on Android, but it runs on the host kernel, and the namespaces, cgroups, and netfilter rules that real containers need are not there. Google's new Terminal app uses the Android Virtualization Framework, which is locked to recent Pixel devices and ships without a container runtime. `proot` is a chroot emulation; most Docker Hub images do not work reliably.

**Podroid runs an actual Alpine Linux VM with its own purpose-built Linux 6.6 kernel.** Every OCI image runs unmodified, every `podman` flag works, and your changes (`apk add`, `rc-update add`, custom configs) persist across reboots in a writable overlay.

|                                | **Podroid**     | Termux + proot  | Google Terminal | KVM / chroot           |
| ------------------------------ | --------------- | --------------- | --------------- | ---------------------- |
| Real Linux kernel              | ✅ (custom 6.6) | ❌              | ✅ (Debian)     | depends                |
| Rootless Podman                | ✅              | ⚠️ unreliable    | ❌ manual       | ✅                     |
| Works on stock Android 9+      | ✅              | ✅              | ❌ (Pixel only) | ❌ (root + recovery)   |
| OCI images run unmodified      | ✅              | ❌              | ⚠️ manual setup  | ✅                     |
| Persistent VM state            | ✅              | n/a             | ✅              | ✅                     |
| Zero post-install setup        | ✅              | ❌              | ❌              | ❌                     |

---

## ✨ Features

<table>
<tr>
<td width="33%" valign="top">

### Real Linux, real Podman

- Standard Alpine 3.23 root filesystem
- **OpenRC as PID 1**: `apk add docker; rc-service docker start` just works
- Custom Linux 6.6.87 kernel with overlayfs, netfilter, bridge, FUSE, and 9p built-in
- Rootless Podman + crun + netavark + slirp4netns
- Persistent ext4 overlay survives reboots

</td>
<td width="33%" valign="top">

### Built-in terminal

- xterm-256color emulator (Termux engine)
- 114 color themes · 13 curated fonts
- CSI mouse tracking: `btop`, `htop`, `mc`, `nvim`, `tmux` work out of the box
- Customizable extra-keys bar (ESC, TAB, CTRL, ALT, F1–F12, arrows, symbols)
- Auto-resize with debounced SIGWINCH so the keyboard slide never flashes the prompt

</td>
<td width="33%" valign="top">

### Networking & SSH

- SLIRP user-mode networking: Internet access, no host changes
- Live port-forward editor (host ↔ guest) over QEMU QMP
- Built-in Dropbear SSH on `127.0.0.1:9922` (toggleable)
- MASQUERADE / bridge built into the kernel for full container networking
- Optional Downloads-folder share via virtio-9p

</td>
</tr>
<tr>
<td valign="top">

### Adaptive UI

- Material 3 with dynamic color
- Phone, tablet, and landscape layouts
- Animated boot-progress indicator
- Live VM controls + status from a foreground service
- In-app update checker

</td>
<td valign="top">

### Performance

- QEMU TCG with `thread=multi`, 256–512 MB translation cache
- Dedicated I/O thread on virtio-blk (massive win for image pulls)
- ZRAM swap at 50 % of guest RAM (lz4) → ~2× effective memory
- `mitigations=off` in guest kernel: safe under TCG, ~5–15 % faster
- 16 KB-page-aligned native binaries (Pixel 8/9/10+ ready)

</td>
<td valign="top">

### Configurable

- 1, 2, 4, 6, 8 vCPUs · 512 MB – 4 GB RAM
- 2 GB – 64 GB persistent storage
- Editable QEMU `-cpu` / `-accel` / `-machine` flags
- Editable guest kernel command line
- Per-app font size, theme, font, haptics, extras-bar layout

</td>
</tr>
</table>

---

## 🚀 Quick Start

1. Download the latest APK from the [Releases page](https://github.com/ExTV/Podroid/releases/latest).
2. Install and open Podroid. The first launch extracts the kernel, initramfs, and Alpine squashfs to app storage (~100 MB, one-time).
3. Tap **Start VM**. Boot completes in 6–15 seconds depending on device.
4. Tap **Open Terminal** when the indicator turns to *Ready*.

```bash
# Hello world
podman run --rm alpine echo hello

# Web server, accessible from Android browser at http://127.0.0.1:8080
podman run -d -p 8080:80 nginx

# Rootless interactive
adduser -G wheel dev   # then: doas su - dev
podman run --rm -it alpine sh
```

Default credentials: **root / podroid**. Change with `passwd`. Create a regular user with `adduser -G wheel <name>`; wheel-group members can use `doas` and `sudo`.

---

## 📋 Requirements

|              |                                                                  |
| ------------ | ---------------------------------------------------------------- |
| Architecture | ARM64 only (`aarch64`)                                           |
| OS           | Android 9.0+ (API 28) · target API 36                            |
| Storage      | ~200 MB app + chosen VM disk size (default 2 GB, max 64 GB)      |
| Memory       | 2 GB device RAM recommended (the VM defaults to 512 MB allocation) |

---

## 🏗 Architecture

```
                         ┌──────────────────────────────────────────────┐
                         │                  Android App                 │
   Compose UI            │  ┌──────────────┐  ┌────────────────────┐    │
  Setup ▸ Home ▸         │  │ PodroidQemu  │  │ PodroidService     │    │
  Terminal ▸ Settings    │  │   (engine)   │  │ (foreground + WL)  │    │
                         │  └──────┬───────┘  └─────────┬──────────┘    │
                         └─────────┼────────────────────┼───────────────┘
                                   │                    │
                          spawns   │                    │ owns notification
                                   ▼                    ▼
                         ┌──────────────────────────────────────────────┐
                         │   QEMU 11.0.0-rc2  (TCG, ARMv8 virt machine) │
                         └──┬─────────────────┬─────────────────┬───────┘
                serial.sock │   terminal.sock │      ctrl.sock  │ qmp.sock
                  (boot)    │      (hvc0)     │     (hvc1)      │ (control)
                            ▼                 ▼                 ▼
                        ┌─────────────────────────────────────────────┐
                        │            Alpine Linux 3.23 VM             │
                        │   /sbin/init (busybox) ▸ /etc/inittab ▸     │
                        │              OpenRC (PID 1)                 │
                        │                                             │
                        │   /dev/vda  ext4 overlay (writable)         │
                        │   /dev/vdb  squashfs (read-only base)       │
                        │                                             │
                        │   podroid-bootstrap  podroid-network        │
                        │   podroid-resize     dropbear (optional)    │
                        │   podroid-ready  ▸ "Ready!" marker          │
                        │                                             │
                        │   Podman + crun + netavark + slirp4netns    │
                        └─────────────────────────────────────────────┘
```

### Boot pipeline

1. **PodroidApplication** copies kernel, initramfs, and Alpine squashfs from APK assets into `filesDir` on first launch (size-checked, idempotent).
2. **QEMU** launches with `-M virt`, two virtio-blk drives (`/dev/vda` writable ext4, `/dev/vdb` read-only squashfs), four Unix sockets, and the user-configured RAM / CPU count.
3. **`init-podroid`** (45 lines, runs in initramfs as PID 1) mounts the squashfs as the lower layer of an overlayfs, the ext4 image as the upper, and `switch_root`s into `/sbin/init`.
4. **OpenRC** brings up the system via three custom services on the squashfs:
   - `podroid-bootstrap`: cgroup v2, devpts/shm/mqueue, sysctl, ZRAM, mount-propagation tweaks for rootless containers
   - `podroid-network`: eth0 up, `10.0.2.15/24`, `/etc/resolv.conf`
   - `podroid-ready`: emits the boot-stage markers consumed by the Android UI
5. **Android UI** sees `Ready!` on `serial.sock` → state = *Running* → the bridge connects directly to virtio-console (`/dev/hvc0`) and the terminal becomes interactive.

> Why `switch_root` and not `chroot`? `setns(CLONE_NEWNS)` inside `crun exec` resets `fs->root`, so a chroot pivot left `podman exec -it` looking at raw kernel paths and failing to open `/dev/ptmx`. `switch_root` reorganizes the kernel mount tree itself, so namespace operations land on a clean `/`. ([fix details](https://github.com/ExTV/Podroid/issues/17))

### Project layout

```
Podroid/
├── app/                                  Android application (Jetpack Compose, Hilt)
│   └── src/main/
│       ├── java/com/excp/podroid/
│       │   ├── engine/                   PodroidQemu, QmpClient, VmState
│       │   ├── service/                  PodroidService (foreground)
│       │   ├── data/repository/          DataStore-backed settings & port forwards
│       │   └── ui/                       Compose screens + theme
│       ├── jniLibs/arm64-v8a/            QEMU, podroid-bridge, libslirp, libtermux
│       └── assets/                       kernel, initramfs, squashfs, fonts, themes
├── init-podroid                          Minimal initramfs script (~45 lines)
├── podroid-bridge.c                      Native PTY ↔ virtio-console relay
├── Dockerfile                            Kernel + initramfs + QEMU build pipeline
├── build-rootfs/                         Alpine squashfs build pipeline
│   ├── Dockerfile.rootfs
│   ├── build-rootfs.sh
│   └── files/                            OpenRC services + helpers baked into the squashfs
├── build-all.sh                          Unified build / deploy script
├── podroid_kernel.config                 Custom kernel Kconfig fragment
└── docs/                                 GitHub Pages site (extv.github.io/Podroid)
```

### Native components

| Binary                      | Purpose                                                     |
| --------------------------- | ----------------------------------------------------------- |
| `libqemu-system-aarch64.so` | QEMU TCG engine (ELF executable shipped as a `.so`)         |
| `libpodroid-bridge.so`      | PTY ↔ virtio-console relay with debounced SIGWINCH          |
| `libtermux.so`              | Termux terminal emulator JNI (rebuilt for 16 KB pages)      |
| `libslirp.so`               | SLIRP user-mode networking, statically linked into QEMU     |

All native binaries are built with `-Wl,-z,max-page-size=16384` for compatibility with 16 KB-page Android devices (mandatory on Android 13+).

---

## 🔧 Building from source

### Prerequisites

- Docker 20.10+ (for kernel, initramfs, rootfs, and QEMU builds)
- Android NDK r27c (for the bridge and Termux native libs)
- Android SDK with platform 36 + build-tools

### Build pipeline

```bash
git clone https://github.com/ExTV/Podroid.git
cd Podroid

./build-all.sh kernel       # ~5–10 min, Docker-cached
./build-all.sh initramfs    # kernel + minimal initramfs
./build-all.sh rootfs       # Alpine squashfs (~30 s, Docker-cached)
./build-all.sh qemu         # ~30 min first run, fully cached after
./build-all.sh termux       # local NDK build of libtermux.so
./gradlew installDebug      # build + install the APK on a connected device
```

Or just:

```bash
./build-all.sh all          # everything above, in order
./build-all.sh deploy       # build + install + launch
./build-all.sh test         # boot validation: deploys APK, polls console.log for "Ready!"
```

### Inspecting a running VM

```bash
adb logcat -s PodroidQemu                                                       # engine logs
adb shell run-as com.excp.podroid.debug cat files/console.log                   # full VM serial log
ssh root@127.0.0.1 -p 9922                                                      # if SSH is enabled (password: podroid)
```

---

## 🗺 Roadmap

- [x] Zero-config **Docker** and **LXC** (in addition to Podman) ✅ *1.1.7*
- [x] `/proc/config.gz` shipped + complete container-kernel feature matrix ✅ *1.1.7*
- [x] OpenRC as PID 1: `apk add docker; rc-service docker start` works and persists ✅ *1.1.6*
- [x] Adaptive layouts for tablets and landscape phones ✅ *1.1.6*
- [x] Issue #17: `podman exec -it` works rootful and rootless ✅ *1.1.6*
- [x] Custom Linux 6.6 kernel with all required options forced built-in ✅ *1.1.4*
- [x] virtio-console terminal channel separate from boot serial ✅ *1.1.4*
- [ ] User-loadable terminal fonts (drop a `.ttf` into a folder, picker auto-discovers)
- [ ] Optional KVM acceleration on devices that expose it

Have an idea? [Open an issue](https://github.com/ExTV/Podroid/issues/new).

---

## 🤝 Contributing

Pull requests are welcome. Before opening a PR, please read [**CONTRIBUTING.md**](CONTRIBUTING.md) and skim [**skill.md**](skill.md). It documents the boot pipeline, every native binary, and the design quirks you need to know to make changes that don't regress.

Found a bug? Please include the contents of `console.log`:

```bash
adb shell run-as com.excp.podroid.debug cat files/console.log
```

### For AI assistants

If you're using Claude Code, Cursor, Copilot, or another assistant, point it at [`skill.md`](skill.md) before making any change. It covers the full architecture, every constant, every quirk, and every common task.

---

## 🙏 Credits

Podroid stands on the shoulders of giants:

| Project                                                              | Role                                                |
| -------------------------------------------------------------------- | --------------------------------------------------- |
| [QEMU](https://www.qemu.org)                                         | Machine emulation, the heart of the VM              |
| [Linux](https://kernel.org)                                          | Custom 6.6.87 kernel                                |
| [Alpine Linux](https://alpinelinux.org)                              | Tiny, fast Linux distribution                       |
| [Podman](https://podman.io) · [crun](https://github.com/containers/crun) | Rootless container runtime                      |
| [Termux](https://github.com/termux/termux-app)                       | Terminal emulator engine                            |
| [Limbo PC Emulator](https://github.com/limboemu/limbo)               | Original groundwork for QEMU on Android             |

See [CREDITS.md](CREDITS.md) for the full list of upstream projects, libraries, and contributors.

---

## 📄 License

Podroid is released under the [GNU General Public License v2.0](LICENSE). QEMU, the Linux kernel, Alpine, and Podman are each distributed under their respective upstream licenses.

<div align="center">
<sub>Built with care by <a href="https://github.com/ExTV">@ExTV</a> · <a href="https://extv.github.io/Podroid/">extv.github.io/Podroid</a></sub>
</div>
