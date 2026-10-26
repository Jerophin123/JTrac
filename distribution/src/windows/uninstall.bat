@echo off
rem JTRAC: uninstall (same as: bin\jtrac.cmd uninstall)
call "%~dp0bin\jtrac.cmd" uninstall %*
exit /b %ERRORLEVEL%
