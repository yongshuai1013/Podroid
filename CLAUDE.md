# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Is This

Podroid is an Android app that runs a rootless Alpine Linux VM via QEMU (ARM64) to provide a Podman container runtime without root. It embeds a Termux-based VT100 terminal emulator and communicates with the VM over QEMU serial/unix sockets.

## Build Commands

All components are coordinated by `build-all.sh`:

```bash
./build-all.sh kernel      # Build custom kernel only (podroid_kernel.config, ~5–10 min)
./build-all.sh initramfs   # Build custom kernel + Alpine initramfs (~10–15 min total)
./build-all.sh qemu        # Build QEMU + podroid-bridge via Docker (~30 min first time)
./build-all.sh termux      # Build libtermux.so via local NDK (16KB page alignment)
./build-all.sh apk         # Build Android APK via Gradle
./build-all.sh all         # Build everything
./build-all.sh deploy      # Full deploy workflow
./build-all.sh test        # Boot validation: deploys APK, polls console.log for "Ready!"
```

`podroidKernelVersion` in `gradle.properties` controls the Linux version downloaded. Docker layer-caches the kernel build; only rebuilds if `podroid_kernel.config` or the version changes.

**APK only:**
```bash
./gradlew assembleDebug
./gradlew installDebug
```

**Monitor VM boot:**
```bash
adb logcat -s PodroidQemu
adb shell run-as com.excp.podroid.debug cat files/console.log
```

The app uses debug build for all releases (release signing not configured). Native binaries require 16KB page alignment (`-Wl,-z,max-page-size=16384`) — mandatory on Android 13+.

## Architecture

### Data Flow

```
Terminal UI (Compose)
    ↕ TerminalSession (Termux JNI)
libpodroid-bridge.so  ←→  terminal.sock + ctrl.sock  ←→  QEMU  ←→  hvc0 + hvc1 (Alpine)

PodroidQemu (boot monitor) ←→ serial.sock ←→ QEMU ←→ ttyAMA0 (boot log only)
```

The terminal layer uses three QEMU Unix sockets, each with a single role:

- **`terminal.sock`** ↔ virtio-console `/dev/hvc0` — primary terminal I/O. getty runs on hvc0; `libpodroid-bridge.so` relays it to a Termux PTY.
- **`ctrl.sock`** ↔ virtio-console `/dev/hvc1` — resize signal channel. Bridge debounces SIGWINCH bursts (~25 per keyboard slide) and writes a single `RESIZE rows cols\n`; the resize daemon in `init-podroid` stty's hvc0.
- **`serial.sock`** ↔ PL011 `/dev/ttyAMA0` — boot log sink. `PodroidQemu.monitorBootSerial` connects for the lifetime of the VM, streaming kernel + init output into `console.log` and the boot-stage detector. No bridge handoff.
- **`qmp.sock`** — runtime control for port forwarding (`QmpClient` sends `netdev_add` / `netdev_remove`).

### Key Components

**`engine/PodroidQemu.kt`** — the core singleton. Builds and launches the QEMU process, monitors boot output from serial, manages `TerminalSession` lifecycle, and auto-starts `podroid-bridge` after boot. State machine: `Idle → Starting → Running → Stopped` (see `VmState.kt`).

**`service/PodroidService.kt`** — foreground service that owns the QEMU process. Holds a `WakeLock`, updates the persistent notification with boot stages, and handles graceful stop when the app is swiped from recents.

**`podroid-bridge.c`** — native C binary (compiled to `libpodroid-bridge.so`) that Termux runs as a subprocess. Bidirectionally relays PTY ↔ `terminal.sock`, and writes `RESIZE rows cols\n` to `ctrl.sock` after a 200 ms-debounced SIGWINCH burst (so a single keyboard-slide animation produces one shell prompt redraw, not 25).

**`init-podroid`** — two-phase Alpine init script embedded as an asset. Phase 1 mounts overlayfs over `storage.img`; Phase 2 sets up cgroup v2, SLIRP networking, Podman, and Dropbear SSH, then prints the boot stage markers consumed by `PodroidQemu`.

**`data/repository/`** — `SettingsRepository` (RAM, CPUs, storage size, theme, SSH toggle) and `PortForwardRepository` (persistent rules) both use Jetpack DataStore. No database.

### Asset Extraction

`PodroidApplication.kt` copies `qemu/`, `vmlinuz-virt`, and `initrd.img` from APK assets to `filesDir` on startup (size-checked to skip if unchanged). All native binaries in `jniLibs/arm64-v8a/` are extracted automatically by Android.

### Navigation

Single-activity Compose app: `NavGraph.kt` routes `setup → home → terminal / settings`. The setup screen only appears until setup is marked complete in DataStore.

## Native Binaries in `jniLibs/arm64-v8a/`

| File | What it is |
|---|---|
| `libqemu-system-aarch64.so` | QEMU TCG emulator (PIE executable, 16KB aligned) |
| `libpodroid-bridge.so` | PTY ↔ virtio-console relay (PIE executable, 16KB aligned) |
| `libtermux.so` | Termux terminal emulator JNI (rebuilt for 16KB pages) |
| `libslirp.so` | SLIRP user-mode networking (statically linked into QEMU) |

These are executed directly via `ProcessBuilder` / `TerminalSession`, not loaded as JNI libraries despite the `.so` extension.

## QEMU Command Construction

`PodroidQemu.buildCommand()` constructs the full QEMU cmdline including:
- `-serial unix:serial.sock` (boot log) + a virtio-serial-pci bus carrying two virtconsoles: `terminal.sock` (org.podroid.term, hvc0) and `ctrl.sock` (org.podroid.ctrl, hvc1)
- `-qmp unix:qmp.sock` for runtime port-forward changes
- RAM, CPU count, and user-editable extras (`-cpu`, `-accel`, RNG, etc.) from `SettingsRepository`
- Virtio block device backed by `storage.img` (resized on first boot to configured size)
- SLIRP networking with runtime port forwards via `QmpClient` (`netdev_add`/`netdev_remove` over `qmp.sock`)

All socket and image paths are under `context.filesDir`.

## Performance Tuning (TCG, no KVM)

All tuning applies under software emulation only — KVM is impossible without root.

**In `PodroidQemu.buildCommand()`:**
- `tcg,thread=multi` — one host thread per vCPU (already enabled)
- `tb-size`: 256 MB for <2 GB RAM, 512 MB for ≥2 GB — larger TB cache reduces re-translation for JIT-heavy workloads (Node, JVM in containers)
- `iothread=iothread0` on `virtio-blk-pci` — dedicated I/O thread decoupled from vCPU threads; biggest single win for container image pull/extraction
- `mitigations=off` in guest kernel cmdline — safe inside TCG VM (speculative execution attacks can't cross the emulated ISA boundary); 5–15% CPU gain
- `elevator=mq-deadline` in guest kernel cmdline — request merging for virtio-blk random writes (Podman overlay graph driver)
**In `init-podroid`:**
- ZRAM swap at half physical RAM using lz4 — gives 1.5–2× effective memory with near-zero I/O cost; swapon at priority 100 so it's preferred over any file swap

**In `Dockerfile` (QEMU build):**
- QEMU 11.0.0-rc2 cross-compiled against NDK r27c with three minimum patches:
  `--disable-plugins` (uftrace.c uses `timespec_get`, absent from Bionic),
  empty `contrib/ivshmem-{server,client}/meson.build` (use shm_open we don't ship),
  `-include shm_shim.h` declarations + a `libshm.a` stub implementing
  `shm_open`/`shm_unlink` via `memfd_create` (NDK API-28 stubs lack them).
- Custom Linux 6.6.87 kernel: arm64 defconfig + `podroid_kernel.config` (modules)
  + `forced_builtin.config` (=y for IPV6 / BRIDGE / NF_TABLES / NFT_COMPAT /
   MASQUERADE family / VETH / TUN / OVERLAY_FS / FUSE_FS).
- A build-time loop greps the resolved `.config` and **fails the build** if
  any of those critical options isn't `=y`. This guards against silent
  Kconfig demotions caused by unmet tristate deps (e.g. CONFIG_BRIDGE caps
  out at IPV6's strength).

**What won't work without root:** `io_uring` (Android 12+ seccomp block), CPU affinity/taskset, KSM memory dedup, TAP networking, huge pages on the host.
