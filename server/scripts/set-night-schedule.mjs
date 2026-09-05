import { readFile, writeFile, rename } from 'node:fs/promises';
const path = new URL('../.env', import.meta.url);
let text = await readFile(path, 'utf8');
for (const [key, value] of Object.entries({ AUTO_START_HOUR: 0, AUTO_START_MINUTE: 20, WAKE_HOUR: 6, WAKE_MINUTE: 30, UTC_OFFSET_MINUTES: 480 })) {
  const line = new RegExp(`^\\s*(?:export\\s+)?${key}\\s*=.*$`, 'gm');
  if (line.test(text)) text = text.replace(line, `${key}=${value}`);
  else text += `\n${key}=${value}\n`;
}
const temporary = new URL('../.env.schedule.tmp', import.meta.url);
await writeFile(temporary, text, { mode: 0o600 });
await rename(temporary, path);
console.log('已设为北京时间00:20—06:30；现有地址和密钥保留。请重启服务使配置生效。');
