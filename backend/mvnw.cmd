@echo off
setlocal
set "BASE_DIR=%~dp0"
set "MAVEN_VERSION=3.9.11"
set "MAVEN_HOME=%BASE_DIR%.maven\apache-maven-%MAVEN_VERSION%"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%BASE_DIR%.maven" mkdir "%BASE_DIR%.maven"
  if not exist "%BASE_DIR%.maven\apache-maven-%MAVEN_VERSION%-bin.zip" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile '%BASE_DIR%.maven\apache-maven-%MAVEN_VERSION%-bin.zip'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%BASE_DIR%.maven\apache-maven-%MAVEN_VERSION%-bin.zip' '%BASE_DIR%.maven'"
)
call "%MAVEN_HOME%\bin\mvn.cmd" -f "%BASE_DIR%pom.xml" %*
