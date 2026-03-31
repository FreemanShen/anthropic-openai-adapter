@echo off
setlocal

if "%APP_JAR%"=="" set APP_JAR=target\anthropic-adapter-1.0.0.jar
if "%SPRING_PROFILES_ACTIVE%"=="" set SPRING_PROFILES_ACTIVE=prod
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xms256m -Xmx512m

if not exist "%APP_JAR%" (
  echo Jar not found: %APP_JAR%
  echo Run: mvn clean package
  exit /b 1
)

java %JAVA_OPTS% -jar "%APP_JAR%" --spring.profiles.active=%SPRING_PROFILES_ACTIVE%
