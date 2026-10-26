@echo off
rem JTRAC: start (same as: bin\jtrac.cmd start)
call "%~dp0bin\jtrac.cmd" start %*
exit /b %ERRORLEVEL%
