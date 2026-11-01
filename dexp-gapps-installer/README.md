# Установщик Google Play для DEXP / Яндекс ТВ

Нативный Java/Android Gradle проект, предназначенный для Android 11 (armeabi-v7a), управляется пультом. Код использует системный PackageInstaller, показывает результаты установки.

В папку app/src/main/assets перед сборкой кладутся **три собственноручно скачанных APK**: gsf.apk (Google Services Framework), gms.apk (Google Play Services для Android TV), store.apk (Google Play Store для Android TV). Google APK не публикуются в GitHub.

Сборка: `gradle :app:assembleDebug` (JDK 17 + Android SDK).

Workflow GitHub Actions проверяет сборку и запуск на эмуляторе Android 11 **без файлов сервисов Google**. Это проверка установщика, а не работы Google Play на DEXP. Для полноценной работы Google Play также могут понадобиться системные права, сертификация и интеграция Google Mobile Services.

Оригинальный самодельный APK был неисправен; эта версия использует официальную сборку Android Gradle Plugin, а не самописный DEX.
