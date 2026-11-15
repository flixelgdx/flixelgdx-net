#!/usr/bin/env bash
#
# Builds the native WebRTC library of the net extension for one platform.
#
#   scripts/build_natives.sh fetch       Download the third-party sources only.
#   scripts/build_natives.sh <target>    Build for a target (downloads the sources if needed).
#
# Targets: linux-x86_64, linux-aarch64, windows-x86_64, windows-aarch64, macos-x86_64,
# macos-aarch64, android-arm64-v8a, android-armeabi-v7a, android-x86_64, android-x86.
#
# The released libraries are always built by the "Build Natives" GitHub Actions workflow, which
# calls this script on a machine of each kind. The script builds for the machine it runs on; it
# does not cross-compile, except for Android, which needs FLIXELNET_ANDROID_NDK to point at an
# Android NDK.
#
# The result lands where the Gradle build expects it:
#   desktop: flixelgdx-net-natives/<target>/src/main/resources/natives/<target>/
#   Android: flixelgdx-net-android/src/main/jniLibs/<abi>/
# Nothing built here is ever committed.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=natives.versions
. "${ROOT}/scripts/natives.versions"

CACHE="${FLIXELNET_NATIVE_CACHE:-${ROOT}/build/natives}"
SOURCES="${CACHE}/src"
TARGET="${1:-}"

if [ -z "${TARGET}" ]; then
  echo "Usage: scripts/build_natives.sh <target>|fetch" >&2
  exit 2
fi

fetch() {
  mkdir -p "${SOURCES}"
  local stamp="${SOURCES}/.versions"
  local wanted="${LIBDATACHANNEL_VERSION} ${MBEDTLS_VERSION}"
  if [ -f "${stamp}" ] && [ "$(cat "${stamp}")" = "${wanted}" ]; then
    return
  fi
  rm -rf "${SOURCES}/libdatachannel" "${SOURCES}/mbedtls"
  echo "Fetching libdatachannel ${LIBDATACHANNEL_VERSION}"
  git clone --depth 1 --branch "${LIBDATACHANNEL_VERSION}" --recurse-submodules --shallow-submodules \
    https://github.com/paullouisageneau/libdatachannel.git "${SOURCES}/libdatachannel"
  echo "Fetching Mbed TLS ${MBEDTLS_VERSION}"
  # The release archive is used instead of a git checkout because it already contains the files
  # that Mbed TLS otherwise has to generate with extra tools.
  curl -fsSL --retry 5 -o "${SOURCES}/mbedtls.tar.bz2" \
    "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/mbedtls-${MBEDTLS_VERSION}.tar.bz2"
  mkdir -p "${SOURCES}/mbedtls"
  # Relative paths, because on Windows tar reads "C:" at the start of a path as a computer name.
  (cd "${SOURCES}" && tar -xjf mbedtls.tar.bz2 -C mbedtls --strip-components=1)
  rm -f "${SOURCES}/mbedtls.tar.bz2"
  echo "${wanted}" > "${stamp}"
}

fetch
if [ "${TARGET}" = "fetch" ]; then
  exit 0
fi

BUILD="${CACHE}/build/${TARGET}"
ARGS=(-S "${ROOT}/native" -B "${BUILD}" -G Ninja -DCMAKE_BUILD_TYPE=Release "-DFLIXELNET_DEPS=${SOURCES}")

case "${TARGET}" in
  linux-x86_64 | linux-aarch64)
    LIBRARY="libflixelnetrtc.so"
    DEST="${ROOT}/flixelgdx-net-natives/${TARGET}/src/main/resources/natives/${TARGET}"
    ;;
  windows-x86_64 | windows-aarch64)
    LIBRARY="flixelnetrtc.dll"
    DEST="${ROOT}/flixelgdx-net-natives/${TARGET}/src/main/resources/natives/${TARGET}"
    ;;
  macos-x86_64)
    LIBRARY="libflixelnetrtc.dylib"
    DEST="${ROOT}/flixelgdx-net-natives/${TARGET}/src/main/resources/natives/${TARGET}"
    ARGS+=(-DCMAKE_OSX_ARCHITECTURES=x86_64 -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0)
    ;;
  macos-aarch64)
    LIBRARY="libflixelnetrtc.dylib"
    DEST="${ROOT}/flixelgdx-net-natives/${TARGET}/src/main/resources/natives/${TARGET}"
    ARGS+=(-DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0)
    ;;
  android-*)
    ABI="${TARGET#android-}"
    if [ -z "${FLIXELNET_ANDROID_NDK:-}" ]; then
      echo "Set FLIXELNET_ANDROID_NDK to the folder of an Android NDK." >&2
      exit 2
    fi
    LIBRARY="libflixelnetrtc.so"
    DEST="${ROOT}/flixelgdx-net-android/src/main/jniLibs/${ABI}"
    ARGS+=("-DCMAKE_TOOLCHAIN_FILE=${FLIXELNET_ANDROID_NDK}/build/cmake/android.toolchain.cmake"
      "-DANDROID_ABI=${ABI}" "-DANDROID_PLATFORM=android-${FLIXELNET_ANDROID_API:-26}" -DANDROID_STL=c++_static)
    ;;
  *)
    echo "Unknown target '${TARGET}'." >&2
    exit 2
    ;;
esac

cmake "${ARGS[@]}"
cmake --build "${BUILD}" --target flixelnetrtc --parallel

BUILT="$(find "${BUILD}" -maxdepth 2 -name "${LIBRARY}" | head -n 1)"
if [ -z "${BUILT}" ]; then
  echo "The build finished but ${LIBRARY} was not found under ${BUILD}." >&2
  exit 1
fi
mkdir -p "${DEST}"
cp "${BUILT}" "${DEST}/${LIBRARY}"

# The licenses of everything that is linked into the library travel with it.
{
  echo "The native WebRTC library of FlixelGDX Net contains the following third-party software."
  echo "Its unmodified source code is available from the addresses given below."
  for entry in \
    "libdatachannel ${LIBDATACHANNEL_VERSION}|https://github.com/paullouisageneau/libdatachannel|libdatachannel/LICENSE" \
    "libjuice (as bundled with libdatachannel)|https://github.com/paullouisageneau/libjuice|libdatachannel/deps/libjuice/LICENSE" \
    "usrsctp (as bundled with libdatachannel)|https://github.com/paullouisageneau/usrsctp|libdatachannel/deps/usrsctp/LICENSE.md" \
    "plog (as bundled with libdatachannel)|https://github.com/SergiusTheBest/plog|libdatachannel/deps/plog/LICENSE" \
    "Mbed TLS ${MBEDTLS_VERSION}|https://github.com/Mbed-TLS/mbedtls|mbedtls/LICENSE"; do
    name="${entry%%|*}"
    rest="${entry#*|}"
    url="${rest%%|*}"
    file="${rest#*|}"
    echo
    echo "================================================================================"
    echo "${name}"
    echo "${url}"
    echo "================================================================================"
    echo
    cat "${SOURCES}/${file}"
  done
} > "${DEST}/licenses.txt"

echo "Built ${DEST}/${LIBRARY}"
ls -l "${DEST}"
