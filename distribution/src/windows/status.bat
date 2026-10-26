@echo off
rem JTRAC: status (same as: bin\jtrac.cmd status)
call "%~dp0bin\jtrac.cmd" status %*
exit /b %ERRORLEVEL%
