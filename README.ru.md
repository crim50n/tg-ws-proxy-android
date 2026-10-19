<p align="center">
  <a href="https://github.com/crim50n/tg-ws-proxy-android/releases/latest"><img src="https://img.shields.io/github/v/release/crim50n/tg-ws-proxy-android?style=flat-square&amp;label=релиз&amp;color=167DA7" alt="Последний релиз"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-167DA7?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8.0 и новее">
  <a href="LICENSE"><img src="https://img.shields.io/badge/Лицензия-MIT-167DA7?style=flat-square" alt="Лицензия MIT"></a>
</p>

<p align="center"><a href="README.md">English</a> &nbsp; / &nbsp; <strong>Русский</strong></p>

<p align="center">
  <img src="screenshots/2.0.0/cover.jpg" width="100%" alt="Иллюстрация террасы со схемой подключения Android к Telegram через WebSocket">
</p>

<h1 align="center">TG WS Proxy</h1>

<p align="center">
  <strong>Локальный мост к Telegram прямо на вашем Android.</strong><br>
  MTProto через WebSocket с резервными маршрутами Cloudflare и прямым TCP.<br>
  Без root-прав и настройки отдельного сервера. Без рекламы и аналитики.
</p>

<p align="center">
  <a href="https://github.com/crim50n/tg-ws-proxy-android/releases/latest"><img src="https://img.shields.io/badge/Скачать-APK-167DA7?style=for-the-badge&amp;logo=android&amp;logoColor=white&amp;labelColor=167DA7" alt="Скачать APK"></a>
  <a href="#быстрый-старт"><img src="https://img.shields.io/badge/Быстрый_старт-263746?style=for-the-badge" alt="Быстрый старт"></a>
  <a href="release-notes/"><img src="https://img.shields.io/badge/История_изменений-263746?style=for-the-badge" alt="История изменений"></a>
</p>

## Просто включить. Удобно настроить.

- **Адаптивная маршрутизация.** Автоматический выбор маршрутов и ручные настройки
  WebSocket, Cloudflare и TCP, когда они нужны.
- **Спокойная работа в фоне.** Дополнительные проверки маршрутов, подготовка
  соединений и запрет сна процессора по умолчанию выключены. Фоновые параметры
  можно настроить под своё устройство и сеть.
- **Понятный статус.** Работа локального сервиса и доступность Telegram показаны
  отдельно. Подробная статистика трафика и журнал диагностики доступны при необходимости.
- **Нативный интерфейс Android.** Управление из уведомления и плитки быстрых
  настроек, светлая и тёмная темы, Material You, русский и английский языки.

## Скриншоты

<table>
  <tr>
    <th width="33%">Подключение</th>
    <th width="33%">Статистика</th>
    <th width="33%">Настройки</th>
  </tr>
  <tr>
    <td valign="top"><a href="screenshots/2.0.0/main-dark.jpg"><img src="screenshots/2.0.0/main-dark.jpg" width="100%" alt="Тёмная тема: локальный прокси запущен, Telegram подключён"></a></td>
    <td valign="top"><a href="screenshots/2.0.0/statistics.jpg"><img src="screenshots/2.0.0/statistics.jpg" width="100%" alt="Статус подключения, счётчики транспортов, трафик и ошибки"></a></td>
    <td valign="top"><a href="screenshots/2.0.0/settings.jpg"><img src="screenshots/2.0.0/settings.jpg" width="100%" alt="Параметры локального прокси, маршрутизации и энергопотребления"></a></td>
  </tr>
</table>

<details>
  <summary>Светлая тема, справка и сведения о приложении</summary>
  <br>
  <p align="center">
    <a href="screenshots/2.0.0/main.jpg"><img src="screenshots/2.0.0/main.jpg" width="31%" alt="Светлая тема: статус прокси и управление подключением Telegram"></a>
    &nbsp;
    <a href="screenshots/2.0.0/help.jpg"><img src="screenshots/2.0.0/help.jpg" width="31%" alt="Раскрывающиеся разделы справки с инструкциями и устранением проблем"></a>
    &nbsp;
    <a href="screenshots/2.0.0/about.jpg"><img src="screenshots/2.0.0/about.jpg" width="31%" alt="Сведения о приложении, проверка обновлений и ограничения транспорта"></a>
  </p>
</details>

## Быстрый старт

1. [Скачайте и установите последний APK](https://github.com/crim50n/tg-ws-proxy-android/releases/latest)
   на Android 8.0 или новее.
2. Откройте приложение и нажмите **«Подключить»**.
3. Нажмите **«Открыть в Telegram»**, подтвердите добавление и выберите этот прокси.
4. Откройте чат, чтобы проверить соединение.

По умолчанию используется адрес **`127.0.0.1:1443`**, доступный только с этого
устройства. Настройки сохраняются автоматически; изменённые параметры работы
применяются при выходе из настроек с перезапуском активного прокси, если это необходимо.

**Последнее действие запоминается.** Если оставить прокси включённым, после загрузки
устройства приложение попытается восстановить его работу. Остановка из приложения,
уведомления или плитки быстрых настроек отменяет восстановление. Принудительная
остановка в Android и ограничения батареи могут помешать запуску в фоне.

## Обновления и поддержка

Проверить обновления можно в разделе **«О приложении»**. При загрузке внутри
приложения проверяются опубликованная контрольная сумма SHA-256, идентификатор
пакета, версия и соответствие сертификата подписи установленному приложению.
Затем Android запрашивает подтверждение установки.

При проблемах с подключением начните со встроенной **справки**. Если проблема
сохраняется, [создайте issue](https://github.com/crim50n/tg-ws-proxy-android/issues),
указав версию Android, тип сети и шаги воспроизведения. Диагностический отчёт
доступен в разделе **«Настройки → Поддержка и диагностика»**. Проверьте его перед
отправкой, даже несмотря на автоматическое скрытие распознанных чувствительных данных.

## Безопасность

Это прокси для Telegram, **а не VPN для всего устройства и не гарантия анонимности**.
Он работает локально, но подключается к удалённым узлам. Шифрование сообщений
Telegram не зависит от транспорта прокси.

Проверка TLS-сертификатов WebSocket намеренно отключена: не считайте этот транспорт
аутентифицированным TLS-каналом. Прямой TCP не добавляет слой TLS. Оставьте
локальный адрес по умолчанию, если не планируете открывать доступ другим
устройствам: `0.0.0.0` включает прослушивание всех IPv4-интерфейсов.
Не публикуйте ссылку и секрет прокси.

## Разработка

<details>
<summary>Сборка из исходников</summary>

Используйте JDK 17 и Gradle wrapper из репозитория:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK находятся в `app/build/outputs/apk/debug/` и `app/build/outputs/apk/release/`.
Debug-сборка устанавливается отдельно с идентификатором `dev.minios.tgwsproxy.debug`.

Для подписи release-сборки нужны исключённый из Git файл
`app/keystore/signing.properties` и хранилище ключей. Никогда не добавляйте
материалы подписи в репозиторий.

</details>

## Авторы и лицензия

Проект поддерживает [crim50n](https://github.com/crim50n). Вдохновлён
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), развивается
независимо. Не является официальным продуктом Telegram.

Иконки: [Tabler](https://github.com/tabler/tabler-icons), Paweł Kuna и участники
проекта ([MIT](app/src/main/assets/licenses/tabler-icons.txt)).
Лицензия приложения: [MIT](LICENSE).
