# Running the Export module on PostgreSQL

The module runs on two databases, with the same code and the same SQL:

| Database | How to start | Source data |
|---|---|---|
| **H2** (default) | `mvn spring-boot:run`, or ▶ in IntelliJ with no profile | None: an empty in-memory database, for trying the screens. Lost when the app stops. |
| **PostgreSQL** | the sidebar's **Database** switch (step 6), or the profile `postgres` | Your database: the tables of the groups discovered from its foreign keys, usually loaded from a dump in `db/dumps`. |

On PostgreSQL the module:

- **reads** the tables of the groups discovered from the foreign keys of the source schema (and nothing else);
- **writes** only its own `ver_export_*` tables, created once by hand with `db/postgres/ver-export-schema.sql`;
- **imports a dump** from `db/dumps` at start-up, once (see `db/dumps/README.md`).

## Set up on a Windows laptop with pgAdmin and IntelliJ

PostgreSQL 17 managed in pgAdmin 4, the dump loaded by the application, the application run from IntelliJ. Do steps 1
to 5 once per laptop.

### 1. Find the server's connection details in pgAdmin

Under **Servers**, use **PostgreSQL 17** (a second installation, such as PostgreSQL 15, may be listed with a red ✗:
leave it alone). **Right-click → Properties → Connection** shows host (`localhost`), port (`5432`) and username
(`postgres`). The password is the one given when PostgreSQL was installed.

### 2. Create the database and the module's tables

1. Right-click **PostgreSQL 17 → Databases → Create → Database…**, name it `reglissData`, **Save**.
2. Select `reglissData`, **Tools → Query Tool**, open `db/postgres/ver-export-schema.sql` (folder icon) and run it
   (**F5**).
3. Check: **reglissData → Schemas → public → Tables → Refresh** lists the `ver_export_*` tables.

To start again from empty module tables later, run `db/postgres/reset-export-tables.sql`, then the schema script again.
Neither touches the source tables.

Run it again after updating the module: it only adds what is missing (a table, or the subscriber columns format, scope,
mode, business line and region) and never touches data.

### 3. Put the dump in `db/dumps`

Copy the dump file into `db/dumps` in the project folder, next to its `README.md`. Dumps are never committed (they hold
bank data). A database takes one dump: to load a different one, drop and recreate the database (step 2) first.

### 4. Tell PostgreSQL where the source tables are

When the dump puts its tables in their own schema (for example `testdb`), let the module find them there and its own
tables in `public`. In a Query Tool on `reglissData`:

```sql
ALTER ROLE postgres IN DATABASE "reglissData" SET search_path = testdb, public;
```

Reconnect pgAdmin and check with `SHOW search_path;` (it shows `testdb, public`). Alternatively, give each group a
`REGLISS_SOURCE_SCHEMA` (or `export.source.schema`) to that schema, for example `dj_demo`.

### 5. Check the foreign keys and the connection

- There is no groups file. The module reads the primary and foreign keys of the source schema and builds the groups itself:
  a table with a one-column primary key and no foreign key is the root of a group, and the tables whose foreign key points
  at it (at any level) belong to it. Check they are declared in the database (pgAdmin: table → **Properties → Constraints →
  Foreign Key**), or run `SELECT … FROM information_schema.table_constraints …` (see `SourceConstraintRepository`).
  Set `export.source.schema` (committed in `config/application.yml`, `dj_demo`) to the schema of the dump. Only tables whose
  name starts with `gs_` (any case) are used: change `export.source.table-prefix` to use another prefix, or leave it empty for
  every table.
- `config/database.properties` holds the connection for the Database switch (host, port, database, username,
  password). Only ever put a local test password in it: the file is committed.

### 6. Start the module and switch to PostgreSQL

1. Start it with no profile: ▶ on `ExportApplication` in IntelliJ, or `mvn spring-boot:run` (add
   `"-Djava.version=17"` with Java 17). It starts on H2.
2. Open http://localhost:8082 and sign in as the List Manager (`maker` / `Regliss-maker`).
3. Under **Database** in the sidebar, click **PostgreSQL**, then **Switch to PostgreSQL**. The module checks the
   connection and its tables, restarts on PostgreSQL (importing the dump the first time) and shows the sign-in screen.

The choice is remembered in `data/database.properties`. Click **H2** to go back. With a fixed profile instead
(`--spring.profiles.active=postgres` and `REGLISS_DB_URL`, `REGLISS_DB_USER`, `REGLISS_DB_PASSWORD`), the switch is off.

### 7. Check

- **List:** the table picker shows your groups, each group's key ticked and locked. **Preview each group** shows one
  preview per group.
- **Export → Export Latest → Export now** stores a data version and opens the send panel; **Synthesis** compares two
  versions.
- If the List page says a table or column was not found, the message names it: check `REGLISS_SOURCE_SCHEMA` and the foreign
  keys in the database (the groups are re-read every 60 seconds).

## When something goes wrong

| Message | Cause and fix |
|---|---|
| `No table group found in schema …` | The schema has no table with a one-column primary key and no foreign key, or the foreign keys are not declared in the database. Set `REGLISS_SOURCE_SCHEMA` to the right schema. |
| `The foreign keys of the source schema do not match its tables` | A table found in the keys cannot be read: check the schema and the user's rights on the tables. |
| A table is missing from a group | Its foreign key has several columns, points into another schema, or the table points at a table of an earlier group: see the log (`TableGroupBuilder` warnings). |
| No `Importing` line in the log | The dump is not in `db/dumps` of the folder the module runs from, or the database already holds a dump. |

## Automated checks (no PostgreSQL needed)

`mvn test` runs every check on H2 in PostgreSQL mode, including a full flow on test groups
(`src/test/resources/group-tables.sql`, whose foreign keys define the groups): previews, a list approved, its XML, two data versions, Synthesis, and a
send delivered and proved.
