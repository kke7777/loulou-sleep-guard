import { randomBytes } from "node:crypto";
import { access, writeFile } from "node:fs/promises";
import { constants } from "node:fs";
import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";

const ui = createInterface({ input, output });
const secret = () => randomBytes(32).toString("base64url");

try {
  await access(new URL("./.env", import.meta.url), constants.F_OK);
  console.error("已经找到 .env，为了不覆盖宝贝现有的密钥，露露先停在这里啦。");
  process.exitCode = 1;
} catch {
  output.write("请输入睡眠守卫的 HTTPS 地址（例如 https://47.242.153.197.sslip.io）：");
  const answer = await ui[Symbol.asyncIterator]().next();
  const publicBaseUrl = String(answer.value ?? "").trim().replace(/\/+$/, "");
  if (!/^https:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(publicBaseUrl)) {
    console.error("地址需要以 https:// 开头，并填写已经指向这台服务器的域名或 sslip.io 地址。露露没有写入任何文件。 ");
    process.exitCode = 1;
  } else {
    const androidDeviceToken = secret();
    const codexControlToken = secret();
    const ownerApprovalCode = secret();
    const environment = [
      "PORT=8787",
      `PUBLIC_BASE_URL=${publicBaseUrl}`,
      "DATA_DIR=./data",
      `ANDROID_DEVICE_TOKEN=${androidDeviceToken}`,
      `CODEX_CONTROL_TOKEN=${codexControlToken}`,
      `OWNER_APPROVAL_CODE=${ownerApprovalCode}`,
      "WAKE_HOUR=6",
      "WAKE_MINUTE=30",
      "AUTO_START_HOUR=0",
      "AUTO_START_MINUTE=20",
      "UTC_OFFSET_MINUTES=480",
      "ACCESS_TOKEN_TTL_DAYS=90",
      "",
    ].join("\n");
    await writeFile(new URL("./.env", import.meta.url), environment, { encoding: "utf8", mode: 0o600, flag: "wx" });
    console.log("\n好啦，露露已经把配置和三枚密钥收进 .env 了。请先保存下面两项，别把它们发到公开聊天或截图里：");
    console.log(`\n手机连接令牌：${androidDeviceToken}`);
    console.log(`\n网页授权口令：${ownerApprovalCode}`);
    console.log("\n下一步运行：pm2 start pm2.config.cjs && pm2 save");
  }
} finally {
  ui.close();
}
