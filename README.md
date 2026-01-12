# YggPeerChecker

Утилита для проверки доступности Yggdrasil-пиров и произвольных хостов.

**Это debug-версия pet-проекта, возможны баги и нестабильная работа.**

## Для чего это нужно


Сеть [Yggdrasil](https://yggdrasil-network.github.io/) использует публичные пиры для подключения. 
При настройке клиента (yggstack, Yggdrasil, и т.д.) важно выбрать рабочие пиры с хорошим временем отклика.

Кроме того иногда может потребоваться проверить доступность каких то хостов по спискам хостов(например доступность для указания SNI), 
и хочется иметь этот список под рукой (вдруг интернет сломается), с заранее разреолвеными IP

**YggPeerChecker помогает:**
- Загрузить списки публичных пиров из разных источников
- Проверить доступность пиров (Ping, TCP/TLS connect, порты)
- Отсортировать по времени отклика
- Скопировать рабочие адреса для использования в конфиге

## Возможности

### Вкладка Checks (Проверки)
- **несколько типов проверок**: ICMP Ping, Ygg RTT (TCP/TLS connect), Port Default, Port 80, Port 443
- **DNS Fallback**: автоматическая проверка резолвленных IP при недоступности основного адреса
- **Always Check DNS IPs**: проверка всех IP (основной + резолвленные)
- **Fast Mode**: остановка при первом успешном ответе
- **Сортировка и фильтр**: по любому типу проверки, фильтр по времени (ms)
- **Параллельность**: настраиваемое количество потоков (1-30)

### Вкладка Lists (Списки)

**Management** — преднастроенная загрузка списков:
- neilalexander.dev (Yggdrasil пиры)
- peers.yggdrasil.link (Yggdrasil пиры)
- RU Whitelist (SNI хосты из whitelist)
- Clipboard / File (свои списки)

**View** — просмотр и фильтрация:
- Фильтры по типу: All / Ygg / SNI
- Фильтры по адресу: All / DNS / NoDNS / IPv6 / IPv4 / IpOnly
- Fill DNS — массовый DNS резолвинг
- Clear DNS / Clear Visible — очистка по фильтру

### Вкладка System
- Настройка параллельных потоков
- Выбор темы (System / Light / Dark)
- Просмотр логов

## Поддерживаемые протоколы

**Yggdrasil пиры:**
- `tcp://host:port`
- `tls://host:port`
- `quic://host:port`
- `ws://host:port`
- `wss://host:port`

**Произвольные хосты** (для проверки доступности IP и доменов):
- Просто `host` или `host:port`
- `http://host` / `https://host`

## Сборка

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/YggPeerChecker-X.X.X.apk`

**Требования:**
- JDK 17+
- Android SDK 34

## Полезные ссылки

**Yggdrasil:**
- [Yggdrasil Network](https://yggdrasil-network.github.io/) — официальный сайт
- [Public Peers](https://github.com/yggdrasil-network/public-peers) — списки публичных пиров
- [yggstack](https://github.com/nicholaspaulapps/yggstack) — Android-клиент Yggdrasil
- [Yggmail](https://github.com/neilalexander/yggmail) — почта через Yggdrasil
- [Tyr](https://github.com/nicholaspaulapps/tyr) — Android-браузер для Yggdrasil

## Лицензия

MIT License

---
*Pet-проект, сгенерирован с Claude AI*
