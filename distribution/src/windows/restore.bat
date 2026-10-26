@echo off
rem JTRAC: restore (same as: bin\jtrac.cmd restore)
call "%~dp0bin\jtrac.cmd" restore %*
exit /b %ERRORLEVEL%
