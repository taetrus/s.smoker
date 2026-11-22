@echo off
if "%SMOKER_JAVA%"=="" (set "SMOKER_JAVA=java")
"%SMOKER_JAVA%" -cp "%~1" smoker.demo.DemoSimulator %2
