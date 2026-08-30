# 再不去睡露露就要生气啦（Android + 自托管 MCP）

这是为咲咲定制的露露睡眠守卫，基于 [bella-and-c/sleepy-dog-lock](https://github.com/bella-and-c/sleepy-dog-lock) 的 Android / 自托管实现改造。它把同一份睡眠守卫状态同时交给：

- Android 手机上的无障碍服务；
- Android 前台常驻守卫服务；
- 服务器中的 Codex（固定 Bearer Token）；
- ChatGPT 官端或其他支持远程 MCP 的客户端（OAuth 2.1 + PKCE）。

不需要 ntfy、Shizuku、Bark、Firebase 或数据库。服务器只需要 Node.js 22，运行时无第三方 npm 依赖。

## 它实际怎样工作

1. 在 ChatGPT 官端或服务器 Codex 中调用 `activate_sleep_guard`。不要求固定说“晚安”；工具说明会让模型结合“准备睡觉、要休息、放下手机”等对话自行判断。
2. 服务器保存“已开启、结束时间、拦截次数”等状态，并通过认证后的 SSE 长连接把最新状态立即广播给在线 Android 守卫。
3. Android 的前台常驻守卫服务维持这条远程状态通道，并每 60 秒做一次兜底状态同步；连接断开会自动重连。它同时记录心跳，App 首页会显示“后台守卫在线”和最近同步时间。
4. Android 无障碍服务负责识别你勾选的应用。每次打开受限应用时仍会立即向服务器发送 `blocked_app_opened` 确认状态，因此 SSE 只是加速远程激活，不取代真正的拦截确认。
5. 守卫服务使用 `START_STICKY`，App 启动、无障碍服务重新连接、系统开机、应用更新完成和用户解锁时都会尝试把它重新拉起。荣耀 / MagicOS 等后台管理较严格的系统仍建议允许应用后台运行。
6. 偷开依次进入 `first_warning`、`locked`、`refused_sleep`。当晚共有三次“申请临时解锁”机会；第三次申请写入后设置 `unlocks_revoked = true` 并隐藏按钮。
7. “申请临时解锁”写入独立的持久记录后会立即返回桌面，不会清掉偷开次数；从桌面或最近任务重新打开受限应用时，守卫依然会再次拦截。“回去睡觉”也会返回桌面，并按设置选择是否熄屏。
8. 调用 `deactivate_sleep_guard` 或到达设定起床时间后自动解除；默认结束时间固定为下一个北京时间早上 `06:30`。

凌晨 `01:00–06:30`（默认中国时区）即使忘了在聊天里说晚安，第一次打开受限应用也会自动启动守卫。手动解除后，当晚不会再次自动启动。

## 服务器部署

要求：Node.js 22、PM2 和一个可用的 HTTPS 地址。没有自有域名时也可以使用指向服务器公网 IP 的 `sslip.io` 地址。2 核 2 GB 的小服务器足够。

```bash
git clone https://github.com/kke7777/loulou-sleep-guard.git
cd loulou-sleep-guard/server
```

```bash
cp .env.example .env
npm run secrets
```

把 `npm run secrets` 打印的三个值分别填进 `.env`，同时填写真实域名：

```dotenv
PUBLIC_BASE_URL=https://sleep.example.com
ANDROID_DEVICE_TOKEN=一段随机值
CODEX_CONTROL_TOKEN=另一段随机值
OWNER_APPROVAL_CODE=第三段随机值
WAKE_HOUR=6
WAKE_MINUTE=30
```

启动并检查：

```bash
pm2 start pm2.config.cjs
pm2 save
curl https://sleep.example.com/health
```

已经部署过旧版本时，拉取新版后重启即可：

```bash
git pull --ff-only
cd server
pm2 restart pm2.config.cjs --update-env
curl https://sleep.example.com/health
```

新版 Android 的即时远程同步使用 `/api/device/stream` SSE 接口。`server/nginx.example.conf` 已设置 `proxy_buffering off`，服务端也会发送 `X-Accel-Buffering: no` 并每 15 秒写入心跳，避免反向代理把长连接缓冲住。

必须使用有效 HTTPS 证书，且不要把 `.env`、`server/data/` 或三个密钥提交到 Git。

## 连接服务器里的 Codex

Codex 可以直接用固定 Token，不需要每次走网页授权。在运行 Codex 的环境中设置：

```bash
export SLEEP_GUARD_CODEX_TOKEN='复制 CODEX_CONTROL_TOKEN 的值'
```

在 Codex 的 `config.toml` 中加入：

```toml
[mcp_servers.rabbit_sleep_guard]
url = "https://sleep.example.com/mcp"
bearer_token_env_var = "SLEEP_GUARD_CODEX_TOKEN"
```

重新打开 Codex 后，应能看到三个工具：

- `activate_sleep_guard`
- `deactivate_sleep_guard`
- `get_sleep_guard_status`

服务器上也附带不经过 MCP 的管理命令，便于脚本或排错：

```bash
SLEEP_GUARD_URL=https://sleep.example.com \
SLEEP_GUARD_CODEX_TOKEN="$SLEEP_GUARD_CODEX_TOKEN" \
node server/bin/sleep-guard-control.mjs status
```

最后一个参数可换成 `start` 或 `stop`。

## 连接 ChatGPT 官端

在 ChatGPT 的开发者模式 / MCP 连接设置中新增远程服务器，地址填写：

```text
https://sleep.example.com/mcp
```

官端会自动读取 OAuth 元数据、动态注册客户端并打开授权页。在授权页输入 `.env` 中的 `OWNER_APPROVAL_CODE`。授权成功后，官端与服务器 Codex 操作的是同一份状态。

这套 OAuth 实现包含：动态客户端注册、授权码流程、PKCE S256、短期授权码、90 天访问令牌和服务器所有者口令确认。要撤销全部官端授权，可以停止服务后删除 `server/data/auth.json` 再启动。

## Android 安装与首次设置

Android 8.0 及以上可用。项目的 `minSdk` 是 26，`targetSdk` 是 35。

1. 安装 APK，打开“再不去睡露露就要生气啦”。应用会启动一个低打扰的前台守卫通知，用于维持远程状态通道。
2. 填写 HTTPS 服务器地址和 `.env` 中的 `ANDROID_DEVICE_TOKEN`，点“保存并测试连接”。连接成功后后台守卫会立即重新确认配置并建立 SSE。
3. 点“打开无障碍设置”，启用“再不去睡露露就要生气啦”。这是识别前台应用和执行返回桌面的必要权限。
4. 如果希望点“回去睡觉”后真的熄屏，再点“允许设备管理锁屏”。不授予时该按钮仍会关闭守卫页并返回桌面。
5. 勾选要拦截的娱乐应用并保存。
6. 荣耀 / MagicOS 建议在应用后台与电池设置中允许后台运行，并关闭会主动终止该应用的电量优化或自动管理限制。不同 MagicOS 版本的菜单名称可能不同，以系统实际显示为准。

应用不会读取页面文字或输入内容。它只使用无障碍事件里的应用包名，且只处理你主动勾选的包。

正常在线时，远程 MCP 激活会经 SSE 立即同步到手机；若 SSE 暂时断开，前台守卫每 60 秒做一次兜底同步，并持续尝试重连。无论 SSE 是否在线，打开受限应用时仍会立即向服务器确认。网络临时不可用且最近一次有效状态为开启时，应用仍按缓存的开启状态执行。

App 首页会同时显示无障碍状态、后台守卫是否最近有心跳，以及最近一次服务器同步时间；排错时不再只靠“无障碍开关看起来是开的”判断服务是否真的在工作。

## 生成 APK

### GitHub Actions（最省事）

每次 Android 或 server 改动都会先运行服务器测试，再编译 Android Debug 包。即使仓库还没有 release 签名材料，也会上传一个明确标记为 `-debug-test` 的测试 APK，用来验证代码确实可以编译运行。

要生成能够长期覆盖升级的正式 APK，仓库维护者需要把同一把长期保存的 release keystore 及其密码放进这四个 GitHub Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

四项存在时，流水线会额外构建、校验签名并上传带明确版本号的 `loulou-sleep-guard-vX.Y.Z` 正式 APK。

Android 只能直接覆盖安装由同一签名证书签出的同包名 APK。如果旧版 APK 是在别处构建并使用另一把私钥签名，而旧私钥又没有保存到 Actions Secrets，就不能靠生成一把新密钥无损覆盖旧版；应优先找回原签名材料。

### Android Studio

用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后选择 **Build > Build APK(s)**。生成路径：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

本地构建 release APK 时，通过 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 环境变量传入同一套签名配置；keystore 和密码不进入仓库。

## 本地测试

服务器测试不需要安装依赖：

```bash
cd server
node --test
```

当前覆盖状态启动、三档偷开计数、三次临时解锁申请、第三次申请后撤销资格、重复开启不清零、解除、凌晨自动启动、过期、Android 与控制 API 共用状态、静态令牌 MCP，以及未授权客户端的 OAuth 发现入口。

GitHub Actions 还会执行 `:app:assembleDebug`，因此 Java 源码、Manifest 和 Android 资源至少会经过一次真实 Gradle 编译检查。

## 数据文件

运行后服务器只在 `server/data/` 保存：

- `state.json`：当前守卫状态；
- `events.jsonl`：事件与拦截次数记录；
- `auth.json`：OAuth 客户端、授权码摘要与访问令牌摘要。

密钥本身只存在 `.env`。OAuth 访问令牌和授权码在磁盘中仅保存 SHA-256 摘要。

## 公开仓库与分享

仓库中的睡眠守卫服务器地址均使用 `sleep.example.com` 示例值，APK 也只带这个输入框占位提示，不包含部署者的服务器地址或令牌。朋友部署时需要自行复制 `server/.env.example`，填写自己的 HTTPS 域名，并独立生成三枚密钥。

提交或发布前建议运行：

```bash
git status --short --ignored
git grep -nE 'ANDROID_DEVICE_TOKEN|CODEX_CONTROL_TOKEN|OWNER_APPROVAL_CODE'
```

确认真实的 `.env`、`server/data/`、`artifacts/`、APK、签名文件仍显示为忽略项，搜索结果中的密钥只来自变量名、示例占位值或文档说明。GitHub Actions 只读取仓库 Secrets 中的 Android 签名材料，不读取部署密钥；APK 不包含服务器配置。
