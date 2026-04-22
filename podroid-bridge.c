/*
 * podroid-bridge — Serial socket ↔ PTY relay for Podroid
 *
 * Bidirectional relay between:
 *   stdin/stdout  — PTY slave (Termux TerminalSession)
 *   serial.sock   — QEMU unix socket serial (ttyAMA0 in VM)
 *
 * Architecture:
 *   - PTY → VM: keystrokes pass through raw; ttyAMA0 handles echo/editing
 *   - VM  → PTY: output filtered for ESC[18t/ESC[19t queries (answered locally)
 *   - SIGWINCH: TerminalSession calls ioctl(TIOCSWINSZ) on PTY master →
 *     we write "RESIZE rows cols\n" to ctrl.sock → VM daemon applies it
 *       to ttyAMA0 → foreground process gets SIGWINCH (nvim/vim/htop resize)
 *
 * Signals:
 *   SIGPIPE  — ignored; EPIPE on write = EOF, handled in main loop
 *   SIGINT   — graceful shutdown
 *   SIGTERM  — graceful shutdown
 *   SIGWINCH — async flag, processed in select() loop; fires send_resize() immediately
 *
 * Args: <serial.sock> <ctrl.sock>
 */

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <unistd.h>

static volatile sig_atomic_t g_winch    = 0;
static volatile sig_atomic_t g_shutdown = 0;
static int                   g_ctrl_fd   = -1;
static int                   g_serial_fd = -1;

static void on_winch(int sig) { (void)sig; g_winch = 1; }
static void on_term(int sig)  { (void)sig; g_shutdown = 1; }

static int connect_unix(const char *path, int max_attempts, unsigned int delay_us) {
    for (int attempt = 0; attempt < max_attempts; attempt++) {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) return -1;
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
        if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) return fd;
        int saved = errno;
        close(fd);
        if (saved != ECONNREFUSED && saved != ENOENT) return -1;
        if (attempt < max_attempts - 1) usleep(delay_us);
    }
    return -1;
}

static int write_all(int fd, const char *buf, int n) {
    int written = 0;
    while (written < n) {
        int w = write(fd, buf + written, n - written);
        if (w <= 0) { if (w < 0 && errno == EINTR) continue; return -1; }
        written += w;
    }
    return 0;
}

static const char *g_ctrl_path = NULL;

static void send_resize(void) {
    // Lazy reconnect: ctrl.sock may not have been ready at startup (QEMU still
    // binding it), or the chardev may have disconnected. Retry on every
    // SIGWINCH so TUI apps eventually get the correct size.
    if (g_ctrl_fd < 0 && g_ctrl_path) {
        g_ctrl_fd = connect_unix(g_ctrl_path, 1, 0);
    }
    if (g_ctrl_fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) < 0) return;
    if (ws.ws_row == 0 || ws.ws_col == 0) return;
    char msg[64];
    int len = snprintf(msg, sizeof(msg), "RESIZE %d %d\n", ws.ws_row, ws.ws_col);
    if (len > 0 && write_all(g_ctrl_fd, msg, len) < 0) {
        // Broken pipe — tear down and reconnect next time.
        close(g_ctrl_fd);
        g_ctrl_fd = -1;
    }
}

/* Intercept ESC[18t / ESC[19t (xterm window size queries) from the VM.
 * Busybox less sends these when ioctl(TIOCGWINSZ) returns a suspicious size.
 * We respond immediately with the real PTY size and strip the query bytes
 * so they never reach the Android TerminalEmulator (which ignores them). */
static int filter_queries(char *buf, int n) {
    int out = 0;
    int respond = 0;
    int code = 8;

    for (int i = 0; i < n; ) {
        if (i + 4 < n && buf[i] == '\033' && buf[i+1] == '['
                      && buf[i+2] == '1'  && buf[i+4] == 't'
                      && (buf[i+3] == '8' || buf[i+3] == '9')) {
            respond = 1;
            code = (buf[i+3] == '8') ? 8 : 9;
            i += 5;
            continue;
        }
        buf[out++] = buf[i++];
    }

    if (respond) {
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0 && ws.ws_row > 0) {
            char resp[32];
            int len = snprintf(resp, sizeof(resp), "\033[%d;%d;%dt", code, ws.ws_row, ws.ws_col);
            write_all(g_serial_fd, resp, len);
        }
    }
    return out;
}

static void cleanup(void) {
    if (g_serial_fd >= 0) { close(g_serial_fd); g_serial_fd = -1; }
    if (g_ctrl_fd   >= 0) { close(g_ctrl_fd);   g_ctrl_fd   = -1; }
}

int main(int argc, char *argv[]) {
    // Silence stderr: this process's stderr is the PTY, so any diagnostic
    // bytes would bleed into the user's terminal output. Redirect to
    // /dev/null before anything can write.
    {
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            if (devnull != STDERR_FILENO) close(devnull);
        }
    }
    if (argc < 3) return 1;

    g_serial_fd = connect_unix(argv[1], 50, 200000);
    if (g_serial_fd < 0) {
        fprintf(stderr, "podroid-bridge: cannot connect to %s\n", argv[1]);
        return 1;
    }

    struct termios raw;
    if (tcgetattr(STDIN_FILENO, &raw) == 0) {
        cfmakeraw(&raw);
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
    }

    // Retry ctrl.sock aggressively — QEMU's virtio chardev can take a beat to
    // bind on slower devices. 50×100ms = 5s budget. If it still fails,
    // send_resize() will keep retrying lazily on every SIGWINCH.
    g_ctrl_path = argv[2];
    g_ctrl_fd = connect_unix(argv[2], 50, 100000);

    send_resize();

    signal(SIGWINCH, on_winch);
    signal(SIGINT,   on_term);
    signal(SIGTERM,  on_term);
    signal(SIGPIPE,  SIG_IGN);

    char buf[8192];

    for (;;) {
        if (g_shutdown) break;
        if (g_winch)    { g_winch = 0; send_resize(); }

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(STDIN_FILENO,  &rfds);
        FD_SET(g_serial_fd,   &rfds);
        int nfds = (g_serial_fd > STDIN_FILENO ? g_serial_fd : STDIN_FILENO) + 1;
        struct timeval tv = { .tv_sec = 0, .tv_usec = 50000 };
        int ret = select(nfds, &rfds, NULL, NULL, &tv);
        if (ret < 0) { if (errno == EINTR) continue; break; }

        if (FD_ISSET(STDIN_FILENO, &rfds)) {
            int n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) break;
            if (write_all(g_serial_fd, buf, n) < 0) break;
        }
        if (FD_ISSET(g_serial_fd, &rfds)) {
            int n = read(g_serial_fd, buf, sizeof(buf));
            if (n <= 0) break;
            n = filter_queries(buf, n);
            if (n > 0 && write_all(STDOUT_FILENO, buf, n) < 0) break;
        }
    }

    cleanup();
    return 0;
}
