#!/usr/bin/env python3
"""Build the app's native toolchain from Termux's prebuilt aarch64 packages.

Termux is only a source of Bionic-compiled binaries; the app never needs
Termux installed. Steps:

1. Resolve the wanted packages (+ dependency closure, minus SKIP) from the
   Termux package index, download each .deb and verify its SHA-256.
2. Extract them into build/termux/root.
3. Copy every wanted executable and the shared libraries it needs into
   app/src/main/jniLibs/arm64-v8a. Android only packages files named
   lib*.so, so executables become libx_<name>.so and versioned sonames
   (libfoo.so.3) become libfoo3.so; every DT_NEEDED/DT_SONAME is rewritten.
4. Rewrite hard-coded "/data/data/com.termux/files/usr/bin/sh" C strings to
   "/system/bin/sh".
5. Pack data files (CA bundle, git templates) into assets/usr-data.zip.

Usage: python3 -I runtime/import_termux.py
"""
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "..", "app", "src", "main")
JNI = os.path.join(APP, "jniLibs", "arm64-v8a")
ASSETS = os.path.join(APP, "assets")
WORK = os.path.join(HERE, "build", "termux")
REPO = "https://packages.termux.dev/apt/termux-main"
TERMUX_PREFIX = "/data/data/com.termux/files/usr"

WANT = ["nodejs-lts", "bash", "git", "ripgrep", "fd", "curl", "ca-certificates", "gh", "openssh"]
# Termux-environment glue we replace ourselves, or tools toybox already covers.
SKIP = {"termux-tools", "resolv-conf", "less", "perl", "openssh-sftp-server"}

# command name -> path inside the Termux prefix
EXECUTABLES = {
    "node": "bin/node",
    "bash": "bin/bash",
    "git": "bin/git",
    "rg": "bin/rg",
    "fd": "bin/fd",
    "curl": "bin/curl",
    "gh": "bin/gh",
    "ssh": "bin/ssh",
    "ssh-keygen": "bin/ssh-keygen",
    "git-remote-http": "libexec/git-core/git-remote-http",
}
# data files/dirs (inside the prefix) shipped in assets/usr-data.zip
DATA = ["etc/tls/cert.pem", "share/git-core/templates"] + [
    # terminfo for the app's Terminal (bash/readline, less, git's pager)
    "share/terminfo/" + e for e in ("x/xterm-256color", "x/xterm", "d/dumb", "v/vt100", "s/screen-256color",
                                     "s/screen", "t/tmux-256color", "l/linux")]

SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
SH_FROM = (TERMUX_PREFIX + "/bin/sh").encode()
SH_TO = b"/system/bin/sh"
# git runs hooks and !aliases through its compiled-in shell path.
SHELL_REWRITES = [SH_FROM, (TERMUX_PREFIX + "/bin/bash").encode()]
SHELL_REWRITE_IN = {"libx_git.so", "libx_git-remote-http.so"}
# bash's compiled-in startup files live in Termux's prefix; if Termux is
# installed that dir exists but is unreadable to us ("Permission denied" noise).
FILE_REWRITES = {"libx_bash.so": [(TERMUX_PREFIX + "/etc/bash.bashrc").encode(), (TERMUX_PREFIX + "/etc/profile").encode()]}
# Same-length renames for libraries whose names exist in Android's /system.
NAME_OVERRIDES = {"libcurl.so": "libcurx.so"}


# --------------------------------------------------------------- packages
def load_index():
    path = os.path.join(WORK, "Packages")
    if not os.path.exists(path):
        url = f"{REPO}/dists/stable/main/binary-aarch64/Packages"
        urllib.request.urlretrieve(url, path)
    pkgs = {}
    for blk in open(path, encoding="utf-8").read().split("\n\n"):
        f = {}
        for line in blk.splitlines():
            if ": " in line and not line.startswith(" "):
                k, v = line.split(": ", 1)
                f[k] = v
        if "Package" in f:
            pkgs[f["Package"]] = f
    return pkgs


def closure(pkgs):
    out = []
    def walk(name):
        if name in out or name in SKIP:
            return
        out.append(name)
        for dep in pkgs[name].get("Depends", "").split(","):
            dep = re.split(r"[ (|]", dep.strip())[0]
            if dep:
                walk(dep)
    for w in WANT:
        walk(w)
    return out


def fetch(pkg):
    deb = os.path.join(WORK, "debs", os.path.basename(pkg["Filename"]))
    if not os.path.exists(deb):
        urllib.request.urlretrieve(f"{REPO}/{pkg['Filename']}", deb)
    digest = hashlib.sha256(open(deb, "rb").read()).hexdigest()
    if digest != pkg["SHA256"]:
        os.remove(deb)
        sys.exit(f"SHA-256 mismatch for {deb}")
    return deb


def extract(deb, root):
    x = os.path.join(WORK, "x")
    shutil.rmtree(x, ignore_errors=True)
    os.makedirs(x)
    subprocess.run(["ar", "x", deb], cwd=x, check=True)
    data = next(f for f in os.listdir(x) if f.startswith("data.tar"))
    with tarfile.open(os.path.join(x, data)) as t:
        t.extractall(root, filter="tar")
    shutil.rmtree(x)


# ------------------------------------------------------------------- ELF
def elf_dynamic(data):
    """Return (dynstr_offset, dynstr_end, [(tag, value)]) of a 64-bit LE ELF."""
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    shoff, = struct.unpack_from("<Q", data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    secs = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shentsize) for i in range(shnum)]
    names = secs[shstrndx][4]
    def name(s):
        return data[names + s[0]:data.index(b"\0", names + s[0])]
    dyn = next((s for s in secs if name(s) == b".dynamic"), None)
    dstr = next((s for s in secs if name(s) == b".dynstr"), None)
    if not dyn or not dstr:
        return None
    entries = [struct.unpack_from("<qQ", data, dyn[4] + i * 16) for i in range(dyn[5] // 16)]
    return dstr[4], dstr[4] + dstr[5], entries


def dt_strings(data, tag):
    info = elf_dynamic(data)
    if not info:
        return []
    lo, _, entries = info
    return [data[lo + v:data.index(b"\0", lo + v)].decode() for t, v in entries if t == tag]


DT_NEEDED, DT_SONAME = 1, 14


def packaged_name(soname):
    """libfoo.so.3 -> libfoo3.so (never longer than the original)."""
    if soname in NAME_OVERRIDES:
        return NAME_OVERRIDES[soname]
    m = re.fullmatch(r"(lib.+)\.so\.([\d.]+)", soname)
    return f"{m.group(1)}{m.group(2).split('.')[0]}.so" if m else soname


def rewrite(data, renames, shells=(SH_FROM,)):
    lo, hi, _ = elf_dynamic(data)
    for old, new in renames.items():
        if old == new:
            continue
        needle = b"\0" + old.encode() + b"\0"
        pos = data.find(needle, lo, hi)
        while pos != -1:
            data[pos + 1:pos + 1 + len(old)] = new.encode().ljust(len(old), b"\0")
            pos = data.find(needle, pos + 1, hi)
    # standalone Termux shell-path C strings -> /system/bin/sh
    for old in shells:
        needle = b"\0" + old + b"\0"
        pos = data.find(needle)
        while pos != -1:
            data[pos + 1:pos + 1 + len(old)] = SH_TO.ljust(len(old), b"\0")
            pos = data.find(needle, pos + 1)


def patch_node_js(data):
    """Node's child_process falls back to Termux's sh for `shell: true`."""
    old = b"'" + SH_FROM + b"'"
    new = (b"'" + SH_TO + b"'").ljust(len(old), b" ")
    return data.replace(old, new)


# ------------------------------------------------------------------ main
def main():
    for d in ("debs",):
        os.makedirs(os.path.join(WORK, d), exist_ok=True)
    pkgs = load_index()
    names = closure(pkgs)
    root = os.path.join(WORK, "root")
    shutil.rmtree(root, ignore_errors=True)
    lock = []
    for n in names:
        p = pkgs[n]
        print(f"  {n} {p['Version']}")
        extract(fetch(p), root)
        lock.append({"package": n, "version": p["Version"], "sha256": p["SHA256"]})
    prefix = root + TERMUX_PREFIX
    libdir = os.path.join(prefix, "lib")

    # soname -> real file in the prefix
    libs = {}
    for f in os.listdir(libdir):
        p = os.path.join(libdir, f)
        if f.endswith(".so") or ".so." in f:
            real = os.path.realpath(p)
            if os.path.isfile(real):
                data = open(real, "rb").read()
                for s in dt_strings(data, DT_SONAME) or [f]:
                    libs.setdefault(s, real)
                libs.setdefault(f, real)

    # dependency closure of the executables
    needed, todo = set(), [os.path.join(prefix, p) for p in EXECUTABLES.values()]
    while todo:
        data = open(os.path.realpath(todo.pop()), "rb").read()
        for n in dt_strings(data, DT_NEEDED):
            if n in SYSTEM_LIBS or n in needed:
                continue
            if n not in libs:
                sys.exit(f"missing library {n}")
            needed.add(n)
            todo.append(libs[n])

    renames = {n: packaged_name(n) for n in needed}
    if len(set(renames.values())) != len(renames):
        sys.exit("packaged library name collision")

    shutil.rmtree(JNI, ignore_errors=True)
    os.makedirs(JNI)
    outputs = {f"libx_{cmd}.so": os.path.join(prefix, p) for cmd, p in EXECUTABLES.items()}
    outputs.update({renames[n]: libs[n] for n in needed})
    for out, src in sorted(outputs.items()):
        data = bytearray(open(os.path.realpath(src), "rb").read())
        rewrite(data, renames, SHELL_REWRITES if out in SHELL_REWRITE_IN else (SH_FROM,))
        for old in FILE_REWRITES.get(out, []):
            needle = b"\0" + old + b"\0"
            pos = data.find(needle)
            while pos != -1:
                data[pos + 1:pos + 1 + len(old)] = b"/dev/null".ljust(len(old), b"\0")
                pos = data.find(needle, pos + 1)
        if out == "libx_node.so":
            data = bytearray(patch_node_js(bytes(data)))
        open(os.path.join(JNI, out), "wb").write(data)
    total = sum(os.path.getsize(os.path.join(JNI, f)) for f in os.listdir(JNI))
    print(f"jniLibs: {len(outputs)} files, {total // 1024 // 1024} MB")

    # data files
    os.makedirs(ASSETS, exist_ok=True)
    with zipfile.ZipFile(os.path.join(ASSETS, "usr-data.zip"), "w", zipfile.ZIP_DEFLATED) as z:
        for d in DATA:
            base = os.path.join(prefix, d)
            walk = [(base, None, [base])] if os.path.isfile(base) else os.walk(base)
            for r, _, files in walk:
                for f in files:
                    p = os.path.join(r, f) if os.path.isdir(r) else f
                    if os.path.isfile(p) and not os.path.islink(p):
                        z.write(p, os.path.relpath(p, prefix))
    # Versions shown in the app's About screen.
    versions = {p["package"]: p["version"].split(":")[-1] for p in lock}
    json.dump({k: versions.get(v) for k, v in {"node": "nodejs-lts", "bash": "bash", "git": "git",
               "ripgrep": "ripgrep", "fd": "fd", "curl": "curl", "openssl": "openssl"}.items()},
              open(os.path.join(ASSETS, "runtime-info.json"), "w"))
    json.dump({"packages": lock, "executables": sorted(EXECUTABLES), "data": DATA},
              open(os.path.join(HERE, "termux.lock.json"), "w"), indent=1)
    h = hashlib.sha256(open(os.path.join(HERE, "termux.lock.json"), "rb").read()).hexdigest()[:12]
    open(os.path.join(ASSETS, "usr-data.version"), "w").write(h + "\n")
    print("done")


if __name__ == "__main__":
    main()
