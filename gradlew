#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

if [ ! -x "$JAVA" ] && ! command -v "$JAVA" >/dev/null 2>&1; then
  echo "ERROR: Java was not found. Set JAVA_HOME or install a JDK." >&2
  exit 1
fi

exec "$JAVA" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
