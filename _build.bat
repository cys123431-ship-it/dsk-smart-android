@echo off
cd /d "C:\Users\user\Desktop\makebuild\dsk\dsk-smart-android"
set "JAVA_HOME=C:\buildtools\jdk-21.0.13+9"
set "ANDROID_HOME=C:\buildtools\sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if exist build_v1101.done del build_v1101.done
call gradlew.bat testDebugUnitTest assembleRelease > build_v1101.log 2>&1
echo DONE_EXIT_%ERRORLEVEL% > build_v1101.done
