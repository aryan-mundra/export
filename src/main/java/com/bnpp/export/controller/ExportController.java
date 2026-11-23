package com.bnpp.export.controller;

import com.bnpp.export.dto.CertifiedVersionResponse;
import com.bnpp.export.dto.DeliveryReportResponse;
import com.bnpp.export.dto.ExportJobResponse;
import com.bnpp.export.dto.ExportLatestResponse;
import com.bnpp.export.dto.ExportPreview;
import com.bnpp.export.dto.ExportTemplatePageResponse;
import com.bnpp.export.dto.ListPageResponse;
import com.bnpp.export.dto.ResendPreviewResponse;
import com.bnpp.export.dto.ResendRequest;
import com.bnpp.export.dto.TriggerExportRequest;
import com.bnpp.export.service.ExportService;
import com.bnpp.export.service.ListCatalogService;
import com.bnpp.export.service.TemplateExportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exports: what can be exported, the review of an export or a send, and starting them. */
@RestController
@RequestMapping("/api")
public class ExportController {

    private final ExportService exportService;
    private final TemplateExportService templateExports;
    private final ListCatalogService listCatalog;

    public ExportController(ExportService exportService, TemplateExportService templateExports, ListCatalogService listCatalog) {
        this.exportService = exportService;
        this.templateExports = templateExports;
        this.listCatalog = listCatalog;
    }

    /** Certified list versions operators can export. */
    @GetMapping("/versions")
    public List<CertifiedVersionResponse> versions() {
        return exportService.versions();
    }

    @GetMapping("/versions/{versionId}/report")
    public DeliveryReportResponse report(@PathVariable Long versionId) {
        return exportService.latestReport(versionId);
    }

    /** Source lists that can be exported (newest certified version of each), searched and paged. */
    @GetMapping("/lists")
    public ListPageResponse lists(@RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "recent") String sort,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(defaultValue = "20") int limit) {
        return listCatalog.lists(q, sort, offset, limit);
    }

    /** The file formats the module can write. A new format appears here, and in the send dialog, once its writer exists. */
    @GetMapping("/formats")
    public List<String> formats() {
        return exportService.formatTypes();
    }

    @PostMapping("/exports/preview")
    public ExportPreview preview(@Valid @RequestBody TriggerExportRequest request) {
        return exportService.previewExport(request);
    }

    @PostMapping("/exports")
    public ExportJobResponse trigger(@Valid @RequestBody TriggerExportRequest request) {
        return exportService.startExport(request);
    }

    /** Approved lists with their latest data version and subscribers, searched and paged. */
    @GetMapping("/export-templates")
    public ExportTemplatePageResponse exportTemplates(@RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "false") boolean withVersions,
                                                      @RequestParam(defaultValue = "0") int offset,
                                                      @RequestParam(defaultValue = "25") int limit) {
        return templateExports.list(q, withVersions, offset, limit);
    }

    /** Reads a list's current data: the existing version if nothing changed, otherwise the next version. */
    @PostMapping("/export-templates/{familyId}/latest")
    public ExportLatestResponse exportLatest(@PathVariable Long familyId) {
        return templateExports.exportLatest(familyId);
    }

    /** Review of sending a stored data version: what each chosen subscriber would receive. */
    @PostMapping("/exports/resend/preview")
    public ResendPreviewResponse previewResend(@Valid @RequestBody ResendRequest request) {
        return exportService.previewResend(request);
    }

    /** Sends a stored data version, from its table. Needs the token of a matching review. */
    @PostMapping("/exports/resend")
    public ExportJobResponse resend(@Valid @RequestBody ResendRequest request) {
        return exportService.startResend(request);
    }
}
