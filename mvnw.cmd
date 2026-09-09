@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0.mvn\wrapper\MavenWrapper.ps1" %*
exit /b %ERRORLEVEL%
