@echo off
chcp 65001 >nul
echo InMc-TitleForge 빌드 시작...
echo.

:: Gradle Wrapper로 빌드 (테스트 제외, 빠른 빌드)
call gradlew build -x test

:: 결과 확인
if %ERRORLEVEL% EQU 0 (
    echo.
    echo 빌드 성공!
    dir /b build\libs\*.jar 2>nul
) else (
    echo.
    echo 빌드 실패 (에러 코드: %ERRORLEVEL%)
)

pause