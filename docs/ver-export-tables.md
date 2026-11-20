# Export module tables

## Rules

1. **Every table the export module writes is named `ver_export_<thing>`.** The `ver_export_` prefix keeps the
   module's tables together and clear of REGLISS's own `ver_` tables (such as `ver_version_member`).
2. **The module never writes to source tables.** `gs_*` tables and the certified-version catalog are read only.
3. **History is only ever added to.** Files, file records, deliveries, and audit entries are never deleted, and a
   retry adds new rows beside the old ones. Job and job-subscriber rows hold the current state of a run and are
   updated as it moves on; files only change status from BUILT to SENT. One exception today: retrying a failed
   subscriber replaces that subscriber's quarantine rows.
4. **Every change names who made it.** Each write adds a row to `ver_export_audit` with the signed-in username in
   `actor`, or `system` for work no user started. Background export runs carry the user who triggered them.
5. **Every file can be proven.** Each built file is recorded with its SHA-256 hash, and every record in it is
   listed, so "did subscriber X receive record Y, and in which file?" has an answer.
6. **Template changes are versioned, not edited.** Saving a template adds a new version; approving one switches
   which version is in use.

## Tables

| Table | One row per | Written when |
|---|---|---|
| `ver_export_subscriber` | Subscriber (format, scope, mode, delivery endpoint) | A subscriber is added, changed, or its endpoint approved |
| `ver_export_template` | Version of a CSV or XML template | A template is submitted or approved |
| `ver_export_template_field` | Column in a template version (gs_ table, column, name in the file, order; matched on BNP_ID) | A template is submitted |
| `ver_export_job` | Export run of a certified version (date window, counters, final status) | An export is triggered, and as it progresses |
| `ver_export_job_subscriber` | Subscriber within a run (status, hold reason, retries, acceptance) | Each subscriber is processed, accepted, released, or retried |
| `ver_export_file` | File built for a subscriber (name, path, size, SHA-256, template version, status BUILDING/BUILT/SENT/FAILED, archive) | When a file starts streaming, when it is complete, and when it is sent |
| `ver_export_file_record` | List record inside a file (record id, action, line number) | A file is built |
| `ver_export_delivery` | Delivery attempt over CFT (tracking id, hash, result, error) | Each send attempt |
| `ver_export_quarantine` | Record held back from a file, with the reason and who accepted the omission | A record fails the quality check, or omissions are accepted |
| `ver_export_audit` | Change of any kind (entity type and id, action, details, actor, time) | Every write above |
| `ver_export_user` | User who can sign in to the module (role MAKER or APPROVER) | Seeded at start-up |

The module reads, and never writes, `VER_VERSION` (certified versions), `VER_VERSION_MEMBER` (list entries, each with a `BNP_ID`) and the
`GS_*` tables. In H2 mock mode the module fills `VER_VERSION` with demo versions itself; on Oracle they come from
the database (see `db/oracle/regliss-mock.sql` and `docs/oracle-testing.md`).

## Audit actions

| Entity type | Actions |
|---|---|
| `EXPORT_JOB` | `EXPORT_QUEUED`, `EXPORT_STARTED`, `SUBSCRIBER_STARTED`, `SUBSCRIBER_SKIPPED`, `SUBSCRIBER_HELD`, `OMISSIONS_ACCEPTED`, `DELIVERY_SUCCESS`, `SUBSCRIBER_FAILED`, `EXPORT_COMPLETED`, `EXPORT_HELD`, `EXPORT_FAILED` |
| `SUBSCRIBER` | `SUBSCRIBER_CREATED`, `SUBSCRIBER_UPDATED` (before and after), `ENDPOINT_APPROVED`, `SUBSCRIBER_DELETED` |
| `TEMPLATE` | `TEMPLATE_SUBMITTED` (columns and names), `TEMPLATE_APPROVED` |
