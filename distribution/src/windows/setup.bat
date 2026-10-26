@echo off
rem JTRAC: setup (same as: bin\jtrac.cmd setup)
call "%~dp0bin\jtrac.cmd" setup %*
exit /b %ERRORLEVEL%
