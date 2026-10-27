#!/bin/sh
# Builds a test-signed ConnectBot BLE APK without GitHub Actions:
#   ci/local/build.sh <git ref> <name>
# Same checks as the apk job of ble-release.yml (spotless, BLE unit tests),
# then the Oss release APK with a test application ID ("ConnectBot BLE
# test"), signed with a local test key that the script creates on first use.
# Needs git and docker. Android SDK, Gradle cache and test key: $BUILD_DIR
# (default ~/.cache/connectbot-ble-build). Output: $BUILD_DIR/out/<name>/.
set -eu
REF=$1 NAME=$2
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
BUILD_DIR=${BUILD_DIR:-$HOME/.cache/connectbot-ble-build}
mkdir -p "$BUILD_DIR/home"
BUILD_DIR=$(cd "$BUILD_DIR" && pwd)
src=$BUILD_DIR/src
run() {
    docker run --rm -u "$(id -u):$(id -g)" -e HOME=/work/home -e ANDROID_HOME=/work/android-sdk \
        -e GRADLE_USER_HOME=/work/gradle -v "$BUILD_DIR:/work" "$@"
}

docker build -q -t connectbot-ble-build "$repo/ci/local" >/dev/null

if [ ! -d "$BUILD_DIR/android-sdk/cmdline-tools/latest" ]; then
    run -w /work connectbot-ble-build sh -euc '
      curl -sSfLo /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
      mkdir -p android-sdk/cmdline-tools
      unzip -q /tmp/tools.zip -d /tmp/tools
      mv /tmp/tools/cmdline-tools android-sdk/cmdline-tools/latest
      yes | android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/work/android-sdk --licenses >/dev/null
    '
fi

if [ ! -f "$BUILD_DIR/test-signing.p12" ]; then
    (umask 077 && head -c 18 /dev/urandom | base64 > "$BUILD_DIR/test-signing.pass")
    run -w /work connectbot-ble-build sh -euc '
      keytool -genkeypair -keystore test-signing.p12 -storetype PKCS12 \
        -storepass "$(cat test-signing.pass)" -alias connectbot-ble-test \
        -keyalg RSA -keysize 3072 -validity 3650 -dname "CN=ConnectBot BLE test builds"
    '
fi

rm -rf "$src"
git clone -q "$repo" "$src"
git -C "$src" checkout -q "$REF"
# spotless compares against upstream's main, as in CI.
base=$(git -C "$repo" for-each-ref --count=1 --format='%(refname)' refs/heads/upstream-main 'refs/remotes/*/upstream-main')
git -C "$src" fetch -q "$repo" "$base:refs/remotes/origin/main"

OUT=$BUILD_DIR/out/$NAME
rm -rf "$OUT"
mkdir -p "$OUT"
run -e ORG_GRADLE_PROJECT_keystoreFile=/work/test-signing.p12 -e ORG_GRADLE_PROJECT_keystoreAlias=connectbot-ble-test \
    -w /work/src connectbot-ble-build sh -euc '
  export ORG_GRADLE_PROJECT_keystorePassword="$(cat /work/test-signing.pass)"
  ./gradlew -q --no-configuration-cache spotlessCheck
  ./gradlew -q --no-configuration-cache :app:testOssDebugUnitTest --tests "org.connectbot.transport.ble.*"
  ./gradlew -q --no-configuration-cache --init-script ci/local/test.init.gradle :app:assembleOssRelease
'

apk=$(ls "$src"/app/build/outputs/apk/oss/release/*.apk | head -n 1)
cp "$apk" "$OUT/connectbot-ble-test-$NAME-$(git -C "$src" rev-parse --short=7 HEAD).apk"
tools=$(cd "$BUILD_DIR" && ls -d android-sdk/build-tools/* | sort -V | tail -n 1)
run -w /work connectbot-ble-build sh -c "$tools/apksigner verify --print-certs out/$NAME/*.apk | grep -E 'SHA-256|DN'
  $tools/aapt2 dump badging out/$NAME/*.apk | grep -E '^package:|application-label:'"
(cd "$OUT" && sha256sum *.apk > SHA256SUMS && cat SHA256SUMS)
