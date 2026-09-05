# 再不去睡露露就要生气啦（Android + 自托管 MCP）

这是为咲咲定制的露露睡眠守卫，基于 [bella-and-c/sleepy-dog-lock](https://github.com/bella-and-c/sleepy-dog-lock) 的 Android / 自托管实现改造。它把同一份睡眠守卫状态同时交给：

- Android 手机上的无障碍服务；
- Android 前台常驻守卫服务；
- 服务器中的 Codex（固定 Bearer Token）；
- ChatGPT 官端或其他支持远程 MCP 的客户端（OAuth 2.1 + PKCE）。

不需要 ntfy、Shizuku、Bark、Firebase 或数据库。服务器只需要 Node.js 22，运行时无第三方 npm 依赖。

## 1.1.0 守卫规则

- 每天北京时间 **00:20** 主动开启，默认 **06:30** 结束。服务器独立时钟和手机本地闹钟都会执行；手机可在断网时执行已保存的规则。
- 只拦选中的应用。后台状态变化时立即重新判断当前应用；不需要打开守卫 APK 唤醒。桌面和非受限应用不会被旧遮罩挡住。
- “好嘛，露露抱我睡”：移除遮罩、回桌面，按设置熄屏。亮屏后根据当前应用判断；亮灭屏、输入法和遮罩事件不重复算闯入。
- “回到桌面 · 使用其他应用”始终可用，不消耗商量次数。
- 每轮守卫可以商量三次，每次放行十分钟。第三次完整放行；期间不计闯入，结束后仍在受限应用则再次拦截。原定结束时间先到时直接结束。重复开启不重置次数，真正结束后重新开启才重置。
- 紧急结束必须填写1—200字理由。理由、时间、会话和待补传记录先同步保存到手机，再解除守卫。断网不影响紧急结束；联网后幂等补传，旧状态不能重新锁住已结束的会话，旧记录也不会结束新会话。
- 夜间提前结束后当晚不自动重启。紧急入口不会随商量次数耗尽而消失；首页的提前结束同样需要理由。
- 拦截页显示当前时间、结束时间和倒计时；手机根据本地结束时间撤掉遮罩，不必等待服务器或点击按钮。

手机首页可查看紧急结束理由。服务器的 `data/events.jsonl` 保存对应记录；理由不出现在公共状态接口、SSE 或源码中。服务端用持久化请求编号和日志待写队列处理重试与崩溃恢复。

为了在远程开启时识别已经打开的应用，无障碍服务需要窗口访问能力；实现只读取窗口类型、焦点及根节点的应用包名，不遍历节点、不读取文字或输入内容。

## 从旧版升级

先更新服务器，再安装新版 APK。新版会识别旧服务器并明确提示升级，避免将旧版“商量”误当作十分钟放行。

在服务器的仓库目录执行：

```bash
git pull --ff-only
node server/scripts/set-night-schedule.mjs
cd server
node --test
pm2 restart pm2.config.cjs --update-env
```

配置脚本只修改时间字段，保留已有地址和密钥。`/health` 应返回版本 `1.1.0`。既有 `.env` 的 `AUTO_START_HOUR=1` 会覆盖默认值，因此这一步不能省略。

安装 APK 后重新开启一次无障碍服务，并在首页允许“准时提醒 / 闹钟和提醒”权限。Android 12及以后需要该权限才能申请精确闹钟；未授权时会使用系统允许的非精确闹钟，可能延迟。荣耀 / MagicOS 还需允许后台运行。应用被强制停止、权限关闭或手机关机时不能保证执行；重新启动会补做当晚应有的状态判断。

### 实机验收

1. 桌面开启守卫：不弹遮罩；进入受限应用才拦截。
2. 点击回桌面，打开未勾选应用；熄屏、亮屏，确认正常使用且不增加闯入。
3. 在受限应用内远程开启：无需退出重进即出现遮罩。
4. 三次十分钟放行逐次使用；第三次完整生效，第四次不可申请，其他应用及紧急出口始终可用。
5. 设短时守卫，留在拦截页等待到期：遮罩自动消失。
6. 开飞行模式，填写理由紧急结束；重启应用仍解除；联网后理由只记录一次。之后新开一轮，旧理由补传不能结束新一轮。
7. 验证00:20在线与离线主动开启、06:30到期结束；紧急结束后当晚不复锁。

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

应用不会读取页面文字或输入内容。它只识别窗口所属应用的包名，并仅拦截你主动勾选的包。

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

服务器回归覆盖三次十分钟放行、00:20主动开启、到期退出、紧急理由持久化与幂等补传、旧会话事件隔离，以及设备、MCP和OAuth接口。安卓Robolectric测试覆盖离线理由与重启恢复、旧状态防复锁、放行期间拦截判断、重复窗口计次与离线跨日定时。

GitHub Actions 还会执行 `:app:testDebugUnitTest` 和 `:app:assembleDebug`，因此 Java 源码、Manifest 和 Android 资源至少会经过一次真实 Gradle 编译检查。

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
