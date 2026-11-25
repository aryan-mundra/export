# Export module tables

## Rules

1. **Every table the module writes is named `ver_export_<thing>`**, which keeps them together and clear of REGLISS's
   own tables.
2. **The module never writes to source tables.** The tables of the groups (discovered from the foreign keys of the source schema) are read only.
3. **History is only added to.** Data versions, deliveries and audit entries are never changed or deleted; a retry
   adds a new delivery beside the old one. Send and file rows hold the current state of a send.
4. **Every change names who made it.** Each write adds a row to `ver_export_audit` with the signed-in user, or
   `system` for work no user started. Background sends carry the user who started them.
5. **Every delivered file can be proven.** Each delivery records the file's SHA-256 hash and an archived copy; History's
   *Check proof* compares them.
6. **Lists are versioned, not edited.** Saving a list adds a version; approving one puts it in use.
7. **Four eyes on every list.** A List Owner approves, rejects or sends back each version, and cannot decide on a
   version they submitted. Rejecting or sending back needs a written reason.

## Who creates the tables

`db/postgres/ver-export-schema.sql`, kept by hand and reviewed like code; Hibernate never creates or alters a table.
Run it once per database (pgAdmin's Query Tool or `psql -f`); every statement is `CREATE … IF NOT EXISTS`, so running it
again is safe. `reset-export-tables.sql` drops the module's tables (test databases only). On H2 the module runs the
script itself at start-up.

## The tables

| Table | One row per | Holds |
|---|---|---|
| `ver_export_user` | user | sign-in name, display name, password hash, role (MAKER / APPROVER) |
| `ver_export_subscriber` | subscriber | name, delivery endpoint and its approval, the list it receives, active |
| `ver_export_template` | list version | name, version number, status (PENDING, APPROVED…), who submitted and reviewed it |
| `ver_export_template_field` | column of a list version | group table and column, its name in the file, how the table joins its group |
| `ver_export_data_version` | data version | list, number, fingerprint, records per group, layout (groups, tables, XML names) |
| `ver_export_data_record` | record of a data version | group, key, content hash, the nested record as JSON |
| `ver_export_job` | send | the data version sent, to whom, by whom, status and counters |
| `ver_export_job_subscriber` | subscriber file of a send | status, records, file and archive paths, error, retries |
| `ver_export_delivery` | delivery attempt | file hash, record count, CFT tracking id, archive path, status |
| `ver_export_audit` | change | who did what to which send, subscriber or list, and when |
| `ver_export_dump_import` | imported dump | file name, hash and errors (written by the importer) |

## Data versions

A data version stores each record once, as JSON with sorted keys, empty values left out and related rows in a fixed
order, so equal data gives equal hashes. The version's fingerprint is the hash of its layout and every record hash:
taking a version when nothing changed returns the newest one and stores nothing. Synthesis joins two versions on
(group, key): equal hashes are unchanged records, which are never read.
