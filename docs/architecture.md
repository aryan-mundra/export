# Code structure

The module is a Spring Boot application organised in layers. Each layer has one job and only calls the layer below
it.

```
com.bnpp.export
├── controller      REST endpoints only: mappings, request binding, response status
├── service         service interfaces: the contract each controller and service depends on
│   └── impl        their implementations (…ServiceImpl): business logic and validation
├── repository      data access: Spring Data JPA repositories for the module's tables, and JDBC repositories for
│                   the source tables (SourceTableRepository, GroupRecordRepository) and data versions
│                   (DataVersionRepository)
├── entity          JPA entities: the module's ver_export_* tables
├── model           internal domain types (TableGroup, GroupSelection, GroupRow, DataVersion, VersionLayout...)
├── dto             request and response objects of the API (records)
├── config          Spring configuration: security, the source-schema settings, database switch, dump import, start-up jobs
├── security        the signed-in user (CurrentUser, ExportPrincipal)
├── delivery        CFT adapters (CftDeliveryAdapter: mock or real)
├── exception       the module's exceptions and the one handler that turns them into responses
└── util            stateless helpers (SqlUtils, XmlNames, Hashing, OffsetPage)
```

## How it works

1. **Table groups** (discovered, no configuration file): `SourceConstraintRepository` reads the primary and foreign keys
   of the source schema from `information_schema`; `TableGroupBuilder` turns them into groups. A table with a one-column
   primary key and no foreign key is the **root** of a group (its primary key is the group's common key); every table whose
   foreign key points at a table of the group is related to it, level by level. `TableGroupService` checks every table and
   column against the database and keeps the result for 60 seconds. XML names come from the table names.
2. **Lists** (`FormatTemplateService`): columns picked from the group tables. Each group's key is always on the list.
   Every version is approved by a List Owner before subscribers receive it.
3. **Preview and XML** (`GroupExportService`, `GroupRecordRepository`): a group's records are read root table first,
   in key order, 500 at a time; each related table is read once per batch with `WHERE foreign_key IN (...)` and
   attached to its parent rows. The XML has one section per group, one element per record with its key as an
   attribute, and related rows nested under it.
4. **Data versions** (`DataVersionService`): a list's data at a moment. Each record is stored once as canonical JSON
   with a SHA-256 hash; a new version is stored only when the data changed.
5. **Synthesis** (`SynthesisService`): two versions compared — records added, removed or changed counted in SQL by
   hash, and what changed inside a record found by comparing its two documents.
6. **Sends** (`SendService`, `ExportRunService`): a data version sent to chosen subscribers. The user reviews the send
   first; each subscriber's file is the version's XML, delivered (two attempts), archived and hashed as proof. Sends
   run in the background and are listed in History (`JobService`).

## Rules

- Controllers call services only, and services call repositories; no SQL outside `repository`.
- Entities never leave the service layer: the API returns DTOs.
- Errors are exceptions (`BadRequestException`, `ConflictException`…), turned into responses by
  `GlobalExceptionHandler`; services never build HTTP responses.
- Names read from the database are always quoted (`SqlUtils.quote`); values are always bound parameters.
- Large data is streamed or paged, never loaded whole: records 500 at a time, files written as they are read.

## Configuration

Every per-machine setting is in the `config/` folder (see `config/README.md`): `database.properties`, and an optional `application.yml` overriding any default of
`src/main/resources/application.yml`. The module's tables are created by `db/postgres/ver-export-schema.sql`.

## Adding something

- **A new group:** create its tables with their primary and foreign keys in the source schema. No code change and no
  file to edit; the List page shows it within a minute (`export.source.cache-seconds`).
- **A new endpoint:** add a method to the service interface, implement it in the Impl class, add a DTO for its
  response, and a one-line controller method.
- **A new error case:** throw the matching exception from the service.

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
| `config/` | `database.properties`, optional `application.yml` |
| `db/dumps/` | Dumps to import into PostgreSQL at start-up |
| `data/` | Built and archived files (`data/exports`) and the chosen database (`data/database.properties`) |

The table script is inside the jar. Use `--server.port=9090` to change the port. The page loads two Google Fonts from
the internet; without internet access the browser uses its standard fonts and everything else works.
