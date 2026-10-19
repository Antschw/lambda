@echo off
cd /d "%~dp0.."
java %JAVA_OPTS% -cp "lib/*" fr.antschw.lambda.Main %*
