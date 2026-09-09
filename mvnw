#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES_FILE="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "Missing $PROPERTIES_FILE" >&2
  exit 1
fi

DISTRIBUTION_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES_FILE" | head -n 1)
if [ -z "$DISTRIBUTION_URL" ]; then
  echo "distributionUrl is not configured" >&2
  exit 1
fi

ARCHIVE_NAME=${DISTRIBUTION_URL##*/}
MAVEN_VERSION=$(printf '%s' "$ARCHIVE_NAME" | sed -e 's/^apache-maven-//' -e 's/-bin\.zip$//')
MAVEN_USER_HOME=${MAVEN_USER_HOME:-"$HOME/.m2"}
INSTALL_ROOT="$MAVEN_USER_HOME/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_HOME="$INSTALL_ROOT/apache-maven-$MAVEN_VERSION"

fetch() {
  url=$1
  output=$2
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$output"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$url" -O "$output"
  else
    echo "curl or wget is required to bootstrap Maven" >&2
    exit 1
  fi
}

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  command -v unzip >/dev/null 2>&1 || {
    echo "unzip is required to bootstrap Maven" >&2
    exit 1
  }

  mkdir -p "$INSTALL_ROOT"
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

  ARCHIVE="$TMP_DIR/$ARCHIVE_NAME"
  CHECKSUM="$TMP_DIR/$ARCHIVE_NAME.sha512"

  echo "Downloading Apache Maven $MAVEN_VERSION..."
  fetch "$DISTRIBUTION_URL" "$ARCHIVE"
  fetch "$DISTRIBUTION_URL.sha512" "$CHECKSUM"

  EXPECTED=$(tr -d '[:space:]' < "$CHECKSUM")
  if command -v sha512sum >/dev/null 2>&1; then
    ACTUAL=$(sha512sum "$ARCHIVE" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 512 "$ARCHIVE" | awk '{print $1}')
  else
    echo "sha512sum or shasum is required to verify Maven" >&2
    exit 1
  fi

  if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "Maven SHA-512 verification failed" >&2
    exit 1
  fi

  rm -rf "$MAVEN_HOME"
  unzip -q "$ARCHIVE" -d "$INSTALL_ROOT"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
