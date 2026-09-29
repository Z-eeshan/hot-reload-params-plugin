# Contributing

## Build Locally

```bash
mvn clean verify
```

Artifacts are produced in `target/`:

- `target/hot-reload-params.hpi` -- the installable plugin file

## Run Tests

```bash
mvn test
```

The tests create a throw-away local Git repository and exercise the plugin's
endpoints against a `JenkinsRule` instance, so no network access is needed.

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
  HotReloadParameterDefinition.java   # ParameterDefinition + descriptor with the fetchParams / triggerBuild endpoints
  ConfigFetcher.java                  # JGit-based Git file fetcher with caching
  ParamConfigParser.java              # Structural Groovy DSL parser

src/main/resources/
  index.jelly                         # Plugin description
  io/github/zeeshan/hotreloadparams/HotReloadParameterDefinition/
    config.jelly                      # Job configuration form
    index.jelly                       # Build-with-Parameters page (JS injection)
    hot-reload-params.js              # Client-side logic
```

### Request flow

1. `index.jelly` renders a marker element carrying the job name, the trigger
   parameter name and the descriptor URL, and loads `hot-reload-params.js`.
2. The script POSTs `job` + `triggerValue` to `fetchParams`. The descriptor
   resolves the job, checks `Item.BUILD`, reads the Git configuration from the
   job's `HotReloadParameterDefinition`, fetches and parses the file and
   returns the parameter list as JSON.
3. The script updates / hides / creates parameter rows and points the Build
   form at `triggerBuild`.
4. `triggerBuild` re-fetches the file for the submitted trigger value and
   accepts only parameters declared on the job or defined in that file.
