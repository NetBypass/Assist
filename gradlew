#!/bin/sh
# Lightweight Gradle bootstrap for this source checkout.
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.7
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VERSION-bin/assist"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DIST_DIR"
    ZIP="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "Downloading Gradle $GRADLE_VERSION..." >&2
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ZIP" "$URL"
    else
        echo "curl or wget is required to bootstrap Gradle" >&2
        exit 1
    fi
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$ZIP" -d "$DIST_DIR"
    else
        echo "unzip is required to bootstrap Gradle" >&2
        exit 1
    fi
    rm -f "$ZIP"
fi

exec "$GRADLE_BIN" -p "$APP_HOME" "$@"
