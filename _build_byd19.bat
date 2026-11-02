@echo off
set JAVA_HOME=D:/work/env/jdk17/jdk-17.0.20.1+1
set ANDROID_HOME=D:/work/env/android-sdk
set ANDROID_SDK_ROOT=D:/work/env/android-sdk
call gradlew.bat :ultrasonic:assembleRelease --no-daemon 2>&1
