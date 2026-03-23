#!/usr/bin/env sh

set -e

WRAPPER_DIR=".mvn/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"
WRAPPER_PROPERTIES="$WRAPPER_DIR/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPERTIES" ]; then
  echo "Could not find $WRAPPER_PROPERTIES" >&2
  exit 1
fi

WRAPPER_URL=$(grep "^wrapperUrl=" "$WRAPPER_PROPERTIES" | sed -e 's/wrapperUrl=//')

download_wrapper_jar() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "Cannot download Maven Wrapper JAR: curl or wget is required" >&2
    exit 1
  fi
}

if [ ! -f "$WRAPPER_JAR" ]; then
  mkdir -p "$WRAPPER_DIR"
  echo "Downloading Maven Wrapper JAR from $WRAPPER_URL"
  download_wrapper_jar
fi

JAVA_EXEC="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if ! command -v "$JAVA_EXEC" >/dev/null 2>&1; then
  JAVA_EXEC=java
fi

exec "$JAVA_EXEC" -classpath "$WRAPPER_JAR" -Dmaven.multiModuleProjectDirectory="$(pwd)" org.apache.maven.wrapper.MavenWrapperMain "$@"

