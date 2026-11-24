# Code structure

The module is a Spring Boot application organised in layers. Each layer has one job and only calls the layer
below it.

```
com.bnpp.export
├── controller      REST endpoints only: mappings, request binding, response status
├── service         service interfaces: the contract each controller and service depends on
│   └── impl        their implementations (…ServiceImpl): all business logic and validation
├── dto             request and response objects of the API (records)
├── repository      Spring Data JPA repositories, and VersionTableRepository for the per-version tables
├── entity          JPA entities: the ver_export_* tables and the source tables the module reads
├── exception       the module's exceptions and the one handler that turns them into responses
├── config          Spring configuration, security, start-up seeders, dump import
├── security        the signed-in user (CurrentUser, ExportPrincipal)
├── format          file writers (CSV, XML), one ExportFormat bean per format
├── delivery        CFT adapters (CftDeliveryAdapter: mock or real)
└── util            stateless helpers (SqlUtils, JdbcStreams)
```

## Rules

1. **Controllers are thin.** A controller method maps a URL to one service call and returns its result. It never
   uses a repository, an entity or a `…ServiceImpl` class. Downloads go through `FileResponses`, which turns a
   `FileDownload` from a service into the HTTP response.
2. **Services are interfaces.** Controllers and other services depend on `service.XService`; Spring injects
   `service.impl.XServiceImpl`. Records used only between services (for example `SnapshotService.Selection`,
   `StorageService.PendingFile`, `RowFilterService.Condition`) live on the interface.
3. **Business logic lives in the Impl classes**: validation, numbering, review tokens, endpoint approval, audit
   entries. A rule that belongs to one entity's own fields can be a method on the entity
   (`SubscriberProfile.receives(record)`).
4. **The API speaks DTOs.** Entities never leave the service layer through a controller. Each response record has a
   static `from(entity)` factory (for example `SubscriberResponse.from`, `DataVersionResponse.from`). Response field
   names are the API contract: the web page reads them by name.
5. **Errors are exceptions.** Services throw `BadRequestException` (400), `UnauthorizedException` (401),
   `ForbiddenException` (403), `ResourceNotFoundException` (404), `ConflictException` (409) or
   `ServiceUnavailableException` (503). `GlobalExceptionHandler` answers every error as `{"message": "..."}` with
   that status.
6. **Database access goes through repositories**, or through `JdbcTemplate` in an Impl class for set-based SQL
   (streaming exports, the per-version tables, catalog searches). SQL text is built with `SqlUtils` (placeholders,
   quoted names, LIKE escaping); values are always bound, never concatenated.

## Main services

| Interface | What it does |
|---|---|
| `ExportService` | The one entry point the controllers use for exports; delegates to the four services below |
| `ExportReviewService` | Reviewing an export (counts per subscriber, review token) and queuing the export the user reviewed |
| `ResendService` | Reviewing and queuing a send of a stored data version, in chosen formats |
| `ExportRunService` | Running jobs in the background (stream, quality check, quarantine, deliver, settle), retries, accepting omissions |
| `JobQueryService` | Dashboard, History (paged), job detail, delivery report, proofs, file downloads, sample files |
| `SnapshotService` | Builds, numbers and stores data versions in their own tables, and reads them back |
| `DataVersionService` | The data-versions API: list, detail, rows, compare, download |
| `ComparisonService` | Compares two versions of a list in SQL: counts, changed values per column, filtered pages of records, CSV of every difference |
| `FormatTemplateService` | Lists (templates): versions, columns, row filters, the approver's review |
| `TemplateExportService` | Approved lists with their latest version; "export latest" |
| `SubscriberService` | Subscribers: add, change, endpoint approval, delete |
| `DataExplorerService` | Previewing and filtering the rows of a list being edited |
| `RowFilterService` | Row filters: validation and SQL conditions |
| `ListCatalogService` | Source lists and subscribers, searched and paged |
| `SourceCatalogService` | The gs_ tables and their columns |
| `GoldenSourceService` | Adds the gs_ values to list entries |
| `QualityCheckService` | Record checks and quarantine |
| `DeliveryService` | Sends a file over CFT and records the delivery |
| `StorageService`, `ExportManifestService` | Files on disk and their manifest rows |
| `AuditService` | The audit log |
| `AuthService`, `SystemService`, `QuarantineService` | Sign-in, system info, held records |

## Adding something

- **A new endpoint:** add a method to the service interface, implement it in the Impl class, add a DTO for its
  response, and a one-line controller method.
- **A new file format:** add an `ExportFormat` bean in `format`. It appears in `/api/formats`, the send dialog and
  the download buttons without other changes.
- **A new error case:** throw the matching exception from the service; do not build responses in services.

## Building and running as a jar

The web page (`src/main/resources/static`: `index.html`, `js/app.js`, `css/app.css`, the logo) is packed into the jar and
served by the module itself, so the jar is all there is to deploy.

```powershell
mvn package -DskipTests
```

```powershell
java -jar target/export-module-1.0.0-SNAPSHOT.jar
```

Add `"-Djava.version=17"` to the `mvn` command to build for Java 17; run the jar with the same Java version or newer.
The jar starts from any folder. These are read or written **next to it** (the folder it is started from):

| Path | What |
|---|---|
| `config/database.properties` | The PostgreSQL connection for the Database switch (copy it next to the jar) |
| `db/dumps/` | Dumps to import into PostgreSQL at start-up |
| `data/` | Built files (`data/exports`) and the chosen database (`data/database.properties`) |

The H2 table scripts are inside the jar. Use `--server.port=9090` to change the port. The page loads two Google Fonts
from the internet; without internet access the browser uses its standard fonts and everything else works.
