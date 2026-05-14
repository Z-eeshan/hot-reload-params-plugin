# Contributing

## Build Locally

```bash
mvn clean package
```

Artifacts are produced in `target/`:

- `target/hot-reload-params.hpi` -- the installable plugin file
- `target/hot-reload-params.jar` -- the JAR library

## Run Tests

```bash
mvn test
```

## Build with Docker

No local JDK or Maven installation required:

```bash
# Build the Docker image
docker build -t hot-reload-params-builder .

# Run it and export artifacts to ./out/
mkdir -p out
docker run --rm -v "$(pwd)/out:/out" hot-reload-params-builder
```

The `.hpi` and `.jar` files will be in the `out/` directory.

## Run for Local Development

Start a Jenkins instance with the plugin loaded for testing:

```bash
mvn hpi:run
```

Jenkins will be available at `http://localhost:8080/jenkins/`.

## Architecture

```
src/main/java/io/github/zeeshan/hotreloadparams/
  HotReloadParameterDefinition.java   # ParameterDefinition extension + AJAX descriptor
  HotReloadParameterValue.java        # Composite ParameterValue (env var injection)
  ConfigFetcher.java                  # JGit-based Git file fetcher with caching
  ParamConfigParser.java              # Structural Groovy DSL parser
  model/
    ParamType.java                    # Enum of supported DSL parameter types
    ParamConfig.java                  # Parsed parameter config data class

src/main/resources/
  index.jelly                         # Plugin description
    .../HotReloadParameterDefinition/
    config.jelly                      # Job configuration form
    index.jelly                       # Build-with-Parameters page (JS injection)
    hot-reload-params.js              # Client-side AJAX logic
```
