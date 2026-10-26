@echo off
rem JTRAC: logs (same as: bin\jtrac.cmd logs)
call "%~dp0bin\jtrac.cmd" logs %*
exit /b %ERRORLEVEL%
