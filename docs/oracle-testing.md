# Testing the Export module against Oracle

The `oracle` profile runs the module against an Oracle schema:

- **Reads** certified versions from `VER_VERSION`, list records from `VER_VERSION_MEMBER`, and every `GS_*` table
  in the schema for the Format Templates page.
- **Writes** its own `VER_EXPORT_*` tables in the same schema. It creates them on first start
  (`ddl-auto: update`), keeps them between restarts, and never drops them.

**`BNP_ID` is the key.** Every list entry in `VER_VERSION_MEMBER` has a `BNP_ID`, and every `GS_*` table has a `BNP_ID`
column: a `GS_*` row belongs to the list entry with the same `BNP_ID`. Each row of a file is one list entry of the
exported version, and holds exactly the `GS_*` columns picked on the template. A `GS_*` table without `BNP_ID` is
shown on the Format Templates page but locked.

`db/oracle/regliss-mock.sql` creates and fills the tables it reads, with test data (`BNP_ID` = `BNP` + record id):

| Table | Rows | Notes |
|---|---|---|
| `VER_VERSION` | 4 | Versions 42 Sanctions, 43 World-Check, 44 PFA; 77 Sanctions is hidden from operators |
| `VER_VERSION_MEMBER` | 400 | 100 per version. Every 29th record has no NAME, every 41st no COUNTRY (to test quarantine). Records ending 14, 20, 34, 40, 54, 60, 80, 94 have awkward names: `Smith & Sons <Holdings>`, `O'Brien, Sean "Jr"`, `Zoë Ünal – Ελένη`, `  Padded Name  ` |
| `GS_PARTY` | 400 | One per record |
| `GS_ADDRESS` | 372 | Every 13th record has none (its columns come out empty) |
| `GS_IDENTIFIER` | 500 | Every 4th record has two (joined as `ID…; AL…`) |
| `GS_SANCTION_PROGRAM` | 400 | One per record |
| `GS_COUNTRY_RISK` | 400 | Risk of each party's country, one row per `BNP_ID` |
| `GS_LOAD_LOG` | 1 | No `BNP_ID` column, so the picker shows it locked |
| `REF_COUNTRY` | 2 | Not a `gs_` table, so it must not appear in the picker |

The mock schema was made for testing. Check its column names and types against the real REGLISS schema before relying on it.
To change the data, edit `db/oracle/generate-regliss-mock.js` and run `node db/oracle/generate-regliss-mock.js`.

## Automated check: database values to CSV and XML

`OracleExportFilesTest` proves the module's main job without any Oracle install. It starts the app on the `oracle`
profile against H2 in Oracle mode, loads `regliss-mock.sql`, sets up CSV and XML templates of `GS_*` columns from five
tables, exports version 42, and then for every subscriber file:

- works out the expected rows with its own SQL against the database (scope, business line, region, and the
  quarantine rule), independent of the module's code;
- reads the file back with a real parser (Apache Commons CSV, or the JDK's XML parser) and compares every cell.

```powershell
mvn test "-Dtest=OracleExportFilesTest"
```

It covers commas, quotes, apostrophes, `&` and `<`, non-ASCII letters, leading and trailing spaces, line breaks,
missing `GS_*` rows (empty cell in CSV, element left out in XML) and records with several `GS_*` rows (`AL…; ID…`).

## Without Docker: SQL Developer

SQL Developer is a client: it connects to an Oracle database and runs scripts, but it is not a database. Use it with
any Oracle database you can reach, instead of steps 1 and 2 below:

- **A test schema on your company's Oracle** (best: closest to production). Ask the DBA for an empty schema with
  `CREATE SESSION`, `CREATE TABLE` and a tablespace quota. Never use a production schema: the mock script drops and
  recreates its tables.
- **Oracle Database 23ai Free installed on Windows** (no Docker; download it from oracle.com). It creates the
  pluggable database `FREEPDB1` on port 1521; create a user in it for the module.

Then:

1. In SQL Developer, create a connection to that schema (host, port, service name, user, password) and open it.
2. Set *Tools > Preferences > Environment > Encoding* to **UTF-8** before opening the script, or the accented and
   Greek test names are stored garbled.
3. Open `db/oracle/regliss-mock.sql` and run it as a script with **F5** (*Run Script*), not F9. On Oracle 19c the
   first run reports "table or view does not exist" for each `DROP TABLE IF EXISTS`: remove `IF EXISTS` from those
   lines, and ignore those errors on the first run.
4. Check it loaded: `SELECT COUNT(*) FROM VER_VERSION_MEMBER;` returns 400.
5. Start the module as in step 3 below, pointing it at the same schema:

```powershell
$env:REGLISS_DB_URL = "jdbc:oracle:thin:@//HOST:1521/SERVICE_NAME"; $env:REGLISS_DB_USER = "YOUR_USER"; $env:REGLISS_DB_PASSWORD = "YOUR_PASSWORD"
```

Use the same host, port, service name and user as the SQL Developer connection. After an export, look at the
`VER_EXPORT_*` tables in SQL Developer, using the queries in step 4.

## 1. Start Oracle in Docker

Needs [Docker Desktop](https://www.docker.com/products/docker-desktop/). This uses Oracle Database 23ai Free
(the `gvenzl/oracle-free` image), which creates the `regliss` user for you. Pick your own passwords.

```powershell
docker run -d --name regliss-oracle -p 1521:1521 -e ORACLE_PASSWORD=ChangeMe_Admin1 -e APP_USER=regliss -e APP_USER_PASSWORD=ChangeMe_Regliss1 gvenzl/oracle-free:23-slim
```

Wait until the log says `DATABASE IS READY TO USE!` (about a minute the first time), then press Ctrl+C:

```powershell
docker logs -f regliss-oracle
```

## 2. Load the mock data

```powershell
docker cp db/oracle/regliss-mock.sql regliss-oracle:/tmp/regliss-mock.sql
```

```powershell
docker exec -e NLS_LANG=AMERICAN_AMERICA.AL32UTF8 regliss-oracle sqlplus -s regliss/ChangeMe_Regliss1@//localhost/FREEPDB1 @/tmp/regliss-mock.sql
```

`NLS_LANG` matters: some test names use accented and Greek letters (`Zoë Ünal – Ελένη`), and without it
SQL*Plus stores them garbled.

Check it loaded (expect 4 and 400):

```powershell
docker exec regliss-oracle bash -c "echo 'SELECT COUNT(*) FROM VER_VERSION; SELECT COUNT(*) FROM VER_VERSION_MEMBER;' | sqlplus -s regliss/ChangeMe_Regliss1@//localhost/FREEPDB1"
```

The script can be run again at any time; it drops and recreates its own tables.

## 3. Start the module on Oracle

The connection comes from environment variables. The URL and user default to the Docker set-up above, so only
the password is needed:

```powershell
$env:REGLISS_DB_PASSWORD = "ChangeMe_Regliss1"
```

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=oracle"
```

If your JDK is 17 rather than 21, add `"-Djava.version=17"`. From an IDE, run `ExportApplication` with the active
profile `oracle` and the same environment variable.

| Variable | Default |
|---|---|
| `REGLISS_DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `REGLISS_DB_USER` | `regliss` |
| `REGLISS_DB_PASSWORD` | none, required |

The log should end with `REGLISS Export Module ready on port 8082 (mock=false, profiles=[oracle])`.

## 4. What to check

Open http://localhost:8082 and sign in as `maker` / `Regliss-maker`. The sidebar's Database switch shows **Oracle**.

| Where | Expected |
|---|---|
| Export page | Versions 42, 43 and 44 are offered. 77 is not (it is hidden). |
| Format Templates | Only `GS_*` tables: `GS_ADDRESS`, `GS_COUNTRY_RISK`, `GS_IDENTIFIER`, `GS_LOAD_LOG` (locked: no BNP_ID), `GS_PARTY`, `GS_SANCTION_PROGRAM`. No `REF_COUNTRY`. The starter CSV and XML templates use `GS_PARTY` columns. |
| Export version 42 (no dates) | Job ends **Held**: SHINE is held because record 42000041 has no country. The others complete. |
| Quarantine page | 42000041 "Country is blank". Accept the omissions on the job to release SHINE's file. |
| Template with gs_ columns | Add `GS_PARTY.FULL_NAME` and `GS_IDENTIFIER.ID_VALUE` to the XML template, have `approver` / `Regliss-approver` approve it, export version 43, and open SUN's file: each `<Record>` carries `Party 43000020`-style names and `ID…; AL…` identifiers, matched on `BNP_ID`. |
| Delta window | Set a window such as 2026-11-01 to 2026-11-18: SHINE (the only delta subscriber) gets only records whose `CHANGED_ON` falls inside it. |

Then look at what the module wrote:

```sql
SELECT table_name FROM user_tables WHERE table_name LIKE 'VER_EXPORT%' ORDER BY table_name;

-- Which records went into which file
SELECT f.file_name, f.status, r.line_number, r.record_id, r.action
  FROM ver_export_file f JOIN ver_export_file_record r ON r.file_id = f.id
 ORDER BY f.id, r.line_number;

-- Who did what
SELECT logged_at, actor, entity_type, action, details FROM ver_export_audit ORDER BY id;
```

## 5. Start again

To clear the module's data but keep the source tables, stop the app and run `db/oracle/reset-export-tables.sql`
the same way as the mock script. To reset everything, run `regliss-mock.sql` again as well.

To remove the database entirely:

```powershell
docker rm -f regliss-oracle
```

## On a shared Oracle database

Set `REGLISS_DB_URL`, `REGLISS_DB_USER` and `REGLISS_DB_PASSWORD` to that schema. The user needs `CREATE TABLE`
for the first start, because the module creates its `VER_EXPORT_*` tables itself; if your DBA prefers to create
them, run the app once against a scratch schema and export the generated DDL. If the `GS_*` tables live in another
schema, set `export.source.schema` to that owner. `DROP TABLE IF EXISTS` in the scripts needs Oracle 23ai; on 19c,
remove `IF EXISTS` and ignore "table or view does not exist" errors.

## Without Oracle: a quick check in H2

H2 can emulate Oracle syntax, which is enough to check the script and the oracle profile before Docker is set up.
It is not a substitute for testing on Oracle. Load the script into an H2 file database (adjust the H2 version to
the one in your Maven repository):

```powershell
java -cp "$env:USERPROFILE\.m2\repository\com\h2database\h2\2.2.224\h2-2.2.224.jar" org.h2.tools.RunScript -url "jdbc:h2:file:./data/oracle-mock;MODE=Oracle" -user regliss -password regliss -script db/oracle/regliss-mock.sql
```

Then start the app with that database:

```powershell
$env:REGLISS_DB_URL = "jdbc:h2:file:./data/oracle-mock;MODE=Oracle"; $env:REGLISS_DB_PASSWORD = "regliss"
```

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=oracle"
```

## Large files

Exports stream: each batch of records is read, checked, joined to its `GS_*` values and written to the file before
the next batch is read, so memory use does not grow with file size. Files are written as `.part` and renamed when
complete; the review step counts in SQL. Measured on the H2 mock with the heap capped at 256 MB: 2,000,000 records
produced a 362 MB XML file in 65 s and a 54 MB CSV file in 52 s. Expect the database (fetch size 500, one `IN` query
per `GS_*` table per batch) to set the pace on Oracle, not the module.
