# Deployment files

Use `compose.production.yml` for production. `compose.prod.yml` is retained for compatibility with the earlier server setup.

- `compose.production.yml`: hardened production stack; requires `ADMIN_TOKEN`.
- `Caddyfile`: HTTPS reverse proxy and security headers.
- `prod.env.example`: public configuration template only; use the secret generator for real values.
- `game-sources.service`: optional hardened systemd unit for direct-JAR deployments.
- `game-sources.env.example`: direct-JAR loopback binding template.

For complete instructions, see `../docs/DEPLOYMENT.md`.
