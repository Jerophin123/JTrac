@echo off
rem JTRAC: configure (same as: bin\jtrac.cmd configure)
call "%~dp0bin\jtrac.cmd" configure %*
exit /b %ERRORLEVEL%
