# AI assistant for Ramus — experimental

The local client has an experimental Swing assistant for generating IDEF0 plans.
It supports DeepSeek and local OpenAI-compatible chat endpoints, such as Ollama.
The user must confirm the proposed plan before the application changes a model.

## Run

From the repository root, with a supported JDK installed:

```sh
./gradlew :local-client:runLocal
```

Open **Tools / Сервис → AI Assistant**, choose a provider, specify the model and
write a task. Select **Сгенерировать**, inspect the proposed plan and confirm
only if it is acceptable. Save a copy of the project first: generation may
reuse and reposition same-named blocks and replace same-named arrows in the
affected diagram, and failures are not guaranteed to roll back atomically.

### macOS on Apple Silicon

Use an ARM64 JDK 21 (for example, a Temurin distribution) and verify it with
`java -version`. After cloning the repository, run `./gradlew :local-client:test`
and `./gradlew :local-client:runLocal` from its root. The Gradle wrapper may
download Gradle and dependencies on the first build. These commands build from
source; no prebuilt app or AI service is required for the client itself.

To build a local macOS DMG, use `./gradlew :local-client:macDmg` with a full JDK
that includes `jpackage` and `jlink`. A locally built unsigned app may require
the user to approve opening it in macOS security settings. Package creation and
signing are separate from source compilation; see the root README for details.

For DeepSeek enter an API key in the password field; it is held in memory for
the session and is not intentionally written to the project. Prompts and a
snapshot containing non-system qualifier and element names are sent to the
selected cloud provider. Do not submit sensitive project data without approval.
Model availability and API billing depend on the provider and account.

For a local model, start an OpenAI-compatible service separately, select
**Локальная LLM**, enter its model name and use a loopback HTTP endpoint such
as `http://127.0.0.1:11434/v1`. The model must already be installed. Remote
hosts and URLs containing credentials are rejected. Local generation depends
on the selected model returning the supported JSON operation format.

The older `:ramus-ai-agent:run` starts a separate rule-based analysis service;
it is not an LLM provider and does not generate executable plans. It is not
required for the DeepSeek or local LLM options in the assistant window.

## Current limitations

- The supported write operations are limited to creating functions and streams.
- The transport-company task currently uses a built-in example plan rather than
  reproducing all details from an arbitrary prompt. It is not a guarantee of
  compliance with a particular course assignment or full IDEF0 balancing.
- Automatic placement attempts to avoid blocks and labels, but dense diagrams,
  inherited boundary arrows and tilda leaders can still overlap or appear
  disconnected. Inspect and adjust the result in Ramus.
- AI output is untrusted; review it and preserve backups. The snapshot is not a
  full semantic export of the project, and the operation parser is limited.

## Tests

```sh
./gradlew :local-client:test
```

Tests cover routing, local HTTP integration with a mock service, and a diagram
saved and reopened using Ramus. They do not establish visual quality for every
project or verify a real cloud/local model.

Before publishing, review the complete diff, third-party licenses, bundled
resources, local paths and secrets. Do not commit API keys, project snapshots,
personal projects, `.env` files or local Gradle caches.
