# Почта НГТУ (Android)

Нативный Android-клиент (Kotlin + Jetpack Compose) для корпоративной почты НГТУ (`https://mail2.nstu.ru`, Microsoft Exchange 2016) с запасным входом через **OWA WebView**.

## Возможности
- **Нативный EWS-клиент** (`/EWS/Exchange.asmx` поверх HTTPS + HTTP/1.1 + NTLMv2):
  - Папки: Входящие, Отправленные, Черновики, Удалённые, Спам (с подсчётом непрочитанных).
  - Список писем с постраничной подгрузкой, поиском и pull-to-refresh.
  - Чтение HTML/текстовых писем (в изолированном `WebView` с отключённым JavaScript), скачивание и открытие вложений.
  - Отправка писем, ответ, пересылка, отметка «прочитано/непрочитано», удаление.
- **Запасной режим OWA (WebView)** (`/owa/`):
  - Доступен прямо с экрана входа и из бокового меню (удобно, если на аккаунте или сервере ограничен NTLM EWS).
  - Поддерживает загрузку/скачивание вложений и хранение сессионных cookies.
- **Безопасность**:
  - Учётные данные не зашиты в код; после входа шифруются через `AndroidKeyStore` (`AES-256-GCM`) и сохраняются локально.
  - Разрешён только HTTPS (`cleartextTrafficPermitted="false"`).
- **Автообновления из GitHub Releases**:
  - При запуске (и вручную через боковое меню) приложение проверяет последний релиз репозитория `SnipDev1/NstuMailApk`.
  - Для выпуска новой версии используйте скрипт `./scripts/release.sh`:
    ```bash
    # Один раз положите GitHub PAT (Contents: Read & Write) в .release.env:
    # GITHUB_TOKEN=ghp_...
    ./scripts/release.sh 1.0.1 "Описание изменений"
    ```
    Скрипт при первом запуске создаст постоянный keystore (`~/.android/nstumail-release.jks`), поднимет `versionCode`/`versionName`, прогонит тесты, соберёт подписанный release APK, создаст тег `v1.0.1`, запушит всё в GitHub и опубликует Release с прикреплённым `.apk`.
    Для тестовой локальной сборки без публикации: `./scripts/release.sh 1.0.1 --dry-run`.

## Сборка в Android Studio / Gradle
1. Откройте папку проекта в **Android Studio** (требуется JDK 17 и Android SDK API 35) — Android Studio автоматически подтянет Gradle Wrapper 8.9.
2. Либо в терминале с установленными JDK 17, Android SDK и `gradle`:
   ```bash
   gradle wrapper
   ./gradlew testDebugUnitTest assembleDebug
   ```
   Готовый APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
