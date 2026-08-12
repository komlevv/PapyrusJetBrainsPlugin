@echo off
chcp 65001 >nul
echo Starting project cleanup...

:: 1. Delete all files in the current folder, except the batch file itself
for /f "delims=" %%f in ('dir /b /a-d') do (
    if /I not "%%f"=="%~nx0" (
        del /q /f /a "%%f"
    )
)

:: 2. Delete all folders, except .idea, .git, third_party, and vendor
for /f "delims=" %%d in ('dir /b /ad') do (
    if /I not "%%d"==".idea" (
        if /I not "%%d"==".git" (
            if /I not "%%d"=="third_party" (
                if /I not "%%d"=="vendor" (
                    rd /s /q "%%d"
                )
            )
        )
    )
)

echo Cleanup completed!