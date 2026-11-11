@echo off
setlocal
title Launcher Studio
rem Prefer the Python runtime that already has Tkinter + Pillow, then fall back to the system Python.
set "PY=C:\Users\79157\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if exist "%PY%" goto run
where py >nul 2>nul
if %errorlevel%==0 (
  set "PY=py"
  goto run
)
set "PY=python"
:run
"%PY%" "%~dp0studio.py"
if errorlevel 1 (
  echo.
  echo Launcher Studio завершилась с ошибкой. Подробности выше.
  pause
)
