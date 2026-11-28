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
// Compiled programs get the same treatment: an Android (bionic) program in app
// storage runs through /system/bin/linker64, a Linux musl program through the
// musl loader Saturn ships (libx_ldmusl.so, libraries from $PREFIX/lib/musl),
// and a Linux glibc program fails with a message saying so.
// Everything else is passed through untouched.
#define _GNU_SOURCE
#include <elf.h>
#include <stdio.h>
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

/* Directory this library was loaded from (the APK's native lib dir). */
static int lib_dir(char out[PATH_MAX]) {
    Dl_info info;
    if (!dladdr((void *)lib_dir, &info) || !info.dli_fname) return -1;
    const char *slash = strrchr(info.dli_fname, '/');
    size_t len = slash ? (size_t)(slash - info.dli_fname) : 0;
    if (!len || len >= PATH_MAX) return -1;
    memcpy(out, info.dli_fname, len);
    out[len] = 0;
    return 0;
}

/* Reads the PT_INTERP of a 64-bit ELF into `out` ("" when it has none). 0 on success. */
static int elf_interp(int fd, char *out, size_t cap) {
    Elf64_Ehdr eh;
    out[0] = 0;
    if (pread(fd, &eh, sizeof eh, 0) != (ssize_t)sizeof eh || eh.e_ident[EI_CLASS] != ELFCLASS64) return -1;
    for (int i = 0; i < eh.e_phnum && i < 64; ++i) {
        Elf64_Phdr ph;
        if (pread(fd, &ph, sizeof ph, (off_t)(eh.e_phoff + (Elf64_Off)i * eh.e_phentsize)) != (ssize_t)sizeof ph) return -1;
        if (ph.p_type != PT_INTERP) continue;
        if (ph.p_filesz == 0 || ph.p_filesz >= cap) return -1;
        if (pread(fd, out, ph.p_filesz, (off_t)ph.p_offset) != (ssize_t)ph.p_filesz) return -1;
        out[ph.p_filesz] = 0;
        return 0;
    }
    return 0;
}

static const char *env_get(char *const envp[], const char *key) {
    size_t k = strlen(key);
    if (envp) for (char *const *e = envp; *e; ++e) if (strncmp(*e, key, k) == 0 && (*e)[k] == '=') return *e + k + 1;
    return getenv(key);
}

/* envp without LD_PRELOAD / LD_LIBRARY_PATH: both name Android (bionic) libraries, which a musl
 * program can't load. Its own children then start without this shim. */
static char **musl_env(char *const envp[]) {
    int n = 0;
    while (envp && envp[n]) ++n;
    char **out = calloc((size_t)n + 1, sizeof(char *));
    if (!out) return NULL;
    int j = 0;
    for (int k = 0; k < n; ++k)
        if (strncmp(envp[k], "LD_PRELOAD=", 11) != 0 && strncmp(envp[k], "LD_LIBRARY_PATH=", 16) != 0) out[j++] = envp[k];
    out[j] = NULL;
    return out;
}

/* Runs a compiled program from app storage through a loader we may exec. */
static int exec_elf(int fd, const char *path, char *const argv[], char *const envp[], int saved) {
    char interp[256];
    if (elf_interp(fd, interp, sizeof interp) != 0) { errno = saved; return -1; }
    int argc = 0;
    while (argv && argv[argc]) ++argc;
    char **nargv = calloc((size_t)argc + 8, sizeof(char *));
    if (!nargv) { errno = ENOMEM; return -1; }
    char loader[PATH_MAX], libs[PATH_MAX];
    char **env = (char **)envp, **owned = NULL;
    int i = 0, rc;
    if (strstr(interp, "/system/bin/linker")) {
        strcpy(loader, "/system/bin/linker64");
        nargv[i++] = loader;
    } else if (strstr(interp, "ld-musl")) {
        const char *prefix = env_get(envp, "PREFIX");
        if (lib_dir(loader) != 0 || !prefix || strlen(loader) + 20 >= PATH_MAX || strlen(prefix) + 20 >= PATH_MAX) {
            free(nargv); errno = saved; return -1;
        }
        strcat(loader, "/libx_ldmusl.so");
        snprintf(libs, sizeof libs, "%s/lib/musl", prefix);
        nargv[i++] = loader;
        nargv[i++] = "--library-path";
        nargv[i++] = libs;
        nargv[i++] = "--argv0";
        nargv[i++] = argc ? argv[0] : (char *)path;
        if (envp && (owned = musl_env(envp))) env = owned;
    } else {
        free(nargv);
        if (interp[0]) dprintf(2, "saturn: %s is a Linux (glibc) program; Saturn can't run those yet\n", path);
        else dprintf(2, "saturn: %s is a static program; Android won't run it from app storage\n", path);
        errno = saved;
        return -1;
    }
    nargv[i++] = (char *)path;
    for (int k = 1; k < argc; ++k) nargv[i++] = argv[k];
    nargv[i] = NULL;
    rc = real_execve()(loader, nargv, env);
    free(nargv);
    free(owned);
    return rc;
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
    // EACCES: Android won't exec from app storage. ENOENT: the program's loader (/lib/ld-musl-…) doesn't exist.
    if (n >= 4 && memcmp(head, ELFMAG, SELFMAG) == 0 && (saved == EACCES || saved == ENOENT)) {
        rc = exec_elf(fd, path, argv, envp, saved);
        close(fd);
        return rc;
    }
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
