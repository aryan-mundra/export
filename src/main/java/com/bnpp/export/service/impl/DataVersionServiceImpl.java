package com.bnpp.export.service.impl;

import com.bnpp.export.dto.DataVersionResponse;
import com.bnpp.export.dto.FileDownload;
import com.bnpp.export.dto.TakeVersionResponse;
import com.bnpp.export.entity.FormatField;
import com.bnpp.export.entity.FormatTemplate;
import com.bnpp.export.exception.BadRequestException;
import com.bnpp.export.exception.ResourceNotFoundException;
import com.bnpp.export.model.DataVersion;
import com.bnpp.export.model.GroupSelection;
import com.bnpp.export.model.TableGroup;
import com.bnpp.export.model.VersionLayout;
import com.bnpp.export.repository.DataVersionRepository;
import com.bnpp.export.repository.GroupRecordRepository;
import com.bnpp.export.security.CurrentUser;
import com.bnpp.export.service.AuditService;
import com.bnpp.export.service.DataVersionService;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.TableGroupService;
import com.bnpp.export.util.Hashing;
import com.bnpp.export.util.XmlNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link DataVersionService}.
 *
 * <p>Taking a version reads every record of every group the list uses and turns each into a canonical document
 * ({@link RecordDocuments}). The version's fingerprint is the hash of its layout and of every (group, key, hash); when
 * it equals the list's newest version, that version is returned and nothing is stored. Records are staged in a file
 * while read, so memory use does not grow with the list, and nothing is written until the data is known to be new.
 */
@Service
public class DataVersionServiceImpl implements DataVersionService {

    private static final int PAGE = GroupRecordRepository.BATCH;

    private final TableGroupService tableGroups;
    private final GroupRecordRepository groupRecords;
    private final DataVersionRepository versions;
    private final FormatTemplateService templates;
    private final TransactionTemplate transactions;
    private final AuditService audit;

    public DataVersionServiceImpl(TableGroupService tableGroups, GroupRecordRepository groupRecords, DataVersionRepository versions,
                                  FormatTemplateService templates, TransactionTemplate transactions, AuditService audit) {
        this.tableGroups = tableGroups;
        this.groupRecords = groupRecords;
        this.versions = versions;
        this.templates = templates;
        this.transactions = transactions;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ taking a version

    @Override
    public TakeVersionResponse take(Long familyId) {
        FormatTemplate template = templates.inUse(familyId).or(() -> templates.latest(familyId))
                .orElseThrow(() -> new ResourceNotFoundException("List not found"));
        List<GroupSelection> selections = tableGroups.selections(templates.fields(template.getId()));
        if (selections.isEmpty()) {
            throw new BadRequestException(template.getName() + " has no columns from the table groups");
        }
        String layout = RecordDocuments.write(layout(selections));
        MessageDigest fingerprint = Hashing.sha256();
        fingerprint.update(layout.getBytes(StandardCharsets.UTF_8));
        Map<String, Long> counts = new LinkedHashMap<>();
        Path staging = null;
        try {
            staging = Files.createTempFile("regliss-data-version", ".tsv");
            try (BufferedWriter out = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
                for (GroupSelection selection : selections) {
                    stage(selection, fingerprint, counts, out);
                }
            }
            String print = Hashing.hex(fingerprint.digest());
            DataVersion latest = versions.findLatest(familyId).orElse(null);
            if (latest != null && print.equals(versions.fingerprint(latest.id()))) {
                return new TakeVersionResponse(DataVersionResponse.from(latest), false);
            }
            Path file = staging;
            Long id = transactions.execute(status -> store(template, layout, print, counts, file));
            DataVersion stored = get(id);
            audit.template(template.getId(), "DATA_VERSION_CREATED",
                    "\"" + template.getName() + "\" data version " + stored.number() + " stored: " + counts);
            return new TakeVersionResponse(DataVersionResponse.from(stored), true);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not stage the data version: " + ex.getMessage(), ex);
        } finally {
            deleteQuietly(staging);
        }
    }

    /** Writes a group's records to the staging file as "group TAB key TAB hash TAB document" lines. */
    private void stage(GroupSelection selection, MessageDigest fingerprint, Map<String, Long> counts, BufferedWriter out) {
        String group = selection.group().name();
        String key = selection.resolved().keyColumn();
        counts.put(group, 0L);
        groupRecords.stream(selection, row -> {
            String recordKey = String.valueOf(row.get(key)).trim();
            String doc = RecordDocuments.write(RecordDocuments.build(selection, selection.group().root().table(), row));
            String hash = Hashing.sha256(doc);
            fingerprint.update((group + '\u0000' + recordKey + '\u0000' + hash + '\n').getBytes(StandardCharsets.UTF_8));
            counts.merge(group, 1L, Long::sum);
            try {
                out.write(group + '\t' + recordKey + '\t' + hash + '\t' + doc + '\n');
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private Long store(FormatTemplate template, String layout, String fingerprint, Map<String, Long> counts, Path staging) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Long id = versions.insert(template.getFamilyId(), template.getId(), template.getVersionNumber(), template.getName(),
                versions.nextNumber(template.getFamilyId()), fingerprint, total, counts, layout, CurrentUser.displayName());
        List<Object[]> batch = new ArrayList<>(PAGE);
        Set<String> seen = new HashSet<>();
        try (BufferedReader in = Files.newBufferedReader(staging, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split("\t", 4);
                if (!seen.add(parts[0] + '\u0000' + parts[1])) {
                    throw new BadRequestException("Group " + parts[0] + " has key " + parts[1] + " twice: the root key must be unique");
                }
                batch.add(new Object[] {id, parts[0], parts[1], parts[2], parts[3]});
                if (batch.size() == PAGE) {
                    versions.insertRecords(batch);
                    batch.clear();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (!batch.isEmpty()) {
            versions.insertRecords(batch);
        }
        return id;
    }

    private static VersionLayout layout(List<GroupSelection> selections) {
        List<VersionLayout.Group> groups = new ArrayList<>();
        for (GroupSelection selection : selections) {
            TableGroup group = selection.group();
            List<VersionLayout.Table> tables = new ArrayList<>();
            for (String table : selection.tables()) {
                List<String> headers = selection.valueFields(table).stream().map(FormatField::getHeaderName).toList();
                TableGroup.Link link = group.link(table).orElse(null);
                tables.add(link == null
                        ? new VersionLayout.Table(table, null, group.name(), group.section(), false, headers)
                        : new VersionLayout.Table(table, link.refTable(), link.element(), link.section(), link.id() != null, headers));
            }
            groups.add(new VersionLayout.Group(group.name(), group.section(), selection.resolved().keyColumn(),
                    XmlNames.element(group.root().key()), group.root().table(), tables,
                    selection.fields().stream().filter(field -> field.getFilterValues() != null && !field.getFilterValues().isEmpty())
                            .map(field -> new VersionLayout.Filter(field.getSourceTable(), field.getSourceColumn(), field.getFilterValues())).toList()));
        }
        return new VersionLayout(groups);
    }

    // ------------------------------------------------------------------ reading

    @Override
    public List<DataVersionResponse> versions(Long familyId) {
        return versions.findByFamily(familyId).stream().map(DataVersionResponse::from).toList();
    }

    @Override
    public DataVersion get(Long id) {
        return versions.find(id).orElseThrow(() -> new ResourceNotFoundException("Data version not found"));
    }

    @Override
    public VersionLayout layout(Long id) {
        return RecordDocuments.read(versions.layout(id), VersionLayout.class);
    }

    // ------------------------------------------------------------------ XML

    @Override
    public FileDownload xml(Long id) {
        DataVersion version = get(id);
        try {
            Path file = Files.createTempFile("regliss-data-version", ".xml");
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writeXml(id, out);
            } catch (IOException | RuntimeException ex) {
                Files.deleteIfExists(file);
                throw ex;
            }
            return FileDownload.temporary(XmlNames.element(version.listName()) + "_data_v" + version.number() + ".xml", file);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the XML file", ex);
        }
    }

    @Override
    public long writeXml(Long id, Writer out) {
        DataVersion version = get(id);
        VersionLayout layout = layout(id);
        try {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.write("<Export list=\"" + XmlNames.escape(version.listName()) + "\" dataVersion=\"" + version.number()
                    + "\" generatedAt=\"" + version.createdAt().withNano(0) + "\">\n");
            long total = 0;
            for (VersionLayout.Group group : layout.groups()) {
                out.write("  <" + group.section() + " count=\"" + version.groups().getOrDefault(group.name(), 0L) + "\">\n");
                String last = null;
                List<String[]> page;
                do {
                    page = versions.page(id, group.name(), last, PAGE);
                    for (String[] record : page) {
                        StringBuilder xml = new StringBuilder(512);
                        xml.append("    <").append(group.name()).append(' ').append(group.keyAttribute()).append("=\"")
                                .append(XmlNames.escape(record[0])).append("\">\n");
                        writeNode(group, group.rootTable(), RecordDocuments.read(record[1]), xml, "      ");
                        xml.append("    </").append(group.name()).append(">\n");
                        out.append(xml);
                        total++;
                        last = record[0];
                    }
                } while (page.size() == PAGE);
                out.write("  </" + group.section() + ">\n");
            }
            out.write("</Export>\n");
            return total;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to write the XML file: " + ex.getMessage(), ex);
        }
    }

    private static void writeNode(VersionLayout.Group group, String table, Map<String, Object> node, StringBuilder xml, String indent) {
        Map<String, Object> values = RecordDocuments.values(node);
        for (String header : group.table(table).headers()) {
            Object value = values.get(header);
            if (value != null) {
                String element = XmlNames.element(header);
                xml.append(indent).append('<').append(element).append('>').append(XmlNames.escape(value))
                        .append("</").append(element).append(">\n");
            }
        }
        for (VersionLayout.Table child : group.childrenOf(table)) {
            List<Map<String, Object>> rows = RecordDocuments.rows(node, child.table());
            if (rows.isEmpty()) {
                continue;
            }
            xml.append(indent).append('<').append(child.section()).append(">\n");
            for (Map<String, Object> row : rows) {
                xml.append(indent).append("  <").append(child.element()).append(">\n");
                writeNode(group, child.table(), row, xml, indent + "    ");
                xml.append(indent).append("  </").append(child.element()).append(">\n");
            }
            xml.append(indent).append("</").append(child.section()).append(">\n");
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // a temporary file: the system clears it
            }
        }
    }
}
