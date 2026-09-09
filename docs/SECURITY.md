# Security model

## Secrets

Never commit production private keys or tokens. Generated material is stored under `.local-secrets/`, which is ignored by Git. GitHub production deployment requires encrypted Environment/Repository Secrets.

`ADMIN_TOKEN` protects `/metrics` using `Authorization: Bearer <token>`. Rotate it if it is exposed.

## Network exposure

Production publishes only:

- SSH TCP port (configurable, rate-limited by UFW)
- TCP 80 for HTTP redirect / ACME
- TCP 443 for HTTPS
- UDP 443 for HTTP/3

The Java process listens on port 8080 only inside the Docker private network in `deployment/compose.production.yml` and must not be opened in a cloud security group.

Docker can interact with host firewall rules differently from ordinary processes. The production stack intentionally publishes only the public Caddy ports; it does not publish application port 8080.

## Container hardening

The Java container runs as non-root UID 10001. Production uses a read-only root filesystem, a small `/tmp` tmpfs and `no-new-privileges`. Caddy and application containers are separated; the application is attached only to an internal Docker network.

## HTTP security

The application and Caddy add defensive headers including content-type sniffing protection, frame denial, a restrictive Content Security Policy, referrer restrictions and permissions policy. Caddy adds HSTS on HTTPS responses.

## Key rotation

1. Generate a replacement key/token into a new local secret directory.
2. Add the new public SSH key to the production account.
3. Update GitHub production secrets.
4. Complete and verify a deployment using the replacement credentials.
5. Remove the old authorized SSH key and revoke the old token.

Do not remove old credentials before the replacement is tested.
