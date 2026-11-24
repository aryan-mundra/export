# Database dumps

Put a PostgreSQL dump here (for example `dowjones-dump-202611241442.sql`) and start the application
with the `postgres` profile. Before anything else starts, the application loads the dump's data into the
database configured in `REGLISS_DB_URL`, once.

The application creates no database, no user and none of its `ver_export_` tables: you create the database and
run `db/postgres/ver-export-schema.sql` once, by hand. (The only tables it creates are the per-version tables
`ver_<list>_v<major>_<minor>`, when an export makes a new data version.)

Files in this folder are **not committed** (see `.gitignore`): dumps hold bank data. Copy the dump here by hand
on each machine, directly in this folder (not in a sub-folder).

## One-time setup

The full walk-through, with pgAdmin and IntelliJ on Windows, is in
[docs/postgres-testing.md](../../docs/postgres-testing.md#set-up-on-a-windows-laptop-with-pgadmin-and-intellij). In short:

1. In pgAdmin, on **PostgreSQL 17**: create the database `reglissData`.
2. In a Query Tool opened from `reglissData`: open `db/postgres/ver-export-schema.sql` and run it (F5).
3. Copy the dump file into this folder.
4. Check `config/database.properties` (host, port, database, username, password); it holds the local connection.
5. Start the application (no profile needed), sign in, and click **PostgreSQL** under **Database** in the sidebar,
   then **Switch to PostgreSQL**.

The log shows `Importing … into "reglissData"`, then `Imported … in N s`. Large dumps take a few minutes. Then open
http://localhost:8082.

## What happens on start-up

| Situation | What the application does |
|---|---|
| No file here | Nothing; it uses the database as it is. |
| Database missing, or `ver_export_` tables missing | Stops, and says which of steps 1 and 2 to do. |
| Same file imported before | Skips it (files are recognised by their content, so renaming does not matter). |
| A different dump, after one was imported | Keeps the data and logs a warning. To load the new dump: drop and recreate the database by hand, repeat steps 1 and 2, restart. |
| Dump creates its own database (`CREATE DATABASE`) | Stops: ask for a dump made without `--create`. |
| Dump owned by users that do not exist here | Loads anyway; the objects are owned by your user. Those lines are listed as harmless errors. |
| Other errors inside the dump | Logged with the first lines; the full output is in `db/dumps/import-logs/`. |

Plain SQL dumps are run with `psql`; `pg_dump` archives (files starting with `PGDMP`, from `pg_dump -Fc`) with
`pg_restore`. They are taken from `PG_BIN`, else the PATH, else the newest `C:\Program Files\PostgreSQL\<version>\bin`.
Set `PG_BIN` when several PostgreSQL versions are installed.

To start without importing, set `REGLISS_IMPORT_ENABLED=false`.
