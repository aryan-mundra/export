# Database dumps

Put a PostgreSQL dump here (for example `shine-dump-shinedata-202611230033.sql`) and start the application
with the `postgres` profile. Before anything else starts, the application loads the dump's data into the
database configured in `REGLISS_DB_URL`.

The application **creates nothing**: no database, no table, no user. You create the database and the
`ver_export_` tables once, by hand. The application then only reads and writes rows.

Files in this folder are **not committed** (see `.gitignore`): dumps hold bank data. Copy the dump here by hand
on each machine.

## One-time setup on a new laptop

1. Install PostgreSQL 16 or 17 for Windows from postgresql.org (the EDB installer). Keep **Command Line Tools**
   ticked and remember the password you give the `postgres` user. If you cannot install software, ask IT for
   PostgreSQL with its command line tools.
2. In PowerShell, in the project folder, make the tools available (use your version number):

```powershell
$env:Path += ";C:\Program Files\PostgreSQL\17\bin"
```

3. Create the database, once:

```powershell
createdb -U postgres shinedata
```

4. Create the application's tables in it, once (every table is `ver_export_*`):

```powershell
psql -U postgres -d shinedata -f db/postgres/ver-export-schema.sql
```

   Or in pgAdmin: open the `shinedata` database, Query Tool, open `db/postgres/ver-export-schema.sql`, run it.

5. Copy the dump file into this folder (`db/dumps`).
6. Set the connection (use your own `postgres` password):

```powershell
$env:REGLISS_DB_URL = "jdbc:postgresql://localhost:5432/shinedata"; $env:REGLISS_DB_USER = "postgres"; $env:REGLISS_DB_PASSWORD = "your-postgres-password"
```

7. Start the application:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

The log shows the import (`Importing … into "shinedata"`, then `Imported … in N s`). Large dumps take a few
minutes. Then open http://localhost:8082.

## What happens on start-up

| Situation | What the application does |
|---|---|
| No file here | Nothing; it uses the database as it is. |
| Database missing, or `ver_export_` tables missing | Stops, and says which of steps 3 and 4 to run. |
| Same file imported before | Skips it (files are recognised by their content, so renaming does not matter). |
| A different dump, after one was imported | Keeps the data and logs a warning. To load the new dump: drop and recreate the database by hand, repeat steps 3 and 4, restart. |
| Dump creates its own database (`CREATE DATABASE`) | Stops: ask for a dump made without `--create`. |
| Dump owned by users that do not exist here | Loads anyway; the objects are owned by your user. Those lines are listed as harmless errors. |
| Other errors inside the dump | Logged with the first lines; the full output is in `db/dumps/import-logs/`. |

Plain SQL dumps are run with `psql`; `pg_dump` archives (files starting with `PGDMP`, from `pg_dump -Fc`) with
`pg_restore`. If they are not found automatically, point to them:

```powershell
$env:PG_BIN = "C:\Program Files\PostgreSQL\17\bin"
```

To start without importing, set `$env:REGLISS_IMPORT_ENABLED = "false"`.
