// libpiexec.so — LD_PRELOAD shim that makes scripts in app storage runnable.
//
// Android (targetSdk >= 29) forbids exec() of files in an app's data dir, so
// `./build.sh`, npm-installed CLIs (`#!/usr/bin/env node`) and MCP servers
// started via npx fail with EACCES. This shim wraps execve(): only when the
// real call fails with EACCES and the file starts with "#!", it re-executes
// the interpreter (which is allowed: our binaries live in the APK's native lib
// dir, reached through usr/bin symlinks) with the script as an argument. It
// also covers scripts whose #! interpreter doesn't exist on Android
// (/usr/bin/env, /bin/bash, /bin/sh).
// Everything else is passed through untouched.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*execve_fn)(const char *, char *const[], char *const[]);

static execve_fn real_execve(void) {
    static execve_fn fn;
    if (!fn) fn = (execve_fn)dlsym(RTLD_NEXT, "execve");
    return fn;
}

/* Finds an executable named `name` on PATH (from `envp`, else our env). */
static int find_on_path(const char *name, char *const envp[], char out[PATH_MAX]) {
    const char *path = NULL;
    if (envp) for (char *const *e = envp; *e; ++e) if (strncmp(*e, "PATH=", 5) == 0) { path = *e + 5; break; }
    if (!path) path = getenv("PATH");
    if (!path) path = "/system/bin";
    while (*path) {
        const char *end = strchr(path, ':');
        size_t len = end ? (size_t)(end - path) : strlen(path);
        if (len > 0 && len + strlen(name) + 2 < PATH_MAX) {
            memcpy(out, path, len);
            out[len] = '/';
            strcpy(out + len + 1, name);
            if (access(out, X_OK) == 0) return 0;
        }
        if (!end) break;
        path = end + 1;
    }
    return -1;
}

/* Maps a shebang interpreter to something we are allowed to exec. */
static int resolve_interpreter(const char *interp, char *const envp[], char out[PATH_MAX]) {
    const char *base = strrchr(interp, '/');
    base = base ? base + 1 : interp;
    if (strcmp(base, "sh") == 0) { strcpy(out, "/system/bin/sh"); return 0; }
    // node, bash, python, ... : prefer our PATH (usr/bin symlinks into the APK).
    if (find_on_path(base, envp, out) == 0) return 0;
    if (access(interp, X_OK) == 0 && strlen(interp) < PATH_MAX) { strcpy(out, interp); return 0; }
    return -1;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    int rc = real_execve()(path, argv, envp);
    // EACCES: Android forbids exec() from app storage. ENOENT on an existing
    // file: its #! interpreter is missing (Android has no /usr/bin/env, /bin/sh).
    // ENOEXEC: no usable interpreter line. Anything else passes through.
    if (errno != EACCES && errno != ENOEXEC && !(errno == ENOENT && access(path, F_OK) == 0)) return rc;
    int saved = errno;

    char head[256];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { errno = saved; return rc; }
    ssize_t n = read(fd, head, sizeof head - 1);
    close(fd);
    if (n < 3 || head[0] != '#' || head[1] != '!') { errno = saved; return rc; }
    head[n] = 0;
    char *nl = strchr(head, '\n');
    if (nl) *nl = 0;
    if (nl && nl > head && nl[-1] == '\r') nl[-1] = 0;

    // "#!<interp> [arg]" — like Linux, everything after the first space is one argument.
    char *p = head + 2;
    while (*p == ' ' || *p == '\t') ++p;
    char *interp = p;
    while (*p && *p != ' ' && *p != '\t') ++p;
    char *arg = NULL;
    if (*p) { *p++ = 0; while (*p == ' ' || *p == '\t') ++p; if (*p) arg = p; }

    // "#!/usr/bin/env node": the program is the argument (ignore env's -S etc).
    const char *base = strrchr(interp, '/');
    if (strcmp(base ? base + 1 : interp, "env") == 0 && arg) {
        if (strncmp(arg, "-S ", 3) == 0) arg += 3;
        interp = arg;
        char *sp = strchr(arg, ' ');
        if (sp) { *sp = 0; arg = sp + 1; while (*arg == ' ') ++arg; if (!*arg) arg = NULL; }
        else arg = NULL;
    }

    char resolved[PATH_MAX];
    if (resolve_interpreter(interp, envp, resolved) != 0) { errno = ENOENT; return -1; }

    int argc = 0;
    while (argv && argv[argc]) ++argc;
    char **nargv = calloc((size_t)argc + 4, sizeof(char *));
    if (!nargv) { errno = ENOMEM; return -1; }
    int i = 0;
    nargv[i++] = (char *)interp;
    if (arg) nargv[i++] = arg;
    nargv[i++] = (char *)path;
    for (int k = 1; k < argc; ++k) nargv[i++] = argv[k];
    nargv[i] = NULL;
    rc = real_execve()(resolved, nargv, envp);
    free(nargv);
    return rc;
}
