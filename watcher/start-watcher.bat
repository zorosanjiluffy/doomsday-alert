@echo off
title Doomsday Ticket Watcher
cd /d "%~dp0"
:loop
node watcher.js
echo Watcher stopped unexpectedly. Restarting in 10 seconds...
timeout /t 10 /nobreak >nul
goto loop
