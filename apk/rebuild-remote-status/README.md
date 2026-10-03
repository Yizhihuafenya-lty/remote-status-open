# Remote Status Android APK

这是 Android 客户端源码。它保留双中继并行刷新、设备所属中继路由、详情查看、改名和指标聚合逻辑，但不再把任何真实中继地址或 App 令牌写死在源码里。

## 配置

```powershell
Copy-Item relay.properties.example relay.properties
```

编辑 `relay.properties`：

```properties
APP_TOKEN=your-app-token
RELAY_V4_SCHEME=https
RELAY_V4_HOST=relay-v4.example.com
RELAY_V4_PORT=50001
RELAY_V6_SCHEME=https
RELAY_V6_HOST=relay-v6.example.com
RELAY_V6_PORT=50001
```

Gradle 会把这些值生成到 `BuildConfig`，`relay.properties` 不应提交。App 令牌会随 APK 编译，生产环境仍必须使用 HTTPS。

## 构建

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

或者：

```powershell
.\build-apk.ps1 -DebugBuild
```

构建脚本会在 `dist/remote-status-open.apk` 生成本地产物，不会写入桌面。Android SDK 路径由本机的 `local.properties` 提供，该文件已被忽略。

## 数据流约束

- V4 和 V6 请求并行发起，一个中继失败不应阻塞另一个中继。
- 设备记录必须保留 `relay` 归属；详情、指标和改名请求必须发往对应中继。
- IPv6 endpoint 必须使用 `[address]:port` 格式。
- `50000` 是目标配置端口，`50001` 是中继 API 端口。
