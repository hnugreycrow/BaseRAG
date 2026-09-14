# Repository Guidelines

## Project Structure & Module Organization

BaseRAG is a modular monolith with a Java 21/Spring Boot backend and a Vue 3/TypeScript frontend.

- `backend/src/main/java/com/hnu/backend/`: business modules such as `knowledgebase`, `document`, `rag`, `conversation`, and `model`.
- `backend/src/main/resources/`: application configuration, Flyway migrations, and MyBatis XML mappers.
- `backend/src/test/`: JUnit unit and integration tests plus model-server fixtures.
- `frontend/src/`: Vue views, reusable components, API clients, stores, and router definitions.
- `deploy/`: local PostgreSQL/pgvector and RustFS Docker Compose configuration.
- `docs/`, `evaluation/`, and `scripts/`: architecture notes, evaluation data, and operational scripts.

## Build, Test, and Development Commands

Run storage from the repository root:

```powershell
docker compose --env-file .env -f deploy/compose.yml up -d
./scripts/start-backend.ps1
```

Backend commands, run from `backend/`:

```powershell
./mvnw.cmd test                 # run JUnit tests
./mvnw.cmd spotless:check       # verify Java formatting
```

Frontend commands, run from `frontend/`:

```powershell
npm ci
npm run dev                     # start Vite locally
npm test                        # run Vitest once
npm run build                   # type-check and build production assets
npm run format:check            # verify Prettier formatting
```

## Coding Style & Architecture

Use two-space indentation in Java, TypeScript, and Vue files. Java is formatted with Google Java Format through Spotless; frontend files use Prettier. Use `PascalCase` for Java classes and Vue components, `camelCase` for methods and variables, and descriptive REST resource names.

Backend modules follow Controller / Service / Mapper layering. Controllers handle HTTP concerns only; business rules belong in services and persistence belongs in mappers or infrastructure adapters. Keep DTOs, response/VO types, and entities separate. Use RESTful endpoints. Do not alter existing database columns directly; add forward-only Flyway migrations. Explain any new dependency and avoid unrelated large-scale refactors.

## Testing Guidelines

Name Java tests `*Test.java` and frontend tests `*.test.ts`. Add focused tests for changed behavior, especially retrieval, chunking, SSE parsing, cancellation, and answer-version state. No numeric coverage threshold is enforced, but all existing tests and both builds must pass. Infrastructure tests require the services and `RAG_INTEGRATION=true` described in `README.md`.

## Commits & Pull Requests

History currently contains only `initial commit`, so no established convention exists. Use short imperative subjects, preferably scoped, such as `conversation: handle duplicate cancel requests`. Keep commits reviewable. Pull requests should explain intent, affected modules, validation performed, configuration or migration impact, and linked issues. Include screenshots for visible UI changes and never commit `.env`, credentials, generated `target/`, `dist/`, or `node_modules/` files.
