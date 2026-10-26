@echo off
rem JTRAC: update (same as: bin\jtrac.cmd update)
call "%~dp0bin\jtrac.cmd" update %*
exit /b %ERRORLEVEL%
