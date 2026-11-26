# Стекло Переводчик 0.1.2
Исходный прототип Android, не коммерческий релиз. Китайский → русский, локальный ML Kit, словарь торговых терминов, прозрачный слой без перехвата касаний, ручной курс CNY/KZT. Pinduoduo, Taobao, 1688. OCR Android 14+, Android 10–13 — доступный текст интерфейса. Не сохраняет снимки. Длинный перевод может не помещаться; оформление и непрерывность при прокрутке пока не доказаны. Эвристика чувствительных экранов не гарантирует обнаружение всех таких экранов. Автокурс пока не реализован.
## Сборка
JDK 17, Gradle 8.9, SDK 35, AGP 8.7.3. gradle --no-daemon :app:assembleDebug :app:lintDebug
APK: app/build/outputs/apk/debug/app-debug.apk. GitHub Actions: Android debug APK.
## Проверки
39 проверок чистой Java логики выполнены в локальном прототипе. Android сборка, lint, работа в реальном Pinduoduo, точность перевода, плавность, утечки памяти, батарея и приватность требуют проверки. Статус релиза NO GO до успешной проверки.

### 0.1.2
Плавающая кнопка питания 44 dp слева сверху: красная — выключено, зелёная — включено. Только поддерживаемые магазины и браузеры. При смене приложения, блокировке экрана или перезапуске службы перевод выключается. Зелёный означает запрос перевода, а не гарантию загрузки модели. Только маленькая кнопка принимает касания; слой перевода не принимает касания. Добавлены 24 проверки состояния сеанса. Установка и реальные жесты ещё требуют испытаний.

### 0.1.3
Fix multiline layout and avoid invalidation from non-Chinese timer changes; show model loading/error status. Amber during loading/error, green only once ready, red off. Long translations that do not fit remain original. Real Pinduoduo tests pending; timer-related starvation is a code-level risk, not a proven device diagnosis.

### 0.1.4
Adds English-to-Russian heuristic and separate local model, diagnostic long press on power button, fallback to active application root if own overlay is returned, fixes WrongConstant lint. Not a verified on-device fix. No new paid services. Language heuristic is limited to Chinese and English; Latin brand names may be translated.

### 0.1.6
Bounded 3-task translation queue, immediate cached text, OCR reuse for unchanged accessible content, more precise OCR deduplication, merchant glossary and count templates, reject untranslated Han in ML output, vector power icon. Runtime quality/speed not yet measured. Longer translations may still not fit.
