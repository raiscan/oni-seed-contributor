# ONI Seed Contributor

![Kotlin](https://img.shields.io/badge/kotlin-2.3.20-blue.svg?logo=kotlin)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
![JVM](https://img.shields.io/badge/-JVM-gray.svg?style=flat)
![WASM](https://img.shields.io/badge/-WASM-gray.svg?style=flat)
[![GitHub Sponsors](https://img.shields.io/badge/Sponsor-gray?&logo=GitHub-Sponsors&logoColor=EA4AAA)](https://github.com/sponsors/StefanOltmann)

Headless ONI seed contributor — runs the
[onimaxxing](https://onimaxxing.com) worldgen WASM module on the JVM,
mints random coordinates, generates clusters, and uploads them to the
[oni-seed-browser-backend](https://github.com/StefanOltmann/oni-seed-browser-backend).
The server-side equivalent of leaving the
[oni-seed-browser](https://github.com/StefanOltmann/oni-seed-browser)
contribute view open in a desktop tab.

## Run via Docker

```bash
docker run --rm -p 8080:8080 \
  -e STEAM_AUTH_TOKEN=eyJ... \
  ghcr.io/stefanoltmann/oni-seed-contributor:latest
```

The container auto-starts the contributor loop at boot. No volume
mount required — a fresh installation UUID is generated per process
(set `INSTALLATION_ID` to a UUID if you want it stable across restarts;
the backend dedupes by Steam ID + coordinate either way).

The official image bakes `MNI_API_KEY_BROWSER` in at build time. If you
build your own image and don't bake it in, pass it explicitly via
`-e MNI_API_KEY_BROWSER=...`; the service refuses to start without it.

A 1 GB memory limit is comfortable; the V8 + WASM heap typically sits
~150 MB at idle and grows to 300–500 MB under load.

### Multi-arch image build (amd64 + arm64)

Docker buildx is required for native multi-arch images:

```bash
docker buildx create --use --name oni-seed-contributor-builder
docker buildx inspect --bootstrap
docker buildx build --platform linux/amd64,linux/arm64 \
  --build-arg MNI_API_KEY_BROWSER=$MNI_API_KEY_BROWSER \
  -t your-registry/oni-seed-contributor:latest --push .
```

Notes:

- Multi-arch builds must be pushed to a registry; `--load` only loads a single-arch image locally.
- If you only want one architecture locally, use `--platform linux/amd64` (or `linux/arm64`) with `--load`.
- The runtime base is `eclipse-temurin:25-jre` (Debian-slim). **Do not switch to Alpine** — Javet ships no musl-libc native and the JAR will fail to load `libnode`.

## Run locally (development)

Requirements: a JDK supported by foojay (Gradle auto-downloads JDK 25 on first build).

```bash
export STEAM_AUTH_TOKEN=eyJ...
export MNI_API_KEY_BROWSER=...
export AUTO_START=false
./gradlew run                                  # boots on :8080
curl http://localhost:8080/generate/LUSH-A-867734350-0-0-0
```

Cold start is ~1–3 s while V8 boots and the WASM is instantiated.
First request after cold start sees that latency; subsequent requests
hit the cached runtime.

## API

```
GET /                       → version banner
GET /generate/{coord}       → generated Cluster as JSON (debug-only; runs WASM, returns the parsed Cluster)
GET /status                 → current ContributorService state (running, counters, lastError, …)
GET /start                  → start the contributor loop  (header `API_KEY: $CONTROL_API_KEY` required)
GET /stop                   → stop the contributor loop   (header `API_KEY: $CONTROL_API_KEY` required)
```

`/start` and `/stop` return `403 Forbidden` if `CONTROL_API_KEY` is
unset (the default) and `401 Unauthorized` on header mismatch.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `STEAM_AUTH_TOKEN` | *(required)* | The MNI/Steam JWT — copy it from the oni-seed-browser frontend's auth flow. Service refuses to start if the token is missing, malformed, missing the `sub`/`steamId` claim, or already past `exp`. |
| `MNI_API_KEY_BROWSER` | *(required)* | Backend API key. Baked into the official Docker image; pass via `-e` if you build your own. |
| `SERVER_URL` | `https://mni.stefan-oltmann.de` | Backend root; appends `/upload`. Override only if you're running your own backend. |
| `INSTALLATION_ID` | *(auto)* | A UUID. If unset, one is generated per process — fine in most cases since the backend dedupes by Steam ID + coordinate. Set explicitly if you want a stable ID across container restarts. |
| `AUTO_START` | `true` | Set to `false` to keep the loop idle at boot — start it later via `GET /start`. |
| `CONTROL_API_KEY` | *(unset)* | When set, `/start` and `/stop` require `API_KEY: <this>` header. When unset, both endpoints return 403. |
| `WORLDGEN_PORT` | `8080` | HTTP listen port. |

## Throttling & error handling

The contributor loop mirrors `oni-seed-browser`'s `MapGenerationView`:

- After each successful upload: continue at the current delay (default 500 ms).
- `409 Conflict` (coord already known): record + continue.
- `429 Too Many Requests`: bump delay by 100 ms, pause 1 s, retry. If the delay grows past 5 s, the loop stops itself; the operator can resume via `GET /start`.
- Any other non-2xx or network failure: wait 30 s, then continue.

## Tests

```bash
./gradlew test                                # 11 tests (1 V8/WASM, 4 service, 6 JWT)
```

The V8/WASM integration test requires a Javet native for the host
platform. CI runs the full suite as part of the Docker build stage on
Linux x86_64 — that's the canonical environment.

## Architecture

Flat-file layers in `src/main/kotlin/`:

```
Application.kt              composition root: parse token, build deps, auto-start
Routings.kt                 GET /, /generate/{coord}, /status, /start, /stop
ContributorService.kt       the work loop + state machine
WorldgenRuntime.kt          V8/Node + WASM lifecycle, mutex, JS strip
BackendClient.kt            ktor-client wrapper around POST /upload
SteamAuthToken.kt           JWT parsing + minimal validation
InstallationId.kt           load-or-create the persistent per-install UUID
RandomCoordinate.kt         generateRandomCoordinate() port
WorldgenModels.kt           vendored from oni-seed-browser frontend
WorldgenMapDataConverter.kt vendored from oni-seed-browser frontend
UploadClusterConverter.kt   vendored from oni-seed-browser frontend
```

## License & credits

AGPL-3.0. The bundled WASM module
(`src/main/resources/worldgen/`) is the upstream
`@tigin-backwards/oxygen-not-included-worldgen` v2.0.1 npm package
(MIT). The `WorldgenModels`, `WorldgenMapDataConverter`, and
`UploadClusterConverter` Kotlin sources are vendored verbatim from the
[oni-seed-browser](https://github.com/StefanOltmann/oni-seed-browser)
frontend. If they ever land in `oni-seed-browser-model`, the vendored
copies should be deleted in favor of the dependency.
