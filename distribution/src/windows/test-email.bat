@echo off
rem JTRAC: test-email (same as: bin\jtrac.cmd test-email)
call "%~dp0bin\jtrac.cmd" test-email %*
exit /b %ERRORLEVEL%
