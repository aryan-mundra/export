# config/

Every setting you change per machine lives in this folder. The module reads it from the folder it is started in
(the project folder with `mvn spring-boot:run`, or the folder next to the jar).

| File | What it holds | Needed |
|---|---|---|
| `table-groups.yml` | The table groups: which tables lists are built from (Person, Entity…), each group's common key, and each related table's foreign key. Read again whenever it changes: no restart needed. | Yes |
| `table-groups.example.yml` | A documented example. Copy it to `table-groups.yml` and put in your real table and column names. | — |
| `database.properties` | The PostgreSQL connection (host, port, database, username, password) used by the sidebar's **Database** switch. | For PostgreSQL |
| `application.yml` | Optional. Any setting of `src/main/resources/application.yml` (port, storage folder, delivery…) to override it without rebuilding; Spring Boot reads this file automatically. | No |

The defaults packed in the jar are in `src/main/resources/application.yml` (and `application-postgres.yml` for the
`postgres` profile). The database scripts are in `db/postgres/`.
