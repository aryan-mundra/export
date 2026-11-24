package com.bnpp.export.controller;

import com.bnpp.export.dto.FormatFieldRequest;
import com.bnpp.export.dto.FormatTemplateRequest;
import com.bnpp.export.dto.FormatTemplateResponse;
import com.bnpp.export.dto.GroupPreviewResponse;
import com.bnpp.export.dto.GroupTableResponse;
import com.bnpp.export.dto.ReviewDecisionRequest;
import com.bnpp.export.service.FormatTemplateService;
import com.bnpp.export.service.GroupExportService;
import com.bnpp.export.service.TableGroupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** Lists: the group tables to build them from, a preview per group, their versions, approval, and their XML. */
@RestController
@RequestMapping("/api/format-templates")
public class FormatTemplateController {

    private final FormatTemplateService templates;
    private final TableGroupService tableGroups;
    private final GroupExportService groupExport;

    public FormatTemplateController(FormatTemplateService templates, TableGroupService tableGroups, GroupExportService groupExport) {
        this.templates = templates;
        this.tableGroups = tableGroups;
        this.groupExport = groupExport;
    }

    @GetMapping
    public List<FormatTemplateResponse> list() {
        return templates.list();
    }

    /** Every table of every group in config/table-groups.yml, with its columns. */
    @GetMapping("/tables")
    public List<GroupTableResponse> tables() {
        return tableGroups.tables();
    }

    /** One preview per group the columns use. */
    @PostMapping("/preview")
    public List<GroupPreviewResponse> preview(@RequestBody PreviewRequest request) {
        return groupExport.preview(request.fields(), request.offset() == null ? 0 : request.offset(),
                request.limit() == null ? 20 : request.limit());
    }

    @PostMapping
    public FormatTemplateResponse save(@Valid @RequestBody FormatTemplateRequest request) {
        return templates.save(request);
    }

    /** A list version's current data as group XML. */
    @GetMapping("/{id}/xml")
    public ResponseEntity<StreamingResponseBody> xml(@PathVariable Long id) {
        return FileResponses.temporary(groupExport.xml(id));
    }

    @PostMapping("/{id}/approve")
    public FormatTemplateResponse approve(@PathVariable Long id, @RequestBody(required = false) ReviewDecisionRequest decision) {
        return templates.approve(id, comment(decision));
    }

    @PostMapping("/{id}/reject")
    public FormatTemplateResponse reject(@PathVariable Long id, @RequestBody(required = false) ReviewDecisionRequest decision) {
        return templates.reject(id, comment(decision));
    }

    @PostMapping("/{id}/request-changes")
    public FormatTemplateResponse requestChanges(@PathVariable Long id, @RequestBody(required = false) ReviewDecisionRequest decision) {
        return templates.requestChanges(id, comment(decision));
    }

    /** The columns of a list being edited, and which page of records to show. */
    public record PreviewRequest(List<FormatFieldRequest> fields, Integer offset, Integer limit) {
    }

    private static String comment(ReviewDecisionRequest decision) {
        return decision == null ? null : decision.comment();
    }
}
