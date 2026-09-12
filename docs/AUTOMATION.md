# Hourly Build, Release and Dependency Updates

## Hourly release workflow

`.github/workflows/release.yml` runs automatically once every hour at minute 17 (`17 * * * *`). Scheduled workflows use the latest commit on the default branch.

Each scheduled run performs the full release path:

1. Resolve a unique prerelease version such as `v0.0.0-hourly.20260912T1117Z.sha04afa94`.
2. Restore/warm the Maven cache and run `mvn clean verify` on Java 21.
3. Validate shell scripts, Render configuration and production Docker Compose.
4. Build the Apache HTTP Server image and run `httpd -t`.
5. Validate the Caddy production configuration.
6. Build and start the local Docker Compose stack (`Apache -> Java`).
7. Verify health, browser game, file-transfer UI, authenticated metrics, upload, download, HTTP Range and delete operations through Apache.
8. Build the JAR and full `game-server-bundle.tar.gz` package.
9. Generate SHA-256 checksums and `BUILD-INFO.txt`.
10. Upload a short-lived GitHub Actions artifact (7-day retention).
11. Publish a GitHub prerelease with the JAR, bundle, checksums and build metadata.

Scheduled releases are prereleases so they do not replace manually published stable versions such as `v1.1.0`.

The workflow remains compatible with tag releases (`v*`) and manual `workflow_dispatch` releases. A manually supplied version can be marked as a prerelease with the workflow input.

> GitHub scheduled jobs can start later than the exact cron minute when the Actions service is busy. The schedule is once per hour, but it is not a real-time scheduler.

## Dependabot

`.github/dependabot.yml` enables version updates for:

- Maven dependencies/plugins in `pom.xml`
- GitHub Actions used in `.github/workflows`
- the root Java Dockerfile
- the Apache Dockerfile under `deployment/apache`
- root Docker Compose images
- production Docker Compose images under `deployment`

Checks run daily in the `Asia/Seoul` timezone at staggered times between 04:05 and 04:55 to reduce simultaneous update jobs.

Dependabot creates update pull requests. It does not bypass Maven CI or automatically merge breaking changes; each PR goes through the repository's normal build and integration validation before it is merged.

## Release storage note

An hourly GitHub Release creates approximately 24 prereleases per day. Workflow artifacts are limited to 7-day retention, but GitHub Release assets remain until a repository maintainer removes them. This preserves release history and avoids automatic destructive cleanup.
