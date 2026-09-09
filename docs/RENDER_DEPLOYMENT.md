# Render real hosting

`render.yaml` provides a real public hosting path that does not require `PROD_HOST`, an SSH server, UFW, Caddy, or a user-owned domain.

## Default Blueprint

The root `render.yaml` creates:

- Docker web service: `game-sources`
- Region: Singapore
- Branch: `main`
- Health check: `/ready`
- Public Render subdomain enabled
- Auto deploy only after linked CI checks pass
- Generated random `ADMIN_TOKEN`
- `HOST=0.0.0.0`
- Free compute plan by default to avoid unexpected charges

The application reads Render's `PORT` environment variable automatically, so it is not hard-coded to 8080 in hosted operation.

## Always-on note

The default Blueprint intentionally uses Render's free compute plan. Free hosting can have platform limitations and should not be treated as an always-on SLA. For a continuously running production instance, upgrade the Render service to a paid instance type (for example Starter) in Render after explicitly accepting the cost.

## GitHub Actions deployment

If a Render service already exists, add these GitHub production secrets:

- `RENDER_DEPLOY_HOOK_URL`
- `RENDER_SERVICE_URL` (for example the service's `https://...onrender.com` URL)

Then run **Production Deploy** with target `render` or `auto`.

The workflow triggers the Render deploy hook and waits up to 10 minutes for:

- `/ready` -> UP
- `/health` -> UP
- `/` -> Game Sources Arena

## Custom domain

A custom domain is optional. The service is available at its Render subdomain immediately after a successful deploy. Custom DNS can be added later from Render without changing the Java server.

## ADMIN_TOKEN

The Blueprint generates `ADMIN_TOKEN` on the hosting platform. Do not commit its value to GitHub. `/metrics` remains protected by Bearer authentication.
