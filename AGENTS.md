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

Use two-space indentation in Java, TypeScript, and Vue files. Java is formatted with Google Java Format through Spotless; frontend files use Prettier. Use `PascalCase` for Java classes and Vue components, `camelCase` for methods and variables, and descriptive REST resource names. Always use braces for if statements, even when the body contains only a single statement.

Backend modules follow Controller / Service / Mapper layering. Controllers handle HTTP concerns only; business rules belong in services and persistence belongs in mappers or infrastructure adapters. Keep DTOs, response/VO types, and entities separate. Use RESTful endpoints. Do not alter existing database columns directly; add forward-only Flyway migrations. Explain any new dependency and avoid unrelated large-scale refactors.

### MyBatis-Plus Database Access Guidelines

When writing or modifying database-access code, use the official [MyBatis-Plus persistence APIs](https://baomidou.com/guides/data-interface/) and [condition constructors](https://baomidou.com/guides/wrapper/), following the project conventions below. Use APIs supported by the version declared in the backend dependencies.

- Define entity mappers with `BaseMapper<Entity>`. For simple CRUD, have business services call built-in methods such as `insert`, `selectById`, `selectList`, `updateById`, and `deleteById`; do not duplicate these operations in custom SQL or XML without a concrete need.
- Prefer `Wrappers.<Entity>lambdaQuery()` / `LambdaQueryWrapper` for single-table query conditions and `Wrappers.<Entity>lambdaUpdate()` / `LambdaUpdateWrapper` for conditional updates. Reference entity getters, such as `.eq(User::getId, id)`, instead of hard-coded column-name strings when the Lambda API supports the operation.
- Use `updateById` when updating an entity by its primary key. For targeted field updates, use an explicit condition and `.set(...)`, for example `mapper.update(null, Wrappers.<User>lambdaUpdate().eq(User::getId, id).set(User::getName, name))`. Preserve the intended handling of null values and the project's field-update strategy.
- Keep complex joins, aggregations, PostgreSQL/pgvector operations, and SQL that cannot be clearly expressed with the built-in APIs in custom Mapper methods and MyBatis XML. Bind values with `#{...}`; do not concatenate untrusted values or accept raw SQL fragments from clients. Map custom query results to appropriate response/projection types rather than overloading persistence entities.
- Keep business rules and transaction boundaries in services; controllers must not call mappers directly. Use service-level transactions when multiple writes must succeed or fail together.
- `IService<Entity>` and `ServiceImpl<Mapper, Entity>` are optional official conveniences, not mandatory base types. The default is a business service using injected mappers; preserve an existing module's service convention unless the task requires changing it.
- Build a fresh Wrapper per operation. Validate required identifiers and scope conditions before updates or deletes; optional filters must not accidentally turn a scoped operation into a full-table operation. Handle affected-row counts when business behavior depends on whether a write actually changed a row.
- Apply these conventions to new and changed code without unrelated persistence-layer rewrites.

### Java Comment Guidelines

Follow the comment and Javadoc rules in the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html), especially sections 4.8.6 and 7. The Chinese language and business-documentation requirements below are project conventions. Use traditional `/** ... */` Javadoc for this Java 21 project.

- Provide Javadoc for every visible class, member, and record component: public top-level classes; public or protected members of visible classes; and components of visible records. Omit it only for genuinely self-explanatory members/components or overriding methods whose inherited documentation is sufficient. Being a constructor or CRUD method alone is not an exemption. Use Javadoc whenever documenting the overall purpose or behavior of a class or member, including non-public ones.
- Begin Javadoc with a concise summary phrase ending in appropriate punctuation. Avoid boilerplate such as "This method..." and do not use an isolated `@return` tag as the summary. Separate paragraphs and the block-tag group with a blank `*` line; prefix subsequent prose paragraphs with `<p>` without a following space, but do not prefix block-level HTML elements such as `<ul>` or `<table>` with `<p>`.
- Order block tags as `@param`, `@return`, `@throws`, then `@deprecated`, with non-empty descriptions. Indent wrapped tag descriptions at least four spaces beyond the `@`. Use single-line Javadoc only when the entire comment fits on one line and contains no block tags. Use `{@code ...}` for inline code and `{@link ...}` for Java references.
- As a project convention, write comments in concise Chinese, retaining established English technical terms and code identifiers. Explain intent, business constraints, and non-obvious tradeoffs instead of restating the code. Document parameter constraints, return semantics, relevant exceptions, nullability, units, ordering, and side effects when they affect callers.
- Keep interface contracts on the interface. Use `{@inheritDoc}` in implementations when useful, adding implementation-specific details only as needed. Never omit information callers need merely because the member's name appears self-explanatory.
- Indent implementation comments at the same level as the surrounding code and put a space after `//`. Both `//` and `/* ... */` are allowed; in multiline block comments, align the leading `*` on each subsequent line. Keep lines within 100 Unicode code points, except for Google Style exceptions such as unbreakable URLs and copyable shell commands. Do not draw decorative boxes around comments.
- Use `//` comments immediately above complex logic to explain algorithm choices, transaction boundaries, concurrency and cancellation behavior, retry conditions, or compatibility workarounds. For retrieval and chunking logic, document meaningful thresholds, units, and boundary conditions; prefer named constants over comments explaining magic numbers.
- Keep comments synchronized with behavior when changing code. Remove stale comments and commented-out code; rely on Git history instead. Avoid author/date banners and decorative section dividers.
- Use `TODO: <context link> - <explanation>` for concrete follow-up work, preferably linking a tracked issue. Do not use a person or team name as the context. For deferred work, specify a concrete date or triggering event. As a project convention, use this same structure for `FIXME`. Never include credentials, tokens, or real user data in comments or examples.
- Explain deliberately ignored caught exceptions in a comment. Mark fall-through between statement groups in traditional colon-style `switch` statements (except the last group), for example with `// fall through`; consecutive empty case labels do not need this comment.
- Use `@Override` whenever legal, with the Google Style exception for overriding deprecated methods. As a project convention, pair `@Deprecated` with a Javadoc `@deprecated` explanation of the replacement or migration path.
- Run Spotless for formatting, but review documentation coverage and accuracy manually: Google Java Format does not validate comment content or ensure that required Javadoc exists.

## Testing Guidelines

Name Java tests `*Test.java` and frontend tests `*.test.ts`. Add focused tests for changed behavior, especially retrieval, chunking, SSE parsing, cancellation, and answer-version state. No numeric coverage threshold is enforced, but all existing tests and both builds must pass. Infrastructure tests require the services and `RAG_INTEGRATION=true` described in `README.md`.

## Commits & Pull Requests

Commit messages must follow Conventional Commits: `<type>(<scope>): <description>` (scope may be omitted). Use a suitable type such as `feat`, `fix`, `refactor`, `docs`, `test`, or `chore`; keep the description short and in Chinese. For example: `feat(document): 支持异步分块与批量提交`. Keep commits reviewable. Pull requests should explain intent, affected modules, validation performed, configuration or migration impact, and linked issues. Include screenshots for visible UI changes and never commit `.env`, credentials, generated `target/`, `dist/`, or `node_modules/` files.
