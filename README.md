# SurveyJS + Java: the backend integration examples on Spring Boot

This is the Java code behind [Integrate SurveyJS with Java](https://surveyjs.io/documentation/backend-integration-java), as a Spring Boot application you can run example by example. Each example of that page has a page here that runs the real SurveyJS component against these controllers, with a short "Try this" list, a live request log, a "What the server stored" panel and the code that just ran. SurveyJS has no backend of its own, so treat these as starting points for your own app.

- SurveyJS demos: [app.demos.surveyjs.io](https://app.demos.surveyjs.io)
- The same examples for other platforms: [PHP (Laravel)](https://github.com/surveyjs/surveyjs-php-laravel), [ASP.NET Core](https://github.com/surveyjs/surveyjs-aspnet-core), [Node.js (Express)](https://github.com/surveyjs/surveyjs-nodejs), [Python (FastAPI)](https://github.com/surveyjs/surveyjs-python-fastapi), [Next.js](https://github.com/surveyjs/surveyjs-nextjs)

There is no hosted catalog for this repository yet.

## Run it

You need a JDK 21 or later. Maven isn't a prerequisite: the Maven wrapper (`mvnw`) downloads it on the first run. Docker runs the SurveyJS service for the four Run Server-Side Workflows examples, and Node.js 22 or later is only for the browser tests, the CSS build and the contract test (see [Prerequisites](#prerequisites)).

```powershell
git clone https://github.com/surveyjs/surveyjs-java-spring-boot.git
cd surveyjs-java-spring-boot
.\mvnw spring-boot:run                 # http://127.0.0.1:8000
```

```bash
git clone https://github.com/surveyjs/surveyjs-java-spring-boot.git
cd surveyjs-java-spring-boot
./mvnw spring-boot:run                 # http://127.0.0.1:8000
```

The first start creates and seeds the H2 database in `storage/database/`. `java -jar target/surveyjs-java-spring-boot.jar` (after `./mvnw -DskipTests package`) is the same app. Run either from the repository root: the app reads `shared/` and `surveyjs-integration.json` from there, and stops with "Run the app from the repository root (shared/CONTRACT not found)" anywhere else.

- **Fill Forms Together** and **Edit Forms Together** use WebSocket relays that run inside the app, on its port (`/ws/rooms/<id>` and `/ws/forms/<id>`). There is nothing else to start.
- **Run Server-Side Workflows** (validate, lint, PDF, extract) call the SurveyJS service, [SurveyJS Server](https://github.com/surveyjs/surveyjs-server). Start it with `docker compose up -d surveyjs` (it listens on `http://localhost:3010`), set `VALIDATE_RESPONSES=true` and `LINT_DEFINITIONS=true` in `.env` (copy it from `.env.example`) to switch Validate Responses and Lint JSON Schemas on, then restart the app. Extract Responses from Documents also needs an AI provider for the service (`SURVEYJS_AI_PROVIDER` and its key).
- The tests:
  - `./mvnw test`: the JUnit tests, about a minute, no browser and no network (the service is mocked). The build compiles with `-Xlint:deprecation -Werror`, so a deprecated API fails it.
  - `./mvnw -q -DskipTests package exec:java@contract`: the shared contract test against the jar and a fresh database (`SERVICE=on` with the service running; `"-Dexec.args=--smoke"` for the smoke test; `"-Dexec.args=--steps save-response,dashboard"` for some examples).
  - `npm ci`, `npx playwright install chromium` once, then `npm run test:browser` (after `./mvnw -q -DskipTests package`): the shared Playwright specs.
  - `./mvnw -q test-compile exec:java@check`: the manifest, the regions, `shared/` and the pins.

Before anything else starts, the app checks that nothing answers on its port, and once it is up it logs the folder it serves and its `<branch>@<commit>`:

```text
Serving C:\src\surveyjs-java-spring-boot (main@4a96b83) on http://127.0.0.1:8000
```

### Prerequisites

- **A JDK 21 or later** (Temurin 25 recommended), with `java` on `PATH` or `JAVA_HOME` set.
  - Without one, `mvnw` stops with Maven's own "The JAVA_HOME environment variable is not defined correctly".
  - With an older JDK, the Maven Enforcer stops the build:

    ```text
    [ERROR] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion failed with message:
    [ERROR] Java 21 or newer is required. Install a JDK 21+ and set JAVA_HOME.
    ```

  - `java -jar` on an older JVM fails with "UnsupportedClassVersionError: io/surveyjs/Application has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of the Java Runtime only recognizes class file versions up to 61.0": the jar was compiled for Java 21 (class file version 65), and that `java` is older (61 is Java 17). Check `java -version`.
- **Maven** comes with the wrapper: the first `./mvnw` downloads Maven 3.10.1 into `~/.m2/wrapper`, which needs internet access once.
- **Docker** for the SurveyJS service (`docker compose up -d surveyjs`). Without it, the four Run Server-Side Workflows examples answer `503` "SurveyJS service is not running at http://localhost:3010", and everything else works.
- **Node.js 22 or later**, only for the browser tests, the CSS build and the contract and smoke tests (they are `shared/*.mjs`). `npm ci` on an older Node stops with npm's own `EBADENGINE` error (`engines` in `package.json`, `engine-strict` in `.npmrc`). Running the app never needs Node.

On Windows:

- Install Temurin 25 from [adoptium.net](https://adoptium.net/) or with `winget install EclipseAdoptium.Temurin.25.JDK`. `java` on `PATH` is enough for the wrapper. The installer can also set `JAVA_HOME` (its "Set JAVA_HOME variable" feature, which is not selected by default); check with `echo $env:JAVA_HOME`.
- After installing a JDK or Node, restart your editor, not only its terminal: VS Code's terminals inherit VS Code's environment, so a terminal in an editor started before the install doesn't see the new `PATH`. To refresh the current PowerShell terminal instead:

  ```powershell
  $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
  java -version
  ```

- `.\mvnw` runs `mvnw.cmd`, a batch file. After `Ctrl+C` stops the app, cmd may ask "Terminate batch job (Y/N)?": answer `Y`.
- `mvnw package` can't replace `target/surveyjs-java-spring-boot.jar` while `java -jar` runs it. Stop the app first; `spring-boot:run` doesn't lock the jar.
- The app listens on `127.0.0.1` by default (`APP_HOST`), so Windows Defender Firewall doesn't ask whether Java may accept network connections.
- Set a variable for one run like this, or put it in `.env`:

  ```powershell
  $env:APP_PORT=8001; .\mvnw spring-boot:run
  ```

  ```bash
  APP_PORT=8001 ./mvnw spring-boot:run
  ```

Tomcat reports a busy port only after the whole application context has started, and by then this app has created and seeded its database; a second copy in the same folder fails earlier still, on H2's file lock. So the app connects to its port before Spring creates anything, and stops in Boot's usual failure block:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Port 8000 is already in use. Another copy of this app (another checkout or branch?) may be running. Stop it, or set APP_PORT.

Action:

Stop the other copy (README: Stop everything), or start this one on another port, for example with APP_PORT=8001.
```

Under `spring-boot:run`, Maven adds its own lines after it ("BUILD FAILURE", "Process terminated with exit code: 1").

### Stop everything

`Ctrl+C` in the terminal, then `docker compose down` for the service. To find a server left running in another terminal or checkout:

```powershell
Get-NetTCPConnection -LocalPort 8000 -State Listen | Select-Object OwningProcess
Stop-Process -Id <OwningProcess>
```

```bash
lsof -i :8000
kill <PID>
```

### Run a branch or a PR

- Check the branch out in its own folder, with `git worktree add ../surveyjs-java-spring-boot-pr <branch>` (for a pull request, `git fetch origin pull/<number>/head:pr-<number>` first) or a second clone. Each checkout has its own `.env`, `storage/` and `target/`. Two checkouts never share a `DATA_DIR`: H2 locks its files.
- Run one checkout on the default port at a time: stop the other one first, or start the second with `APP_PORT=8001`, or put `APP_PORT=8001` in its `.env`.
- The SurveyJS service is shared: `docker-compose.yml` names its project `surveyjs-java`, so every checkout manages the same container.
- The page says which checkout answered: "Running `<branch>@<commit>`" under the catalog's footer buttons and at the end of every example's Description panel.

## Docker

```bash
docker compose up --build    # http://127.0.0.1:8000, with the SurveyJS service next to it
```

The image (`Dockerfile`) builds the jar with the Maven wrapper on `eclipse-temurin:25.0.5_8-jdk` and runs it on `eclipse-temurin:25.0.5_8-jre` as a non-root user, with `java -jar` as PID 1, so `docker compose stop` shuts it down gracefully. Next to the jar it copies `shared/`, `surveyjs-integration.json` and the example sources the Code tab shows. Its environment sets `APP_HOST=0.0.0.0`, `DATA_DIR=/app/storage` and `SPRING_PROFILES_ACTIVE=production` (templates cached, no error details, no banner, logs on stdout); real environment variables win over `.env`, which isn't copied into the image. It creates and seeds the database on its first start. Its one volume, `/app/storage`, holds the database, the uploads, the visitor sandboxes and the relay snapshots. `GET /up` answers `200 {"status":"ok"}` without touching the database.

**One process, many visitors.** Run one JVM per data volume: the relay rooms, the countries cache and the demo's AI counter live in its memory, and H2 lets one process open the database anyway. Concurrency comes from virtual threads (`spring.threads.virtual.enabled=true`): an extraction that waits up to 120 seconds for the service parks its virtual thread, and other visitors are served meanwhile. Scaling out to several processes or hosts needs a shared room store (Redis pub/sub, for example) and a server database (PostgreSQL with a `text` column; `jsonb` normalizes the document); both are out of scope here.

On Windows, Docker Compose 5 can't build a service from a git URL (`failed to evaluate path "https://github.com/…"`), which the `surveyjs` service is: it hands the URL to the builder as a file path. Let Compose print the build and give it to the builder yourself, then start without building:

```powershell
docker compose build --print | docker buildx bake -f -
docker compose up -d --no-build
```

Linux and macOS (and CI) build with `docker compose up --build` as above.

### Behind a proxy

`server.forward-headers-strategy=native` makes Tomcat's `RemoteIpValve` trust `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Port` from its internal proxies (loopback and private address ranges, which cover Docker's networks). Behind a TLS proxy, the pages' absolute URLs and the relays' `wss://` URLs then use the scheme the visitor sees, the relays' origin check compares the page's origin with it, and the demo's `Secure` cookies work. In your own production, set `server.tomcat.remoteip.internal-proxies` to the proxy's address and don't publish the app's port. The relays are on the app's own host and port, so the proxy needs no extra route, only WebSocket upgrades passed through on `/ws/`. For Traefik, next to your entry point and TLS labels:

```yaml
services:
  app:
    labels:
      - traefik.http.routers.surveyjs.rule=Host(`example.com`)
      - traefik.http.services.surveyjs.loadbalancer.server.port=8000
```

Traefik passes WebSocket upgrades on by default; nginx needs `proxy_set_header Upgrade $http_upgrade;` and `proxy_set_header Connection "upgrade";` on `/ws/`. `RELAY_URL` and `EDIT_RELAY_URL` are only needed when `/ws/` is served from another origin. The SurveyJS service stays internal (`expose: ["3000"]`): only the app calls it.

## Configuration

`config/SurveyJsProperties.java` is one `@ConfigurationProperties("surveyjs")` record, bound once and injected; no other class reads the environment. `application.properties` maps the cross-platform variable names to it (`surveyjs.service-url=${SURVEYJS_SERVICE_URL:http://localhost:3010}`), so environment variables, `.env` and the contract runner use the same words as the other platforms. Spring Boot reads `.env` as a properties file (`spring.config.import=optional:file:.env[.properties]`): `KEY=value` lines, no `export`, and quotes are taken literally. Copy `.env.example` to `.env` to change a setting. Switches accept `1`, `true`, `on` or `yes`; a blank value means unset.

| Variable | Default | What it does |
|---|---|---|
| `APP_PORT`, `APP_HOST` | `8000`, `127.0.0.1` | Where the app listens. |
| `DATA_DIR` | `storage` | The H2 database, uploads, sandboxes and relay snapshots. |
| `SURVEYJS_SERVICE_URL` | `http://localhost:3010` | The SurveyJS service (`docker compose up -d surveyjs`). In Compose, the app uses `http://surveyjs:3000`. |
| `VALIDATE_RESPONSES` | `false` | Validate Responses: `ValidateResponseController` replaces `SaveResponseController`. |
| `LINT_DEFINITIONS` | `false` | Lint JSON Schemas: `LintDefinitionController` replaces the PUT of `CreatorLoadSaveController`. |
| `SURVEYJS_AI_PROVIDER`, `SURVEYJS_AI_MODEL`, `SURVEYJS_OLLAMA_BASE_URL`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` | empty | Extract Responses from Documents: Compose passes them to the service as `AI_PROVIDER`, `AI_MODEL` and `OLLAMA_BASE_URL` (`openai`, `anthropic` or `ollama`). The app reads them only for the page's banner. |
| `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL` | OpenAI, empty, `gpt-4o-mini` | Translate with AI: any OpenAI-compatible `/chat/completions` endpoint. Without a key, `/api/translate` answers `501`. |
| `SURVEYJS_LICENSE_KEY` | empty | Calls `setLicenseKey()` on every page. Without it, the commercial components show a licence banner. The service at its pinned revision ignores it, so server PDFs carry the SurveyJS alert banner. |
| `RELAY_URL`, `EDIT_RELAY_URL` | empty | The relays' origin when a proxy serves `/ws/` elsewhere, for example `wss://example.com`. Empty: the page's own origin. |
| `DEMO_MODE` | `false` | The hosted demo's visitor sandboxes (below). |
| `SURVEYJS_BUILD` | from git | The `<branch>@<commit>` the pages show as "Running …". Deploys set it themselves. |
| `SURVEYJS_DOCS`, `SURVEYJS_GITHUB` | the Java docs page, this repository | Where the pages link. |

## The examples

<!-- steps:start (generated by `./mvnw -q test-compile exec:java@readme` from surveyjs-integration.json) -->
| Example | Run | Server | Client | Page | Licence |
|---|---|---|---|---|---|
| [Save Responses](https://surveyjs.io/documentation/backend-integration-java#save-responses) | `/examples/save-response` | [`src/main/java/io/surveyjs/examples/SaveResponseController.java`](src/main/java/io/surveyjs/examples/SaveResponseController.java) | [`shared/client/save-response.js`](shared/client/save-response.js) | [`src/main/resources/templates/examples/save-response.html`](src/main/resources/templates/examples/save-response.html) | MIT |
| [Load JSON Schemas, Variables, and Answers](https://surveyjs.io/documentation/backend-integration-java#load-json-schemas-variables-and-answers) | `/examples/load-definition-variables-data` | [`src/main/java/io/surveyjs/examples/LoadDefinitionVariablesDataController.java`](src/main/java/io/surveyjs/examples/LoadDefinitionVariablesDataController.java) | [`shared/client/load-definition-variables-data.js`](shared/client/load-definition-variables-data.js) | [`src/main/resources/templates/examples/load-definition-variables-data.html`](src/main/resources/templates/examples/load-definition-variables-data.html) | MIT |
| [Resume Progress](https://surveyjs.io/documentation/backend-integration-java#resume-progress) | `/examples/resume-progress` | [`src/main/java/io/surveyjs/examples/ResumeProgressController.java`](src/main/java/io/surveyjs/examples/ResumeProgressController.java) | [`shared/client/resume-progress.js`](shared/client/resume-progress.js) | [`src/main/resources/templates/examples/resume-progress.html`](src/main/resources/templates/examples/resume-progress.html) | MIT |
| [Store Files Separately](https://surveyjs.io/documentation/backend-integration-java#store-files-separately) | `/examples/store-files` | [`src/main/java/io/surveyjs/examples/StoreFilesController.java`](src/main/java/io/surveyjs/examples/StoreFilesController.java) | [`shared/client/store-files.js`](shared/client/store-files.js) | [`src/main/resources/templates/examples/store-files.html`](src/main/resources/templates/examples/store-files.html) | MIT |
| [Load Choices from Web](https://surveyjs.io/documentation/backend-integration-java#load-choices-from-web) | `/examples/choices-from-web` | [`src/main/java/io/surveyjs/examples/ChoicesFromWebController.java`](src/main/java/io/surveyjs/examples/ChoicesFromWebController.java) | [`shared/client/choices-from-web.js`](shared/client/choices-from-web.js) | [`src/main/resources/templates/examples/choices-from-web.html`](src/main/resources/templates/examples/choices-from-web.html) | MIT |
| [Async Calculations and Validation](https://surveyjs.io/documentation/backend-integration-java#async-calculations-and-validation) | `/examples/async-functions` | [`src/main/java/io/surveyjs/examples/AsyncFunctionsController.java`](src/main/java/io/surveyjs/examples/AsyncFunctionsController.java) | [`shared/client/async-functions.js`](shared/client/async-functions.js) | [`src/main/resources/templates/examples/async-functions.html`](src/main/resources/templates/examples/async-functions.html) | MIT |
| [Use Relational Storage](https://surveyjs.io/documentation/backend-integration-java#use-relational-storage) | `/examples/relational-storage` | [`src/main/java/io/surveyjs/examples/RelationalStorageController.java`](src/main/java/io/surveyjs/examples/RelationalStorageController.java) | [`shared/client/relational-storage.js`](shared/client/relational-storage.js) | [`src/main/resources/templates/examples/relational-storage.html`](src/main/resources/templates/examples/relational-storage.html) | MIT |
| [Fill Forms Together](https://surveyjs.io/documentation/backend-integration-java#fill-forms-together) | `/examples/fill-together` | [`src/main/java/io/surveyjs/examples/FillTogetherRelay.java`](src/main/java/io/surveyjs/examples/FillTogetherRelay.java) | [`shared/client/fill-together.js`](shared/client/fill-together.js) | [`src/main/resources/templates/examples/fill-together.html`](src/main/resources/templates/examples/fill-together.html) | MIT |
| [Analyze Results with Dashboard](https://surveyjs.io/documentation/backend-integration-java#analyze-results-with-dashboard) | `/examples/dashboard` | [`src/main/java/io/surveyjs/examples/DashboardController.java`](src/main/java/io/surveyjs/examples/DashboardController.java) | [`shared/client/dashboard.js`](shared/client/dashboard.js) | [`src/main/resources/templates/examples/dashboard.html`](src/main/resources/templates/examples/dashboard.html) | Commercial client |
| [Load and Save Form JSON Schemas](https://surveyjs.io/documentation/backend-integration-java#load-and-save-form-json-schemas) | `/examples/creator-load-save` | [`src/main/java/io/surveyjs/examples/CreatorLoadSaveController.java`](src/main/java/io/surveyjs/examples/CreatorLoadSaveController.java) | [`shared/client/creator-load-save.js`](shared/client/creator-load-save.js) | [`src/main/resources/templates/examples/creator-load-save.html`](src/main/resources/templates/examples/creator-load-save.html) | Commercial client |
| [Upload Files](https://surveyjs.io/documentation/backend-integration-java#upload-files) | `/examples/creator-uploads` | [`src/main/java/io/surveyjs/examples/StoreFilesController.java`](src/main/java/io/surveyjs/examples/StoreFilesController.java) | [`shared/client/creator-uploads.js`](shared/client/creator-uploads.js) | [`src/main/resources/templates/examples/creator-uploads.html`](src/main/resources/templates/examples/creator-uploads.html) | Commercial client |
| [Translate with AI](https://surveyjs.io/documentation/backend-integration-java#translate-with-ai) | `/examples/ai-translation` | [`src/main/java/io/surveyjs/examples/AiTranslationController.java`](src/main/java/io/surveyjs/examples/AiTranslationController.java) | [`shared/client/ai-translation.js`](shared/client/ai-translation.js) | [`src/main/resources/templates/examples/ai-translation.html`](src/main/resources/templates/examples/ai-translation.html) | Commercial client, needs an AI provider |
| [Variable Presets](https://surveyjs.io/documentation/backend-integration-java#variable-presets) | `/examples/variable-presets` | [`src/main/java/io/surveyjs/examples/VariablePresetsController.java`](src/main/java/io/surveyjs/examples/VariablePresetsController.java) | [`shared/client/variable-presets.js`](shared/client/variable-presets.js) | [`src/main/resources/templates/examples/variable-presets.html`](src/main/resources/templates/examples/variable-presets.html) | Commercial client |
| [Edit Forms Together](https://surveyjs.io/documentation/backend-integration-java#edit-forms-together) | `/examples/edit-together` | [`src/main/java/io/surveyjs/examples/EditTogetherRelay.java`](src/main/java/io/surveyjs/examples/EditTogetherRelay.java) | [`shared/client/edit-together.js`](shared/client/edit-together.js) | [`src/main/resources/templates/examples/edit-together.html`](src/main/resources/templates/examples/edit-together.html) | Commercial client |
| [Validate Responses](https://surveyjs.io/documentation/backend-integration-java#validate-responses) | `/examples/validate-response` | [`src/main/java/io/surveyjs/examples/ValidateResponseController.java`](src/main/java/io/surveyjs/examples/ValidateResponseController.java) | [`shared/client/validate-response.js`](shared/client/validate-response.js) | [`src/main/resources/templates/examples/validate-response.html`](src/main/resources/templates/examples/validate-response.html) | MIT |
| [Lint JSON Schemas](https://surveyjs.io/documentation/backend-integration-java#lint-json-schemas) | `/examples/lint-definition` | [`src/main/java/io/surveyjs/examples/LintDefinitionController.java`](src/main/java/io/surveyjs/examples/LintDefinitionController.java) | [`shared/client/lint-definition.js`](shared/client/lint-definition.js) | [`src/main/resources/templates/examples/lint-definition.html`](src/main/resources/templates/examples/lint-definition.html) | MIT |
| [Generate PDFs](https://surveyjs.io/documentation/backend-integration-java#generate-pdfs) | `/examples/server-pdf` | [`src/main/java/io/surveyjs/examples/ServerPdfController.java`](src/main/java/io/surveyjs/examples/ServerPdfController.java) | [`shared/client/server-pdf.js`](shared/client/server-pdf.js) | [`src/main/resources/templates/examples/server-pdf.html`](src/main/resources/templates/examples/server-pdf.html) | Commercial |
| [Extract Responses from Documents](https://surveyjs.io/documentation/backend-integration-java#extract-responses-from-documents) | `/examples/extract-from-paper` | [`src/main/java/io/surveyjs/examples/ExtractFromPaperController.java`](src/main/java/io/surveyjs/examples/ExtractFromPaperController.java) | [`shared/client/extract-from-paper.js`](shared/client/extract-from-paper.js) | [`src/main/resources/templates/examples/extract-from-paper.html`](src/main/resources/templates/examples/extract-from-paper.html) | MIT, needs an AI provider |
<!-- steps:end -->

## Where each example lives

Each example is two or three files you can read on their own. The full explanation is in [the docs](https://surveyjs.io/documentation/backend-integration-java).

- `src/main/java/io/surveyjs/examples/<Pascal>Controller.java`: the example's `@RestController` (or `@Controller`), the server code the docs show. `ValidateResponseController` and `SaveResponseController`, and `LintDefinitionController` and `CreatorLoadSaveController`, are swapped by the switches (`@ConditionalOnSwitch`). `FillTogetherRelay.java` and `EditTogetherRelay.java` are the two relays, raw `TextWebSocketHandler`s; their rooms are in `relay/`.
- `shared/client/<slug>.js`: the example's client code, a plain ES module, the same file on every platform.
- `src/main/resources/templates/examples/<slug>.html`: the example's page (Thymeleaf with the Layout Dialect): its description, the "Try this" list and the component.
- `integration/`: small helpers some controllers call, so the code the docs show stays about SurveyJS. They aren't demo code:
  - `StoredJson.java`: `write()`, `insertResponse()`, `put()` and the UTC timestamp (see below).
  - `SurveyJsService.java`: `post(path, body, timeout)` with its `RestClient` per timeout, the 503, 504 and 502 when the service doesn't answer, and the logging.
  - `AiTranslation.java`: the call to your OpenAI-compatible provider.
  - `FileType.java`, `FileStore.java`, `TtlCache.java`: file types by their first bytes, uploads on disk, and the countries cache.
- `security/` (`User`, the `UserStore` behind Spring Security, `Permissions.canReadFile()`, `SecurityConfig`), `storage/` (the H2 `DataSource` and the seeder), `web/` (the error shapes and the request size limit) and `config/`.

To copy an example, copy its controller and the helpers it imports. The code the docs show is cut from these files between `// #region sjs:<slug>.server` and `// #endregion` (and `// #region sjs:<slug>.client` in the client modules). `surveyjs-integration.json` lists every example's files and regions; `exec:java@check` verifies them. The controllers use `JdbcClient` with plain parameterized SQL, and no JPA entities, so that a response stays visibly one JSON document; Spring Data JDBC or JPA with a JSON text column works just as well.

### Shared files

`shared/` holds what every platform repository uses byte for byte: the client modules, the form definitions, samples and seed data, the page UI (`shared/ui/`), and the contract, smoke and browser tests. It is a copy of [`surveyjs-php-laravel`](https://github.com/surveyjs/surveyjs-php-laravel)'s `shared/` at the commit in `shared/UPSTREAM`; `exec:java@check` compares it with `shared/SHA256SUMS`. Don't edit it here: send a fix to that repository, then copy `shared/` again. `.gitattributes` keeps it in LF on Windows checkouts too, or every hash would fail.

### Editing example descriptions

The text in an example's Description panel is the `description` fragment of `src/main/resources/templates/examples/<slug>.html`. It can hold several paragraphs, links and other markup:

```html
<th:block layout:fragment="description">
    <p>Your custom description.</p>
    <p>More details with <strong>formatting</strong>.</p>
</th:block>
```

The "Try this" list is the `try` fragment; the layout is `templates/layouts/example.html`. The catalog cards use `need` from `surveyjs-integration.json` for their "Use when" text. Thymeleaf caches the templates, and there is no DevTools, so restart the app to see an edit. Rebuild the stylesheet too (see [Front end](#front-end)) if you used a Tailwind class no other template uses.

### Storing JSON exactly as it arrives

A response, a definition, saved progress and variable presets are each one JSON document, stored as text and returned as stored.

- **`@RequestBody JsonNode`, not a DTO.** A Spring developer reaches for a record or POJO there, but binding to a class drops the keys it doesn't declare and coerces values. The storing handlers take Jackson's `JsonNode` instead, which keeps every key, `{}`, `[{}]`, `null`, `""`, whitespace inside strings and `1.0`. A comment in each handler says why. Translate with AI stores nothing, so it reads a record.
- **Jackson as Boot configures it.** The controllers use the auto-configured `JsonMapper` (Jackson 3); it rejects `NaN` and trailing tokens, which answer `400 { "error": "The request body is not valid JSON" }`. `StoredJson.write()` writes `"naïve — 東京"` as it is. Digits beyond a double are rounded, as the browser's `JSON.parse` rounds them.
- **`CLOB` columns.** The documents are `CLOB`, never H2's `JSON` type, whose values come back as bytes.
- **Dates are strings.** `created_at` is ISO 8601 UTC to the second (`2026-11-17T13:36:06Z`, from `Instant`), so the dashboard's date filters are string comparisons. `LocalDateTime` has no zone, and `OffsetDateTime.toString()` writes the local offset and nanoseconds.
- Under `/api`, every error is `{ "error": … }`, also Spring's own (`web/ApiErrors.java` for what a handler raises, `web/ErrorPages.java` for what never reaches one): a 404, a 405 (with `Allow`), a 415, a bad query parameter (`limit=abc`) as `400`, an upload over the limit as `413`.

### The database: H2

The examples store their tables in [H2](https://h2database.com), the pure-Java database, embedded in the app's JVM in file mode (`storage/database/surveyjs.mv.db`). Nothing to install, and the SQL is the plain SQL the docs show. `storage/DatabaseConfig.java` builds the URL from `DATA_DIR`:

```text
jdbc:h2:file:<DATA_DIR>/database/surveyjs;NON_KEYWORDS=KEY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE
```

`NON_KEYWORDS=KEY` lets the contract's `key` columns be column names; `DATABASE_TO_LOWER=TRUE` returns column labels in lower case (`form_id`), as other databases do. `schema.sql` creates the tables at every start (`spring.sql.init.mode=always`) and `storage/Seeder.java` seeds them from `shared/seed/`. A production app uses Flyway or Liquibase for its schema; Spring Boot configures either.

- **Look inside with SQL** while the app is stopped (H2 locks the files while it runs), with H2's shell from your Maven repository:

  ```bash
  java -cp ~/.m2/repository/com/h2database/h2/2.4.240/h2-2.4.240.jar org.h2.tools.Shell \
    -url "jdbc:h2:file:./storage/database/surveyjs;NON_KEYWORDS=KEY" -user sa
  ```

  ```powershell
  java -cp "$env:USERPROFILE\.m2\repository\com\h2database\h2\2.4.240\h2-2.4.240.jar" org.h2.tools.Shell -url "jdbc:h2:file:./storage/database/surveyjs;NON_KEYWORDS=KEY" -user sa
  ```

  Then `SELECT id, form_id, data FROM responses ORDER BY id DESC LIMIT 5;`.
- **Fields inside a document.** H2 has no `->>` and no `JSON_VALUE`, so where the examples filter on a field of the stored answers (the claim owner's `customer_email`), they select the rows in SQL and compare the field after reading the document. It reads the same on any database.
- **After a Spring Boot upgrade**, a newer H2 may refuse an old database file. Delete `storage/database/`: the app creates and seeds a new one on start.
- **PostgreSQL instead:** replace the `DataSource` bean with Boot's `spring.datasource.*` properties, keep the document columns `text` (`jsonb` reorders keys and drops duplicate ones), and use `GENERATED BY DEFAULT AS IDENTITY` ids as `schema.sql` does.

## Security

Spring Security signs in the user and checks the permission, as it would in your app:

- **The demo user.** `demo/DemoUserFilter.java` runs in the security filter chain and signs the request in as the user its `demo_user` cookie names, through the `UserDetailsService` over the `users` table (`security/UserStore.java`). Handlers take `@AuthenticationPrincipal User user`, which is `null` when signed out. In your app, that filter's place is taken by `.formLogin()`, `.oauth2Login()` or `.oauth2ResourceServer(jwt -> …)`.
- **The edit-forms permission** is an authority. The `PUT` handlers of Load and Save Form JSON Schemas, Lint JSON Schemas and Variable Presets declare `@PreAuthorize("hasAuthority('edit-forms')")` (`@EnableMethodSecurity`); a refusal answers `403 { "error": "You don't have editor rights: only editors can change forms" }`, signed out too.
- **Stateless.** No session (`SessionCreationPolicy.STATELESS`, no `JSESSIONID`), no login form and no basic auth; every request is permitted, and the examples answer `401` and `403` themselves.
- **CSRF is off**, because the demo signs in by a cookie the page sets, not a session. A real app with a session cookie keeps CSRF on and sends its token: `CookieCsrfTokenRepository` with the `X-XSRF-TOKEN` header, which the client's `fetch` calls must then send.
- **The firewall** rejects paths such as `/shared/../.env` with `404`; Spring Security's default headers stay (`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`). The `/shared/` and `/css/` files are sent with `Cache-Control: no-cache`, so the browser revalidates them instead of Security's default `no-store`.

## How data works in the demo

Locally, everyone shares one database, `storage/database/` (delete `storage/` to start over).

On a hosted demo (`DEMO_MODE=true`), each visitor gets a private H2 database and uploads folder, created and seeded on first use, chosen by a `demo_sid` cookie, and kept until it has been unused for 24 hours (an hourly `@Scheduled` task in the app, `demo/SandboxPruner.java`). The "Open a second window" link in Fill Forms Together and Edit Forms Together carries that id (`?join=`), so whoever opens it works in the same copy and the same relay rooms. The demo also caps uploads at 5 MB and the AI examples at 20 calls per visitor per day, counted in this process. None of this is in the example code: `demo/SandboxDataSource.java` routes each request's connections to its visitor's database, so the controllers don't change.

## Front end

- The pages are Thymeleaf templates with the Layout Dialect (`src/main/resources/templates/`), autoescaped. Each page diffs against its Blade source in the reference repository in template syntax only, with the Tailwind class strings unchanged. Inline JSON (the page config, `window.FORM_DEFINITION`, `window.FORM_VARIABLES`) is written with `th:inline="javascript"`, which writes maps, records and `JsonNode`s as JSON and escapes `</script>`; no value from the database is ever `th:utext`.
- The site stylesheet is Tailwind CSS v4 without Preflight: `styles/site.css` imports `tailwindcss/theme.css` (static), `tailwindcss/utilities.css` and `shared/ui/theme.css`. `npm run css` builds it into `src/main/resources/static/css/site.css` with `@tailwindcss/cli`; the app serves it at `/css/site.css`. The output is committed, so running the app never needs Node; CI rebuilds it and fails on a difference. Rebuild it after editing a template. The [Tailwind standalone binary](https://tailwindcss.com/blog/standalone-cli) builds the same file without Node: `tailwindcss -i styles/site.css -o src/main/resources/static/css/site.css --minify`.
- The client code is the shared ES modules in `shared/client/` and `shared/ui/`, loaded unchanged with `<script type="module">` and an import map (`pages/Frontend.java`). No Maven artifact carries SurveyJS's 3.x browser builds (WebJars stop at `survey-core` 1.12 and have no Creator), so every specifier points to unpkg: the SurveyJS packages at the version in `shared/surveyjs-version.json` (`fesm/*.mjs`), and `chart.js`, `@kurkle/color`, `lodash-es` and highlight.js at pinned versions. There is no bundler.
- To self-host them, copy each package's `fesm/` files (and the CSS the pages load) into `src/main/resources/static/vendor/<package>@<version>/`, serve that folder, and point `UNPKG` in `pages/Frontend.java` at it. Don't mix the script-tag builds (`survey.core.js` and friends) with the ES modules on one page: they would load SurveyJS twice.
- The Code tab highlights the Java regions with highlight.js's `java` language, which `shared/ui/code-highlight.js` registers; the import map has an entry for every language that file imports.
- The forms use the SurveyJS Tailwind adapter (`survey-core/themes/adapters/tailwind4.css`). No page calls `applyTheme()`: its inline styles would override the adapter.

## Not on Spring Boot?

The controllers only use the request, the database and the response, so they port in a few lines. Save Responses in Jakarta REST, as Quarkus writes it, taking the same `JsonNode` for the same reason:

```java
@POST
@Path("/api/responses")
@Consumes(MediaType.APPLICATION_JSON)
public Response saveResponse(JsonNode body) {   // a JsonNode, not a DTO: the answers keep every key
    if (!body.path("formId").isTextual() || !body.has("data")) {
        return Response.status(400).entity(Map.of("error", "Expected { formId, data }")).build();
    }
    int id = storedJson.insertResponse(body.get("formId").asText(), body.get("data"));
    return Response.status(201).entity(Map.of("id", id)).build();
}
```

Only the annotations and the response type change (Quarkus still ships Jackson 2, hence `isTextual()` and `asText()`).

## Disclaimer

These examples must not be used as a real service as they are. They don't cover authentication, authorization, user management, access levels and other security aspects of a real survey service. The places where real checks go are marked:

- `@PreAuthorize("hasAuthority('edit-forms')")` on the saving handlers, and `Permissions.canReadFile(user, file)` in `security/Permissions.java`: who may save definitions and presets, and who may read an uploaded file.
- `demo/DemoUserFilter.java` in `security/SecurityConfig.java`: the demo version reads a `demo_user` cookie; your app authenticates there with form login, OAuth2 login or a JWT resource server. With a real session, turn CSRF back on.

When you copy an example into your app, leave out the demo-only code: the `io.surveyjs.demo` package (and its lines in `security/SecurityConfig.java`, `web/WebConfig.java`, `relay/RelayConfig.java` and `storage/DatabaseConfig.java`), `relay/RoomKey.java`'s sandbox keys, `pages/`, `templates/`, `shared/ui/` (the page frame, its tabs and highlighting, the fonts and site images) and `shared/client/host.js` (its `mountSurvey()` is one line: `renderSurvey(survey, element)` from `survey-js-ui`).
