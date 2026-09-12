# Release readiness race fix

The hourly/manual Build Release workflow now waits for both the Java application and Apache reverse proxy Docker health checks before making external HTTP requests.

The previous workflow started `curl http://127.0.0.1:8080/health` immediately after `docker compose up -d --build`. Docker Compose had already started the Apache container, but Apache had not finished accepting connections yet, which could produce `curl: (56) Recv failure: Connection reset by peer`.

The release smoke test now:

- starts Compose with `--wait --wait-timeout 180`;
- confirms both `game-sources-server` and `game-sources-apache` are `healthy`;
- retries transient connection/reset failures with `curl --retry-all-errors`;
- prints `docker compose ps` and full Compose logs automatically if the smoke test fails;
- preserves the existing authenticated upload, download, HTTP Range, delete, metrics, game, and Apache checks.
