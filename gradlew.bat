@echo off
set DIR=%~dp0
java -Xmx2g -Dfile.encoding=UTF-8 -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
