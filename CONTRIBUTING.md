# Contributing to Podroid

Contributions are welcome! Whether it's a bug report, feature idea, or code change — all help is appreciated.

## Getting Started

1. Clone the repo:
   ```sh
   git clone https://github.com/ExTV/Podroid.git
   cd Podroid
   ```

2. Build the initramfs (requires Docker with multi-arch support):
   ```sh
   ./docker-build-initramfs.sh
   ```

3. Open in Android Studio and build, or from the command line:
   ```sh
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

You need an **arm64 Android device** running **Android 14+** to test.

## Reporting Bugs

Open an [issue](https://github.com/ExTV/Podroid/issues) with:
- Steps to reproduce
- What you expected vs what happened
- Device model and Android version
- Relevant logcat output (`adb logcat --pid=$(adb shell pidof com.excp.podroid.debug)`)

## Submitting Changes

1. Fork the repo and create a branch (`fix/your-bug` or `feature/your-feature`)
2. Keep PRs focused — one fix or feature per PR
3. Test on a real device before submitting
4. Update the README if your change affects user-facing behavior

## Project Structure

- `init-podroid` — VM init script (Alpine Linux boot, overlay setup, getty)
- `Dockerfile` / `docker-build-initramfs.sh` — initramfs build pipeline
- `app/src/main/java/com/excp/podroid/engine/` — QEMU lifecycle, QMP client, VM state
- `app/src/main/java/com/excp/podroid/service/` — Android foreground service
- `app/src/main/java/com/excp/podroid/ui/` — Jetpack Compose UI (Home, Terminal, Settings)
- `app/src/main/jniLibs/` — Pre-built QEMU and libslirp binaries

## Code Style

- Follow standard [Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Keep it simple — no unnecessary abstractions or premature optimization
- Match the existing style of the file you're editing

## License

By contributing, you agree that your work will be licensed under the **GNU General Public License v2.0**, the same license as the project.
