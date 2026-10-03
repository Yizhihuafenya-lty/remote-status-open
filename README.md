# Remote Status（开源版）

这是 Remote Status 的可公开发布版本。它保留原项目的三端结构和核心逻辑：

```text
open/
├─ apk/rebuild-remote-status/  Android APK 源码
├─ fuwuqi/                     Linux Agent/服务器版
└─ zhongzhuan/                 中转站服务
```

本目录不包含生产环境地址、令牌、集群密钥、服务器清单、设备连接信息、运行日志、状态文件、APK/EXE 成品或本机 Android SDK 路径。示例中的域名、令牌和地址均为占位符或文档保留地址，部署前必须替换。

## 架构和逻辑

1. Linux Agent 读取本机指标，只主动向中转站上报，不监听服务器入站采集端口。
2. 中转站保存已登记设备的最新快照和显示名称，提供鉴权后的设备列表、指标、添加目标和改名接口。
3. Android APK 并行访问 V4/V6 两个中转站，合并设备列表；查看详情和改名时始终回到设备所属的中转站。
4. `50001` 是中转站 API 端口；`50000` 只是目标配置里的示例端口，不代表 Agent 入站监听。
5. IPv6 地址在 URL 中自动使用方括号，设备存储地址仍保持纯地址格式。

## 构建和测试

### Linux Agent

```bash
cd fuwuqi
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o remote-status-agent ./cmd/agent
```

### 中转站

```bash
cd zhongzhuan
go test ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o remote-status-relay ./cmd/relay
```

### Android APK

先复制并编辑 APK 的本地配置；`relay.properties` 已被 `.gitignore` 排除：

```powershell
cd apk\rebuild-remote-status
Copy-Item relay.properties.example relay.properties
# 编辑 relay.properties，填入你自己的中继地址和 App 令牌
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

也可以直接运行 `.\build-apk.ps1 -DebugBuild`。产物只会写到该工程的 `dist/` 目录，不会复制到桌面或其他个人目录。

`local.properties` 由 Android Studio/Gradle 按本机 SDK 路径创建，不要提交。

## 配置

- `fuwuqi/agent.env.example`：复制到服务器的 Agent 环境文件，填写中继 URL 和集群密钥。
- `zhongzhuan/relay.env.example`：复制到中转站的环境文件，填写 TLS、状态文件、集群密钥和 App 令牌。
- `apk/rebuild-remote-status/relay.properties.example`：复制为 `relay.properties`，配置 APK 的两个中继和 App 令牌。

生产部署应使用 HTTPS/TLS、随机生成的长密钥和最小权限服务用户。APK 内的 App 令牌会被编译进客户端，因此它只能作为访问控制凭据，不能当作永不泄露的服务器秘密。

## 本地演示

中转站可以临时监听 `127.0.0.1:50001`，再用 `zhongzhuan/scripts/demo-feed.ps1` 发送假指标。演示数据使用 `demo-fleet-key-change-me`，只用于本机测试，禁止直接用于生产。

## 开源边界

`open/` 是脱敏副本；原工作目录中的历史截图、运行产物和生产部署资料没有被移动或删除。发布前仍建议在目标 Git 仓库中执行一次密钥扫描，并由项目维护者确认依赖许可证和最终开源许可证。
