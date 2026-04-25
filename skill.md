# Podroid AI Context

> **Last updated:** 2026-04-25
> **Current version:** 1.1.5 (versionCode 17)
> **Purpose:** Complete project context for AI-assisted development. Read this before touching any file.

---

## Project Overview

**Podroid** is an Android app that runs rootless Podman containers on arm64 Android devices with no root required. It spins up a headless Alpine Linux 3.23 VM using QEMU TCG and exposes a serial console terminal inside the app.

- **Package:** `com.excp.podroid` (debug: `com.excp.podroid.debug`)
- **Min SDK:** 28 (Android 9.0)  
- **Target SDK:** 36
- **Architecture:** AArch64 only (no x86/x86_64)
- **Root project name in Gradle:** `VirtuDroid`
- **GitHub:** https://github.com/ExTV/Podroid

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (constructor injection, no @Provides) |
| Async | Kotlin Coroutines + StateFlow |
| Persistence | DataStore Preferences |
| Terminal | Termux TerminalView v0.118.1 (JitPack AAR) |
| VM | QEMU 11.0.0-rc2 TCG (no KVM) |
| Container Runtime | Podman + crun + netavark + slirp4netns |
| VM Init | Custom two-phase shell script (`init-podroid`) |
| VM Linux | Alpine Linux 3.23 aarch64 |

---

## Architecture

### High-Level Flow
```
Android App
├── PodroidApplication — extracts assets (vmlinuz, initrd, qemu/) on first run (size-checked to skip if unchanged)
├── PodroidService (foreground) — hosts QEMU, holds WakeLock, updates notification with boot stages
├── PodroidQemu (singleton)
│   ├── QEMU process (libqemu-system-aarch64.so — ELF binary renamed .so for APK)
│   ├── serial.sock     — ttyAMA0 boot-log sink; monitor stays connected for VM lifetime
│   ├── terminal.sock   — virtio-console hvc0; primary shell I/O for podroid-bridge
│   ├── ctrl.sock       — virtio-console hvc1; debounced RESIZE messages from bridge
│   └── qmp.sock        — QEMU Machine Protocol for runtime control (port forwarding)
└── Compose UI
    └── Screens: Setup → Home → Terminal / Settings
```

### VM Boot Sequence
1. QEMU starts and binds all four sockets (`server,nowait` so it doesn't block on a connection).
2. **Boot monitor** (`PodroidQemu.monitorBootSerial`) connects to `serial.sock` and streams everything kernel and init-podroid write to ttyAMA0 into `console.log` + the in-memory tail used by `detectBootStage`. Stays connected for the lifetime of the VM (no hand-off).
3. `init-podroid` Phase 1 (initramfs): mounts virtio-blk as ext4, creates overlayfs (`lowerdir=/`, `upperdir=/mnt/persist/upper`), pivots via `/bin/busybox chroot /mnt/overlay /init --main`.
4. `init-podroid` Phase 2 (`--main`): kernel modules (just `9p*` now — bridge / netfilter / overlay / fuse / virtio are all built-in) → networking → containers → SSH → starts getty on `/dev/hvc0`. Resize daemon listens on `/dev/hvc1`.
5. Phase 2 emits `boot_stage "Ready!"` immediately before launching the getty loop — no `sleep 2` is needed any more because the bridge connects to a different socket.
6. `detectBootStage("Ready!")` fires → `_state = Running` → `autoStartBridge()` posts to the main thread.
7. `TerminalSession` launches `libpodroid-bridge.so` with `terminal.sock` + `ctrl.sock` as args. Bridge connects directly; no socket hand-off, no 500 ms guard delay.

### No socket hand-off
The old PL011 path used a single-client `serial.sock` shared by the boot monitor and the bridge, which forced a `releaseSerial()` + delay dance. With the virtio-console split that constraint is gone. `releaseSerial()` only runs during `cleanup()` on VM stop now.

---

## Performance Tuning (TCG, no KVM)

All tuning applies under software emulation only — KVM is impossible without root.

**In `PodroidQemu.buildCommand()`:**
- `tcg,thread=multi` — one host thread per vCPU.
- `tb-size`: 256 MB for <2 GB RAM, 512 MB for ≥2 GB — larger Translation Block cache reduces re-translation for JIT-heavy workloads (Node, JVM in containers).
- `iothread=iothread0` on `virtio-blk-pci` — dedicated I/O thread decoupled from vCPU threads; biggest win for container image pull/extraction.
- `mitigations=off` in guest kernel cmdline — safe inside TCG VM (speculative execution attacks can't cross the emulated ISA boundary); 5–15% CPU gain.
- `elevator=mq-deadline` in guest kernel cmdline — request merging for virtio-blk random writes (Podman overlay graph driver).

**In `init-podroid`:**
- ZRAM swap at half physical RAM using lz4 — gives 1.5–2× effective memory with near-zero I/O cost; swapon at priority 100 so it's preferred over any file swap.

**In `Dockerfile` (QEMU build):**
- Currently `-fPIC -DANDROID` only (no LTO / -O3) — those previously caused link failures with QEMU 11.0.0-rc2 + NDK r27c. Three minimum patches make the build clean: `--disable-plugins`, empty `contrib/ivshmem-{server,client}/meson.build` stubs, and an `-include shm_shim.h` + `libshm.a` (memfd_create-backed) to satisfy `shm_open`/`shm_unlink`.

---

## Project Structure

### Root Level
```
/
├── app/                            # Android application module
├── init-podroid                    # VM init script (baked into initrd.img as /init)
├── podroid-bridge.c                # Native PTY↔virtio-console relay (compiled to libpodroid-bridge.so)
├── Dockerfile                      # Unified multi-stage: downloader → rootfs-builder → packer → qemu-builder → final
├── build-all.sh                    # Unified build+deploy script
├── gradle.properties               # podroidQemuVersion=11.0.0-rc2
├── build.gradle.kts, settings.gradle.kts
└── gradle/, gradlew
```

### app/src/main/java/com/excp/podroid/

```
├── MainActivity.kt                 # Single Activity, WindowSizeClass, Hilt entry point
├── PodroidApplication.kt           # Hilt @HiltAndroidApp, asset extraction on first run
│
├── engine/
│   ├── PodroidQemu.kt              # QEMU lifecycle, boot monitor, bridge session, resize
│   ├── QmpClient.kt                # QMP socket client (runtime port forwarding)
│   └── VmState.kt                  # Sealed: Idle|Starting|Running|Paused|Saving|Resuming|Stopped|Error
│
├── service/
│   └── PodroidService.kt           # Foreground service, WakeLock, notification with boot stages
│       └── Actions: ACTION_START, ACTION_STOP
│
├── data/repository/
│   ├── SettingsRepository.kt       # DataStore Preferences (all settings)
│   ├── PortForwardRepository.kt    # Port forward rules (Set<String> in DataStore)
│   └── UpdateRepository.kt         # GitHub releases API checker
│
├── di/
│   └── AppModule.kt                # Hilt module (minimal — constructor injection used everywhere)
│
└── ui/
    ├── navigation/
    │   └── NavGraph.kt             # Routes: SETUP, HOME, TERMINAL, SETTINGS
    │       └── TerminalViewModel scoped OUTSIDE NavHost (survives screen navigation)
    │
    ├── theme/
    │   ├── Theme.kt                # Material You dynamic colors + dark/light
    │   ├── Color.kt                # Purple80/Grey80/Cyan80 + status colors
    │   └── Type.kt                 # Default Material 3 typography
    │
    ├── HapticManager.kt            # Centralized haptics: extraKeyPress, longPressMenu, bell, error
    │
    └── screens/
        ├── setup/
        │   ├── SetupScreen.kt      # 3-page HorizontalPager: Storage / VM config+SSH / Downloads
        │   └── SetupViewModel.kt   # completeSetup() → DataStore, notification permission request
        │
        ├── home/
        │   ├── HomeScreen.kt       # Start/Stop/Restart VM, open terminal, update dialog, AdaptiveContainer
        │   ├── HomeViewModel.kt    # checkForUpdate(), startPodroid(), stopVm(), restartVm()
        │   └── AnimatedBootProgress.kt  # Rotating arc Canvas + boot stage text (replaces CircularProgressIndicator)
        │
        ├── terminal/
        │   ├── TerminalScreen.kt       # TerminalView composable, extra keys bar, focus observer, pushSize
        │   ├── TerminalViewModel.kt    # Session wiring, CSI keys, focus events, font/theme, forceUpdateSizeFromView
        │   ├── ExtraKey.kt             # sealed KeyAction + JSON serde via ExtraKeySerde
        │   ├── DefaultKeyLayouts.kt    # Built-in layouts: minimal, full (nvim/shell)
        │   └── QuickSettingsDrawer.kt  # Bottom-sheet: font size, theme, font, extras toggle, haptics
        │
        ├── settings/
        │   ├── SettingsScreen.kt   # Sections: VM Resources / Network / Appearance / Diagnostics / About
        │   │                       # Terminal settings moved to QuickSettingsDrawer (NOT here)
        │   └── SettingsViewModel.kt # Port forward CRUD, VM reset, console log export
        │
        └── components/
            └── AdaptiveContainer.kt # WindowSizeClass-based max-width wrapper (tablet/foldable)
```

### Assets (app/src/main/assets/)
```
├── vmlinuz-virt                    # Linux kernel (MUST match modules in initrd.img — same rootfs-builder stage)
├── initrd.img                      # Alpine initramfs (~71MB compressed)
├── qemu/
│   ├── efi-virtio.rom
│   └── keymaps/
├── colors/                         # 114 terminal color schemes (.properties files)
└── fonts/                          # 13 terminal fonts (.ttf)
    └── JetBrains-Mono.ttf, Fira-Code.ttf, CascadiaCode.ttf, etc.
```

### Native Libs (app/src/main/jniLibs/arm64-v8a/)

**Note:** All native binaries require 16KB page alignment (`-Wl,-z,max-page-size=16384`) — mandatory on Android 13+.

| File | What it is |
|---|---|
| `libqemu-system-aarch64.so` | QEMU TCG emulator (PIE executable, 16KB aligned) |
| `libpodroid-bridge.so` | PTY ↔ virtio-console relay (PIE executable, 16KB aligned) |
| `libtermux.so` | Termux terminal emulator JNI (rebuilt for 16KB pages) |
| `libslirp.so` | SLIRP user-mode networking (statically linked into QEMU) |

---

## Key Constants & Values

| Item | Value |
|------|-------|
| podroidKernelVersion | Set in `gradle.properties` (controls kernel source version) |
| QEMU binary | `libqemu-system-aarch64.so` in `nativeLibraryDir` |
| Kernel | `filesDir/vmlinuz-virt` |
| Initrd | `filesDir/initrd.img` |
| Storage image | `filesDir/storage.img` (ext4, sparse) |
| serial.sock | `filesDir/serial.sock` — ttyAMA0, boot-log only; monitor stays connected for VM lifetime |
| terminal.sock | `filesDir/terminal.sock` — virtio-console hvc0; primary terminal for podroid-bridge |
| ctrl.sock | `filesDir/ctrl.sock` — virtio-console hvc1; bridge writes debounced `RESIZE rows cols\n` |
| qmp.sock | `filesDir/qmp.sock` |
| VM IP | 10.0.2.15/24 (SLIRP) |
| Gateway | 10.0.2.2 |
| DNS (SLIRP) | 10.0.2.3 (unreliable on Android) + 8.8.8.8, 1.1.1.1 |
| SSH host port | 9922 → VM:22 (Dropbear, password "podroid") |
| Default RAM | 512 MB (range: 512–4096) |
| Default CPUs | 1 (range: 1, 2, 4, 6, 8) |
| Default font size | 20sp |
| Default storage | 2 GB (range: 2–64 GB) |
| QEMU machine | `-M virt,gic-version=3 -smp <cpus> -m <ramMb>`; tunable extras (`-cpu`, `-accel`, RNG, etc.) come from the user-editable Settings field |
| Kernel cmdline | `console=ttyAMA0 <user-extras> androidip=<ip> [ssh=1]` (default user-extras: `loglevel=1 quiet mitigations=off elevator=mq-deadline`) |

---

## Boot Stage Strings (init-podroid → detectBootStage)

These exact strings are emitted by `init-podroid` and matched by `PodroidQemu.detectBootStage()`:

| String in serial output | Android UI label |
|------------------------|-----------------|
| `Initializing system...` | (no UI change — first phase 2 line) |
| `Loading kernel modules...` | "Loading kernel modules..." |
| `Waiting for network...` | (no match — intermediate) |
| `Network found` | "Network found" |
| `Configuring containers...` | "Configuring containers..." |
| `Starting SSH...` | "Starting SSH..." |
| `Almost ready...` | "Almost ready..." |
| `Ready!` | "Ready" → state = Running → autoStartBridge() |

Also detected: `Mounting storage...`, `Booting kernel...` (emitted by PodroidQemu itself, not init-podroid).

No `sleep 2` after `Ready!` is needed any more — the boot monitor stays on `serial.sock` while the bridge connects to a separate `terminal.sock`, so there's no socket hand-off race to guard.

---

## init-podroid Structure

### Phase 1 (runs in initramfs as PID 1)
```bash
# Located AFTER the `if [ "$1" = "--main" ]; then ... fi` block (at end of file)
mount proc/sys/dev
# virtio_blk is built-in to the custom kernel (no need to modprobe).
find /dev/vda or /dev/vdb → PERSIST_DEV
mount -t ext4 PERSIST_DEV /mnt/persist  # mkfs.ext4 if needed
mkdir -p /mnt/persist/upper /mnt/persist/work /mnt/overlay
mount -t overlay overlay -o lowerdir=/,upperdir=...,workdir=... /mnt/overlay
exec /bin/busybox chroot /mnt/overlay /init --main
# FALLBACK (if overlay failed): exec /init --main  ← runs without persistence
```

**Critical bug to avoid**: the chroot must use `/bin/busybox chroot` explicitly. Phase 1 runs before busybox applet symlinks are installed, so a bare `chroot` is not in PATH and the script exits with code 127, which the kernel interprets as init dying → panic on every boot.

### Phase 2 (`--main` flag, runs inside chroot overlay)
```bash
boot_stage "Initializing system..."
# mount proc/sys/devtmpfs/devpts/tmpfs/cgroup2
boot_stage "Loading kernel modules..."
# Most networking/fs is =y in our custom kernel; only 9p* are still =m.
boot_stage "Waiting for network..."
# wait for eth0 with IP (loop), set up resolv.conf (10.0.2.3, 8.8.8.8, 1.1.1.1)
boot_stage "Network found"
boot_stage "Configuring containers..."
# sysctl, /etc/subuid, containers.conf, storage.conf, registries.conf
boot_stage "Starting SSH..."  # only if ssh=1 in kernel cmdline
# dropbearkey + dropbear
boot_stage "Almost ready..."
# MOTD generation with async internet check, bash profile + aliases
# resize daemon (background): reads RESIZE from /dev/hvc1, runs stty on /dev/hvc0
# write podroid-login wrapper: #!/bin/bash; cat /etc/motd; exec script -q -c 'exec /bin/bash --login' /dev/null
boot_stage "Ready!"
rm -f /run/boot_stage
while true; do
    /sbin/getty -n -l /usr/local/bin/podroid-login 0 hvc0 xterm-256color
    sleep 1
done
```

### podroid-login (written by init-podroid at runtime)
```bash
#!/bin/bash
cat /etc/motd 2>/dev/null
exec /usr/bin/script -q -c 'exec /bin/bash --login' /dev/null
```
The `script` wrapper is required for nvim to work correctly — it allocates a proper PTY inside the serial terminal session.

---

## Terminal Architecture

### Data Flow
```
Keyboard  → TerminalView → TerminalSession.write() → PTY master fd
                                                           ↓
                                            podroid-bridge stdin (PTY slave)
                                                           ↓
                                          terminal.sock → /dev/hvc0 in VM
VM output → /dev/hvc0 → terminal.sock → bridge stdout → PTY slave → TerminalEmulator → TerminalView
Resize    → TerminalView.updateSize() → TIOCSWINSZ on PTY → SIGWINCH → bridge
                                           → bridge debounces 200 ms → TIOCGWINSZ
                                           → writes "RESIZE rows cols\n" to ctrl.sock
                                           → init resize daemon reads /dev/hvc1
                                           → stty rows N cols M < /dev/hvc0
                                           → Linux SIGWINCH to VM fg process group
Mouse     → TerminalView touch handler → PTY → bridge → terminal.sock → VM
```

### PodroidQemu Key Methods

| Method | What it does |
|--------|-------------|
| `start(portForwards, ramMb, cpus, sshEnabled, androidIp)` | Launches QEMU process, starts boot monitor coroutine, 60s timeout fallback |
| `monitorBootSerial(proc)` | Reads serial.sock, appends to consoleBuilder + console.log, calls detectBootStage() |
| `detectBootStage()` | Scans the last 1024 chars of `consoleBuilder` (stateful rolling buffer) and matches boot-stage strings; sets _bootStage and _state. The rolling buffer was added in 1.1.5 to survive markers split across `read()` chunks on fast devices (e.g. Pixel 10 Pro XL was missing "Ready!" and falling back to the 60 s timeout). |
| `releaseSerial()` | shutdownInput + shutdownOutput + close on bootSocket |
| `autoStartBridge()` | Posts to MainLooper, calls releaseSerial(), 500ms delay, creates TerminalSession |
| `createTerminalSession(client)` | Returns pre-started session or creates new one; sets sessionClientDelegate |
| `replayBootOutput(sess)` | Appends post-"Ready!" serial bytes to new emulator (so first screen isn't blank) |
| `cleanup()` | Cancels ioScope, kills session, deletes socket files |

### TerminalViewModel Key Patterns

**`forceUpdateSizeFromView(view)`**: Computes cols/rows from `Paint` metrics using `currentTypeface` and raw `terminalFontSize.value.toFloat()` (NO scaledDensity multiplication — matches TerminalView's internal path exactly). Must use `currentTypeface` not `Typeface.MONOSPACE` for custom fonts.

**`pushSizeNow()`**: Fires `v.updateSize()` immediately + at 300ms + 800ms. Called after font size changes and after closing QuickSettings.

**`forceUpdateSizeFromView` via pushSize in TerminalScreen**: Fires at 0/150/600/1500ms after layout change to outlast lazy layout settling.

**Focus events**: `TerminalScreen` installs `LifecycleEventObserver`. ON_RESUME → `sendFocusEvent(true)` → `\x1b[I`. ON_PAUSE → `sendFocusEvent(false)` → `\x1b[O`. Gated on DECSET 1004 via reflection on `mCurrentDecSetFlags`.

**Proxy sessionClient** (`proxySessionClient` in PodroidQemu): Delegates to `sessionClientDelegate`. Lets session be created at boot-complete time before UI exists. TerminalViewModel sets itself as delegate via `qemu.sessionClientDelegate = sessionClient`.

### Key Sequences (TerminalViewModel.viewClient.onKeyDown)

```
mod = 1 + (shift?1:0) + (alt?2:0) + (ctrl?4:0)
arrow(final): if mod==1 → "\x1b[$final"  else → "\x1b[1;$mod$final"
```

| Key | Sequence |
|-----|----------|
| Shift+Tab | `\x1b[Z` |
| Ctrl+Left/Right | `\x1b[1;5D` / `\x1b[1;5C` |
| Shift+Up/Down | `\x1b[1;2A` / `\x1b[1;2B` |
| F1–F4 | `\x1bOP` … `\x1bOS` |
| F5–F12 | `\x1b[15~` … `\x1b[24~` |

**Bracketed paste**: `onPasteTextFromClipboard` calls `emu.paste(text)` — Termux wraps in `\x1b[200~…\x1b[201~` when DECSET 2004 is active.

**Extra keys hold-to-repeat**: `KeyButton` composable uses `pointerInput + LaunchedEffect` — 400ms initial delay then 70ms → 30ms accelerating cadence. Only arrow keys flagged `repeatable = true`.

**CTRL/ALT sticky**: `extraCtrl` / `extraAlt` toggles on the extras bar. Consumed after any non-modifier key press.

---

## podroid-bridge.c

- **Purpose**: Relay PTY (Termux-allocated) ↔ `terminal.sock` (QEMU virtio-console / hvc0).
- **Arguments**: `bridge_exe terminal_sock_path ctrl_sock_path`.
- **STDERR silenced** immediately via `dup2(/dev/null, STDERR_FILENO)` — bridge runs as TerminalSession subprocess so stderr IS the PTY.
- **`cfmakeraw()`** on own stdin (VM's getty handles echo/line editing).
- **terminal.sock retry**: 50 attempts × 200 ms = 10 s max.
- **ctrl.sock retry**: 50 × 100 ms = 5 s max; lazy reconnect on each SIGWINCH if not yet connected.
- **`send_resize()`**: reads new size via `TIOCGWINSZ`, writes `RESIZE rows cols\n` to ctrl.sock.
- **SIGWINCH debounce**: every signal just refreshes a `clock_gettime(CLOCK_MONOTONIC)` timestamp; the actual `send_resize()` only fires once the burst has been quiet for `RESIZE_DEBOUNCE_MS` (200 ms). Collapses the ~25 per-frame layout changes during an Android keyboard slide into a single shell prompt redraw.
- **select timeout**: 50 ms — wakes the loop frequently enough to fire the debounced resize on schedule.
- The old `filter_queries()` intercept (`ESC[18t`/`ESC[19t` xterm size queries) is gone — virtio-console reports real `TIOCGWINSZ` inside the guest, so apps don't need the workaround.

---

## Dockerfile (Unified Multi-Stage)

### Stages
```
downloader     — downloads Alpine netboot tarball (currently unused for vmlinuz — see CRITICAL below)
rootfs-builder — --platform=linux/arm64/v8, installs all Alpine packages via apk, copies init-podroid as /init
packer         — cpio + gzip the rootfs into initrd.img; copies vmlinuz from rootfs-builder
qemu-builder   — debian:bookworm + NDK r27c, cross-compiles QEMU 11.0.0-rc2 + podroid-bridge + deps
final          — scratch stage, collects all output artifacts
```

### CRITICAL: vmlinuz source
The packer stage copies `vmlinuz-virt` from **`rootfs-builder /boot/vmlinuz-virt`** (the kernel installed by `apk add linux-virt`). This ensures vmlinuz and `/lib/modules/$KVER/` are the same version. **Never copy vmlinuz from the downloader/netboot tarball** — that is a different Alpine release and causes a kernel/modules version mismatch → `modprobe virtio_blk` silently fails → no block device → kernel panic.

### QEMU Build Details
- NDK r27c, API 28 sysroot
- Dependencies (static): pcre2, libffi, glib 2.82.5, pixman 0.44.2, libattr, libucontext
- **libucontext shim**: Android Bionic lacks `ucontext.h` → built from kaniini/libucontext; header shim remaps `getcontext/swapcontext/etc.` to libucontext functions
- **`--with-coroutine=ucontext`**: Required for ARMv9.2 PAC (Android 15+ Tensor devices) — avoids SIGILL from siglongjmp across alternate signal stacks
- **librt optional** patch: Bionic includes librt functions in libc
- **`st_*_nsec` undef** patch: Android's `sys/stat.h` macros clash with 9p struct fields
- **patchelf**: libslirp soname patched from `libslirp.so.0` → `libslirp.so`

### 16KB Page Alignment
All native libs must have `p_align >= 16384`. Enforced via `-Wl,-z,max-page-size=16384` everywhere. Required for Android devices with 16KB system pages. Verified by Python ELF parser in `build-all.sh`.

### VM Rootfs Packages (Alpine 3.23 aarch64)
```
linux-virt bash busybox busybox-extras ttyd podman podman-remote
netavark aardvark-dns fuse-overlayfs slirp4netns iptables ip6tables
shadow-uidmap ca-certificates crun curl e2fsprogs util-linux openrc
dropbear ncurses-terminfo-base musl-locales
```

---

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

**APK only:**
```bash
./gradlew assembleDebug
./gradlew installDebug
```

**Fast bridge rebuild (without full Docker QEMU build):**
```bash
NDK=$HOME/Android/Sdk/ndk/$(ls ~/Android/Sdk/ndk/ | tail -1)
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang
$CC --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
    -target aarch64-linux-android28 -fPIE -pie -Wl,-z,max-page-size=16384 \
    podroid-bridge.c -o app/src/main/jniLibs/arm64-v8a/libpodroid-bridge.so
./gradlew :app:installDebug
```

**Monitor VM boot:**
```bash
adb logcat -s PodroidQemu
adb shell run-as com.excp.podroid.debug cat files/console.log
```

**Workflow**:
- `init-podroid` changed → `./build-all.sh initramfs` → `gradlew assembleDebug` → `adb install`
- Kotlin/Java only → `gradlew assembleDebug` → `adb install`
- `podroid-bridge.c` changed → fast bridge rebuild above → `adb install`
- Check boot: `adb shell run-as com.excp.podroid.debug cat files/console.log`

---

## DataStore Keys (SettingsRepository)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `dark_theme` | Boolean | true | Dark mode |
| `vm_ram_mb` | Int | 512 | VM RAM (512/1024/2048/4096 MB) |
| `vm_cpus` | Int | 1 | VM CPUs (1/2/4/6/8) |
| `terminal_font_size` | Int | 20 | Font size (sp, raw — no scaledDensity) |
| `terminal_color_theme` | String | "default" | Color scheme name (maps to assets/colors/*.properties) |
| `terminal_font` | String | "default" | Font name (maps to assets/fonts/*.ttf) |
| `storage_gb` | Int | 2 | Persistent storage size (GB) |
| `storage_access_enabled` | Boolean | false | Downloads sharing via virtio-9p |
| `setup_done` | Boolean | false | Initial setup completed |
| `ssh_enabled` | Boolean | false | Dropbear SSH in VM |
| `port_forwards` | Set\<String\> | {} | `"tcp:8080:80"` format |
| `dismissed_update_version` | String | "" | Last dismissed update version |

---

## Port Forwarding

```
PortForwardRule(hostPort=8080, guestPort=80, protocol="tcp")
Serialized as: "tcp:8080:80"
```

- **At boot**: passed as QEMU `-netdev user,hostfwd=tcp::8080-:80` args
- **At runtime**: QMP `human-monitor-command hostfwd_add` / `hostfwd_remove`
- **SSH preset**: always forwarded as `tcp::9922-:22` when `sshEnabled = true`
- **Service presets** (SettingsScreen): Pi-hole (5300→53, 8080→80), Nginx (8080→80, 8443→443), Gitea (3000→3000, 2222→22), Grafana (3001→3000)
- **Pi-hole DNS**: host port 5300 used because Android blocks ports < 1024

---

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | SLIRP, QMP socket, GitHub API |
| `ACCESS_NETWORK_STATE` | Get device IP for display |
| `WAKE_LOCK` | Keep CPU alive during VM |
| `VIBRATE` | Terminal bell, haptic feedback |
| `FOREGROUND_SERVICE` | Host QEMU process |
| `FOREGROUND_SERVICE_SPECIAL_USE` | QEMU "emulation" subtype |
| `POST_NOTIFICATIONS` | Android 13+ notification |
| `MANAGE_EXTERNAL_STORAGE` | Downloads sharing via virtio-9p |

---

## Design Patterns & Quirks

### Architecture
- **Single Activity**: Compose Navigation with NavHost
- **TerminalViewModel scoped outside NavHost**: Survives navigation between screens; session persists
- **Proxy sessionClient**: `proxySessionClient` in PodroidQemu delegates to `sessionClientDelegate`; lets session be pre-created at boot before UI attaches
- **Reflection**: `TerminalView.mTermSession`, `mEmulator`, `TerminalEmulator.mCurrentDecSetFlags`, `mapDecSetBitToInternalBit` — all accessed reflectively (Termux AAR private fields)
- **VmState flow**: Idle → Starting → Running → Stopped/Error; service/UI observe via StateFlow

### Known Quirks
- **QEMU + bridge packaged as `.so`**: ELF executables renamed to `.so` for APK packaging; `nativeLibraryDir` extraction still works
- **libslirp soname patched**: `libslirp.so.0` → `libslirp.so` via patchelf (Android linker needs exact match)
- **libtermux.so custom-built**: Termux prebuilt uses 4KB pages; rebuilt with `-Wl,-z,max-page-size=16384`
- **Root project name is "VirtuDroid"**: In `settings.gradle.kts`
- **Room declared but unused**: DataStore used instead
- **Downloads sharing via 9p**: `msize=1048576,cache=loose,noatime` — 128× fewer round-trips vs default 8KB msize
- **QEMU DNS forwarder (10.0.2.3) unreliable on Android**: init-podroid uses 8.8.8.8 + 1.1.1.1 directly
- **No ICMP in SLIRP**: `ping` doesn't work inside VM (QEMU SLIRP limitation)
- **forceUpdateSizeFromView must NOT multiply by scaledDensity**: TerminalView passes raw int textSize to Paint.setTextSize; our computation must match or TUI apps render in wrong grid
- **Bridge stderr silenced**: bridge runs as TerminalSession subprocess, its stderr IS the PTY; any write would bleed into the terminal. Never add `fprintf(stderr, ...)` to podroid-bridge.c
- **seenActive flag in PodroidService**: Prevents premature wakelock release on service start before VM reaches Running state

---

## Common Tasks Reference

### Rebuild initramfs (init-podroid changed)
```bash
./build-all.sh initramfs && ./gradlew assembleDebug && adb uninstall com.excp.podroid.debug; adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Check VM console output
```bash
adb shell run-as com.excp.podroid.debug cat files/console.log
```

### Add a new boot stage
1. Add `boot_stage "My stage..."` at correct position in `init-podroid`
2. Add match in `PodroidQemu.detectBootStage()`
3. Rebuild initramfs

### Add a setting
1. Add DataStore key + Flow in `SettingsRepository.kt`
2. Add UI in `SettingsScreen.kt` (Slider/Switch)
3. Add setter in `SettingsViewModel.kt`
4. Pass to `PodroidQemu.start()` if VM-time setting

### Add a port forward preset
1. Add to `servicePresets` list in `SettingsScreen.kt`

### Add terminal color theme
1. Add `.properties` file to `app/src/main/assets/colors/`
2. Appears automatically in QuickSettingsDrawer picker

### Add terminal font
1. Add `.ttf` to `app/src/main/assets/fonts/`
2. Appears automatically in QuickSettingsDrawer picker

### Modify QEMU arguments
1. Edit `PodroidQemu.buildCommand()`

### Add a new VM control channel message
1. Add write to `ctrl.sock` in `podroid-bridge.c`
2. Add handler in the resize daemon loop in `init-podroid` (reads from `/dev/hvc0`)

---

## Dependency Versions (gradle/libs.versions.toml)

| Library | Version |
|---------|---------|
| Android Gradle Plugin | 9.1.0 |
| Kotlin | 2.2.21 |
| KSP | 2.3.6 |
| Compose BOM | 2026.03.01 |
| Navigation Compose | 2.9.7 |
| Hilt | 2.59.2 |
| Coroutines | 1.9.0 |
| DataStore | 1.2.1 |
| Termux terminal-view | v0.118.1 (JitPack) |
| Lifecycle | 2.9.0 |
| Activity Compose | 1.10.1 |
| WindowSizeClass | material3-adaptive (Compose BOM) |
| Room | 2.8.4 (declared, NOT used) |

---

## ProGuard

`app/proguard-rules.pro` keeps Termux reflection targets:
- `TerminalView.mTermSession`
- `TerminalView.mEmulator`

---

## Pending Work (as of 2026-04-25)

### Next Feature: Container Hub
Full container management screen — SSH into VM at `127.0.0.1:9922`, run `podman ps`, one-tap deploy from a catalog of services (Pi-hole, Vaultwarden, code-server, Gitea, Jellyfin, Uptime Kuma, Filebrowser, Nginx, Grafana). Requires JSch dependency.

### Other TODOs
- **OpenRC integration**: Replace manual init-podroid boot with proper OpenRC service scripts (fixes `rc-service` and docker socket compatibility)
- **Pin Dockerfile packages**: Add version pins to prevent reproducibility breaks
- **DNS configurable**: Currently hardcoded 8.8.8.8 + 1.1.1.1; add settings UI
- **Overlay mount validation**: Detect and surface overlay failures with actionable error
- **Terminal title → TopAppBar**: Wire `onTitleChanged()` to update app bar from OSC sequences
- **Custom font loading**: Allow users to load their own .ttf files (GitHub issue #5)
