#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
cd "$DIR" || exit 1
JAVACMD=${JAVA_HOME:+$JAVA_HOME/bin/java}
JAVACMD=${JAVACMD:-java}
exec "$JAVACMD" -Xmx2g -Dfile.encoding=UTF-8 \
  -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
