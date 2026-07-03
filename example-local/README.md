# example-local — dev harness against a local admin

Unlike `:example` (the feature showcase), this app is a development tool: it points at a
**locally running bunting-admin** and fetches, verifies, and evaluates real published
config end-to-end. No bundled seed, no codegen — a status card plus the debug panel.

## Setup

1. Start the admin stack (in `../bunting-admin`):
   ```bash
   make setup && make dev
   ```
2. Log in at http://localhost:3000 (dex: `admin@bunting.dev` / `password`) and create an
   app with identifier **`local-demo`** (or any identifier — pass it to the script below).
   App creation publishes an initial config automatically.
3. Generate this app's bootstrap (reads the signing key from the admin's Postgres
   container and points the endpoint at the local MinIO bucket):
   ```bash
   ./scripts/local-bootstrap.sh                # app local-demo, host 10.0.2.2
   ```
4. Run the `example-local` configuration on an emulator. Edit flags in the dashboard,
   publish, then tap **Refresh from local admin**.

## Notes

- `10.0.2.2` is the emulator's alias for the host's localhost. On a physical device,
  regenerate with your machine's IP (`./scripts/local-bootstrap.sh local-demo 192.168.x.x`)
  and make sure MinIO's port 9000 is reachable.
- The app allows cleartext HTTP (`usesCleartextTraffic`) because the local MinIO has no
  TLS. Don't copy that into a production app.
- The checked-in `assets/bunting_config.json` is a placeholder with no keys; the app
  shows a hint until you run the script.
