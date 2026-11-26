#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/Android/jdk-17" ]; then
  export JAVA_HOME="$HOME/Android/jdk-17"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(python3 -c 'import re; s=open("app/build.gradle.kts").read(); print(re.search(r"versionName\s*=\s*\"([^\"]+)\"", s).group(1))')"
fi
VERSION="${VERSION#v}"
TAG="v$VERSION"

CACHE_DIR="${NSTUMAIL_BUILD_CACHE:-$HOME/.cache/nstumail-build}"
mkdir -p "$CACHE_DIR"

DIST_DIR="$ROOT_DIR/desktop/build/distributions"
mkdir -p "$DIST_DIR"

echo "=== 1/2 Сборка Linux AppImage ($TAG) ==="
./gradlew :desktop:createReleaseDistributable --no-daemon --console=plain

APP_IMG_SRC="$(find "$ROOT_DIR/desktop/build/compose/binaries/main-release/app" -maxdepth 2 -type d -name "NstuMail" | head -n 1)"
if [ -z "$APP_IMG_SRC" ] || [ ! -d "$APP_IMG_SRC" ]; then
  echo "Ошибка: не найден результат createReleaseDistributable" >&2
  exit 1
fi

APPIMAGETOOL="$CACHE_DIR/appimagetool-x86_64.AppImage"
if [ ! -x "$APPIMAGETOOL" ]; then
  echo "Скачиваю appimagetool-x86_64.AppImage..."
  curl -fsSL -o "$APPIMAGETOOL" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$APPIMAGETOOL"
fi

APPDIR="$ROOT_DIR/desktop/build/appdir/NstuMail.AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr"
cp -a "$APP_IMG_SRC/." "$APPDIR/usr/"
cp -f "$ROOT_DIR/desktop/src/main/resources/nstumail.png" "$APPDIR/nstumail.png"
ln -sf "nstumail.png" "$APPDIR/.DirIcon"

cat > "$APPDIR/nstumail.desktop" <<'DESKTOP'
[Desktop Entry]
Name=Почта НГТУ
Exec=NstuMail
Icon=nstumail
Type=Application
Categories=Network;Email;
Terminal=false
StartupWMClass=ru-nstu-mail-desktop-MainKt
DESKTOP

cat > "$APPDIR/AppRun" <<'APPRUN'
#!/usr/bin/env bash
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$HERE/usr/bin/NstuMail" "$@"
APPRUN
chmod +x "$APPDIR/AppRun"

OUT_APPIMAGE="$DIST_DIR/NstuMail-$TAG-linux-x86_64.AppImage"
rm -f "$OUT_APPIMAGE"
ARCH=x86_64 "$APPIMAGETOOL" --appimage-extract-and-run "$APPDIR" "$OUT_APPIMAGE"
chmod +x "$OUT_APPIMAGE"
echo "Готовый AppImage: $OUT_APPIMAGE"

echo "=== 2/2 Сборка Windows x64 бандла ($TAG) ==="
./gradlew :desktop:packageWindowsUberJar --no-daemon --console=plain

UBER_JAR="$DIST_DIR/NstuMail-windows-uber.jar"
if [ ! -f "$UBER_JAR" ]; then
  echo "Ошибка: не найден $UBER_JAR" >&2
  exit 1
fi

WIN_JRE_ZIP="$CACHE_DIR/temurin17-win-x64-jre.zip"
if [ ! -s "$WIN_JRE_ZIP" ]; then
  echo "Скачиваю Eclipse Temurin 17 Windows x64 JRE..."
  curl -fsSL -o "$WIN_JRE_ZIP" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse"
fi

WIN_STAGE="$ROOT_DIR/desktop/build/win-stage"
rm -rf "$WIN_STAGE"
BUNDLE_DIR="$WIN_STAGE/NstuMail"
mkdir -p "$BUNDLE_DIR/app" "$WIN_STAGE/jre-unpack"
unzip -q "$WIN_JRE_ZIP" -d "$WIN_STAGE/jre-unpack"
JRE_TOP="$(find "$WIN_STAGE/jre-unpack" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
mv "$JRE_TOP" "$BUNDLE_DIR/jre"
cp -f "$UBER_JAR" "$BUNDLE_DIR/app/NstuMail.jar"
cp -f "$ROOT_DIR/desktop/src/main/resources/nstumail.ico" "$BUNDLE_DIR/nstumail.ico"

cat > "$BUNDLE_DIR/NstuMail.vbs" <<'VBS'
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
cmd = """" & dir & "\jre\bin\javaw.exe"" -Xmx512m -jar """ & dir & "\app\NstuMail.jar"""
shell.Run cmd, 0, False
VBS

cat > "$BUNDLE_DIR/NstuMail.bat" <<'BAT'
@echo off
setlocal
set "DIR=%~dp0"
start "" "%DIR%jre\bin\javaw.exe" -Xmx512m -jar "%DIR%app\NstuMail.jar"
BAT

cat > "$BUNDLE_DIR/README.txt" <<EOF
Почта НГТУ ($TAG) для Windows (x64)

Запуск:
1. Распакуйте весь архив в удобную папку (например, C:\Program Files\NstuMail или на Рабочий стол).
2. Запустите NstuMail.vbs (без консольного окна) или NstuMail.bat.
   Java устанавливать не нужно — JRE 17 уже внутри папки jre\.
EOF

OUT_WIN_ZIP="$DIST_DIR/NstuMail-$TAG-windows-x64.zip"
rm -f "$OUT_WIN_ZIP"
(cd "$WIN_STAGE" && zip -q -r "$OUT_WIN_ZIP" "NstuMail")
echo "Готовый Windows-архив: $OUT_WIN_ZIP"
