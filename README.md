# compassadmin

This project was created using the [Ktor Project Generator](https://start.ktor.io).

## Building & Running

To build or run the project, use one of the following tasks:

| Task              | Description       |
|-------------------|-------------------|
| `./gradlew test`  | Run the tests     |
| `./gradlew build` | Build the project |
| `./gradlew run`   | Run the server    |
| `./gradlew buildImage` | Build a Docker image tarball with Jib |
| `./gradlew publishImageToLocalRegistry` | Build `compassadmin:1` into the local Docker daemon |

If the server starts successfully, you'll see the following output:

```text
Application started
Responding at http://0.0.0.0:9001
```

## Docker

The Gradle Ktor plugin builds the image through Jib. Secret and mutable runtime files are not copied into the image:

- `application.yaml` is mounted as `/app/config/application.yaml`
- `google/credentials.json` is mounted as `/app/google/credentials.json`
- `tokens` is mounted as `/app/tokens`
- SQLite data is mounted as `/app/data`

```shell
cp application.example.yaml application.yaml
mkdir -p google tokens data
# put google/credentials.json in place and replace placeholders in application.yaml

./gradlew publishImageToLocalRegistry
docker run --rm -p 9001:9001 \
  -v "$PWD/application.yaml:/app/config/application.yaml:ro" \
  -v "$PWD/google:/app/google" \
  -v "$PWD/tokens:/app/tokens" \
  -v "$PWD/data:/app/data" \
  compassadmin:1
```

On Windows PowerShell:

```powershell
Copy-Item application.example.yaml application.yaml
New-Item -ItemType Directory -Force google, tokens, data
# put google\credentials.json in place and replace placeholders in application.yaml

.\gradlew.bat publishImageToLocalRegistry
docker run --rm -p 9001:9001 `
  -v "${PWD}\application.yaml:/app/config/application.yaml:ro" `
  -v "${PWD}\google:/app/google" `
  -v "${PWD}\tokens:/app/tokens" `
  -v "${PWD}\data:/app/data" `
  compassadmin:1
```

`templates`, `static`, and `system_instructions.txt` are copied into the image. `application.yaml`, `google`, `tokens`, and the SQLite database are mounted as volumes; the container uses `/app/data/compassadmin.db` via `COMPASS_DB_PATH`.

If you already have `compassadmin.db` in the project root, move or copy it to `data/compassadmin.db` before running the container. If the file is absent, the application creates the SQLite schema on startup.

## GitHub image publishing

`.github/workflows/docker-image.yml` builds and publishes the image to GitHub Container Registry:

```text
ghcr.io/<owner>/<repository>:<commit-sha>
ghcr.io/<owner>/<repository>:latest
```

The workflow does not need application secrets because they are only used at runtime. On the server, copy `docker-compose.example.yml` to `docker-compose.yml`, replace `ghcr.io/OWNER/REPOSITORY:latest`, and keep these files next to it:

```text
application.yaml
google/credentials.json
tokens/
data/compassadmin.db
```

Then run:

```shell
docker compose pull
docker compose up -d
```
