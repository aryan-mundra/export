# Running and testing the Export module on PostgreSQL

The module runs on two databases, with the same code and the same SQL:

| Database | How to start | Source data |
|---|---|---|
| **H2** (default) | `mvn spring-boot:run`, or ▶ in IntelliJ with no profile | Seeded in memory at start-up. H2 runs in PostgreSQL mode with lower-case names, so it behaves like PostgreSQL. Data is lost when the app stops. |
| **PostgreSQL** | the sidebar's **Database** switch (see step 5), or the profile `postgres` | Read from your database: a REGLISS dump you put in `db/dumps`, or the mock data in `db/postgres/regliss-mock.sql`. |

The sidebar's **Database** switch shows which one is running, and switches between them: the module restarts on the
other database.

The `postgres` profile:

- **Reads** certified versions from `ver_version`, list entries from `ver_version_member`, and every `gs_*` table in the
  schema (for the Create a List page and the exports).
- **Writes** rows into its own `ver_export_*` tables in the same database. You create those tables once by hand with
  `db/postgres/ver-export-schema.sql`.
- **Creates one table per data version** (`ver_<list>_v<major>_<minor>`, e.g. `ver_list_apac_v1_1`), so its database
  user needs the right to create tables in the schema. It creates no other table.
- **Imports a dump** from `db/dumps` at start-up, once (see `db/dumps/README.md`).

**`BNP_ID` is the key.** Every list entry has a `BNP_ID`, and every `gs_*` table has a `bnp_id` column: a `gs_*` row
belongs to the list entry with the same `BNP_ID`. PostgreSQL folds unquoted names to lower case, so tables and columns
appear as `gs_party`, `full_name`, and so on.

## Set up on a Windows laptop with pgAdmin and IntelliJ

This is the set-up used on the development laptop: PostgreSQL 17 managed in pgAdmin 4, the dump loaded by the
application, and the application run from IntelliJ. Do steps 1 to 4 once per laptop.

### 1. Find the server's connection details in pgAdmin

In pgAdmin's Object Explorer, under **Servers**, use **PostgreSQL 17**. (A second installation, such as PostgreSQL 15,
may be listed with a red ✗: it is not connected; leave it alone.) Right-click **PostgreSQL 17 → Properties →
Connection** and note:

| Field | Development laptop |
|---|---|
| Host name/address | `localhost` |
| Port | `5432` |
| Username | `postgres` |

The password is the one given to the `postgres` user when PostgreSQL was installed. pgAdmin may have saved it, so it is
not asked for when connecting. Close the dialog without saving.

Do not import into other databases on the server (for example `enterprise_db` or `v5_batch_db`): they belong to other
applications.

### 2. Create the database and the module's tables

1. Right-click **PostgreSQL 17 → Databases → Create → Database…**, name it `shinedata`, owner `postgres`, **Save**.
2. Select `shinedata`, open **Tools → Query Tool**, open the file `db/postgres/ver-export-schema.sql` (folder icon) and
   run it (**F5**). "Query returned successfully" appears.
3. Check: right-click **shinedata → Schemas → public → Tables → Refresh**: the `ver_export_*` tables are listed.

If the Query Tool was opened from another database, the tables end up there: open it again from `shinedata`.

### 3. A database user for the module (only if you do not know the `postgres` password)

The module can connect as `postgres`. If nobody remembers that password, do not change it (other applications may use
it): while pgAdmin is still connected, give the module a user of its own with a password you choose. In a Query Tool
opened from the `postgres` database:

```sql
CREATE ROLE regliss LOGIN PASSWORD 'choose-a-password';
ALTER DATABASE shinedata OWNER TO regliss;
```

Then in a Query Tool opened from `shinedata`:

```sql
ALTER SCHEMA public OWNER TO regliss;
GRANT ALL ON ALL TABLES IN SCHEMA public TO regliss;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO regliss;
```

Owning the schema lets `regliss` load the dump and create the data version tables. If the role already exists, set a
new password with `ALTER ROLE regliss PASSWORD 'choose-a-password';`.

### 4. Put the dump in `db/dumps`

Copy the dump file (for example `shine-dump-shinedata-202611230033.sql`) into `db/dumps` in the project folder, next to
its `README.md`, not in a sub-folder. Dumps are never committed (they hold bank data): copy them by hand on each laptop.

### 5. Check the connection in `config/database.properties`

The project folder's `config/database.properties` holds the connection the Database switch uses. It is committed with the
development laptop's local values; change them if your server differs:

```
host=localhost
port=5432
database=reglissData
username=postgres
password=postgres
```

The screen never asks for the password. Because this file is committed, only ever put a local test password in it.

### 6. Start the module and switch to PostgreSQL

1. Start it the plain way, with no profile and no environment variables: in IntelliJ, open
   `src/main/java/com/bnpp/export/ExportApplication.java` and click ▶ (leave **Program arguments** and **Environment
   variables** empty); or in PowerShell, `mvn spring-boot:run` (add `"-Djava.version=17"` with Java 17). It starts on H2.
2. Open http://localhost:8082 and sign in as `maker` / `Regliss-maker`.
3. In the sidebar, under **Database**, click **PostgreSQL**. The dialog shows the database, host, port and user from the
   file; click **Switch to PostgreSQL**.
4. The module checks the connection and that the `ver_export_*` tables are there. If something is wrong (password,
   database name, port, missing tables) it says so and nothing changes. Otherwise it restarts on PostgreSQL: the
   dump in `db/dumps` is imported (several minutes for a large one; watch the Run window), then the page shows the
   sign-in screen. Sign in again.

The module remembers the choice (in `data/database.properties`, also git-ignored) and starts on PostgreSQL from then on.
Click **H2** to go back; H2's data stays as it was while the module keeps running. If PostgreSQL does not start after a
switch, the module comes back on H2 and shows why. The switch is refused while an export is running.

**Fixed profile instead of the switch** (servers, scripts): start with `--spring.profiles.active=postgres` and the
environment variables `REGLISS_DB_URL`, `REGLISS_DB_USER`, `REGLISS_DB_PASSWORD` (and `PG_BIN` when several
PostgreSQL versions are installed). An explicit profile always wins over `data/database.properties`, and the switch is
then turned off. Set `export.database-switch.enabled=false` to turn the switch off everywhere.

When two PostgreSQL versions are installed, the import uses the newest one's `psql` (PostgreSQL 17 here); set `PG_BIN`
to choose another.

### 7. Check

- **pgAdmin:** **shinedata → Schemas → public → Tables → Refresh** lists the dump's tables (including `ver_version`,
  `ver_version_member` and the `gs_*` tables) next to the `ver_export_*` ones. `ver_export_dump_import` has one row for
  the imported file.
- **The module:** open http://localhost:8082, sign in as `maker` / `Regliss-maker`. The Database switch shows
  **PostgreSQL**, and **Create a List** offers the dump's `gs_*` tables.

If the dump keeps its tables in another schema than `public`, set `export.source.schema` to it.

### When something goes wrong

| In the log | Cause and fix |
|---|---|
| No `Importing` line | The file is not in `db/dumps` of the folder the module runs from, or does not end in `.sql` / is not a `pg_dump` archive. |
| `password authentication failed for user ...` / "Wrong password" | The password in the dialog (or `REGLISS_DB_PASSWORD`) does not match that user's password. |
| `database "shinedata" does not exist` | Create it (step 2), or put the real name at the end of `REGLISS_DB_URL`. |
| `ver_export_ tables are missing` | Run `ver-export-schema.sql` in a Query Tool opened from that database (step 2). |
| `Connection refused` | Wrong port, or PostgreSQL 17 is not running. |
| `invalid target release: 21` | Java 17: add `"-Djava.version=17"` to the Maven command, or in IntelliJ set Maven → Runner → Properties `java.version` = `17`. |
| Dump creates its own database | Ask for a dump made without `--create`. |
| Errors about roles or owners | Harmless: the objects belong to the module's user. |
| A different dump after one was imported | Kept as it is, with a warning. To load the new one, drop and recreate the database, repeat step 2, restart. |

The full output of each import is in `db/dumps/import-logs/`.

## Without a dump: the mock data

`db/postgres/regliss-mock.sql` creates and fills the tables the module reads (`BNP_ID` = `BNP` + record id). Use it on an
empty database instead of a dump (with no file in `db/dumps`):

| Table | Rows | Notes |
|---|---|---|
| `ver_version` | 4 | Versions 42 Sanctions, 43 World-Check, 44 PFA; 77 Sanctions is hidden from operators |
| `ver_version_member` | 400 | 100 per version. Every 29th record has no name, every 41st no country (to test quarantine). Some names hold `&`, `<`, quotes, commas, non-ASCII letters and padding |
| `gs_party` | 400 | One per record |
| `gs_address` | 372 | Every 13th record has none (its cells come out empty) |
| `gs_identifier` | 500 | Every 4th record has two (joined as `ID…; AL…`) |
| `gs_sanction_program` | 400 | One per record |
| `gs_country_risk` | 400 | One row per `BNP_ID` |
| `gs_load_log` | 1 | No `bnp_id` column, so the picker shows it locked |
| `ref_country` | 2 | Not a `gs_` table, so it must not appear in the picker |

Run it in pgAdmin's Query Tool on the database (open the file, F5), or with psql:

```powershell
psql -h localhost -U postgres -d shinedata -f db/postgres/regliss-mock.sql
```

Then run `ver-export-schema.sql` as in step 2. Check: `SELECT COUNT(*) FROM ver_version_member;` returns 400. To change
the data, edit `db/postgres/generate-regliss-mock.js` and run `node db/postgres/generate-regliss-mock.js`.

## What to check

Sign in as `maker` / `Regliss-maker`. The module starts with no lists: create them yourself.

| Where | Expected |
|---|---|
| Create a List | Only `gs_*` tables (with the mock data: `gs_load_log` locked as it has no `bnp_id`, no `ref_country`). Pick columns from several tables, press **Preview data and filter rows**, filter with the funnel on a column header, **Save list**. |
| Approvals | Sign in as `approver` / `Regliss-approver`: approve the list, or request changes or reject it with a reason. |
| Subscribers | Choose the approved list for one or more subscribers. |
| Export → Export Latest | **Export** the list: version 1.1 and the table `ver_<list>_v1_1` appear. Export again with no change: 1.1 is reused. Change a value in a `gs_*` table and export: 1.2 and `ver_<list>_v1_2` appear. |
| Export → Previous Versions | Download any version as CSV or XML: 1.1 still holds the old value. **Send** a version to any subscribers, choosing each one's format. |
| Export → Compare | Pick the list, then any two versions (From ⇄ To). The tiles count added, removed, changed and unchanged records; "Changed values by column" ranks columns by how many values changed. Click a tile or a column to filter, search a BNP ID, page through the records (each shows only what changed), or **Download CSV** (one line per changed value). From Previous Versions, **Compare with …** opens it on a version and the one before. |
| History | One job per export or send, with each subscriber's file. With the mock data, version 42 holds SHINE back (record 42000041 has no country) until the omissions are accepted. |

Then look at what the module wrote:

```sql
SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'ver_%' ORDER BY table_name;

-- Data versions, their tables and how they were used
SELECT label, template_name, list_code, table_name, row_count, inserted_rows, deleted_rows, changed_rows, reuse_count, created_at
  FROM ver_export_snapshot ORDER BY id;

-- The rows of one version (use a table_name from the query above)
SELECT * FROM ver_list_apac_v1_1 ORDER BY _line;

-- Who did what
SELECT logged_at, actor, entity_type, action, details FROM ver_export_audit ORDER BY id;
```

## Automated checks (no PostgreSQL install needed)

These start the app on the `postgres` profile against H2 in PostgreSQL mode, load `regliss-mock.sql`, and check the
results against plain SQL or plain Java written in the test, independent of the module's code:

| Test | Proves |
|---|---|
| `PostgresExportFilesTest` | CSV and XML files hold exactly the database values, cell by cell (escaping, empty cells, multi-row `gs_*` values), and the review counts equal the export |
| `RowFilterPostgresTest` | Row filters: the explorer, the review count and the file hold exactly the entries that pass |
| `DataVersionTest` | Data versions: identical data reuses its version, changed data gets the next number in a new table, old versions download unchanged as CSV and XML, retries resend the same data, two simultaneous exports create one version |

```powershell
mvn test
```

## Start again

To clear the module's data but keep the source tables, stop the module and run `db/postgres/reset-export-tables.sql`
(it also drops every data version table), then `ver-export-schema.sql` again. To start from an empty database, drop and
recreate it in pgAdmin and repeat steps 2 to 5; the dump is imported again.

## Data versions: how they are stored

Every export records the exact rows it produced as a **data version**, numbered `list version.data revision`
(1.1, 1.2, ... and 2.1 once a new list version is approved).

- An export reads the source once per list, writes each row in a fixed form (every value, empty ones included),
  and hashes it. The dataset's fingerprint is the hash of the column layout and all row hashes in record order. If a
  version of the same list version already has that fingerprint, it is reused; otherwise the next number is given.
- Each new version gets **a table of its own**, `ver_<list>_v<major>_<minor>`, with the list's columns and its rows.
  `ver_export_snapshot` holds one row per version, with the table's name. See `docs/ver-export-tables.md`.
- Subscriber files, retries, downloads and comparisons read the version's table; the source query is not run again.

## Large data

Exports stream: rows are read with a database cursor (PostgreSQL needs this inside a transaction; the module does it),
staged to a file while hashing, and written to the version's table in batches of 500. The explorer pages and counts in
the database. On a large database, index `ver_version_member (version_id, bnp_id)` and `bnp_id` on every `gs_*`
table.
