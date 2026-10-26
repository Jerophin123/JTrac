@echo off
rem JTRAC: stop (same as: bin\jtrac.cmd stop)
call "%~dp0bin\jtrac.cmd" stop %*
exit /b %ERRORLEVEL%
