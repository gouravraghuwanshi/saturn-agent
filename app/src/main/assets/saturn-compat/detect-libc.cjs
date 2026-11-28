'use strict';
// detect-libc for install scripts Saturn runs as musl Linux (see ../saturn-compat.cjs).
const GLIBC = 'glibc', MUSL = 'musl';
module.exports = {
  GLIBC, MUSL,
  family: async () => MUSL, familySync: () => MUSL,
  isNonGlibcLinux: async () => true, isNonGlibcLinuxSync: () => true,
  version: async () => '1.2.5', versionSync: () => '1.2.5',
};
