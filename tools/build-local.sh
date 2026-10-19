#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
ROOT_DIR="$PWD"
export JAVA_HOME="$ROOT_DIR/.toolchain/jdk/Contents/Home"
export ANDROID_HOME="$ROOT_DIR/.toolchain/sdk"
export ANDROID_USER_HOME="$ROOT_DIR/.toolchain/android-user"
export GRADLE_USER_HOME="$ROOT_DIR/.toolchain/gradle-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
exec "$ROOT_DIR/.toolchain/gradle-8.11.1/bin/gradle" --no-daemon --console=plain "$@"
