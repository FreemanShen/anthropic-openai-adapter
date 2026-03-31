#!/usr/bin/env sh

set -eu

APP_JAR="${APP_JAR:-target/anthropic-adapter-1.0.0.jar}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"

if [ ! -f "$APP_JAR" ]; then
  echo "Jar not found: $APP_JAR"
  echo "Run: mvn clean package"
  exit 1
fi

exec java $JAVA_OPTS -jar "$APP_JAR" --spring.profiles.active="$SPRING_PROFILES_ACTIVE"
