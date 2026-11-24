# Code review: Export module

Review of the whole Spring Boot code base (about 11,600 lines of Java, plus the web page), done as a senior engineer
would review production code: read first, change only what pays off, never change an API, a table or a behaviour
without saying so.

**How the fixes were checked.** Before changing anything, the JSON of every endpoint touched was captured on the
PostgreSQL test data with two real exports (jobs, held records, deliveries, searches including `_` and `%`). After the
changes the same 21 responses were captured again and compared byte for byte: they are identical (the one difference,
an XML file size, comes from the generation time written in the file's header, which differs on every run). All
automated tests pass (28).

**Second round (items 7 and 9, approved).** The split of `ExportServiceImpl` was checked the same way, on 26
responses (the 21 above plus versions, formats, two job details and an export review): identical, apart from the XML
size above and the mock delivery's random tracking ids. Paging changes the JSON of History and Quarantine on purpose;
for every search above, the rows of the new page (`limit=100`) are exactly the rows the old endpoint returned, and the
totals match. A new test (`HistoryQuarantinePagingTest`) checks slices, totals, searches and limits. All tests pass
(29). Both screens were checked in the browser with 28 jobs (two pages).

Status: **Fixed** = changed in this review. **Proposed** = needs a decision (it changes behaviour, an API or the
schema), so it was not changed.

## Summary

| # | Category | Finding | Priority | Status |
|---|---|---|---|---|
| 1 | Performance / database | List versions endpoint ran ~3 queries per list version, every 15 s | High | Fixed |
| 2 | Performance / database | History search loaded every job and filtered in Java | High | Fixed |
| 3 | Security | Demo users with passwords in code are created on PostgreSQL and shown on its sign-in page | High | Proposed |
| 4 | Performance / database | Subscribers page ran 2 queries per subscriber | Medium | Fixed |
| 5 | Performance / database | Quarantine search loaded every held record and filtered in Java | Medium | Fixed |
| 6 | Code quality / reliability | `@Transactional` that never applied; internal steps on the public interface | Medium | Fixed |
| 7 | Architecture | `ExportServiceImpl` (1,439 lines) does six jobs | Medium | Fixed |
| 8 | Security | CSRF protection is off | Medium | Proposed |
| 9 | API / database | History and Quarantine return every row (no paging) | Medium | Fixed |
| 10 | Database | Missing indexes for frequent quarantine queries | Low | Proposed |
| 11 | Performance / database | Export Latest loaded every list version, then kept the ones in use | Low | Fixed |
| 12 | Performance / database | Delivery report read all jobs of a version to use the newest | Low | Fixed |
| 13 | Performance | Send review streams the version once per subscriber | Low | Proposed |
| 14 | Code quality | `onList()` always returns true: a rule that no longer applies | Low | Proposed |
| 15 | Code quality | Convenience overloads on `ExportService` used only by tests | Low | Proposed |
| 16 | Security | Dump import builds `psql` commands with quoted values | Low | No change needed |

## Fixed

### 1. List versions: 3 queries per version, polled every 15 seconds (High)

- **Where:** `FormatTemplateServiceImpl.list()` → `toResponse(template)`; called by `GET /api/format-templates`.
- **Was:** for each list version, one query for its columns, one for its row filters, one for the subscribers that use
  it. The List and Approvals pages poll this endpoint every 15 seconds. With 300 list versions: about 900 queries per
  poll, per open browser.
- **Why it matters:** this is the classic N+1 pattern; the cost grows with every saved version, forever, and hits the
  database while nobody is doing anything.
- **Now:** `list()` reads all columns, all filters and all subscribers that chose a list in three queries
  (`findAllByOrderByTemplateIdAscPositionAsc`, `findByTemplateFamilyIdIsNotNullOrderByNameAsc`), groups them in memory
  and builds the same responses. Single-version paths (save, approve, reject) still read one version as before.
- **Benefit:** 4 queries per call whatever the number of versions. Same JSON.

### 2. History search loaded every job (High)

- **Where:** `listJobs(query)` (now in `JobQueryServiceImpl`); `GET /api/jobs?q=`.
- **Was:** `findAllByOrderByCreatedAtDesc()` then a Java filter on version, user, status and list name.
- **Now:** with a search text, `ExportJobRepository.search(like)` matches the same four fields in SQL
  (case-insensitive "contains", `%` `_` `\` escaped so they match literally). Without a search text it is unchanged.
- **Benefit:** a search reads only the matching jobs. Same results, same order.

### 4. Subscribers page: 2 queries per subscriber (Medium)

- **Where:** `SubscriberServiceImpl.list()` → `toResponse()`; `GET /api/subscribers`.
- **Was:** for each subscriber, a query for its list's name and one for its last delivery.
- **Now:** `FormatTemplateService.templateNames()` (all list names, one query) and
  `DeliveryRepository.findLatestOfEverySubscriber()` (each subscriber's newest delivery, one query).
- **Benefit:** 3 queries whatever the number of subscribers. Same JSON.

### 5. Quarantine search loaded every held record (Medium)

- **Where:** `QuarantineServiceImpl.search(query)`; `GET /api/quarantine?q=`.
- **Was:** read every quarantine row (this table grows with every export) and every subscriber, then filtered in Java.
- **Now:** subscriber names are matched in memory (a small table, needed anyway for the response), and
  `QuarantineRepository.search(like, subscriberIds)` matches record id, reason, version or those subscribers in SQL.
- **Benefit:** a search reads only matching rows. Same results, same order.

### 6. Transactions that never applied (Medium)

- **Where:** `recount(jobId)` and `finish(jobId, status, error)` (now in `ExportRunServiceImpl`), both `public`, `@Transactional`,
  declared on `ExportService`.
- **Was:** both are only called from inside `ExportServiceImpl`, so Spring's proxy is bypassed and `@Transactional`
  does nothing. They work because each one's update (`updateCounters`, `markFinished`) is itself transactional in the
  repository. The annotations mislead the next developer, and the interface exposed internal steps of a run.
- **Now:** both are `private`, without the annotation, with a comment saying what is atomic; removed from
  `ExportService`. Nothing outside the class called them.
- **Benefit:** the code says what really happens. No behaviour change.

### 7. `ExportServiceImpl` split by responsibility (Medium, architecture)

- **Where:** `service/impl/ExportServiceImpl.java`, 1,439 lines, 19 injected dependencies including a `@Lazy` reference
  to itself (needed so `@Async runExport` went through Spring's proxy).
- **Was:** one class reviewed exports, queued them, ran them, handled sends of stored versions, held and failed files,
  the dashboard and history, and every download.
- **Now:** `ExportService` is unchanged for the controllers and tests; `ExportServiceImpl` (about 200 lines) only
  delegates to:
  - `ExportReviewService`: export reviews, review tokens, date checks, queuing an export;
  - `ResendService`: reviews and queuing of sends of stored data versions;
  - `ExportRunService`: running a job (stream, check, quarantine, deliver with retries, counters, final status),
    retries, accepting omissions, recovery at start-up;
  - `JobQueryService`: dashboard, history, job detail, delivery report, proofs, downloads, sample files.

  What they share (finding a version, job, file or subscriber with the same error messages, the file formats, the
  audit log) is in one package-private `ExportSupport`. The two services that queue jobs call
  `ExportRunService.runExport` on the injected bean, so `@Async` applies without the `@Lazy` self-reference.
- **Benefit:** each class has one job and its own dependencies (the run service no longer sees the dashboard
  queries, the history no longer sees delivery). No behaviour change.

### 9. History and Quarantine are paged (Medium, API; approved change)

- **Where:** `GET /api/jobs`, `GET /api/quarantine`; `JobQueryServiceImpl.listJobs`, `QuarantineServiceImpl.search`;
  the History and Quarantine screens in `app.js`.
- **Was:** both endpoints returned every row as a JSON array.
- **Now:** both take `offset` (default 0) and `limit` (default 25, at most 100, like the other paged endpoints) and
  return `{total, offset, jobs}` and `{total, offset, records}`. Paging and counting run in the database
  (`Page` queries with an explicit count query; ties on the creation time are broken by id, so pages never overlap).
  The screens show "1–25 of 312 jobs" with Previous / Next; a new search starts again at the first page.
- **API change:** a client reading these two endpoints as arrays must read `jobs` / `records` instead. The web page is
  the only client in this repository and is updated.

### 11. Export Latest read every list version (Low)

- **Where:** `TemplateExportServiceImpl.list(...)`.
- **Was:** `findAll()` on the list versions table, then `filter(FormatTemplate::isActive)`.
- **Now:** `findByActiveTrue()`.

### 12. Delivery report read all jobs of a version (Low)

- **Where:** `latestReport(versionId)` (now in `JobQueryServiceImpl`).
- **Now:** `findFirstByVersionIdOrderByCreatedAtDesc` instead of loading the whole list and taking the first.

## Proposed (need a decision)

### 3. Demo sign-ins on real databases (High, security)

- **Where:** `config/UserSeeder` (always active), `application-postgres.yml` (`export.demo-data: true`),
  `AuthServiceImpl.DEMO_ACCOUNTS`.
- **Problem:** on any database with no users, including PostgreSQL, the module creates `maker` / `Regliss-maker` and
  `approver` / `Regliss-approver`; with `demo-data: true` the sign-in page also shows these passwords. Anyone who has
  read the source can sign in as a List Owner and approve lists.
- **Recommendation:** run `UserSeeder` only when `export.demo-data` is true, and set it to `false` in
  `application-postgres.yml` for any shared or production database (keep it `true` for your local laptop, e.g. through
  an environment variable). Real users then come from the bank's identity system (LDAP / SSO), or are created by an
  administrator.
- **Impact:** on a database with these users already, nothing changes until they are removed; on a new PostgreSQL
  database, nobody can sign in until users exist. That is why it needs your decision.

### 8. CSRF protection is off (Medium, security)

- **Where:** `SecurityConfig` (`csrf.disable()`).
- **Problem:** the API uses a session cookie. `SameSite=Lax` stops most cross-site POSTs, but CSRF tokens are the
  standard defence.
- **Recommendation:** enable CSRF with `CookieCsrfTokenRepository` and have `app.js` send the `X-XSRF-TOKEN` header
  (a few lines in its `api()` helper).
- **Impact:** every POST/PUT/DELETE needs the header: page and tests must change together.

### 10. Indexes for quarantine queries (Low, database)

- **Where:** `db/postgres/ver-export-schema.sql`.
- **Problem:** the quarantine list is sorted by `created_at` and the dashboard counts `patch_applied = false`; neither
  has an index (the existing one starts with `subscriber_status_id`).
- **Recommendation:** `CREATE INDEX ix_ver_export_quarantine_created ON ver_export_quarantine (created_at);` and
  `CREATE INDEX ix_ver_export_quarantine_open ON ver_export_quarantine (patch_applied);`
- **Impact:** schema change, run by hand on existing databases.

### 13. Send review streams the version once per subscriber (Low, performance)

- **Where:** `ResendServiceImpl.previewResend(...)`.
- **Problem:** to count what each chosen subscriber would get, the version is read once per subscriber: 50
  subscribers × 10,000 rows = 500,000 rows read for one review.
- **Recommendation:** read the version once and count for every subscriber in the same pass.
- **Impact:** none on the API; only worth it if reviews of large sends feel slow.

### 14. `onList()` always returns true (Low, code quality)

- **Where:** `ExportSupport.onList(...)` and its three callers.
- **Problem:** "a subscriber only receives lists in its scope" was replaced by "every subscriber receives every list,
  filtered by scope inside"; the check and its SKIPPED branch can no longer run.
- **Recommendation:** remove it if that rule will not come back; otherwise keep it as the place to add it.

### 15. Convenience overloads on `ExportService` (Low, code quality)

- `triggerExport` (3 forms), `preview` (2), `previewResend` (3), `triggerResend` (3) exist for tests. Reduce them to
  one form each when the tests are next touched.

### 16. Dump import commands (no change needed)

- `DumpImportListener` builds `psql -c` commands with quoted values. The values are a constant, a SHA-256 hash and the
  configured database name, never user input, and the file name is escaped. Low risk.

## Checked and left as they are

- **SQL injection:** request values are always bound; identifiers come from the database catalog and are quoted
  (`SqlUtils.quote`); the only free-form part of a query, the sort direction, is whitelisted.
- **Layers:** controllers only call service interfaces; entities never leave through the API; errors go through
  `GlobalExceptionHandler`.
- **Exports and versions:** streamed with database cursors and written in batches of 500; versions numbered under a
  row lock; comparisons computed in SQL with paging.
- **Resources:** files and JDBC streams are closed (try-with-resources everywhere).
- **Thread safety:** no shared mutable state; background runs carry the signed-in user.
- **Logging:** SLF4J throughout, no `System.out`.
- **The one broad catch** (`ExportRunServiceImpl`, `RuntimeException | Error`) is deliberate: a subscriber must never
  stay "In progress" after a crash.
