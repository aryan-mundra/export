# The XML file

One format, written by one class (`GroupXml`), used for the **live file** of a list (List page → XML) and for the file
**sent to subscribers** (a stored data version). The same data always gives the same text.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Export list="Sanctions Screening" listVersion="1" dataVersion="2" generatedAt="2026-11-25T10:00:00" records="3">
  <persons count="2">
    <person person_id="101">
      <birth_year>1970</birth_year>
      <person_address address_no="11">
        <city>London</city>
        <address_country id="1">
          <country>GB</country>
        </address_country>
      </person_address>
      <person_name name_id="1">
        <first_name>John</first_name>
      </person_name>
    </person>
  </persons>
  <entities count="1">
    <entity entity_id="E-9001">
      <legal_name>ACME Holdings</legal_name>
    </entity>
  </entities>
</Export>
```

## Reading it

| Part | Meaning |
|---|---|
| `<Export …>` | `list` is the list's name, `listVersion` the approved version of its columns, `dataVersion` the stored version sent (absent in the live file), `generatedAt` when the data was read (for a data version: when it was taken), `records` the number of records of every group. |
| `<persons count="2">` | One section per **group**, in the order the list's columns first use the groups. `count` is the number of records in it. |
| `<person person_id="101">` | One **record**. Its key is an attribute named after the key column. |
| `<birth_year>` | A column of the record's own table, in the list's order. Values are text, trimmed. **Empty values are left out.** |
| `<person_address address_no="11">` | A **related row**, one element per row, repeated (no wrapper element). When the related table has a one-column primary key it is written as an attribute (`address_no`), or `id` when the key has another shape, so a row can be referred to. |

## Names

- The group and the related rows are named after their **tables without the prefix every table shares**
  (`cfg_dj_person`, `cfg_dj_entity` → `person`, `entity`). A group's section adds a plural ending (`persons`, `entities`).
- Column elements use the export name chosen on the List page (letters, digits, `_`, `-`, `.`).
- A related row that would have the same name as a column of its parent gets the ending `_row` (a column `name` and a
  related table `name` → `<name_row>`), so a name never means two things.

## Filters

A list can keep only some values of a column (Preview → ▾ on a header). The file then holds only the records and related
rows that pass, and `count` / `records` count those. The filters used are stored in the data version's layout.

## Changes from the first format

| Before | Now |
|---|---|
| `<cfg_dj_entityaddresses><cfg_dj_entityaddress>…` (table names with their prefix, a wrapper per table, two levels per row) | `<entityaddress>…` repeated |
| Related rows had no identifier | The primary key of the row as an attribute |
| Groups in table-name order | Groups in the order of the list's columns |
| Header: `version` (live) or `dataVersion` (sent), no total | `listVersion`, `dataVersion`, `records` |
| Two writers (live and stored) | One writer |
