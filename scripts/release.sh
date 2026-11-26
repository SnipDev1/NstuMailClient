#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPO="SnipDev1/NstuMailApk"
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"
KEYSTORE_PROPS="$ROOT_DIR/keystore.properties"
DEFAULT_KEYSTORE="$HOME/.android/nstumail-release.jks"
RELEASE_ENV="$ROOT_DIR/.release.env"

usage() {
  cat <<'EOF'
Использование:
  ./scripts/release.sh <version> [release_notes] [--dry-run]

Примеры:
  ./scripts/release.sh 1.0.1 "Исправления и улучшения"
  ./scripts/release.sh 1.0.1 --dry-run

Переменные окружения (можно положить в .release.env в корне проекта):
  GITHUB_TOKEN       Personal Access Token с правами Contents: Read & Write (или public_repo)
  KEYSTORE_FILE      Путь к .jks/.keystore (если не используется keystore.properties)
  KEYSTORE_PASSWORD  Пароль хранилища ключей
  KEY_ALIAS          Alias ключа
  KEY_PASSWORD       Пароль ключа
EOF
}

DRY_RUN=0
ARGS=()
for arg in "$@"; do
  case "$arg" in
    -h|--help)
      usage
      exit 0
      ;;
    --dry-run)
      DRY_RUN=1
      ;;
    *)
      ARGS+=("$arg")
      ;;
  esac
done

if [ "${#ARGS[@]}" -lt 1 ]; then
  usage
  exit 1
fi

VERSION="${ARGS[0]#v}"
NOTES="${ARGS[1]:-Релиз v$VERSION}"
TAG="v$VERSION"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Ошибка: версия должна быть в формате X.Y.Z (например, 1.0.1), получено: ${ARGS[0]}" >&2
  exit 1
fi

if [ -f "$RELEASE_ENV" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$RELEASE_ENV"
  set +a
fi

if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/Android/jdk-17" ]; then
  export JAVA_HOME="$HOME/Android/jdk-17"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
  export ANDROID_HOME="$HOME/Android/Sdk"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ "$DRY_RUN" -eq 0 ]; then
  if [ -n "$(git status --porcelain)" ]; then
    echo "Ошибка: в репозитории есть незакоммиченные изменения. Сначала закоммитьте их." >&2
    exit 1
  fi
  if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Ошибка: тег $TAG уже существует." >&2
    exit 1
  fi
  USE_GH=0
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    USE_GH=1
  elif [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "Ошибка: не найден GITHUB_TOKEN (и gh CLI не авторизован)." >&2
    echo "Создайте GitHub token (Contents: Read & Write) и задайте:" >&2
    echo "  export GITHUB_TOKEN=ghp_..." >&2
    echo "или запишите его в $RELEASE_ENV (файл добавлен в .gitignore)." >&2
    exit 1
  fi
fi

# Ensure a persistent release signing keystore exists
if [ ! -f "$KEYSTORE_PROPS" ] && [ -z "${KEYSTORE_FILE:-}" ]; then
  echo "=== Создаю постоянный release-keystore: $DEFAULT_KEYSTORE ==="
  mkdir -p "$(dirname "$DEFAULT_KEYSTORE")"
  PASS="$(openssl rand -base64 24 | tr -d '/+=\n' | cut -c1-24)"
  ALIAS="nstumail"
  if [ ! -f "$DEFAULT_KEYSTORE" ]; then
    keytool -genkeypair -v \
      -keystore "$DEFAULT_KEYSTORE" \
      -storetype PKCS12 \
      -alias "$ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$PASS" -keypass "$PASS" \
      -dname "CN=NstuMail, O=NSTU, C=RU"
    chmod 600 "$DEFAULT_KEYSTORE"
    cat > "$KEYSTORE_PROPS" <<EOF
storeFile=$DEFAULT_KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
    chmod 600 "$KEYSTORE_PROPS"
    echo "ВАЖНО: сохраните резервную копию $DEFAULT_KEYSTORE и $KEYSTORE_PROPS!"
  else
    echo "Ошибка: $DEFAULT_KEYSTORE уже существует, но нет $KEYSTORE_PROPS и KEYSTORE_FILE." >&2
    exit 1
  fi
fi

ORIG_GRADLE="$(cat "$GRADLE_FILE")"
COMMITTED=0
cleanup() {
  if [ "$COMMITTED" -eq 0 ]; then
    printf '%s' "$ORIG_GRADLE" > "$GRADLE_FILE"
  fi
}
trap cleanup EXIT

python3 - "$GRADLE_FILE" "$VERSION" <<'PY'
import re, sys

path, new_ver = sys.argv[1], sys.argv[2]
txt = open(path, encoding="utf-8").read()

m_code = re.search(r'(versionCode\s*=\s*)(\d+)', txt)
m_name = re.search(r'(versionName\s*=\s*")([^"]+)(")', txt)
if not m_code or not m_name:
    raise SystemExit("Не найдены versionCode/versionName в app/build.gradle.kts")

next_code = int(m_code.group(2)) + 1
txt = txt[:m_code.start(2)] + str(next_code) + txt[m_code.end(2):]
m_name = re.search(r'(versionName\s*=\s*")([^"]+)(")', txt)
txt = txt[:m_name.start(2)] + new_ver + txt[m_name.end(2):]

open(path, "w", encoding="utf-8").write(txt)
print(f"Версия обновлена: versionCode={next_code}, versionName={new_ver}")
PY

echo "=== Запуск unit-тестов и сборки release APK ==="
./gradlew testDebugUnitTest assembleRelease --no-daemon --console=plain

RAW_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$RAW_APK" ]; then
  echo "Ошибка: не найден подписанный APK ($RAW_APK)." >&2
  exit 1
fi

APKSIGNER="$(ls -1 "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
if [ -n "$APKSIGNER" ]; then
  echo "=== Проверка подписи APK ==="
  "$APKSIGNER" verify --print-certs "$RAW_APK" | head -n 6
fi

OUT_APK="$ROOT_DIR/app/build/outputs/apk/release/NstuMail-$TAG.apk"
cp -f "$RAW_APK" "$OUT_APK"
echo "Готовый APK: $OUT_APK"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "=== --dry-run завершён успешно (без коммита, тега и публикации) ==="
  exit 0
fi

git add "$GRADLE_FILE"
git commit -m "release: $TAG"
COMMITTED=1
git tag -a "$TAG" -m "Release $TAG"

echo "=== Отправка коммита и тега $TAG в origin ==="
git push origin HEAD:main
git push origin "$TAG"

echo "=== Создание GitHub Release $TAG и загрузка APK ==="
if [ "$USE_GH" -eq 1 ]; then
  gh release create "$TAG" "$OUT_APK" \
    --repo "$REPO" \
    --title "NstuMail $TAG" \
    --notes "$NOTES"
else
  CREATE_RESP="$(python3 - "$REPO" "$TAG" "$NOTES" <<'PY' | curl -fsSL -X POST \
    -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/json" \
    --data-binary @- \
    "https://api.github.com/repos/$REPO/releases"
import json, sys
repo, tag, notes = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "tag_name": tag,
    "target_commitish": "main",
    "name": f"NstuMail {tag}",
    "body": notes,
    "draft": False,
    "prerelease": False,
}))
PY
)"
  UPLOAD_URL="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["upload_url"].split("{")[0])' <<<"$CREATE_RESP")"
  HTML_URL="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["html_url"])' <<<"$CREATE_RESP")"
  APK_NAME="$(basename "$OUT_APK")"

  curl -fsSL -X POST \
    -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$OUT_APK" \
    "${UPLOAD_URL}?name=${APK_NAME}" >/dev/null

  echo "Релиз опубликован: $HTML_URL"
fi
