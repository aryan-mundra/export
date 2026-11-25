# config/

Every setting you change per machine lives in this folder. The module reads it from the folder it is started in
(the project folder with `mvn spring-boot:run`, or the folder next to the jar).

| File | What it holds | Needed |
|---|---|---|
| `database.properties` | The PostgreSQL connection (host, port, database, username, password) used by the sidebar's **Database** switch. | For PostgreSQL |
| `application.yml` | Optional. Any setting of `src/main/resources/application.yml` (port, storage folder, delivery…) to override it without rebuilding; Spring Boot reads this file automatically. | No |

There is no file for the table groups: they are discovered from the primary and foreign keys declared in the source schema
(`export.source.schema`, or the environment variable `REGLISS_SOURCE_SCHEMA`; empty = the connection's current schema).
See `docs/architecture.md`.

The defaults packed in the jar are in `src/main/resources/application.yml` (and `application-postgres.yml` for the
`postgres` profile). The database scripts are in `db/postgres/`.
