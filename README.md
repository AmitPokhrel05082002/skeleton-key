# Skeleton Key

A lean low-code skeleton for workflow apps: one JSON app definition describes roles, entities, forms, views and
workflows, and the skeleton runs it. See `schema/app-definition.schema.json` and `examples/grievance.app.json`.

## Running locally

### Prerequisites

- Docker Desktop (Compose v2) for the full stack.
- For development without containers: Java 21, Node 22 with npm 11.6.2 (the version pinned in
  `skeleton/frontend/package.json`), and a PostgreSQL 16 database.

Commands below use Windows CMD syntax.

### Environment variables

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `SKELETONKEY_JWT_SECRET` | yes | none | Signing key for login tokens, at least 32 characters. Compose refuses to start without it. Set it in the shell; never write it to a file in the repository. |
| `POSTGRES_PASSWORD` | no | `skeletonkey` | Password of the PostgreSQL user, used by both the database and the backend. Change it for anything but a local demo. |
| `FRONTEND_PORT` | no | `80` | Host port of the web frontend (nginx). |
| `SKELETONKEY_SEED_FILE` | no | empty | Path **inside the backend container** of a JSON file with users to create at startup (see below). |

Example for one CMD session (use your own random value of 64 characters):

```cmd
set SKELETONKEY_JWT_SECRET=<64 random characters>
set FRONTEND_PORT=8081
```

### Start the stack

```cmd
docker compose up -d --build
docker compose ps
```

`docker compose ps` shows `postgres`, `app` and `frontend` as `healthy` when the stack is ready (the backend
takes up to a minute on first start). The definitions in `examples/` are loaded and published at startup.

The backend port is bound to the loopback address only (`127.0.0.1:8080`), so from other machines the stack is
reachable only through nginx. The backend health check is at http://127.0.0.1:8080/actuator/health; nginx
forwards only `/api/...` to the backend, so the health endpoint is not available through http://localhost/.

Stop the stack and keep the database volume:

```cmd
docker compose down
```

### Create the first admin with a seed file

Users are created from a seed file at backend startup. Existing users (matched by username, ignoring case) are left
unchanged, so the file can stay configured. Keep the file **outside the repository**, because it contains a
password, for example `%USERPROFILE%\skeletonkey\seed.json`:

```json
{
  "users": [
    {
      "username": "admin",
      "password": "<choose-a-password-of-8-or-more-characters>",
      "roles": ["PLATFORM_ADMIN"]
    },
    {
      "username": "clerk1",
      "password": "<another-password>",
      "roles": ["CLERK"],
      "active": true
    }
  ]
}
```

Each user has `username`, either `password` (hashed at startup) or `passwordHash` (an existing BCrypt hash),
optional `roles` and optional `active` (default `true`). `PLATFORM_ADMIN` is the platform admin role
(`skeletonkey.admin-role`); the other role codes come from the app definitions.

The compose file does not mount a seed file, so add a local `docker-compose.override.yml` next to
`docker-compose.yml` (Compose reads it automatically) that mounts yours read-only:

```yaml
services:
  app:
    volumes:
      - ${USERPROFILE}/skeletonkey/seed.json:/run/seed/seed.json:ro
```

Then start with the in-container path:

```cmd
set SKELETONKEY_SEED_FILE=/run/seed/seed.json
docker compose up -d
```

After the first admin exists, manage all other users at `/admin/users`, and remove the seed password from your
file.

### Demo URLs

With the default `FRONTEND_PORT` (replace `80` with your port otherwise):

| URL | What it is |
|---|---|
| http://localhost/login | Sign in. |
| http://localhost/public/GRIEVANCE/REPORT_ISSUE | Public grievance form, no login. The confirmation links to tracking. |
| http://localhost/public/GRIEVANCE/track | Public status tracking by reference number and the contact email. |
| http://localhost/admin | Administration: app definitions (requires `PLATFORM_ADMIN`). |
| http://localhost/admin/users | User administration (requires `PLATFORM_ADMIN`). |

The public track endpoint is rate-limited per client address (`skeletonkey.track.rate-limit.per-minute`,
default 10 per minute). Behind nginx the client address comes from `X-Real-IP`, which the backend trusts only from
the addresses in `skeletonkey.trusted-proxies` (loopback and private ranges by default).

Deactivating a user at `/admin/users` signs them out on their next request. Role changes take effect at their
next login, because roles travel in the token until it expires (`skeletonkey.jwt.expiry-seconds`, default 3600).

### Development setup (without containers)

1. Start a PostgreSQL 16 database, for example in Docker:

   ```cmd
   docker run -d --name skeletonkey-dev-db -e POSTGRES_DB=skeletonkey -e POSTGRES_USER=skeletonkey ^
     -e POSTGRES_PASSWORD=skeletonkey -p 127.0.0.1:5432:5432 postgres:16
   ```

2. Run the backend from `skeleton\backend` (Flyway creates the schema on start):

   ```cmd
   cd skeleton\backend
   set SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/skeletonkey
   set SPRING_DATASOURCE_USERNAME=skeletonkey
   set SPRING_DATASOURCE_PASSWORD=skeletonkey
   set SKELETONKEY_JWT_SECRET=<64 random characters>
   set SKELETONKEY_DEFINITIONS_DIR=..\..\examples
   set SKELETONKEY_SEED_FILE=%USERPROFILE%\skeletonkey\seed.json
   mvnw.cmd spring-boot:run
   ```

   The API listens on http://localhost:8080 (OpenAPI UI at http://localhost:8080/swagger-ui.html).

3. Run the frontend from `skeleton\frontend` in a second terminal:

   ```cmd
   cd skeleton\frontend
   npm ci
   npm start
   ```

   `npm start` runs `ng serve` with `proxy.conf.json`, which forwards `/api` to http://localhost:8080. Open
   http://localhost:4200/.

4. Tests: `mvnw.cmd test` in `skeleton\backend` (the `*IT` tests and `ApplicationContextTest` need Docker for
   Testcontainers) and `npm test` in `skeleton\frontend`.
