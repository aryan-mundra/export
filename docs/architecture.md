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
| `ExportService` | Reviews and runs exports and sends of stored versions, retries, omissions, proofs, history, file downloads |
| `SnapshotService` | Builds, numbers and stores data versions in their own tables, and reads them back |
| `DataVersionService` | The data-versions API: list, detail, rows, compare, download |
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
