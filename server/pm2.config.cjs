module.exports = {
  apps: [{
    name: "loulou-sleep-guard",
    script: "src/server.mjs",
    cwd: __dirname,
    node_args: "--env-file=.env",
    env: { SLEEP_GUARD_PM2_ENTRY: "1" },
    autorestart: true,
    max_memory_restart: "300M",
    time: true
  }]
};
