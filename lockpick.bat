@echo off
rem ---------------------------------------------------------------------------
rem  Starts The Colony's Skeleton Key - the Gothic Remake auto-lockpicker.
rem
rem  Usage:  lockpick.bat  [--dump] [game-process-name]
rem             lockpick.bat                              solve the open lock on F8
rem             lockpick.bat --dump                       F8 only saves a frame + sidecar
rem             lockpick.bat "G1R-Win64-Shipping.exe"     name the game's process
rem
rem  Builds first if needed, then runs AutoLockpick in this console. The two JVM
rem  flags are not optional: --enable-native-access silences the user32.dll FFM
rem  downcalls, and uiScale=1 makes Robot capture true 3840x2160 device pixels on
rem  a DPI-scaled display. Quit with Ctrl-C.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set. Point it at a JDK 25 install, for example:
    echo     setx JAVA_HOME "C:\Users\%USERNAME%\.jdks\corretto-25.0.2"
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: no java.exe under JAVA_HOME ^(%JAVA_HOME%^).
    exit /b 1
)

set "APP=build\install\colonys-skeleton-key"
set "JAR=%APP%\lib\colonys-skeleton-key.jar"

rem A half-deleted install (e.g. `gradlew clean` while the app held the jar open)
rem makes installDist refuse to run. Clear it out rather than fail cryptically.
if exist "%APP%" if not exist "%APP%\bin" rmdir /s /q "%APP%"

rem Always build: Gradle is incremental, and a stale jar is a far nastier surprise than a
rem second of startup. Call the wrapper by full path -- cmd does not always search the
rem current directory. -q keeps it silent unless something is actually rebuilt or breaks.
call "%~dp0gradlew.bat" installDist -q --console=plain
if errorlevel 1 (
    echo ERROR: build failed. Fix the errors above, or run gradlew.bat build for detail.
    exit /b 1
)
if not exist "%JAR%" (
    echo ERROR: build succeeded but %JAR% is missing.
    exit /b 1
)

rem Forward the arguments as typed: %* keeps the quoting, so a process name with spaces still
rem arrives as ONE argument, and flags like --dump come through beside it. Without any,
rem AutoLockpick uses its own default (G1R-Win64-Shipping.exe).
set "JAVA=%JAVA_HOME%\bin\java.exe"
set "FLAGS=--enable-native-access=ALL-UNNAMED -Dsun.java2d.uiScale=1"

set "MAIN=io.github.markosa84.colonysskeletonkey.AutoLockpick"

if "%~1"=="" (
    "%JAVA%" %FLAGS% -cp "%JAR%" %MAIN%
) else (
    "%JAVA%" %FLAGS% -cp "%JAR%" %MAIN% %*
)
