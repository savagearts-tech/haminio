@ECHO OFF
SETLOCAL

SET WRAPPER_DIR=.mvn\wrapper
SET WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar
SET WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties

IF NOT EXIST "%WRAPPER_PROPERTIES%" (
  ECHO Could not find %WRAPPER_PROPERTIES%
  EXIT /B 1
)

FOR /F "usebackq tokens=1,2 delims==" %%A IN ("%WRAPPER_PROPERTIES%") DO (
  IF "%%A"=="wrapperUrl" SET WRAPPER_URL=%%B
)

IF NOT EXIST "%WRAPPER_JAR%" (
  IF NOT EXIST "%WRAPPER_DIR%" (
    MKDIR "%WRAPPER_DIR%"
  )
  ECHO Downloading Maven Wrapper JAR from %WRAPPER_URL%
  powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'" 1>NUL
  IF ERRORLEVEL 1 (
    ECHO Failed to download Maven Wrapper JAR
    EXIT /B 1
  )
)

IF DEFINED JAVA_HOME (
  SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVA_EXE=java"
)

"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%CD%" org.apache.maven.wrapper.MavenWrapperMain %*

ENDLOCAL

