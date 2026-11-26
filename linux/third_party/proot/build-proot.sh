#!/bin/bash
# Builds libproot.so and libproot_loader.so (arm64-v8a) from source.
#
# The linux module's Gradle build runs this script (task :linux:buildProot) and packages the result, so the
# binaries are never committed. They are the only third-party binaries in the module and are built from:
#   - PRoot  https://github.com/termux/proot  commit 0e4287546a7df1a7f4395c75f7eca8b62d5f2cc9
#            GPL-2.0-or-later (file headers: "either version 2 of the License, or (at your option)
#            any later version"; licence text in licenses/GPL-2.0.txt)
#   - talloc 2.5.0  https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz
#            sha256 912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
#            LGPL-3.0-or-later (licenses/LGPL-3.0.txt), linked statically into libproot.so
# Both are shipped as separate executables that Atom runs as child processes.
#
# The build is path-independent: every compile maps the work directory to "." (__FILE__ in talloc and PRoot
# asserts, debug info), the outputs are stripped, and the script fails if a host path is left in them.
#
# Usage: build-proot.sh WORK_DIR OUT_DIR [NDK_DIR]
#   WORK_DIR  a scratch directory (sources and objects; reused between runs)
#   OUT_DIR   receives libproot.so and libproot_loader.so
#   NDK_DIR   defaults to $ANDROID_HOME/ndk/30.0.16248370 (the module's ndkVersion)
set -euo pipefail

USAGE="usage: build-proot.sh WORK_DIR OUT_DIR [NDK_DIR]"
WORK="${1:?$USAGE}"
OUT="${2:?$USAGE}"
NDK="${3:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/30.0.16248370}"
PROOT_COMMIT=0e4287546a7df1a7f4395c75f7eca8b62d5f2cc9
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
TALLOC_URLS=("https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
             "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz")
mkdir -p "$WORK" "$OUT"
WORK="$(cd "$WORK" && pwd)"
OUT="$(cd "$OUT" && pwd)"

case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;
  *) HOST=linux-x86_64 ;;
esac
T="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
CC="$T/aarch64-linux-android30-clang"
[ -x "$CC" ] || { echo "NDK clang not found at $CC" >&2; exit 1; }

cd "$WORK"
# Source paths that end up in __FILE__ strings and debug info are written relative to the work directory.
MAP="-ffile-prefix-map=$WORK/=./ -fmacro-prefix-map=$WORK/=./ -fdebug-prefix-map=$WORK/=./ -g0"

# ---- sources
if [ ! -d proot ]; then
  git clone https://github.com/termux/proot.git proot
fi
git -C proot checkout -q "$PROOT_COMMIT"
if [ ! -f "talloc-$TALLOC_VERSION.tar.gz" ]; then
  for url in "${TALLOC_URLS[@]}"; do
    curl -fsSL --retry 4 --retry-delay 5 -o "talloc-$TALLOC_VERSION.tar.gz.part" "$url" && break
  done
  mv "talloc-$TALLOC_VERSION.tar.gz.part" "talloc-$TALLOC_VERSION.tar.gz"
fi
if command -v sha256sum >/dev/null; then SHA256SUM=(sha256sum); else SHA256SUM=(shasum -a 256); fi
echo "$TALLOC_SHA256  talloc-$TALLOC_VERSION.tar.gz" | "${SHA256SUM[@]}" -c -
rm -rf "talloc-$TALLOC_VERSION" && tar xzf "talloc-$TALLOC_VERSION.tar.gz"

# ---- talloc: one translation unit plus a hand-written config for bionic (no waf cross-answers needed)
rm -rf build && mkdir -p build/talloc
cat > build/talloc/config.h <<'EOF'
/* talloc configuration for the Android NDK (bionic, API 30, arm64). */
#define _GNU_SOURCE 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STDARG_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_FCNTL_H 1
#define HAVE_LIMITS_H 1
#define HAVE_ERRNO_H 1
#define HAVE_MALLOC_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_BOOL 1
#define HAVE__Bool 1
#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_STRERROR_R 1
#define HAVE_SETLINEBUF 1
#define HAVE_MKDTEMP 1
#define HAVE_STRTOLL 1
#define HAVE_STRTOULL 1
#define HAVE_FUNCTION_MACRO 1
#define HAVE_FUNC_MACRO 1
#define HAVE_LONG_LONG 1
#define HAVE_UINT64_T 1
#define HAVE_WORKING_STRERROR_R 1
#define HAVE_IMMEDIATE_STRUCTURES 1
#define HAVE_DLOPEN 1
#define HAVE_DLFCN_H 1
#define HAVE_LIBREPLACE 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_WARN_UNUSED_RESULT 1
#define HAVE_FALLTHROUGH_ATTRIBUTE 1
#define HAVE_USLEEP 1
#define HAVE_SETPROCTITLE_INIT 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0
EOF
# bionic at API 30 has no memset_explicit; libreplace would provide this one function.
cat > build/talloc/shim.c <<'EOF'
#include <string.h>
void *rep_memset_explicit(void *block, int c, size_t size)
{
	void *ptr = memset(block, c, size);
	__asm__ volatile("" : : "g"(block) : "memory");
	return ptr;
}
EOF
"$CC" -Os -fPIC $MAP -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H -Ibuild/talloc -I"talloc-$TALLOC_VERSION" \
  -I"talloc-$TALLOC_VERSION/lib/replace" -c "talloc-$TALLOC_VERSION/talloc.c" -o build/talloc/talloc.o
"$CC" -Os -fPIC $MAP -c build/talloc/shim.c -o build/talloc/shim.o
"$T/llvm-ar" rcs build/talloc/libtalloc.a build/talloc/talloc.o build/talloc/shim.o
cp "talloc-$TALLOC_VERSION/talloc.h" build/talloc/

# ---- proot: in-tree build of a copy. The loader is unbundled (Android forbids exec from app data, so it
# ships as libproot_loader.so and is found through PROOT_LOADER); no 32-bit loader.
cp -R proot build/proot
cd build/proot/src
COMMON=(V=1 "CC=$CC" "STRIP=$T/llvm-strip" "OBJCOPY=$T/llvm-objcopy" "OBJDUMP=$T/llvm-objdump"
        HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/data/local/tmp/atom-proot)
make "${COMMON[@]}" CFLAGS="-Os $MAP" loader/loader
DEF="-DPROOT_UNBUNDLE_LOADER='\"/data/local/tmp/atom-proot\"'"
make -j8 "${COMMON[@]}" \
  CFLAGS="-I$WORK/build/talloc -Os -fPIE $MAP -include string.h $DEF" \
  LDFLAGS="-L$WORK/build/talloc -pie -ltalloc -Wl,-z,noexecstack -Wl,-z,max-page-size=16384" proot

"$T/llvm-strip" --strip-all -o "$OUT/libproot.so" proot
"$T/llvm-strip" --strip-all -o "$OUT/libproot_loader.so" loader/loader

# ---- no host path may remain. The only "/tmp/" strings allowed are PRoot's own: its help text and the
# default loader location (Atom always sets PROOT_LOADER).
ALLOWED='[[:space:]]*(\* )?/tmp/|/data/local/tmp/atom-proot/loader(32)?'
for f in "$OUT/libproot.so" "$OUT/libproot_loader.so"; do
  if "$T/llvm-strings" -a "$f" | grep -E '/tmp/|/Users/|/home/|/private/' | grep -vxE "$ALLOWED" \
     || "$T/llvm-strings" -a "$f" | grep -F "$WORK"; then
    echo "error: host paths left in $f (see above)" >&2
    exit 1
  fi
done
ls -l "$OUT"
