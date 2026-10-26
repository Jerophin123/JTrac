@echo off
rem JTRAC: restart (same as: bin\jtrac.cmd restart)
call "%~dp0bin\jtrac.cmd" restart %*
exit /b %ERRORLEVEL%
