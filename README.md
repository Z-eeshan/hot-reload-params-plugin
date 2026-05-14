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

### Git Credentials

If the target Git repository is private, configure a **Username/Password** credential in Jenkins (Manage Jenkins > Credentials) and reference its ID in the plugin configuration.

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

### Supported Parameter Syntax

The parser recognises both the standard Groovy DSL form with parentheses:

```groovy
parameters {
    string(name: 'TAG', defaultValue: 'latest', description: 'Image tag')
    booleanParam(name: 'DEBUG', defaultValue: false, description: 'Verbose logs')
}
```

and the parenthesis-less command form emitted by Jenkins' Pipeline Snippet
Generator (one call per line):

```groovy
parameters {
    booleanParam defaultValue: true, description: 'my test', name: 'test'
    string defaultValue: 'test', description: 'my string', name: 'str'
}
```

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for instructions on building the plugin
from source and running it locally.

## License

See [LICENSE](LICENSE) for details.
