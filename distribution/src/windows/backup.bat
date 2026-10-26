@echo off
rem JTRAC: backup (same as: bin\jtrac.cmd backup)
call "%~dp0bin\jtrac.cmd" backup %*
exit /b %ERRORLEVEL%
