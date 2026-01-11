# YggPeerChecker

Android application for checking and managing Yggdrasil Network peers and .

## Features

### Checks Tab
- **Multi-type peer checking**: ICMP Ping, Ygg RTT (TCP/TLS connect), Port Default, Port 80, Port 443
- **DNS Fallback**: Automatically checks resolved DNS IPs if main address fails
- **Always Check DNS IPs**: Option to always check fallback IPs regardless of main result
- **Fast Mode**: Stop checking after first successful response
- **Sort & Filter**: Sort by any check type, filter by response time (ms)
- **Concurrent streams**: Configurable parallel connections (1-30)

### Lists Tab

#### Management
- Load peers from public sources:
  - neilalexander.dev (Yggdrasil peers)
  - peers.yggdrasil.link (Yggdrasil peers)
  - RU Whitelist (SNI/HTTP(S) hosts)
- Load from clipboard
- Clear all hosts

#### View
- Two-level filtering:
  - Type: All / Ygg / SNI
  - Address: All / DNS / NoDNS / IPv6 / IPv4 / IpOnly
- Fill DNS: Resolve DNS names to IPs (with IDN support)
- Clear DNS: Clear resolved IPs by current filter
- Clear Visible: Remove hosts matching current filter

### Config Tab
- Concurrent streams slider (1-30)
- Network interface check toggle
- Theme selection (System / Light / Dark)

## Supported Protocols

### Yggdrasil (Ygg)
- tcp://
- tls://
- quic:// (detection only, no RTT check)
- ws://
- wss://

### SNI/HTTP
- sni://
- http://
- https://

## Technical Details

- **Min SDK**: 23 (Android 6.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: arm64-v8a
- **UI Framework**: Jetpack Compose with Material 3
- **Database**: Room for local storage
- **Concurrency**: Kotlin Coroutines with Semaphore

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/YggPeerChecker-X.X.X.apk`

## Requirements

- Android Studio or Gradle
- JDK 17+
- Android SDK 34

## Credits

This project was generated with assistance from Claude AI (Anthropic).

## Links

- [Yggdrasil Network](https://yggdrasil-network.github.io/)
- [Public Peers List](https://github.com/yggdrasil-network/public-peers)

## License

MIT License
