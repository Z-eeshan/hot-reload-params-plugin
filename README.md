# Hot Reload Parameters Plugin for Jenkins

A Jenkins plugin that dynamically reloads parameter default values on the **Build with Parameters** page when a trigger parameter changes. It fetches a Groovy DSL file from a Git branch matching the trigger value and updates all other parameter defaults in-place via AJAX -- no page reload required.

## How It Works

1. You define your pipeline parameters normally (`string`, `booleanParam`, `choice`, `imageTag`, etc.) in a `Jenkinsfile` or a shared-library Groovy file.
2. You add a single `hotReloadParams(...)` entry that tells the plugin which Git repo, file path, and trigger parameter to watch.
3. On the **Build with Parameters** page, when a user changes the trigger parameter (e.g. `RELEASE_BRANCH`), the plugin:
   - Fetches the Groovy DSL file from the Git branch matching the new value
   - Parses all `parameters {}` definitions from that branch
   - Updates default values of existing fields on the page
   - Hides parameters that don't exist on the target branch
   - Creates placeholder inputs for parameters that are new on the target branch

### Branch Resolution

The plugin resolves the trigger value to a Git branch in this order:

1. Exact match (e.g. `release/v1.1.0`)
2. Suffix after the last `/` (e.g. `v1.1.0` from `release/v1.1.0`)
3. Fallback to the configured `defaultBranch` (e.g. `master`)

Results are cached in-memory for 60 seconds to avoid redundant Git clones.

## Prerequisites

### Jenkins Version

- **Jenkins 2.440** or newer

### Java Version

- **Java 17** or newer (both for building and at runtime)

### Required Jenkins Plugins

These must be installed on your Jenkins instance before loading this plugin:

| Plugin                                                              | Minimum Version |
| ------------------------------------------------------------------- | --------------- |
| [Git](https://plugins.jenkins.io/git/)                              | 5.2.1           |
| [Git Client](https://plugins.jenkins.io/git-client/)                | 4.6.0           |
| [Credentials](https://plugins.jenkins.io/credentials/)              | 1337.v60b       |
| [Plain Credentials](https://plugins.jenkins.io/plain-credentials/)  | 179.vc5cb       |
| [Workflow: Step API](https://plugins.jenkins.io/workflow-step-api/) | 657.v03b        |
| [Workflow: CPS](https://plugins.jenkins.io/workflow-cps/)           | 3883.vb_3ff     |
| [Structs](https://plugins.jenkins.io/structs/)                      | 337.v1b         |
| [Script Security](https://plugins.jenkins.io/script-security/)      | 1326.vdb        |

### Git Credentials

If the target Git repository is private, configure a **Username/Password** credential in Jenkins (Manage Jenkins > Credentials) and reference its ID in the plugin configuration.

## Installation

### From .hpi file

1. Build the plugin (see [Building](#building) below) or download a release `.hpi`.
2. Go to **Manage Jenkins > Plugins > Advanced settings**.
3. Under **Deploy Plugin**, upload the `hot-reload-params.hpi` file.
4. Restart Jenkins if prompted.

## Usage

### Declarative Pipeline (Jenkinsfile)

```groovy
pipeline {
    agent any

    parameters {
        string(name: 'RELEASE_BRANCH', defaultValue: 'master', description: 'Branch to load parameters from')
        string(name: 'DEPLOY_ENV', defaultValue: 'staging', description: 'Target deployment environment')
        string(name: 'API_VERSION', defaultValue: 'v1', description: 'API version to deploy')
        booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip the test suite')
        booleanParam(name: 'NOTIFY_SLACK', defaultValue: true, description: 'Send Slack notification on completion')

        hotReloadParams(
            repoUrl: 'https://github.com/your-org/your-repo.git',
            credentialsId: 'my-git-credentials',
            paramFilePath: 'vars/release-pipeline.groovy',
            triggerParamName: 'RELEASE_BRANCH',
            defaultBranch: 'master'
        )
    }

    stages {
        stage('Build') {
            steps {
                echo "Building branch: ${params.RELEASE_BRANCH}"
                echo "Deploy env: ${params.DEPLOY_ENV}"
                echo "API version: ${params.API_VERSION}"
            }
        }
    }
}
```

### Groovy DSL File

The `paramFilePath` points to any Groovy file in your repo that contains a `parameters {}` block. The plugin fetches this file from the Git branch matching the trigger value.

For example, if your `paramFilePath` is `vars/release-pipeline.groovy`, the plugin reads that file from whichever branch the user selects.

#### `vars/release-pipeline.groovy` on `master`

```groovy
parameters {
    string(name: 'DEPLOY_ENV', defaultValue: 'staging', description: 'Target deployment environment')
    string(name: 'API_VERSION', defaultValue: 'v1', description: 'API version to deploy')
    booleanParam(name: 'SKIP_TESTS', defaultValue: false, description: 'Skip the test suite')
    booleanParam(name: 'NOTIFY_SLACK', defaultValue: true, description: 'Send Slack notification on completion')
    choice(name: 'LOG_LEVEL', choices: ['INFO', 'DEBUG', 'WARN'], description: 'Application log level')
}
```

#### `vars/release-pipeline.groovy` on `feature/payments-v2`

```groovy
parameters {
    string(name: 'DEPLOY_ENV', defaultValue: 'dev', description: 'Target deployment environment')
    string(name: 'API_VERSION', defaultValue: 'v2', description: 'API version to deploy')
    booleanParam(name: 'SKIP_TESTS', defaultValue: true, description: 'Skip the test suite')
    booleanParam(name: 'NOTIFY_SLACK', defaultValue: false, description: 'Send Slack notification on completion')
    string(name: 'FEATURE_FLAG', defaultValue: 'payments-v2-enabled', description: 'Feature flag to activate')
    choice(name: 'LOG_LEVEL', choices: ['DEBUG', 'INFO', 'WARN'], description: 'Application log level')
}
```

When a user types `feature/payments-v2` in the `RELEASE_BRANCH` field, the plugin:

- Updates `DEPLOY_ENV` from `staging` to `dev`
- Updates `API_VERSION` from `v1` to `v2`
- Flips `SKIP_TESTS` to `true` and `NOTIFY_SLACK` to `false`
- Creates a new `FEATURE_FLAG` input (since it doesn't exist on `master`)
- All changes happen instantly on the page without a reload

### hotReloadParams Configuration Options

| Parameter          | Required | Default                        | Description                                                                   |
| ------------------ | -------- | ------------------------------ | ----------------------------------------------------------------------------- |
| `repoUrl`          | Yes      | --                             | Git repository URL containing the DSL file                                    |
| `credentialsId`    | No       | `""`                           | Jenkins credentials ID for Git authentication                                 |
| `paramFilePath`    | No       | `vars/release-pipeline.groovy` | Path to the Groovy file within the repo that contains a `parameters {}` block |
| `triggerParamName` | No       | `RELEASE_BRANCH`               | Name of the parameter that triggers a reload                                  |
| `defaultBranch`    | No       | `master`                       | Fallback branch when the trigger value doesn't match                          |

### Supported Parameter Types

| Type          | DSL Function        | Reloaded Fields             |
| ------------- | ------------------- | --------------------------- |
| String        | `string(...)`       | `defaultValue`              |
| Boolean       | `booleanParam(...)` | `defaultValue`              |
| Password      | `password(...)`     | `defaultValue`              |
| Choice        | `choice(...)`       | `choices` + default (first) |
| Image Tag     | `imageTag(...)`     | `defaultTag`                |
| Active Choice | `activeChoice(...)` | visibility                  |
| Separator     | `separator(...)`    | visibility                  |

### Job Configuration UI

You can also configure the plugin through the Jenkins UI:

1. In your job, check **This project is parameterized**.
2. Click **Add Parameter** and select **Hot Reload Parameters**.
3. Fill in the Git repository URL, credentials, file path, trigger parameter name, and default branch.

### Cache Management

The plugin caches fetched file contents for 60 seconds. To force a refresh, an administrator can hit the clear-cache endpoint:

```
GET ${JENKINS_URL}/descriptorByName/io.github.zeeshan.hotreloadparams.HotReloadParameterDefinition/clearCache
```

This requires `Jenkins.ADMINISTER` permission.

## Building

### Requirements

- **JDK 17+**
- **Maven 3.8+**

### Build Locally

```bash
mvn clean package
```

Artifacts are produced in `target/`:

- `target/hot-reload-params.hpi` -- the installable plugin file
- `target/hot-reload-params.jar` -- the JAR library

### Run Tests

```bash
mvn test
```

### Build with Docker

No local JDK or Maven installation required:

```bash
# Build the Docker image
docker build -t hot-reload-params-builder .

# Run it and export artifacts to ./out/
mkdir -p out
docker run --rm -v "$(pwd)/out:/out" hot-reload-params-builder
```

The `.hpi` and `.jar` files will be in the `out/` directory.

### Run for Local Development

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

## License

See [LICENSE](LICENSE) for details.
