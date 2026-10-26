@echo off
rem JTRAC server command. Usage: jtrac <command> [options]   (jtrac help)
setlocal
set "JTRAC_HOME=%~dp0.."
set "JTRAC_JAVA=%JTRAC_HOME%\runtime\bin\java.exe"
if not exist "%JTRAC_JAVA%" (
  echo JTRAC: the bundled Java runtime is missing from "%JTRAC_HOME%\runtime". The package is incomplete. 1>&2
  exit /b 1
)
"%JTRAC_JAVA%" -Djtrac.home="%JTRAC_HOME%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "%JTRAC_HOME%\lib\launcher\*" com.jtrac.launcher.Launcher %*
exit /b %ERRORLEVEL%
