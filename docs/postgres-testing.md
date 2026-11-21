# Running and testing the Export module on PostgreSQL

The module runs on two databases, with the same code and the same SQL:

| Database | How to start | Source data |
|---|---|---|
| **H2** (default) | `mvn spring-boot:run` | Seeded in memory at start-up. H2 runs in PostgreSQL mode with lower-case names, so it behaves like PostgreSQL. Data is lost when the app stops. |
| **PostgreSQL** | `mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"` | Read from your database. Load the mock data first with `db/postgres/regliss-mock.sql`. |

The sidebar's **Database** switch shows which one is running.

The `postgres` profile:

- **Reads** certified versions from `ver_version`, list entries from `ver_version_member`, and every `gs_*` table in the
  schema (for the Format Templates page and the exports).
- **Writes** its own `ver_export_*` tables in the same database. It creates them on first start (`ddl-auto: update`),
  keeps them between restarts, and never drops them.

**`BNP_ID` is the key.** Every list entry has a `BNP_ID`, and every `gs_*` table has a `bnp_id` column: a `gs_*` row
belongs to the list entry with the same `BNP_ID`. PostgreSQL folds unquoted names to lower case, so tables and columns
appear as `gs_party`, `full_name`, and so on.

## The mock data

`db/postgres/regliss-mock.sql` creates and fills the tables the module reads (`BNP_ID` = `BNP` + record id):

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

To change the data, edit `db/postgres/generate-regliss-mock.js` and run `node db/postgres/generate-regliss-mock.js`.

## Automated checks (no PostgreSQL install needed)

These start the app on the `postgres` profile against H2 in PostgreSQL mode, load `regliss-mock.sql`, and check the
results against plain SQL or plain Java written in the test, independent of the module's code:

| Test | Proves |
|---|---|
| `PostgresExportFilesTest` | CSV and XML files hold exactly the database values, cell by cell (escaping, empty cells, multi-row `gs_*` values), and the review counts equal the export |
| `RowFilterPostgresTest` | Row filters: the explorer, the review count and the file hold exactly the entries that pass |
| `DataVersionTest` | Data versions: identical data reuses its version, changed data gets the next number, old versions download unchanged as CSV and XML, retries resend the same data, two simultaneous exports create one version |

```powershell
mvn test
```

These use H2's PostgreSQL mode. Before relying on a real server, run the steps below once against PostgreSQL itself.

## 1. Get a PostgreSQL database

Any PostgreSQL 13 or newer works. Pick one:

- **A test database on your company's PostgreSQL** (closest to production). Ask the DBA for an empty database and a user
  that can create tables. Never use a production database: the mock script drops and recreates its tables.
- **PostgreSQL installed on Windows** (no Docker): download the installer from postgresql.org; it includes pgAdmin.
  Then create the user and database in pgAdmin's Query Tool, connected as `postgres`:

```sql
CREATE USER regliss WITH PASSWORD 'ChangeMe_Regliss1';
CREATE DATABASE regliss OWNER regliss ENCODING 'UTF8';
```

- **Docker**:

```powershell
docker run -d --name regliss-postgres -p 5432:5432 -e POSTGRES_USER=regliss -e POSTGRES_PASSWORD=ChangeMe_Regliss1 -e POSTGRES_DB=regliss postgres:16
```

## 2. Load the mock data

With psql:

```powershell
psql -h localhost -U regliss -d regliss -f db/postgres/regliss-mock.sql
```

Or in pgAdmin: connect to the `regliss` database as `regliss`, open `db/postgres/regliss-mock.sql` in the Query Tool,
and run it (F5). With Docker and no local psql:

```powershell
docker cp db/postgres/regliss-mock.sql regliss-postgres:/tmp/regliss-mock.sql
```

```powershell
docker exec regliss-postgres psql -U regliss -d regliss -f /tmp/regliss-mock.sql
```

Check it loaded: `SELECT COUNT(*) FROM ver_version_member;` returns 400. The script can be run again at any time.

## 3. Start the module on PostgreSQL

```powershell
$env:REGLISS_DB_URL = "jdbc:postgresql://localhost:5432/regliss"; $env:REGLISS_DB_USER = "regliss"; $env:REGLISS_DB_PASSWORD = "ChangeMe_Regliss1"
```

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

If your JDK is 17 rather than 21, add `"-Djava.version=17"`. The log should end with
`REGLISS Export Module ready on port 8082 (mock=false, profiles=[postgres])`.

| Variable | Default |
|---|---|
| `REGLISS_DB_URL` | `jdbc:postgresql://localhost:5432/regliss` |
| `REGLISS_DB_USER` | `regliss` |
| `REGLISS_DB_PASSWORD` | none, required |

If the `gs_*` tables live in another schema, set `export.source.schema` to it.

## 4. What to check

Open http://localhost:8082 and sign in as `maker` / `Regliss-maker`. The sidebar's Database switch shows **PostgreSQL**.

| Where | Expected |
|---|---|
| Export page | Versions 42, 43 and 44 are offered. 77 is not (it is hidden). |
| Format Templates | Only `gs_*` tables, `gs_load_log` locked (no `bnp_id`), no `ref_country`. |
| Data explorer | Pick columns from several tables, press **Preview data and filter rows**, filter with the funnel on a column header. |
| Export version 42 | The job ends **Held**: SHINE is held because record 42000041 has no country. Accept the omissions on the job to release its file. |
| Approver review | Save a template, sign in as `approver` / `Regliss-approver`, review it on **Approvals**, request changes or approve. |
| Data versions | Export version 42 twice. On **Data Versions** the template has version 1.0, used by both exports (the second reused it). Change a value, e.g. `UPDATE gs_party SET full_name = 'Renamed' WHERE bnp_id = 'BNP42000005';`, export again: version 1.1 appears with "~1 changed". Download 1.0: it still holds the old name. |

Then look at what the module wrote:

```sql
SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'ver_export%' ORDER BY table_name;

-- Data versions and how they were used
SELECT label, template_name, list_code, row_count, stored_rows, inserted_rows, deleted_rows, changed_rows, reuse_count, created_at
  FROM ver_export_snapshot ORDER BY id;

-- Who did what
SELECT logged_at, actor, entity_type, action, details FROM ver_export_audit ORDER BY id;
```

## 5. Start again

To clear the module's data but keep the source tables, stop the app and run `db/postgres/reset-export-tables.sql`.
To reset everything, run `regliss-mock.sql` again as well.

## Data versions: how they are stored

Every export records the exact rows it produced as a **data version**, numbered `template version.data revision`
(1.0, 1.1, … and 2.0 once a new template version is approved).

- An export reads the source once per template, writes each row in a fixed form (every value, empty ones included),
  and hashes it. The dataset's fingerprint is the hash of the column layout and all row hashes in record order. If a
  version of the same template version already has that fingerprint, it is reused; otherwise the next number is given.
- `ver_export_snapshot` holds one row per version. `ver_export_snapshot_row` holds its lines (version, line, row hash).
  `ver_export_snapshot_data` holds each distinct row once, so a version that changes five rows stores five rows.
- Versions are numbered under a row lock on `ver_export_snapshot_series` (one row per template and list), and a unique
  key on (template, list, template version, fingerprint) makes duplicates impossible even across app instances.
- Subscriber files, retries and downloads are built from the stored rows; the source query is not run again. Nothing in
  these tables is ever updated (apart from usage counters) or deleted.

## Large data

Exports stream: rows are read with a database cursor (PostgreSQL needs this inside a transaction; the module does it),
staged to a file while hashing, and written to the snapshot tables in batches of 500. The explorer pages and counts in
the database. On a large database, index `ver_version_member (version_id, bnp_id)` and `bnp_id` on every `gs_*`
table; the module creates its own indexes on the snapshot tables.
