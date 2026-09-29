# Hot Reload Parameters Plugin for Jenkins

A Jenkins plugin that reloads parameter default values on the **Build with Parameters** page when a trigger parameter changes. It reads a Groovy DSL file from the Git branch (or tag) matching the trigger value and updates the other parameters in place, without a page reload, a rebuild, or a job reconfiguration.

## How It Works

1. You define your pipeline parameters normally (`string`, `booleanParam`, `choice`, `text`, `password`, ...) in a `Jenkinsfile` or a shared-library Groovy file.
2. You add a single `hotReloadParams(...)` entry that tells the plugin which Git repository, file path, and trigger parameter to watch.
3. On the **Build with Parameters** page, when a user changes the trigger parameter (e.g. `RELEASE_BRANCH`), the plugin:
   - Fetches the Groovy DSL file from the Git branch or tag matching the new value
   - Parses all `parameters {}` definitions from that branch
   - Updates the default values of existing fields on the page
   - Hides parameters that don't exist on the target branch
   - Adds inputs for parameters that only exist on the target branch
4. When the user clicks **Build**, the plugin fetches the same file again on the server and only accepts parameters that are declared on the job or defined in that branch's file (see [Security](#security)).

### Branch Resolution

The plugin resolves the trigger value to a Git ref in this order:

1. Exact branch or tag name (e.g. `release/v1.1.0`)
2. The part after the last `/` (e.g. `v1.1.0` from `release/v1.1.0`)
3. The configured `defaultBranch` (e.g. `master`)

Fetched files are cached in memory for 60 seconds.

### Git Credentials

If the target Git repository is private, configure a **Username with password** credential in Jenkins and reference its ID in `credentialsId`. Credentials are resolved in the scope of the job, so folder-scoped credentials work.

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

`paramFilePath` points to any Groovy file in the repository that contains a `parameters {}` block. The plugin reads this file from the branch matching the trigger value.

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
- Adds a new `FEATURE_FLAG` input (since it doesn't exist on `master`)
- Rebuilds the `LOG_LEVEL` choices in the branch's order

### Supported Parameter Syntax

The parser recognises both the standard Groovy DSL form with parentheses:

```groovy
parameters {
    string(name: 'TAG', defaultValue: 'latest', description: 'Image tag')
    booleanParam(name: 'DEBUG', defaultValue: false, description: 'Verbose logs')
}
```

and the parenthesis-less command form emitted by Jenkins' Pipeline Snippet Generator (one call per line):

```groovy
parameters {
    booleanParam defaultValue: true, description: 'my test', name: 'test'
    string defaultValue: 'test', description: 'my string', name: 'str'
}
```

### `hotReloadParams` Configuration Options

| Parameter          | Required | Default                        | Description                                                                   |
| ------------------ | -------- | ------------------------------ | ----------------------------------------------------------------------------- |
| `repoUrl`          | Yes      | --                             | Git repository URL containing the DSL file                                    |
| `credentialsId`    | No       | `""`                           | ID of a username/password credential used to read the repository             |
| `paramFilePath`    | No       | `vars/release-pipeline.groovy` | Path to the Groovy file within the repo that contains a `parameters {}` block |
| `triggerParamName` | No       | `RELEASE_BRANCH`               | Name of the parameter that triggers a reload                                  |
| `defaultBranch`    | No       | `master`                       | Fallback branch when the trigger value doesn't match a branch or tag          |

### Supported Parameter Types

| Type          | DSL Function        | Reloaded Fields             | Value type when only defined on the branch |
| ------------- | ------------------- | --------------------------- | ------------------------------------------ |
| String        | `string(...)`       | `defaultValue`              | String parameter                           |
| Text          | `text(...)`         | `defaultValue`              | Text parameter                             |
| Boolean       | `booleanParam(...)` | `defaultValue`              | Boolean parameter                          |
| Password      | `password(...)`     | `defaultValue`              | Password parameter                         |
| Choice        | `choice(...)`       | `choices` + default (first) | String parameter, value must be a choice   |
| Image Tag     | `imageTag(...)`     | `defaultTag`                | String parameter                           |
| Active Choice | `activeChoice(...)` | visibility                  | String parameter                           |
| Separator     | `separator(...)`    | visibility                  | --                                         |

### Job Configuration UI

You can also configure the plugin through the Jenkins UI:

1. In your job, check **This project is parameterized**.
2. Click **Add Parameter** and select **Hot Reload Parameters**.
3. Fill in the Git repository URL, credentials, file path, trigger parameter name, and default branch.

## Security

- Both endpoints used by the Build page require **Build** permission on the job. The repository URL, credentials and file path are read from the job's own configuration; nothing about the repository is accepted from the browser.
- When a build is submitted, the plugin fetches the parameter file for the selected branch again on the server. A submitted parameter is accepted only if it is declared on the job (handled by Jenkins core as usual) or defined in that branch's `parameters {}` block, and it is typed according to that definition. Anything else is rejected with `400 Bad Request`.
- Only parameter names taken from the branch's file are added to the build's SECURITY-170 allow-list, so they reach `params.*` and the build environment. Whoever can commit to the configured repository therefore controls which parameter names a job accepts, in the same way they already control the job's `Jenkinsfile`.

## Cache Management

Fetched files are cached for 60 seconds. An administrator can drop the cache with a `POST` request (requires **Administer**):

```
curl -X POST -u USER:API_TOKEN "$JENKINS_URL/descriptorByName/io.github.zeeshan.hotreloadparams.HotReloadParameterDefinition/clearCache"
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for instructions on building the plugin from source and running it locally.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
