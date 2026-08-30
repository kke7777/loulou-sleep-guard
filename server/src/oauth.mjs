import { createHash, randomBytes } from "node:crypto";
import { bearer, escapeHtml, html, json, readBody, readJson, redirect, sameSecret } from "./http-utils.mjs";

const SCOPE = "sleep_guard:write";
const AUTH_REQUEST_TTL_MS = 10 * 60 * 1000;
const AUTH_CODE_TTL_MS = 5 * 60 * 1000;

const token = (length = 32) => randomBytes(length).toString("base64url");
const digest = (value) => createHash("sha256").update(value).digest("base64url");

function emptyAuth() {
  return { clients: {}, requests: {}, codes: {}, tokens: {} };
}

function allowedRedirectUri(value) {
  if (typeof value !== "string" || value.length > 2048) return false;
  try {
    const url = new URL(value);
    if (url.hash) return false;
    if (url.protocol === "https:") return true;
    return url.protocol === "http:" && ["localhost", "127.0.0.1", "::1"].includes(url.hostname);
  } catch {
    return false;
  }
}

function prune(auth) {
  const now = Date.now();
  for (const bucket of ["requests", "codes", "tokens"]) {
    for (const [key, record] of Object.entries(auth[bucket])) {
      if (record.expires_at && Date.parse(record.expires_at) <= now) delete auth[bucket][key];
    }
  }
}

export class OAuthService {
  constructor(store, config) {
    this.store = store;
    this.config = config;
  }

  protectedResource(response) {
    json(response, 200, {
      resource: `${this.config.publicBaseUrl}/mcp`,
      authorization_servers: [this.config.publicBaseUrl],
      bearer_methods_supported: ["header"],
      scopes_supported: [SCOPE],
    });
  }

  authorizationMetadata(response) {
    json(response, 200, {
      issuer: this.config.publicBaseUrl,
      authorization_endpoint: `${this.config.publicBaseUrl}/oauth/authorize`,
      token_endpoint: `${this.config.publicBaseUrl}/oauth/token`,
      registration_endpoint: `${this.config.publicBaseUrl}/oauth/register`,
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["none"],
      scopes_supported: [SCOPE],
    });
  }

  async register(request, response) {
    if (request.method !== "POST") return json(response, 405, { error: "method_not_allowed" });
    let input;
    try {
      input = await readJson(request);
    } catch {
      return json(response, 400, { error: "invalid_client_metadata" });
    }
    const inputUris = Array.isArray(input.redirect_uris) ? input.redirect_uris : [];
    if (!inputUris.length || !inputUris.every(allowedRedirectUri)) {
      return json(response, 400, { error: "invalid_redirect_uri" });
    }
    if (input.token_endpoint_auth_method && input.token_endpoint_auth_method !== "none") {
      return json(response, 400, { error: "invalid_client_metadata" });
    }

    const clientId = token(24);
    const createdAt = new Date().toISOString();
    await this.store.withLock(async () => {
      const auth = await this.store.readAuth(emptyAuth());
      prune(auth);
      auth.clients[clientId] = {
        client_id: clientId,
        client_name: typeof input.client_name === "string" ? input.client_name.slice(0, 120) : "ChatGPT or Codex",
        redirect_uris: inputUris,
        created_at: createdAt,
      };
      await this.store.writeAuth(auth);
    });
    return json(response, 201, {
      client_id: clientId,
      client_id_issued_at: Math.floor(Date.parse(createdAt) / 1000),
      client_name: typeof input.client_name === "string" ? input.client_name.slice(0, 120) : "ChatGPT or Codex",
      redirect_uris: inputUris,
      grant_types: ["authorization_code"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
    });
  }

  async authorize(url, response) {
    const clientId = url.searchParams.get("client_id") ?? "";
    const redirectUri = url.searchParams.get("redirect_uri") ?? "";
    const state = url.searchParams.get("state") ?? "";
    const scope = url.searchParams.get("scope") || SCOPE;
    const challenge = url.searchParams.get("code_challenge") ?? "";
    const challengeMethod = url.searchParams.get("code_challenge_method");
    const responseType = url.searchParams.get("response_type");

    let client;
    await this.store.withLock(async () => {
      const auth = await this.store.readAuth(emptyAuth());
      prune(auth);
      client = auth.clients[clientId];
    });

    if (!client || !client.redirect_uris.includes(redirectUri)) {
      return json(response, 400, { error: "invalid_request", error_description: "Unknown client or redirect URI" });
    }
    if (responseType !== "code" || !state || scope !== SCOPE || challengeMethod !== "S256" || challenge.length < 43) {
      const destination = new URL(redirectUri);
      destination.searchParams.set("error", "invalid_request");
      destination.searchParams.set("state", state);
      return redirect(response, destination);
    }

    const requestId = token(24);
    await this.store.withLock(async () => {
      const auth = await this.store.readAuth(emptyAuth());
      prune(auth);
      auth.requests[requestId] = {
        id: requestId,
        client_id: clientId,
        redirect_uri: redirectUri,
        state,
        scope,
        code_challenge: challenge,
        status: "pending",
        failed_attempts: 0,
        created_at: new Date().toISOString(),
        expires_at: new Date(Date.now() + AUTH_REQUEST_TTL_MS).toISOString(),
      };
      await this.store.writeAuth(auth);
    });

    return html(response, 200, `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>兔酱睡眠守卫 · 授权</title><style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0,#29314d,#0c101a 65%);color:#f8f8ff;font-family:system-ui,-apple-system,sans-serif}.card{width:min(90vw,430px);padding:32px;border:1px solid #ffffff24;border-radius:28px;background:#ffffff10;box-shadow:0 28px 90px #0008}.mark{font-size:42px}.muted{color:#c5cbdb;line-height:1.6}input{width:100%;margin:14px 0;padding:14px 16px;border:1px solid #ffffff2e;border-radius:14px;background:#0b0f19;color:#fff;font-size:16px}button{width:100%;padding:14px;border:0;border-radius:14px;background:#8ba7ff;color:#10182c;font-size:16px;font-weight:700}</style></head><body><main class="card"><div class="mark">🐾</div><h1>允许连接睡眠守卫</h1><p class="muted">${escapeHtml(client.client_name)} 正在请求“开启、解除和查询睡眠守卫”的权限。输入你在服务器环境变量里设置的授权口令。</p><form method="post" action="/oauth/approve"><input type="hidden" name="id" value="${escapeHtml(requestId)}"><input type="password" name="approval_code" autocomplete="current-password" placeholder="授权口令" required><button type="submit">允许这次连接</button></form></main></body></html>`);
  }

  async approve(request, response) {
    if (request.method !== "POST") return json(response, 405, { error: "method_not_allowed" });
    const form = new URLSearchParams(await readBody(request));
    const id = form.get("id") ?? "";
    const approvalCode = form.get("approval_code") ?? "";
    let destination = null;
    let failure = "授权请求不存在或已经失效。";

    await this.store.withLock(async () => {
      const auth = await this.store.readAuth(emptyAuth());
      prune(auth);
      const authRequest = auth.requests[id];
      if (!authRequest || authRequest.status !== "pending") return;
      if (!sameSecret(approvalCode, this.config.ownerApprovalCode)) {
        authRequest.failed_attempts = Number(authRequest.failed_attempts ?? 0) + 1;
        if (authRequest.failed_attempts >= 5) authRequest.status = "rejected";
        await this.store.writeAuth(auth);
        failure = "授权口令不正确。";
        return;
      }
      const code = token(32);
      auth.codes[digest(code)] = {
        client_id: authRequest.client_id,
        redirect_uri: authRequest.redirect_uri,
        scope: authRequest.scope,
        code_challenge: authRequest.code_challenge,
        created_at: new Date().toISOString(),
        expires_at: new Date(Date.now() + AUTH_CODE_TTL_MS).toISOString(),
        used: false,
      };
      authRequest.status = "completed";
      await this.store.writeAuth(auth);
      const callback = new URL(authRequest.redirect_uri);
      callback.searchParams.set("code", code);
      callback.searchParams.set("state", authRequest.state);
      destination = String(callback);
    });
    if (destination) return redirect(response, destination);
    return html(response, 403, `<!doctype html><meta charset="utf-8"><title>授权失败</title><p>${escapeHtml(failure)}</p>`);
  }

  async exchange(request, response) {
    if (request.method !== "POST") return json(response, 405, { error: "method_not_allowed" });
    const form = new URLSearchParams(await readBody(request));
    const code = form.get("code") ?? "";
    const clientId = form.get("client_id") ?? "";
    const redirectUri = form.get("redirect_uri") ?? "";
    const verifier = form.get("code_verifier") ?? "";
    if (form.get("grant_type") !== "authorization_code" || !code || !clientId || !redirectUri || verifier.length < 43) {
      return json(response, 400, { error: "invalid_request" });
    }

    let accessToken = null;
    await this.store.withLock(async () => {
      const auth = await this.store.readAuth(emptyAuth());
      prune(auth);
      const record = auth.codes[digest(code)];
      if (!record || record.used || record.client_id !== clientId || record.redirect_uri !== redirectUri || digest(verifier) !== record.code_challenge) return;
      record.used = true;
      accessToken = token(32);
      auth.tokens[digest(accessToken)] = {
        client_id: clientId,
        scope: record.scope,
        created_at: new Date().toISOString(),
        expires_at: new Date(Date.now() + this.config.accessTokenTtlDays * 86_400_000).toISOString(),
      };
      await this.store.writeAuth(auth);
    });
    if (!accessToken) return json(response, 400, { error: "invalid_grant" });
    return json(response, 200, {
      access_token: accessToken,
      token_type: "Bearer",
      expires_in: this.config.accessTokenTtlDays * 86_400,
      scope: SCOPE,
    });
  }

  async hasOAuthAccess(request) {
    const value = bearer(request);
    if (!value) return false;
    return this.store.withLock(async () => {
      const auth = await this.store.readAuth(emptyAuth());
      prune(auth);
      const record = auth.tokens[digest(value)];
      await this.store.writeAuth(auth);
      return Boolean(record && record.scope === SCOPE && Date.parse(record.expires_at) > Date.now());
    });
  }
}
