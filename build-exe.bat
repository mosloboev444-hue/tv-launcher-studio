@echo off
setlocal
title Build Launcher Studio EXE
set "PY=C:\Users\79157\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
if not exist "%PY%" set "PY=python"
"%PY%" -m PyInstaller --noconfirm --clean --windowed --name LauncherStudio --distpath dist --workpath build_pyi --specpath build_pyi studio.py
if errorlevel 1 goto fail
xcopy /E /I /Y template "dist\LauncherStudio\template" >nul
xcopy /E /I /Y tools "dist\LauncherStudio\tools" >nul
copy /Y README.md "dist\LauncherStudio\README.md" >nul
echo.
echo Готово: dist\LauncherStudio\LauncherStudio.exe
pause
exit /b 0
:fail
echo Ошибка сборки EXE
pause
exit /b 1
