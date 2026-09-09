# Downloads and integrity checks

## CI bundle

Every successful `main` run of **Maven CI** creates the `game-server-bundle` Actions artifact. It contains:

- executable `game-sources.jar`
- Docker and Compose configuration
- production Caddy configuration
- deployment/install/firewall/key scripts
- documentation and license
- SHA-256 checksum

The CI artifact is retained for 30 days.

## Versioned releases

Run **Build Release** manually with a version such as `v1.0.0`, or push a `v*` tag. The workflow publishes:

- `game-sources.jar`
- `game-sources.jar.sha256`
- `game-server-bundle.tar.gz`
- `game-server-bundle.tar.gz.sha256`

## Verify downloads

Linux/macOS:

```bash
sha256sum -c game-server-bundle.tar.gz.sha256
sha256sum -c game-sources.jar.sha256
```

Windows PowerShell:

```powershell
(Get-FileHash .\game-server-bundle.tar.gz -Algorithm SHA256).Hash
(Get-Content .\game-server-bundle.tar.gz.sha256)
```

Compare the hash values before extracting or running a downloaded artifact.
