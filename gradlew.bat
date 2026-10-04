@echo off
setlocal
set GRADLE_VERSION=8.7
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_DIR=%GRADLE_USER_HOME%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\assist
set GRADLE_BIN=%DIST_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if exist "%GRADLE_BIN%" goto run
mkdir "%DIST_DIR%" 2>nul
echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_DIR%\gradle.zip'; Expand-Archive -Force '%DIST_DIR%\gradle.zip' '%DIST_DIR%'; Remove-Item '%DIST_DIR%\gradle.zip'"
if errorlevel 1 exit /b 1
:run
call "%GRADLE_BIN%" -p "%~dp0" %*
