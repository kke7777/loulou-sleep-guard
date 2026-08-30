import { randomBytes } from "node:crypto";

const secret = () => randomBytes(32).toString("base64url");

console.log(`ANDROID_DEVICE_TOKEN=${secret()}`);
console.log(`CODEX_CONTROL_TOKEN=${secret()}`);
console.log(`OWNER_APPROVAL_CODE=${secret()}`);
