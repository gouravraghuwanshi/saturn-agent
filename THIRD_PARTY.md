# Third-party software in Saturn

Saturn's own code is Apache-2.0 (see [LICENSE](LICENSE)). The app also ships these, each under its own license:

| Component | License | Source |
|---|---|---|
| Pi coding agent (`@earendil-works/pi-coding-agent`) and its npm dependencies | MIT and others (see each package) | https://pi.dev, npm |
| Node.js (Termux `nodejs-lts` build) | MIT and bundled licenses | https://nodejs.org |
| npm | Artistic-2.0 | https://github.com/npm/cli |
| bash | GPL-3.0-or-later | https://www.gnu.org/software/bash/ |
| git | GPL-2.0-only | https://git-scm.com |
| curl | curl license (MIT-style) | https://curl.se |
| ripgrep | MIT / Unlicense | https://github.com/BurntSushi/ripgrep |
| fd | MIT / Apache-2.0 | https://github.com/sharkdp/fd |
| OpenSSL | Apache-2.0 | https://www.openssl.org |
| CA certificates (Mozilla) | MPL-2.0 | https://curl.se/docs/caextract.html |
| Other shared libraries pulled in by the packages above (zlib, ncurses, readline, libiconv, …) | their own licenses | https://github.com/termux/termux-packages |
| musl libc / dynamic loader (Alpine `musl` 1.2.5), runs Linux musl programs | MIT | https://musl.libc.org |
| libgcc_s (Alpine `libgcc` 14.2.0) | GPL-3.0 with GCC Runtime Library Exception | https://gcc.gnu.org |
| Termux terminal-emulator and terminal-view | Apache-2.0 | https://github.com/termux/termux-app |
| AndroidX, Jetpack Compose, CameraX | Apache-2.0 | https://developer.android.com/jetpack |
| Inter, JetBrains Mono fonts | SIL OFL 1.1 | see [NOTICE-fonts.txt](NOTICE-fonts.txt) |

The command-line tools are the unmodified Termux builds, downloaded and checked against the
sha256 hashes in `runtime/termux.lock.json` by `runtime/import_termux.py`. Their source code is
in the termux-packages repository (exact package versions are pinned in that lock file). If you
want a copy of the source for any GPL component in a release and can't find it, open an issue
and we'll send it.
