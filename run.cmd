@echo off
rem Run recipe-balancer with the environment that has numpy/scipy installed.
rem Uses the WorkBuddy managed venv if present, otherwise falls back to python on PATH.
setlocal
set "PY=C:\Users\JesseTech\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
if not exist "%PY%" set "PY=python"
set "PYTHONPATH=%~dp0src;%PYTHONPATH%"
"%PY%" -m recipe_balancer.cli %*
