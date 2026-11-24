# Running the Export module on PostgreSQL

The module runs on two databases, with the same code and the same SQL:

| Database | How to start | Source data |
|---|---|---|
| **H2** (default) | `mvn spring-boot:run`, or ▶ in IntelliJ with no profile | None: an empty in-memory database, for trying the screens. Lost when the app stops. |
| **PostgreSQL** | the sidebar's **Database** switch (step 6), or the profile `postgres` | Your database: the tables of the groups in `config/table-groups.yml`, usually loaded from a dump in `db/dumps`. |

On PostgreSQL the module:

- **reads** the tables named in `config/table-groups.yml` (and nothing else), joined by the foreign keys written there;
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
`schema:` in `config/table-groups.yml`.

### 5. Describe the table groups and check the connection

- Copy `config/table-groups.example.yml` to **`config/table-groups.yml`** and write your groups: for each group its root
  table and key, and for each related table its foreign key and the `table.column` it references (pgAdmin:
  table → **Properties → Constraints → Foreign Key**). The file's comments explain every setting.
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
- If the List page says a table or column was not found, the message names it: fix `config/table-groups.yml` (it is
  read again on the next page load) or the `search_path`.

## When something goes wrong

| Message | Cause and fix |
|---|---|
| `table-groups.yml was not found` | Copy `config/table-groups.example.yml` to `config/table-groups.yml` in the folder the module runs from. |
| `does not match the database: table … was not found` | Wrong name, or the table is in another schema: check `search_path` (step 4) or add `schema:` to the group. |
| `… cannot be an XML name` | Group names, sections and elements must be letters, digits and `_`, without spaces. |
| `Group … has key … twice` | The root key is not unique in the root table: choose the table's primary key as `key`. |
| No `Importing` line in the log | The dump is not in `db/dumps` of the folder the module runs from, or the database already holds a dump. |

## Automated checks (no PostgreSQL needed)

`mvn test` runs every check on H2 in PostgreSQL mode, including a full flow on test groups
(`src/test/resources/table-groups-test.yml`): previews, a list approved, its XML, two data versions, Synthesis, and a
send delivered and proved.
