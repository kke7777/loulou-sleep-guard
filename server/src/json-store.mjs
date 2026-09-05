import { appendFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

async function readJson(path, fallback) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return structuredClone(fallback);
    throw error;
  }
}

export class JsonStore {
  #tail = Promise.resolve();

  constructor(dataDir) {
    this.dataDir = dataDir;
    this.statePath = join(dataDir, "state.json");
    this.authPath = join(dataDir, "auth.json");
    this.eventsPath = join(dataDir, "events.jsonl");
  }

  async init() {
    await mkdir(this.dataDir, { recursive: true, mode: 0o700 });
  }

  withLock(operation) {
    const run = this.#tail.then(operation, operation);
    this.#tail = run.catch(() => undefined);
    return run;
  }

  readState(fallback) {
    return readJson(this.statePath, fallback);
  }

  writeState(value) {
    return this.#atomicWrite(this.statePath, value);
  }

  readAuth(fallback) {
    return readJson(this.authPath, fallback);
  }

  writeAuth(value) {
    return this.#atomicWrite(this.authPath, value);
  }

  async appendEventOnce(value) {
    let existing = "";
    try { existing = await readFile(this.eventsPath, "utf8"); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    const lines = existing.split("\n");
    if (lines.some(line => { try { return JSON.parse(line).id === value.id; } catch { return false; } })) return;
    // Recover a partially written final line before appending after a crash.
    if (existing && !existing.endsWith("\n")) {
      await writeFile(this.eventsPath, existing.slice(0, existing.lastIndexOf("\n") + 1), { mode: 0o600 });
    }
    await this.appendEvent(value);
  }

  async appendEvent(value) {
    await mkdir(dirname(this.eventsPath), { recursive: true, mode: 0o700 });
    await appendFile(this.eventsPath, `${JSON.stringify(value)}\n`, { encoding: "utf8", mode: 0o600 });
  }

  async #atomicWrite(path, value) {
    await mkdir(dirname(path), { recursive: true, mode: 0o700 });
    const temporary = `${path}.${process.pid}.${Date.now()}.tmp`;
    await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, path);
  }
}

