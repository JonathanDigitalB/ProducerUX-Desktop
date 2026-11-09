@echo off
chcp 65001 >nul
cd /d "%~dp0"

:: ==========================================
:: CONFIGURACIÓN DE RUTAS Y VARIABLES
:: ==========================================
set JDK=C:\Program Files\Java\jdk-25.0.2
set JAVAFXSDK=C:\Users\Jonathan López\Documents\Librerias\javafx-sdk-25.0.5
set JAVAFX=%JAVAFXSDK%\lib
set VERSION=1.0
set APPNAME=ProducerUX

:: Añadir WiX Toolset al PATH temporalmente
set PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin

:: ==========================================
:: 1. VALIDACIONES PREVIAS
:: ==========================================
if not exist "%JDK%\bin\jpackage.exe" (
    echo ERROR: No se encontró jpackage en "%JDK%\bin"
    goto error
)

if not exist "dist\%APPNAME%.jar" (
    echo ERROR: No se encuentra el archivo 'dist\%APPNAME%.jar'
    goto error
)

if not exist "%JAVAFXSDK%\bin\glass.dll" (
    echo ERROR: No se encontraron las DLL de JavaFX en "%JAVAFXSDK%\bin"
    goto error
)

:: ==========================================
:: 2. CREAR APP-IMAGE (Carpeta ejecutable)
:: ==========================================
echo [1/2] Generando imagen de la aplicacion...
if exist instalador rmdir /s /q instalador

set ICONO=
if exist "icon.ico" set ICONO=--icon icon.ico

"%JDK%\bin\jpackage" --type app-image --name %APPNAME% ^
  --input dist --main-jar %APPNAME%.jar ^
  --main-class com.jonathan.producerux.Main ^
  --module-path "%JDK%\jmods;%JAVAFX%" ^
  --add-modules java.base,java.desktop,java.logging,java.prefs,java.xml,jdk.unsupported,javafx.controls,javafx.fxml,javafx.graphics,javafx.base ^
  --java-options "--enable-native-access=javafx.graphics" ^
  %ICONO% ^
  --dest instalador

if not exist "instalador\%APPNAME%\%APPNAME%.exe" (
    echo ERROR: Fallo al crear la imagen de la aplicacion.
    goto error
)

echo Copiando DLLs de JavaFX al runtime...
xcopy /y /q "%JAVAFXSDK%\bin\*.dll" "instalador\%APPNAME%\runtime\bin\" >nul

echo Copiando herramientas (yt-dlp y ffmpeg)...
if not exist "instalador\%APPNAME%\tools" mkdir "instalador\%APPNAME%\tools"
xcopy /y /q "tools\*.*" "instalador\%APPNAME%\tools\" >nul

:: ==========================================
:: 3. CREAR INSTALADOR .EXE (Usando WiX v3.14)
:: ==========================================
echo.
echo [2/2] Generando instalador .exe con WiX Toolset...
if exist salida rmdir /s /q salida

set ICONO_INSTALADOR=
if exist "icon.ico" set ICONO_INSTALADOR=--icon icon.ico

"%JDK%\bin\jpackage" --type exe ^
  --app-image instalador\%APPNAME% ^
  --name %APPNAME% ^
  --app-version %VERSION% ^
  --vendor "Jonathan" ^
  --win-upgrade-uuid 7a2b8c3d-4e65-5f2f-8c9b-3d8e6f1a5b42 ^
  --win-shortcut ^
  --win-menu ^
  --win-dir-chooser ^
  --win-per-user-install ^
  %ICONO_INSTALADOR% ^
  --dest salida

:: ==========================================
:: 4. RESULTADO FINAL
:: ==========================================
echo.
if exist "salida\%APPNAME%-%VERSION%.exe" (
    echo ========================================================
    echo  ¡LISTO! Instalador generado con exito en:
    echo  salida\%APPNAME%-%VERSION%.exe
    echo ========================================================
) else (
    echo ERROR: El proceso termino pero no se encontro el .exe final.
    goto error
)

pause
exit /b 0

:error
echo.
echo Algo fallo durante el proceso. Lee los mensajes de arriba.
pause
exit /b 1