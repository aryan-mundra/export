const state = {
    page: "dashboard",
    dashboard: null,
    subscribers: [],
    jobs: [],
    quarantine: [],
    system: null,
    search: { history: "", subscribers: "", quarantine: "" },
    openJobId: null,
    jobDetail: null,
    exportMessage: null,
    templates: [],
    templateDraft: null,
    sources: [],
    sourcesError: null,
    activeTable: null,
    tableFilter: "",
    user: null,
    versions: [],
    preview: null,
    previewRequest: null,
    templateStep: "columns",
    explore: null,
    editor: null
};

const pages = ["dashboard", "export", "history", "subscribers", "quarantine", "templates", "approvals", "versions"];

document.getElementById("db-h2").addEventListener("click", () => explainDatabase("h2"));
document.getElementById("db-postgres").addEventListener("click", () => explainDatabase("postgres"));
window.addEventListener("hashchange", () => navigate());
document.addEventListener("click", onClick);
document.addEventListener("submit", onSubmit);
document.addEventListener("input", onInput);
document.addEventListener("keydown", event => {
    if (event.key === "Enter" && event.target.matches && event.target.matches("[data-table-filter]")) {
        event.preventDefault();
    }
    onExplorerKey(event);
});

boot();

function pageFromHash() {
    const name = (location.hash || "#/dashboard").replace("#/", "");
    return pages.includes(name) ? name : "dashboard";
}

async function navigate() {
    state.page = pageFromHash();
    if (typeof closeEditor === "function") closeEditor();
    document.querySelectorAll(".nav a").forEach(link => {
        const current = link.dataset.page === state.page;
        link.classList.toggle("active", current);
        if (current) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    });
    const main = document.getElementById("main");
    main.classList.remove("is-entering");
    main.innerHTML = '<div class="loading">Loading…</div>';
    try {
        await refresh();
        render();
        if (state.page === "versions" && state.dv) loadVersionTab();
        void main.offsetWidth;
        main.classList.add("is-entering");
        schedulePoll();
    } catch (error) {
        document.getElementById("main").innerHTML = `<div class="banner" style="border-color:#fecaca;background:#fef2f2;color:#991b1b">${esc(error.message)}</div>`;
    }
}

async function refresh() {
    if (state.page === "dashboard") {
        state.dashboard = await api("/api/dashboard");
    } else if (state.page === "export") {
        state.subscribers = await api("/api/subscribers");
        state.templates = await api("/api/format-templates");
        state.versions = await api("/api/versions");
    } else if (state.page === "subscribers") {
        state.subscribers = await api("/api/subscribers?q=" + encodeURIComponent(state.search.subscribers));
        state.templates = await api("/api/format-templates");
    } else if (state.page === "history") {
        state.jobs = await api("/api/jobs?q=" + encodeURIComponent(state.search.history));
        if (state.openJobId) {
            state.jobDetail = await api("/api/jobs/" + state.openJobId);
        }
    } else if (state.page === "versions") {
        const [dataVersions, subscribers] = await Promise.all([api("/api/data-versions"), api("/api/subscribers")]);
        state.dataVersions = dataVersions;
        state.subscribers = subscribers;
    } else if (state.page === "quarantine") {
        state.quarantine = await api("/api/quarantine?q=" + encodeURIComponent(state.search.quarantine));
    } else if (state.page === "templates") {
        const [templates, sources, versions] = await Promise.all([
            api("/api/format-templates"),
            api("/api/format-templates/sources").catch(error => ({ error })),
            api("/api/versions").catch(() => [])
        ]);
        state.templates = templates;
        state.versions = versions;
        state.sources = sources.error ? [] : sources;
        state.sourcesError = sources.error ? sources.error.message : null;
        if (!state.templateDraft) {
            state.templateDraft = draftFromActive();
        }
        if (state.activeTable === null || !state.sources.some(source => tableKey(source.table) === state.activeTable)) {
            const firstGs = state.sources[0];
            state.activeTable = firstGs ? tableKey(firstGs.table) : null;
        }
    } else if (state.page === "approvals" && state.user && state.user.role === "APPROVER") {
        const [templates, subscribers, sources, versions] = await Promise.all([
            api("/api/format-templates"),
            api("/api/subscribers"),
            api("/api/format-templates/sources").catch(() => []),
            api("/api/versions").catch(() => [])
        ]);
        state.templates = templates;
        state.subscribers = subscribers;
        state.sources = sources;
        state.versions = versions;
    }
    if (state.page === "templates" || state.page === "approvals") updateBadges(state.templates);
}

function render() {
    const main = document.getElementById("main");
    if (state.page === "dashboard") main.innerHTML = dashboardHtml();
    if (state.page === "export") main.innerHTML = exportHtml();
    if (state.page === "history") main.innerHTML = historyHtml();
    if (state.page === "subscribers") main.innerHTML = subscribersHtml();
    if (state.page === "quarantine") main.innerHTML = quarantineHtml();
    if (state.page === "templates") main.innerHTML = templatesHtml();
    if (state.page === "versions") main.innerHTML = versionsHtml();
    if (state.page === "approvals") {
        main.innerHTML = state.user && state.user.role === "APPROVER"
            ? approvalsHtml()
            : `${head("Review", "Approvals", "Only an approver can open this page.")}<section class="panel"><div class="empty">Only an approver can open this page.</div></section>`;
    }
    const search = document.querySelector("[data-search]");
    if (search && document.activeElement !== search) {
        search.value = state.search[search.dataset.search] || "";
    }
}

let pollTimer = null;

function schedulePoll() {
    clearTimeout(pollTimer);
    if (!state.user) return;
    const busy = state.dashboard && state.dashboard.inProgressJobs > 0;
    const watchJob = state.page === "dashboard" || state.page === "history";
    const watchTemplate = state.page === "templates" || state.page === "approvals";
    const delay = watchJob && busy ? 2000 : (watchJob || watchTemplate ? 15000 : 0);
    if (!delay) return;
    pollTimer = setTimeout(async () => {
        await poll();
        schedulePoll();
    }, delay);
}

async function poll() {
    if (!state.user) return;
    if (state.page === "templates" || (state.page === "approvals" && state.user.role === "APPROVER")) {
        try {
            const templates = await api("/api/format-templates");
            const before = state.templates.map(item => item.id + ":" + item.status + ":" + item.active).join();
            if (document.querySelector("#decision-form")) return;
            const after = templates.map(item => item.id + ":" + item.status + ":" + item.active).join();
            if (before !== after) {
                state.templates = templates;
                updateBadges(templates);
                const form = document.getElementById("template-form");
                const editing = form && form.contains(document.activeElement);
                if (!editing) {
                    if (state.page === "templates") renderTemplatesKeepingFilter();
                    else render();
                }
            }
        } catch (error) {
            /* keep the current screen if a refresh fails */
        }
    }
    if (!state.dashboard && state.page !== "dashboard" && state.page !== "history") {
        return;
    }
    try {
        const dashboard = await api("/api/dashboard");
        const busy = dashboard.inProgressJobs > 0;
        const snapshot = JSON.stringify(dashboard);
        const changed = snapshot !== state.dashboardJson;
        state.dashboard = dashboard;
        state.dashboardJson = snapshot;
        if (!changed && !busy) return;
        if (state.page === "dashboard" && changed) {
            document.getElementById("main").innerHTML = dashboardHtml();
        } else if (state.page === "history" && busy) {
            state.jobs = await api("/api/jobs?q=" + encodeURIComponent(state.search.history));
            if (state.openJobId) state.jobDetail = await api("/api/jobs/" + state.openJobId);
            document.getElementById("main").innerHTML = historyHtml();
            const search = document.querySelector("[data-search]");
            if (search) search.value = state.search.history;
        }
    } catch (error) {
        /* keep the current screen if a refresh fails */
    }
}

function head(kicker, title, lede, extra = "") {
    return `<div class="page-head"><div class="page-title"><p class="kicker">${kicker}</p><h1>${title}</h1><p class="lede">${lede}</p></div>${extra}</div>`;
}

function dashboardHtml() {
    const data = state.dashboard || {};
    const recent = data.recentJobs || [];
    return `
        ${head("Overview", "Dashboard", "Files still to deliver, open quarantine, and what is running.")}
        <section class="stats">
            ${stat("Not delivered", data.undeliveredSubscribers, "red")}
            ${stat("Quarantine open", data.openQuarantine, "orange")}
            ${stat("In progress", data.inProgressJobs, "orange")}
            ${stat("Active subscribers", data.activeSubscribers, "green")}
            ${stat("Successful jobs", data.successfulJobs, "green")}
        </section>
        <section class="panel">
            <h2>Recent activity</h2>
            ${recent.length ? `<div class="activity-head"><span>Version</span><span>Status</span><span>Records</span><span>Operator</span><span>When</span></div>${recent.map(activityRow).join("")}` : '<div class="empty">No export jobs yet</div>'}
        </section>`;
}

function stat(label, value, tone) {
    return `<article class="stat ${tone}"><div class="stat-label">${label}</div><div class="stat-value">${num(value)}</div></article>`;
}

function activityRow(job) {
    const windowText = job.fromDate ? `${job.fromDate} to ${job.toDate}` : "All changes";
    return `<button class="activity-row" data-history="${job.id}" type="button">
        <span><strong>Version ${esc(job.versionId)}</strong><br><span class="muted">${esc(job.listName || "")} · ${esc(windowText)}</span></span>
        ${badge(job.status)}
        <span>${num(job.totalRecordsProcessed)} records</span>
        <span class="muted">${esc(job.triggeredBy || "operator")}</span>
        <span class="muted">${when(job.createdAt)}</span>
    </button>`;
}

function exportHtml() {
    const active = state.subscribers.filter(subscriber => subscriber.active);
    return `
        ${head("Delivery", "Trigger Export", "Review the export, then send it. Each subscriber gets its own template; the date window limits delta subscribers only.")}
        ${state.exportMessage ? `<div class="banner">${state.exportMessage}</div>` : ""}
        <section class="export-card">
            <div class="date-range" style="margin-top:0">
                <label class="field-label" for="versionId">List version<select id="versionId">${state.versions.map(version => `<option value="${version.versionId}" ${state.previewRequest && String(state.previewRequest.versionId) === String(version.versionId) ? "selected" : ""}>${esc(version.listName)} · version ${esc(version.versionId)}${version.contentAsOf ? " · as of " + esc(version.contentAsOf) : ""}</option>`).join("")}</select></label>
                <label class="field-label" for="fromDate">Delta window from<input id="fromDate" type="date"></label>
                <label class="field-label" for="toDate">Delta window to<input id="toDate" type="date"></label>
            </div>
            <div class="note"><strong>Full</strong> subscribers receive the whole version. The dates do not apply to them. <strong>Delta</strong> subscribers receive only records that changed inside the window.</div>
            <button id="reviewBtn" class="btn-primary" type="button" ${active.length ? "" : "disabled"}>Review export</button>
            ${previewHtml()}
            <div class="section-label">Active subscribers (${active.length})</div>
            <div class="chips">
                ${active.length ? active.map(chip).join("") : '<span class="muted">No active subscribers.</span>'}
            </div>
        </section>`;
}

function previewHtml() {
    const preview = state.preview;
    if (!preview) return "";
    const rows = preview.subscribers.map(row => `<tr>
        <td>${esc(row.name)}</td>
        <td>${esc(row.scope)}</td>
        <td>${esc(row.formatType)} · ${esc(row.exportMode)}</td>
        <td>${row.templateName ? `${esc(row.templateName)} <span class="muted">v${row.templateVersion}</span>${(row.templateFilters || []).length ? `<div class="row-rule">Rows: ${esc(row.templateFilters.join("; "))}</div>` : ""}` : '<span class="error-text">No approved template</span>'}</td>
        <td>${row.included ? num(row.records) + (row.quarantined ? ` <span class="error-text">+ ${num(row.quarantined)} held</span>` : "") : "—"}</td>
    </tr>`).join("");
    return `<div class="detail">
        <p>${preview.fromDate ? `Delta window ${esc(preview.fromDate)} to ${esc(preview.toDate)}.` : "No date window."}</p>
        <table class="detail-table">
            <thead><tr><th>Subscriber</th><th>Scope</th><th>File</th><th>Template</th><th>Records</th></tr></thead>
            <tbody>${rows}</tbody>
        </table>
        <div class="modal-actions">
            <button id="preview-cancel" class="btn-ghost" type="button">Cancel</button>
            <button id="triggerBtn" class="btn-primary" type="button">Confirm send</button>
        </div>
    </div>`;
}

function chip(subscriber) {
    return `<span class="chip"><span class="chip-name">${esc(subscriber.name)}</span><span class="fmt-${esc(subscriber.formatType)}">${esc(subscriber.formatType)}</span><span class="chip-mode">${esc(subscriber.scope)} ${esc(subscriber.exportMode)}</span></span>`;
}

function historyHtml() {
    const rows = state.jobs.map(job => {
        const open = String(state.openJobId) === String(job.id);
        return `<tr class="clickable" data-job="${job.id}">
            <td>${job.id}</td>
            <td>${esc(job.versionId)}</td>
            <td>${badge(job.status)}</td>
            <td>${esc(job.listName || "")}</td>
            <td>${job.fromDate ? esc(job.fromDate) + " → " + esc(job.toDate) : "All changes"}</td>
            <td>${esc(job.triggeredBy || "")}</td>
            <td>${num(job.totalRecordsProcessed)}</td>
            <td>${num(job.completedSubscribers)} sent · ${num(job.heldSubscribers)} held · ${num(job.skippedSubscribers)} other list</td>
            <td>${when(job.createdAt)}</td>
        </tr>
        ${open ? `<tr><td colspan="9">${detailHtml(state.jobDetail)}</td></tr>` : ""}`;
    }).join("");
    return `
            ${head("Jobs", "Export Jobs", "Open a job to see each subscriber file.", `<input class="search" data-search="history" placeholder="Search by version, list, or sender" value="${esc(state.search.history)}">`)}
        <section class="table-card">
            <table>
                <thead><tr><th>Job ID</th><th>Version</th><th>Status</th><th>List</th><th>Delta window</th><th>Triggered by</th><th>Records</th><th>Delivery</th><th>Created</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            ${state.jobs.length ? "" : '<div class="empty">No export jobs found.</div>'}
        </section>`;
}

function detailHtml(detail) {
    if (!detail) return '<div class="detail">Loading…</div>';
    const rows = (detail.subscribers || []).map(subscriber => `<tr>
        <td>${esc(subscriber.subscriberName)}</td>
        <td class="fmt-${esc(subscriber.formatType)}">${esc(subscriber.formatType)}</td>
        <td>${badge(subscriber.status)}</td>
        <td>${num(subscriber.recordsProcessed)}</td>
        <td>${num(subscriber.recordsQuarantined)}</td>
        <td>${subscriber.fileName ? `<a class="file-link" href="/api/jobs/${detail.id}/subscribers/${subscriber.id}/file">${esc(subscriber.fileName)}</a>` : "—"}</td>
        <td>${subscriber.snapshotLabel ? `<span class="dv-chip" title="${subscriber.snapshotOutcome === "CREATED" ? "This export found changed data and created this data version" : "The data was identical to this existing data version, so it was reused"}">${esc(subscriber.snapshotLabel)} · ${subscriber.snapshotOutcome === "CREATED" ? "new" : "reused"}</span>` : '<span class="muted">—</span>'}</td>
        <td class="error-text">${esc([subscriber.holdReason || subscriber.errorMessage || "", subscriber.acceptedBy ? "Accepted by " + subscriber.acceptedBy : ""].filter(Boolean).join(" "))}</td>
        <td class="actions">${subscriberActions(detail, subscriber)}</td>
    </tr>`).join("");
    return `<div class="detail">
        ${detail.errorMessage ? `<p class="error-text">${esc(detail.errorMessage)}</p>` : ""}
        <table class="detail-table">
            <thead><tr><th>Subscriber</th><th>Format</th><th>Status</th><th>Processed</th><th>Quarantined</th><th>File</th><th>Data version</th><th>Note</th><th></th></tr></thead>
            <tbody>${rows || '<tr><td colspan="9" class="muted">Waiting for subscribers…</td></tr>'}</tbody>
        </table>
    </div>`;
}

function subscriberActions(detail, subscriber) {
    const buttons = [];
    if (subscriber.status === "HELD") {
        buttons.push(`<button class="btn-edit" type="button" data-accept="${detail.id}:${subscriber.id}">Accept omissions</button>`);
    }
    if (subscriber.status === "FAILED") {
        buttons.push(`<button class="btn-edit" type="button" data-retry="${detail.id}:${subscriber.id}">Retry</button>`);
    }
    if (subscriber.archiveFileName) {
        buttons.push(`<button class="btn-ghost" type="button" data-proof="${detail.id}:${subscriber.id}">Check proof</button>`);
    }
    return buttons.join(" ");
}

function subscribersHtml() {
    const rows = state.subscribers.map(subscriber => `<tr>
        <td>${subscriber.id}</td>
        <td>${esc(subscriber.name)}</td>
        <td class="fmt-${esc(subscriber.formatType)}">${esc(subscriber.formatType)}</td>
        <td>${esc(subscriber.exportMode)}</td>
        <td>${esc(subscriber.scope)}</td>
        <td>${esc(subscriber.businessLine || "—")}</td>
        <td>${esc(subscriber.region || "—")}</td>
        <td>${esc(subscriber.cftEndpoint || "—")}</td>
        <td>${subscriber.templateName ? esc(subscriber.templateName) : '<span class="error-text">None</span>'}</td>
        <td>${subscriber.lastFileName ? `<a class="file-link" href="/api/subscribers/${subscriber.id}/last-file" download="${esc(subscriber.lastFileName)}">${esc(subscriber.lastFileName)}</a>` : "—"}</td>
        <td><span class="pill ${subscriber.active ? "pill-on" : "pill-off"}">${subscriber.endpointStatus === "PENDING" ? "Endpoint pending" : subscriber.active ? "Active" : "Inactive"}</span></td>
        <td class="actions">
            <button class="btn-edit" type="button" data-edit="${subscriber.id}">Edit</button>
            <button class="btn-ghost" type="button" data-sample="${subscriber.id}">Sample</button>
            ${subscriber.templateFamilyId ? `<button class="btn-ghost" type="button" data-versions-family="${subscriber.templateFamilyId}" data-versions-subscriber="${subscriber.id}">Versions</button>` : ""}
            <button class="btn-delete" type="button" data-delete="${subscriber.id}">Delete</button>
        </td>
    </tr>`).join("");
    return `
        ${head("Recipients", "Subscribers", "Who receives a file, in which format, and where it is delivered.", `<input class="search" data-search="subscribers" placeholder="Search subscribers..." value="${esc(state.search.subscribers)}">`)}
        <section class="table-card">
            <form class="add-form" id="add-form">
                <input name="name" placeholder="Subscriber name" required>
                ${select("formatType", ["CSV", "XML"], "CSV")}
                <select name="templateId" required aria-label="Template">${templateOptions("CSV", null)}</select>
                ${select("scope", ["ALL", "SANCTIONS", "WORLDCHECK", "PFA"], "ALL")}
                ${select("exportMode", ["DELTA", "FULL"], "DELTA")}
                <input name="businessLine" placeholder="Business line">
                <input name="region" placeholder="Region">
                <input name="cftEndpoint" placeholder="Delivery endpoint">
                <button class="btn-primary" type="submit">+ Add</button>
            </form>
            <table>
                <thead><tr><th>ID</th><th>Name</th><th>Format</th><th>Mode</th><th>Scope</th><th>Business line</th><th>Region</th><th>Delivery</th><th>Template</th><th>Last file</th><th>Active</th><th></th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            ${state.subscribers.length ? "" : '<div class="empty">No subscribers found.</div>'}
        </section>`;
}

function tableKey(table) {
    return table ? String(table).toLowerCase() : "";
}

function fieldKey(table, column) {
    return tableKey(table) + "." + String(column).toLowerCase();
}

function sourceLabel(table) {
    return table || "";
}

function tablePickerHtml(draft) {
    if (state.sourcesError) {
        return `<div class="banner banner-error">${esc(state.sourcesError)}.</div>`;
    }
    const picked = new Set(draft.fields.map(field => fieldKey(field.sourceTable, field.sourceColumn)));
    const gsCount = state.sources.length;
    const tables = state.sources.map(source => {
        const key = tableKey(source.table);
        const count = source.columns.filter(column => picked.has(fieldKey(source.table, column.name))).length;
        const meta = source.columns.length + " columns" + (source.keyColumn ? "" : " · no BNP_ID");
        return `<button type="button" class="table-option ${key === state.activeTable ? "active" : ""} ${source.keyColumn ? "" : "unlinked"}" data-pick-table="${esc(key)}" data-table-name="${esc(source.label.toLowerCase())}">
            <span class="table-name">${esc(source.label)}</span>
            <span class="table-meta">${esc(meta)}</span>
            ${count ? `<span class="table-count">${count}</span>` : ""}
        </button>`;
    }).join("");
    const active = state.sources.find(source => tableKey(source.table) === state.activeTable);
    let columns = '<div class="empty">Choose a table.</div>';
    if (active) {
        const usable = !!active.keyColumn;
        const allPicked = active.columns.every(column => picked.has(fieldKey(active.table, column.name)));
        const head = `<div class="columns-head">
            <div>
                <h3>${esc(active.label)}</h3>
                <p class="muted">${usable
                    ? `Matched to each list entry on <code>${esc(active.keyColumn)}</code>.`
                    : "This table has no BNP_ID column, so its rows cannot be matched to list entries."}</p>
            </div>
            ${usable ? `<button type="button" class="btn-ghost" data-pick-all="${allPicked ? "none" : "all"}">${allPicked ? "Clear all" : "Select all"}</button>` : ""}
        </div>`;
        const items = active.columns.map(column => {
            const key = fieldKey(active.table, column.name);
            return `<label class="column-option ${usable ? "" : "disabled"}">
                <input type="checkbox" data-pick-column="${esc(column.name)}" ${picked.has(key) ? "checked" : ""} ${usable ? "" : "disabled"}>
                <span class="column-name">${esc(column.name)}</span>
                <span class="column-type">${esc(column.type || "")}</span>
            </label>`;
        }).join("");
        columns = head + `<div class="column-list">${items || '<div class="empty">This table has no columns.</div>'}</div>`;
    }
    return `<div class="picker">
        <div class="picker-tables">
            <div class="field-label">Source tables · ${gsCount} gs_ table${gsCount === 1 ? "" : "s"}</div>
            <input class="table-filter" data-table-filter placeholder="Filter tables">
            <div class="table-list">${tables}</div>
            ${gsCount ? "" : '<p class="muted">No table starting with gs_ was found in the database.</p>'}
        </div>
        <div class="picker-columns">${columns}</div>
    </div>`;
}

function exportColumnsHtml(draft) {
    if (!draft.fields.length) {
        return '<div class="empty">No columns yet. Tick columns in a table above to add them.</div>';
    }
    return draft.fields.map((field, index) => {
        const renamed = field.headerName !== field.sourceColumn;
        return `<div class="field-row export-col">
            <span class="col-order">${index + 1}</span>
            <span class="col-source" title="${esc(sourceLabel(field.sourceTable) + "." + field.sourceColumn)}"><span class="col-table">${esc(sourceLabel(field.sourceTable))}</span><strong>${esc(field.sourceColumn)}${filterIndexFor(field.sourceTable, field.sourceColumn) >= 0 ? ' <span class="filtered-tag" title="Rows are filtered on this column">filtered</span>' : ""}</strong></span>
            <span class="muted">→</span>
            <input data-draft-header="${index}" value="${esc(field.headerName)}" placeholder="${esc(field.sourceColumn)}" aria-label="Export name for ${esc(field.sourceColumn)}">
            <button class="btn-ghost btn-small" type="button" data-reset-header="${index}" ${renamed ? "" : "disabled"} title="Use the column name as the export name">Keep original</button>
            <button class="btn-ghost btn-icon" type="button" data-move="${index}:-1" ${index === 0 ? "disabled" : ""} aria-label="Move up">↑</button>
            <button class="btn-ghost btn-icon" type="button" data-move="${index}:1" ${index === draft.fields.length - 1 ? "disabled" : ""} aria-label="Move down">↓</button>
            <button class="btn-delete" type="button" data-remove-field="${index}">Remove</button>
        </div>`;
    }).join("");
}

function templatesHtml() {
    const draft = state.templateDraft || draftFromActive();
    const families = templateFamilies(draft.formatType);
    const current = families.find(family => String(family.id) === String(draft.familyId));
    const saved = state.templates.map(template => `<tr>
        <td>${esc(template.name)}${template.active ? `<div><button type="button" class="link-btn" data-versions-family="${template.familyId}">Data versions</button></div>` : ""}</td>
        <td class="fmt-${esc(template.formatType)}">${esc(template.formatType)}</td>
        <td>${template.versionNumber}</td>
        <td>${templateStatus(template)}</td>
        <td>${template.active ? esc((template.usedBy || []).join(", ") || "—") : ""}</td>
        <td>${esc(template.fields.map(field => field.headerName).join(", "))}</td>
        <td>${(template.filters || []).length ? esc(template.filters.map(filter => filter.summary).join("; ")) : '<span class="muted">Every entry</span>'}</td>
    </tr>`).join("");
    let waiting = "";
    if (current && current.latest.status === "PENDING") {
        waiting = `<div class="banner">Version ${current.latest.versionNumber} of ${esc(current.name)} is waiting for approval. ${current.inUse ? `Its subscribers keep version ${current.inUse.versionNumber} until it is approved.` : "Subscribers can choose it once it is approved."} Saving again replaces it in the approver's queue.</div>`;
    } else if (current && current.latest.status === "CHANGES_REQUESTED") {
        waiting = `<div class="review-callout warn"><strong>${esc(current.latest.reviewedByName || "An approver")} asked for changes to version ${current.latest.versionNumber}:</strong> “${esc(current.latest.reviewComment || "")}”<br>The editor below holds version ${current.latest.versionNumber}. Make the changes and save to resubmit it as version ${current.latest.versionNumber + 1}.</div>`;
    } else if (current && current.latest.status === "REJECTED") {
        waiting = `<div class="review-callout bad"><strong>Version ${current.latest.versionNumber} was rejected</strong> by ${esc(current.latest.reviewedByName || "an approver")}: “${esc(current.latest.reviewComment || "")}”<br>${current.inUse ? `Version ${current.inUse.versionNumber} stays in use.` : ""} Saving starts a new version for approval.</div>`;
    }
    return `
        ${head("Formats", "Format Templates", "The file holds exactly the gs_ columns you pick, one row per entry of the list version you export. Each subscriber chooses its template on the Subscribers page.")}
        <section class="export-card template-card">
            <h2>${current ? "Edit " + esc(current.name) : "New template"}</h2>
            <p class="muted">${current
                ? `Saving creates version ${current.latest.versionNumber + 1} for approval. Its subscribers keep the version in use until an approver accepts it.`
                : "Saving sends version 1 for approval. Once approved, any subscriber of this format can choose it."}</p>
            ${waiting}
            <form id="template-form">
                <div class="add-form" style="padding:12px 0;border:0">
                    <select name="formatType" id="template-format" aria-label="Format">
                        <option value="CSV" ${draft.formatType === "CSV" ? "selected" : ""}>CSV</option>
                        <option value="XML" ${draft.formatType === "XML" ? "selected" : ""}>XML</option>
                    </select>
                    <select id="template-pick" aria-label="Template">
                        <option value="" ${current ? "" : "selected"}>＋ New template</option>
                        ${families.map(family => `<option value="${family.id}" ${current && current.id === family.id ? "selected" : ""}>${esc(family.name)}</option>`).join("")}
                    </select>
                    <input name="name" value="${esc(draft.name)}" placeholder="Template name" required ${current ? "readonly" : ""} aria-label="Template name">
                </div>
                ${stepperHtml(draft)}
                ${state.templateStep === "data" && draft.fields.length ? explorerHtml() : `
                ${tablePickerHtml(draft)}
                <div class="field-label">Export columns · ${draft.fields.length} · in file order</div>
                <p class="muted hint">Each column keeps its own name in the file unless you type a new one.</p>
                <div class="field-list">${exportColumnsHtml(draft)}</div>`}
                <div class="builder-foot">
                    <span class="muted" id="builder-summary">${builderSummary(draft)}</span>
                    <span class="spacer"></span>
                    ${state.templateStep === "data" && draft.fields.length
                        ? '<button type="button" class="btn-ghost" data-step="columns">← Back to columns</button>'
                        : `<button type="button" class="btn-ghost" data-step="data" ${draft.fields.length ? "" : "disabled"}>Preview data and filter rows →</button>`}
                    <button class="btn-primary" type="submit" ${draft.fields.length ? "" : "disabled"}>${current ? "Save new version" : "Save template"}</button>
                </div>
            </form>
            ${current ? `<details class="template-history"><summary>History of ${esc(current.name)}</summary>${templateHistoryHtml(current.id, null)}</details>` : ""}
        </section>
        <section class="table-card" style="margin-top:16px">
            <table>
                <thead><tr><th>Name</th><th>Format</th><th>Version</th><th>Status</th><th>Used by</th><th>Headers</th><th>Rows</th></tr></thead>
                <tbody>${saved}</tbody>
            </table>
            ${state.templates.length ? "" : '<div class="empty">No format templates yet.</div>'}
        </section>`;
}

function builderSummary(draft) {
    const columns = num(draft.fields.length) + " column" + (draft.fields.length === 1 ? "" : "s");
    const rows = draft.filters.length ? num(draft.filters.length) + " row filter" + (draft.filters.length === 1 ? "" : "s") : "every list entry";
    return columns + " · " + rows;
}

function stepperHtml(draft) {
    const onData = state.templateStep === "data" && draft.fields.length;
    const filters = draft.filters.length;
    return `<div class="stepper" role="tablist" aria-label="Template steps">
        <button type="button" role="tab" class="step ${onData ? "" : "active"}" aria-selected="${onData ? "false" : "true"}" data-step="columns">
            <span class="step-no">1</span><span><strong>Columns</strong><small>${num(draft.fields.length)} picked</small></span>
        </button>
        <span class="step-line" aria-hidden="true"></span>
        <button type="button" role="tab" class="step ${onData ? "active" : ""}" aria-selected="${onData ? "true" : "false"}" data-step="data" ${draft.fields.length ? "" : "disabled"}>
            <span class="step-no">2</span><span><strong>Data and row filters</strong><small>${filters ? num(filters) + " filter" + (filters === 1 ? "" : "s") : "All rows"}</small></span>
        </button>
    </div>`;
}

/** A draft to edit: the newest version of an existing template, or an empty new template. */
function draftFromActive(formatType = "CSV", familyId = null) {
    const family = familyId ? templateFamilies(formatType).find(item => String(item.id) === String(familyId)) : null;
    if (family) {
        const fields = family.latest.fields.map(field => ({
            sourceTable: field.sourceTable,
            sourceColumn: field.sourceColumn,
            headerName: field.headerName
        }));
        const filters = (family.latest.filters || []).map(filter => ({
            sourceTable: filter.sourceTable,
            sourceColumn: filter.sourceColumn,
            op: filter.op,
            values: filter.values || [],
            blank: !!filter.blank
        }));
        return { familyId: family.id, name: family.name, formatType, fields, filters };
    }
    return { familyId: null, name: "", formatType, fields: [], filters: [] };
}

function templateStatus(template) {
    const comment = template.reviewComment && (template.status === "CHANGES_REQUESTED" || template.status === "REJECTED")
        ? `<div class="row-rule" title="${esc(template.reviewedByName || "")}">“${esc(template.reviewComment)}”</div>` : "";
    return statusPill(template) + comment;
}

function endpointApprovals() {
    const waiting = (state.subscribers || []).filter(subscriber => subscriber.endpointStatus === "PENDING");
    if (!waiting.length) return "";
    const rows = waiting.map(subscriber => `<tr>
        <td>${esc(subscriber.name)}</td>
        <td>${esc(subscriber.cftEndpoint || "")}</td>
        <td><button class="btn-primary" type="button" data-endpoint="${subscriber.id}">Approve endpoint</button></td>
    </tr>`).join("");
    return `<section class="table-card"><h2>New delivery endpoints</h2><table>
        <thead><tr><th>Subscriber</th><th>Endpoint</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
    </table></section>`;
}


function quarantineHtml() {
    const rows = state.quarantine.map(record => `<tr>
        <td>${record.id}</td>
        <td>${esc(record.versionId)}</td>
        <td>${esc(record.subscriberName)}</td>
        <td>${esc(record.recordIdentifier)}</td>
        <td>${esc(record.reason)}</td>
        <td>${record.patchApplied ? "Accepted" + (record.acceptedBy ? " by " + esc(record.acceptedBy) : "") : "Open"}</td>
    </tr>`).join("");
    return `
        ${head("Quality", "Quarantine", "Records held back from a file, and the reason they were held.", `<input class="search" data-search="quarantine" placeholder="Search quarantined records..." value="${esc(state.search.quarantine)}">`)}
        <section class="table-card">
            <table>
                <thead><tr><th>ID</th><th>Version</th><th>Subscriber</th><th>Record ID</th><th>Reason</th><th>Decision</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            ${state.quarantine.length ? "" : '<div class="empty">No quarantined records.</div>'}
        </section>`;
}

/** One entry per template of a format: its name, the version in use (if approved) and the newest version. */
function templateFamilies(formatType) {
    const families = new Map();
    state.templates.filter(template => template.formatType === formatType).forEach(template => {
        const family = families.get(template.familyId) || { id: template.familyId, name: template.name, inUse: null, latest: template };
        if (template.active) family.inUse = template;
        if (template.versionNumber > family.latest.versionNumber) family.latest = template;
        families.set(template.familyId, family);
    });
    return [...families.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/** Template choices for a subscriber: only templates with an approved version can be picked. */
function templateOptions(formatType, selected) {
    const families = templateFamilies(formatType);
    if (!families.length) return `<option value="">No ${esc(formatType)} template yet</option>`;
    return `<option value="" ${selected ? "" : "selected"}>Choose a template…</option>` + families.map(family =>
        `<option value="${family.id}" ${String(family.id) === String(selected) ? "selected" : ""} ${family.inUse ? "" : "disabled"}>${esc(family.name)}${family.inUse ? "" : " (" + ({ PENDING: "waiting for approval", CHANGES_REQUESTED: "changes requested", REJECTED: "rejected" }[family.latest.status] || "not approved") + ")"}</option>`
    ).join("");
}

function select(name, values, selected) {
    return `<select name="${name}">${values.map(value => `<option ${value === selected ? "selected" : ""}>${esc(value)}</option>`).join("")}</select>`;
}

function badge(status) {
    return `<span class="badge badge-${esc(status)}">${esc(label(status))}</span>`;
}

function label(status) {
    return ({
        PENDING: "Pending",
        IN_PROGRESS: "In progress",
        COMPLETED: "Completed",
        FAILED: "Failed",
        CANCELLED: "Cancelled",
        QUARANTINED: "Quarantined",
        SUCCESS: "Success",
        HELD: "Held",
        SKIPPED: "Other list"
    })[status] || status || "";
}

async function onClick(event) {
    if (state.page === "approvals" && onReviewClick(event)) return;
    if (state.page === "versions" && onVersionsClick(event)) return;
    const versionsLink = event.target.closest("[data-versions-family]");
    if (versionsLink) {
        openDataVersions(Number(versionsLink.dataset.versionsFamily), versionsLink.dataset.versionsSubscriber || null);
        return;
    }
    if (onExplorerClick(event)) return;
    const historyButton = event.target.closest("[data-history]");
    if (historyButton) {
        location.hash = "#/history";
        state.openJobId = historyButton.dataset.history;
        return;
    }
    const jobRow = event.target.closest("[data-job]");
    if (jobRow && !event.target.closest("a")) {
        const id = jobRow.dataset.job;
        state.openJobId = String(state.openJobId) === id ? null : id;
        if (state.openJobId) {
            state.jobDetail = await api("/api/jobs/" + id);
        }
        render();
        return;
    }
    if (event.target.id === "reviewBtn") {
        await reviewExport();
        return;
    }
    if (event.target.id === "preview-cancel") {
        state.preview = null;
        state.previewRequest = null;
        render();
        return;
    }
    if (event.target.id === "triggerBtn") {
        await triggerExport();
        return;
    }
    const accept = event.target.closest("[data-accept]");
    if (accept) {
        await postJobAction(accept.dataset.accept, "accept", "Omissions accepted");
        return;
    }
    const retry = event.target.closest("[data-retry]");
    if (retry) {
        await postJobAction(retry.dataset.retry, "retry", "Subscriber retried");
        return;
    }
    const proof = event.target.closest("[data-proof]");
    if (proof) {
        const [jobId, statusId] = proof.dataset.proof.split(":");
        try {
            const result = await api(`/api/jobs/${jobId}/subscribers/${statusId}/proof`, { method: "POST" });
            toast(result.result + ". " + result.message, result.result === "MATCH" ? "success" : "error");
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    const sample = event.target.closest("[data-sample]");
    if (sample) {
        await downloadSample(sample.dataset.sample);
        return;
    }
    const endpoint = event.target.closest("[data-endpoint]");
    if (endpoint) {
        try {
            await api("/api/subscribers/" + endpoint.dataset.endpoint + "/approve-endpoint", { method: "POST" });
            toast("Delivery endpoint approved", "success");
            await refresh();
            render();
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    const edit = event.target.closest("[data-edit]");
    if (edit) {
        openEdit(state.subscribers.find(subscriber => String(subscriber.id) === edit.dataset.edit));
        return;
    }
    const remove = event.target.closest("[data-delete]");
    if (remove) {
        const subscriber = state.subscribers.find(item => String(item.id) === remove.dataset.delete);
        if (subscriber && confirm("Delete subscriber " + subscriber.name + "?")) {
            try {
                await api("/api/subscribers/" + subscriber.id, { method: "DELETE" });
                toast(subscriber.name + " deleted", "success");
                await refresh();
                render();
            } catch (error) {
                toast(error.message, "error");
            }
        }
        return;
    }
    if (event.target.id === "edit-cancel" || event.target.id === "modal-back") {
        document.getElementById("modal-root").innerHTML = "";
        return;
    }
    const pickTable = event.target.closest("[data-pick-table]");
    if (pickTable) {
        readDraftFromForm();
        state.activeTable = pickTable.dataset.pickTable;
        renderTemplatesKeepingFilter();
        return;
    }
    if (event.target.matches && event.target.matches("input[data-pick-column]")) {
        readDraftFromForm();
        toggleColumn(event.target.dataset.pickColumn, event.target.checked);
        renderTemplatesKeepingFilter();
        return;
    }
    const pickAll = event.target.closest("[data-pick-all]");
    if (pickAll) {
        readDraftFromForm();
        const source = state.sources.find(item => tableKey(item.table) === state.activeTable);
        if (source) source.columns.forEach(column => toggleColumn(column.name, pickAll.dataset.pickAll === "all"));
        renderTemplatesKeepingFilter();
        return;
    }
    const resetHeader = event.target.closest("[data-reset-header]");
    if (resetHeader) {
        readDraftFromForm();
        const field = state.templateDraft.fields[Number(resetHeader.dataset.resetHeader)];
        if (field) field.headerName = field.sourceColumn;
        renderTemplatesKeepingFilter();
        return;
    }
    const move = event.target.closest("[data-move]");
    if (move) {
        readDraftFromForm();
        const [index, step] = move.dataset.move.split(":").map(Number);
        const fields = state.templateDraft.fields;
        const target = index + step;
        if (target >= 0 && target < fields.length) {
            [fields[index], fields[target]] = [fields[target], fields[index]];
        }
        renderTemplatesKeepingFilter();
        return;
    }
    const removeField = event.target.closest("[data-remove-field]");
    if (removeField) {
        readDraftFromForm();
        const [removed] = state.templateDraft.fields.splice(Number(removeField.dataset.removeField), 1);
        if (removed) dropFilterOf(removed.sourceTable, removed.sourceColumn);
        renderTemplatesKeepingFilter();
        return;
    }
    const fill = event.target.closest(".demo-accounts button");
    if (fill) {
        const form = document.getElementById("login-form");
        form.username.value = fill.dataset.username;
        form.password.value = fill.dataset.password;
        form.username.focus();
        return;
    }
    if (event.target.id === "logout") {
        await api("/api/auth/logout", { method: "POST" });
        showLogin();
        return;
    }
    const approve = event.target.closest("[data-approve]");
    if (approve) {
        approve.disabled = true;
        try {
            const saved = await api("/api/format-templates/" + approve.dataset.approve + "/approve", { method: "POST" });
            toast(saved.name + " version " + saved.versionNumber + " approved", "success");
            await refresh();
            render();
        } catch (error) {
            toast(error.message, "error");
            approve.disabled = false;
        }
        return;
    }
}

async function onSubmit(event) {
    // getAttribute, not .id: a form with a field named "id" (the edit form) shadows form.id.
    const formId = event.target.getAttribute("id");
    if (formId === "decision-form") {
        event.preventDefault();
        await submitDecision(event.target);
        return;
    }
    if (formId === "login-form") {
        event.preventDefault();
        const form = new FormData(event.target);
        const error = document.getElementById("login-error");
        error.hidden = true;
        try {
            state.user = await api("/api/auth/login", {
                method: "POST",
                body: { username: form.get("username"), password: form.get("password") }
            });
            enterApp(true);
        } catch (loginError) {
            error.textContent = loginError.message;
            error.hidden = false;
        }
        return;
    }
    if (formId === "add-form") {
        event.preventDefault();
        const form = new FormData(event.target);
        try {
            await api("/api/subscribers", {
                method: "POST",
                body: {
                    name: form.get("name"),
                    formatType: form.get("formatType"),
                    scope: form.get("scope") || "ALL",
                    exportMode: form.get("exportMode"),
                    businessLine: form.get("businessLine"),
                    region: form.get("region"),
                    cftEndpoint: form.get("cftEndpoint"),
                    templateId: Number(form.get("templateId")) || null,
                    active: true
                }
            });
            toast("Subscriber added", "success");
            state.search.subscribers = "";
            await refresh();
            render();
        } catch (error) {
            toast(error.message, "error");
        }
    }
    if (formId === "edit-form") {
        event.preventDefault();
        const form = new FormData(event.target);
        try {
            await api("/api/subscribers/" + form.get("id"), {
                method: "PUT",
                body: {
                    name: form.get("name"),
                    formatType: form.get("formatType"),
                    scope: form.get("scope"),
                    exportMode: form.get("exportMode"),
                    businessLine: form.get("businessLine"),
                    region: form.get("region"),
                    cftEndpoint: form.get("cftEndpoint"),
                    templateId: Number(form.get("templateId")) || null,
                    active: form.get("active") === "on"
                }
            });
            document.getElementById("modal-root").innerHTML = "";
            toast("Subscriber updated", "success");
            await refresh();
            render();
        } catch (error) {
            toast(error.message, "error");
        }
    }
    if (formId === "template-form") {
        event.preventDefault();
        readDraftFromForm();
        const draft = state.templateDraft;
        const clash = !draft.familyId && templateFamilies(draft.formatType)
            .find(family => family.name.toLowerCase() === (draft.name || "").trim().toLowerCase());
        if (clash) {
            toast(`A ${draft.formatType} template named "${clash.name}" already exists. Pick it under Template to save a new version of it.`, "error");
            return;
        }
        try {
            const saved = await api("/api/format-templates", {
                method: "POST",
                body: {
                    name: state.templateDraft.name,
                    formatType: state.templateDraft.formatType,
                    fields: state.templateDraft.fields.map(field => ({
                        sourceTable: field.sourceTable,
                        sourceColumn: field.sourceColumn,
                        headerName: (field.headerName || "").trim() || field.sourceColumn
                    })),
                    filters: state.templateDraft.filters
                }
            });
            toast(saved.name + " version " + saved.versionNumber + " is pending for approval", "success");
            await refresh();
            // Stay on the template just saved, so its next version can be edited straight away.
            state.templateDraft = draftFromActive(saved.formatType, saved.familyId);
            renderTemplatesKeepingFilter();
            if (state.templateStep === "data") loadExplore();
        } catch (error) {
            toast(error.message, "error");
        }
    }
}

let searchTimer;
function onInput(event) {
    if (state.page === "approvals" && onReviewInput(event)) return;
    if (state.page === "versions" && onVersionsInput(event)) return;
    if (onExplorerInput(event)) return;
    if (event.target.name === "formatType" && event.target.closest("#add-form, #edit-form")) {
        const pick = event.target.form.querySelector('select[name="templateId"]');
        if (pick) pick.innerHTML = templateOptions(event.target.value, null);
        return;
    }
    if (event.target.id === "template-format") {
        state.templateDraft = draftFromActive(event.target.value);
        state.templateStep = "columns";
        closeEditor();
        renderTemplatesKeepingFilter();
        return;
    }
    if (event.target.id === "template-pick") {
        state.templateDraft = draftFromActive(state.templateDraft ? state.templateDraft.formatType : "CSV", event.target.value || null);
        state.templateStep = "columns";
        closeEditor();
        renderTemplatesKeepingFilter();
        return;
    }
    if (event.target.matches("[data-table-filter]")) {
        applyTableFilter(event.target.value);
        return;
    }
    if (event.target.matches("[data-draft-header]") && state.templateDraft) {
        const index = Number(event.target.dataset.draftHeader);
        const field = state.templateDraft.fields[index];
        if (field) {
            field.headerName = event.target.value;
            const reset = document.querySelector(`[data-reset-header="${index}"]`);
            if (reset) reset.disabled = event.target.value === field.sourceColumn;
        }
        return;
    }
    if (event.target.closest("#template-form") && event.target.name === "name" && state.templateDraft) {
        state.templateDraft.name = event.target.value;
        return;
    }
    if (event.target.id === "versionId" || event.target.id === "fromDate" || event.target.id === "toDate") {
        state.preview = null;
        state.previewRequest = null;
        const existing = document.querySelector(".export-card .detail");
        if (existing) existing.remove();
        return;
    }
    const field = event.target.closest("[data-search]");
    if (!field) return;
    state.search[field.dataset.search] = field.value;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
        try {
            await refresh();
            const caret = field.selectionStart;
            render();
            const again = document.querySelector("[data-search]");
            if (again) {
                again.focus();
                again.setSelectionRange(caret, caret);
            }
        } catch (error) {
            toast(error.message, "error");
        }
    }, 200);
}

function dateWindow() {
    const fromDate = document.getElementById("fromDate").value;
    const toDate = document.getElementById("toDate").value;
    if ((fromDate && !toDate) || (!fromDate && toDate)) {
        toast("Enter both dates, or leave both empty", "error");
        return null;
    }
    if (fromDate && toDate && fromDate > toDate) {
        toast("From date must be on or before the to date", "error");
        return null;
    }
    return { fromDate: fromDate || null, toDate: toDate || null };
}

async function reviewExport() {
    const versionEl = document.getElementById("versionId");
    const versionId = versionEl
        ? Number(versionEl.value)
        : ((state.versions && state.versions[0] && state.versions[0].versionId) || 42);
    if (!versionId) {
        toast("Choose a certified list version", "error");
        return;
    }
    const dates = dateWindow();
    if (!dates) return;
    const button = document.getElementById("reviewBtn");
    button.disabled = true;
    try {
        state.previewRequest = { versionId, ...dates };
        state.preview = await api("/api/exports/preview", {
            method: "POST",
            body: { versionId, fromDate: dates.fromDate, toDate: dates.toDate }
        });
        render();
    } catch (error) {
        toast(error.message, "error");
        button.disabled = false;
    }
}

async function triggerExport() {
    const request = state.previewRequest;
    if (!request) {
        toast("Review the export before sending", "error");
        return;
    }
    const button = document.getElementById("triggerBtn");
    button.disabled = true;
    try {
        const job = await api("/api/exports", {
            method: "POST",
            body: {
                versionId: request.versionId,
                fromDate: request.fromDate,
                toDate: request.toDate,
                reviewToken: state.preview ? state.preview.reviewToken : null
            }
        });
        const windowText = request.fromDate ? ` from ${request.fromDate} to ${request.toDate}` : "";
        state.exportMessage = `Export job #${job.id} started for ${state.preview.listName} version ${request.versionId}${windowText}. <a href="#/history">View history</a>`;
        state.preview = null;
        state.previewRequest = null;
        state.dashboard = await api("/api/dashboard");
        toast("Export job #" + job.id + " started", "success");
        render();
    } catch (error) {
        toast(error.message, "error");
        button.disabled = false;
    }
}

async function postJobAction(pair, action, message) {
    const [jobId, statusId] = pair.split(":");
    try {
        state.jobDetail = await api(`/api/jobs/${jobId}/subscribers/${statusId}/${action}`, { method: "POST" });
        state.jobs = await api("/api/jobs?q=" + encodeURIComponent(state.search.history));
        toast(message, "success");
        render();
    } catch (error) {
        toast(error.message, "error");
    }
}

async function downloadSample(subscriberId) {
    const response = await fetch("/api/subscribers/" + subscriberId + "/sample", { method: "POST" });
    if (!response.ok) {
        let message = "Sample failed";
        try { message = (await response.json()).message || message; } catch (error) { /* keep the fallback */ }
        toast(message, "error");
        return;
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    const header = response.headers.get("Content-Disposition") || "";
    const match = header.match(/filename="([^"]+)"/);
    link.href = url;
    link.download = match ? match[1] : "sample.csv";
    link.click();
    URL.revokeObjectURL(url);
    toast("Sample downloaded. It is not a delivery.", "success");
}

function openEdit(subscriber) {
    if (!subscriber) return;
    const options = (values, selected) => values.map(value => `<option ${value === selected ? "selected" : ""}>${esc(value)}</option>`).join("");
    document.getElementById("modal-root").innerHTML = `
        <div class="modal-back" id="modal-back">
            <form class="modal" id="edit-form">
                <h2>Edit subscriber</h2>
                <input type="hidden" name="id" value="${subscriber.id}">
                <label>Name<input name="name" value="${esc(subscriber.name)}" required></label>
                <label>Format<select name="formatType">${options(["CSV", "XML"], subscriber.formatType)}</select></label>
                <label>Template<select name="templateId" required>${templateOptions(subscriber.formatType, subscriber.templateFamilyId)}</select></label>
                <label>Scope<select name="scope">${options(["ALL", "SANCTIONS", "WORLDCHECK", "PFA"], subscriber.scope)}</select></label>
                <label>Mode<select name="exportMode">${options(["FULL", "DELTA"], subscriber.exportMode)}</select></label>
                <label>Business line<input name="businessLine" value="${esc(subscriber.businessLine || "")}"></label>
                <label>Region<input name="region" value="${esc(subscriber.region || "")}"></label>
                <label>Delivery endpoint<input name="cftEndpoint" value="${esc(subscriber.cftEndpoint || "")}"></label>
                <label class="check"><input type="checkbox" name="active" ${subscriber.active ? "checked" : ""}> Active</label>
                <div class="modal-actions">
                    <button class="btn-ghost" id="edit-cancel" type="button">Cancel</button>
                    <button class="btn-primary" type="submit">Save</button>
                </div>
            </form>
        </div>`;
}

function readDraftFromForm() {
    const form = document.getElementById("template-form");
    if (!form || !state.templateDraft) return;
    const data = new FormData(form);
    if (!state.templateDraft.familyId) state.templateDraft.name = data.get("name");
    state.templateDraft.formatType = data.get("formatType");
    state.templateDraft.fields.forEach((field, index) => {
        const header = form.querySelector(`[data-draft-header="${index}"]`);
        if (header) field.headerName = header.value;
    });
}

function toggleColumn(column, on) {
    const source = state.sources.find(item => tableKey(item.table) === state.activeTable);
    if (!source) return;
    if (!source.keyColumn) return;
    const fields = state.templateDraft.fields;
    const key = fieldKey(source.table, column);
    const index = fields.findIndex(field => fieldKey(field.sourceTable, field.sourceColumn) === key);
    if (on && index < 0) {
        fields.push({
            sourceTable: source.table || null,
            sourceColumn: column,
            headerName: column
        });
    } else if (!on && index >= 0) {
        fields.splice(index, 1);
        dropFilterOf(source.table, column);
    }
}

/** A filter needs its column on the template: removing the column removes the filter too. */
function dropFilterOf(table, column) {
    const at = filterIndexFor(table, column);
    if (at < 0) return;
    state.templateDraft.filters.splice(at, 1);
    toast("The row filter on " + column + " was removed with its column", "info");
}

function applyTableFilter(value) {
    const needle = String(value || "").trim().toLowerCase();
    state.tableFilter = value;
    document.querySelectorAll("[data-pick-table]").forEach(button => {
        button.hidden = needle !== "" && !button.dataset.tableName.includes(needle);
    });
}

function renderTemplatesKeepingFilter() {
    const columnList = document.querySelector(".column-list");
    const scroll = columnList ? columnList.scrollTop : 0;
    render();
    const filter = document.querySelector("[data-table-filter]");
    if (filter && state.tableFilter) {
        filter.value = state.tableFilter;
        applyTableFilter(state.tableFilter);
    }
    const again = document.querySelector(".column-list");
    if (again) again.scrollTop = scroll;
}

async function boot() {
    await loadDemoAccounts();
    try {
        state.user = await api("/api/auth/me");
        enterApp();
    } catch (error) {
        showLogin();
    }
}

async function loadDemoAccounts() {
    try {
        const config = await fetch("/api/auth/config").then(response => response.json());
        const box = document.querySelector(".demo-accounts");
        if (!box || !config.demo) return;
        box.hidden = false;
        box.innerHTML = `<div class="database-label">Demo accounts</div>` + (config.accounts || []).map(account =>
            `<button type="button" data-username="${esc(account.username)}" data-password="${esc(account.password)}">${esc(account.role)} · ${esc(account.username)} / ${esc(account.password)}</button>`
        ).join("");
    } catch (error) {
        /* sign-in still works by typing a username and password */
    }
}

function enterApp(fromLogin) {
    document.body.classList.remove("signed-out");
    updateBadges();
    document.getElementById("login-screen").hidden = true;
    const signedIn = document.getElementById("signed-in");
    signedIn.hidden = false;
    document.getElementById("signed-name").textContent = state.user.displayName;
    document.getElementById("signed-role").textContent = state.user.role === "APPROVER" ? "Approver" : "Maker";
    const avatar = document.querySelector(".avatar");
    if (avatar) {
        const parts = String(state.user.displayName || "").split(/\s+/).filter(Boolean);
        avatar.textContent = ((parts[0] || "").charAt(0) + (parts[1] || "").charAt(0)).toUpperCase();
    }
    const approvals = document.querySelector('[data-page="approvals"]');
    if (approvals) approvals.hidden = state.user.role !== "APPROVER";
    loadSystem();
    schedulePoll();
    if (fromLogin && state.user.role === "APPROVER") {
        if (location.hash === "#/approvals") navigate();
        else location.hash = "#/approvals";
        return;
    }
    if (state.user.role !== "APPROVER" && (location.hash === "" || location.hash === "#")) {
        if (location.hash === "#/dashboard") navigate();
        else location.hash = "#/dashboard";
        return;
    }
    if (state.user.role === "APPROVER" && (location.hash === "" || location.hash === "#" || location.hash === "#/dashboard")) {
        location.hash = "#/approvals";
        return;
    }
    navigate();
}

function showLogin() {
    state.user = null;
    clearTimeout(pollTimer);
    document.body.classList.add("signed-out");
    const screen = document.getElementById("login-screen");
    screen.hidden = false;
    document.getElementById("signed-in").hidden = true;
    document.getElementById("main").innerHTML = "";
}

async function loadSystem() {
    try {
        state.system = await api("/api/system");
        document.getElementById("db-h2").classList.toggle("selected", state.system.mode === "h2");
        document.getElementById("db-postgres").classList.toggle("selected", state.system.mode === "postgres");
    } catch (error) {
        /* the sidebar still shows the default H2 selection */
    }
}

function explainDatabase(mode) {
    const current = state.system ? state.system.mode : "h2";
    if (mode === current) return;
    if (mode === "postgres") {
        toast("To use PostgreSQL, restart with the postgres profile. See docs/postgres-testing.md.", "info");
    } else {
        toast("H2 is the default. Restart without the postgres profile.", "info");
    }
}

async function api(url, options = {}) {
    const opts = { ...options, headers: { ...(options.headers || {}) } };
    if (opts.body && typeof opts.body !== "string") {
        opts.headers["Content-Type"] = "application/json";
        opts.body = JSON.stringify(opts.body);
    }
    const response = await fetch(url, opts);
    if (response.status === 401 && !url.endsWith("/api/auth/login") && !url.endsWith("/api/auth/me")) {
        showLogin();
    }
    if (response.status === 204) return null;
    const text = await response.text();
    let data = null;
    if (text) {
        try { data = JSON.parse(text); } catch (error) { data = { message: text }; }
    }
    if (!response.ok) throw new Error((data && data.message) || "Request failed");
    return data;
}

function toast(message, tone, action) {
    const element = document.getElementById("toast");
    element.textContent = "";
    const text = document.createElement("span");
    text.textContent = message;
    element.appendChild(text);
    if (action) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "toast-action";
        button.textContent = action.label;
        button.addEventListener("click", () => {
            element.classList.remove("show");
            action.run();
        });
        element.appendChild(button);
    }
    element.className = "toast show " + (tone || "") + (action ? " has-action" : "");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => element.classList.remove("show"), action ? 6000 : 3200);
}

function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, char => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[char]);
}

function num(value) {
    return Number(value || 0).toLocaleString();
}

function when(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return esc(value);
    return date.toLocaleString(undefined, {
        day: "2-digit", month: "short", year: "numeric", hour: "numeric", minute: "2-digit"
    });
}

/* ---------- Data explorer: the rows a template puts in a file, and its row filters ---------- */

const EXPLORE_PAGE = 100;

const FILTER_OPS = {
    text: [["in", "is one of"], ["not_in", "is not one of"], ["contains", "contains"], ["empty", "is empty"], ["not_empty", "is not empty"]],
    number: [["eq", "="], ["ne", "≠"], ["gt", ">"], ["gte", "≥"], ["lt", "<"], ["lte", "≤"], ["between", "is between"], ["empty", "is empty"], ["not_empty", "is not empty"]],
    date: [["between", "is between"], ["empty", "is empty"], ["not_empty", "is not empty"]]
};

const FUNNEL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16l-6 7.5V19l-4 1.5v-8z"></path></svg>';

function sourceColumnInfo(table, column) {
    const source = state.sources.find(item => tableKey(item.table) === tableKey(table));
    const found = source && source.columns.find(item => item.name.toLowerCase() === String(column).toLowerCase());
    return found || { name: column, type: "", kind: "other" };
}

function kindTag(kind) {
    return kind === "number" ? "123" : kind === "date" ? "date" : kind === "text" ? "abc" : "—";
}

function fieldHeader(field) {
    return (field.headerName || "").trim() || field.sourceColumn;
}

function filterIndexFor(table, column) {
    const filters = (state.templateDraft && state.templateDraft.filters) || [];
    return filters.findIndex(filter => fieldKey(filter.sourceTable, filter.sourceColumn) === fieldKey(table, column));
}

/** The same words the server uses, so chips, approvals and the audit log read alike. */
function filterPhrase(op, values, blank) {
    const list = items => items.length > 8 ? items.slice(0, 8).join(", ") + " and " + (items.length - 8) + " more" : items.join(", ");
    const items = values || [];
    const first = items[0] || "";
    const second = items[1] || "";
    switch (op) {
        case "in": return items.length ? "is " + list(items) + (blank ? " or empty" : "") : "is empty";
        case "not_in": return items.length ? "is not " + list(items) + (blank ? ", and not empty" : "") : "is not empty";
        case "contains": return "contains \"" + first + "\"";
        case "eq": return "= " + first;
        case "ne": return "≠ " + first;
        case "gt": return "> " + first;
        case "gte": return "≥ " + first;
        case "lt": return "< " + first;
        case "lte": return "≤ " + first;
        case "between":
            if (!first) return "is on or before " + second;
            if (!second) return "is on or after " + first;
            return /^\d{4}-\d{2}-\d{2}$/.test(first) ? "is from " + first + " to " + second : "is between " + first + " and " + second;
        case "empty": return "is empty";
        case "not_empty": return "is not empty";
        default: return op;
    }
}

function filterHeader(filter) {
    const field = state.templateDraft.fields.find(item => fieldKey(item.sourceTable, item.sourceColumn) === fieldKey(filter.sourceTable, filter.sourceColumn));
    return field ? fieldHeader(field) : filter.sourceColumn;
}

function ensureExplore() {
    if (!state.explore) {
        state.explore = { versionId: null, sort: null, rows: [], total: null, entries: null, more: false, loading: false, loadingMore: false, error: null, seq: 0 };
    }
    if (!state.explore.versionId && state.versions.length) {
        state.explore.versionId = state.versions[0].versionId;
    }
    return state.explore;
}

function exploreBody(extra) {
    const draft = state.templateDraft;
    return Object.assign({
        versionId: state.explore.versionId,
        fields: draft.fields.map(field => ({ sourceTable: field.sourceTable, sourceColumn: field.sourceColumn, headerName: fieldHeader(field) })),
        filters: draft.filters
    }, extra);
}

function pageRequest(offset, withTotal) {
    const ex = state.explore;
    return exploreBody({
        sortColumn: ex.sort ? ex.sort.index : null,
        sortDirection: ex.sort ? ex.sort.dir : null,
        offset: offset,
        limit: EXPLORE_PAGE,
        withTotal: withTotal
    });
}

async function loadExplore() {
    const ex = ensureExplore();
    if (!state.templateDraft || !state.templateDraft.fields.length) return;
    const seq = ++ex.seq;
    ex.loading = true;
    ex.loadingMore = false;
    ex.error = null;
    renderExplorer();
    try {
        const page = await api("/api/format-templates/explore", { method: "POST", body: pageRequest(0, true) });
        if (seq !== ex.seq) return;
        Object.assign(ex, {
            rows: page.rows, total: page.total, entries: page.entries, more: page.more,
            versionId: page.versionId, listName: page.listName, loading: false
        });
    } catch (error) {
        if (seq !== ex.seq) return;
        Object.assign(ex, { rows: [], total: null, entries: null, more: false, loading: false, error: error.message });
    }
    renderExplorer();
    const wrap = document.getElementById("grid-wrap");
    if (wrap) wrap.scrollTop = 0;
}

async function loadMoreRows() {
    const ex = state.explore;
    if (!ex || ex.loading || ex.loadingMore || !ex.more) return;
    ex.loadingMore = true;
    const seq = ex.seq;
    const more = document.getElementById("grid-more");
    if (more) more.textContent = "Loading more rows…";
    try {
        const page = await api("/api/format-templates/explore", { method: "POST", body: pageRequest(ex.rows.length, false) });
        if (seq !== ex.seq) return;
        const start = ex.rows.length;
        ex.rows = ex.rows.concat(page.rows);
        ex.more = page.more;
        const body = document.getElementById("grid-body");
        if (body) body.insertAdjacentHTML("beforeend", gridRowsHtml(page.rows, start));
    } catch (error) {
        if (seq === ex.seq) toast(error.message, "error");
    } finally {
        if (seq === ex.seq) {
            ex.loadingMore = false;
            const sentinel = document.getElementById("grid-more");
            if (sentinel) sentinel.textContent = ex.more ? "Scroll for more rows" : "";
            const foot = document.getElementById("grid-foot");
            if (foot) foot.innerHTML = gridFootHtml();
        }
    }
}

function renderExplorer() {
    const host = document.getElementById("explorer");
    if (host) host.innerHTML = explorerInnerHtml();
    const summary = document.getElementById("builder-summary");
    if (summary) summary.textContent = builderSummary(state.templateDraft);
    const stepper = document.querySelector(".stepper");
    if (stepper) stepper.outerHTML = stepperHtml(state.templateDraft);
    if (state.editor) placeEditor();
}

function explorerHtml() {
    return `<div id="explorer" class="explorer">${explorerInnerHtml()}</div>`;
}

function explorerInnerHtml() {
    const ex = ensureExplore();
    const draft = state.templateDraft;
    const filters = draft.filters;
    const versions = state.versions.map(version => `<option value="${version.versionId}" ${String(version.versionId) === String(ex.versionId) ? "selected" : ""}>${esc(version.listName)} · version ${esc(version.versionId)}${version.contentAsOf ? " · as of " + esc(version.contentAsOf) : ""}</option>`).join("");
    let count = "";
    if (ex.total === null || ex.total === undefined) {
        count = ex.loading ? '<span class="muted">Counting rows…</span>' : "";
    } else if (filters.length) {
        count = `<strong>${num(ex.total)}</strong> of ${num(ex.entries)} list entries go in the file`;
    } else {
        count = `All <strong>${num(ex.total)}</strong> list entries go in the file`;
    }
    const chips = filters.map((filter, index) => `<span class="filter-chip">
            <button type="button" class="filter-chip-text" data-edit-filter="${index}" title="Change this filter"><strong>${esc(filterHeader(filter))}</strong> ${esc(filterPhrase(filter.op, filter.values, filter.blank))}</button>
            <button type="button" class="filter-chip-x" data-remove-filter="${index}" aria-label="Remove the filter on ${esc(filterHeader(filter))}">×</button>
        </span>`).join("");
    const headers = draft.fields.map((field, index) => {
        const kind = sourceColumnInfo(field.sourceTable, field.sourceColumn).kind;
        const filtered = filterIndexFor(field.sourceTable, field.sourceColumn) >= 0;
        const sorted = ex.sort && ex.sort.index === index ? ex.sort.dir : null;
        const usable = kind !== "other";
        return `<th class="${filtered ? "filtered" : ""} ${kind === "number" ? "num" : ""}" ${sorted ? `aria-sort="${sorted === "asc" ? "ascending" : "descending"}"` : ""}>
            <div class="gh">
                <button type="button" class="gh-title" data-sort="${index}" ${usable ? "" : "disabled"} title="${usable ? "Sort the rows on screen" : "This column type cannot be sorted"}">${esc(fieldHeader(field))}<span class="gh-sort">${sorted === "asc" ? "▲" : sorted === "desc" ? "▼" : ""}</span></button>
                <button type="button" class="gh-filter ${filtered ? "on" : ""}" data-filter-col="${index}" ${usable ? "" : "disabled"} aria-label="Filter ${esc(fieldHeader(field))}" title="${usable ? (filtered ? "Change the filter" : "Filter rows on this column") : "This column type cannot be filtered"}">${FUNNEL}</button>
            </div>
            <div class="gh-src"><span class="kind kind-${kind}">${kindTag(kind)}</span>${esc(field.sourceTable)}.${esc(field.sourceColumn)}</div>
        </th>`;
    }).join("");
    const empty = !ex.loading && !ex.error && ex.rows.length === 0 && ex.total !== null
        ? `<div class="empty">${filters.length ? "No list entry matches these filters. Loosen or remove one." : "This version has no list entries."}</div>`
        : "";
    let hint;
    if (filters.length > 1) hint = "A row goes in the file only if it matches every filter.";
    else if (filters.length) hint = "Only rows that match go in the file. The filter is saved with the template.";
    else hint = "No filters: every list entry goes in the file. Use the funnel on a column to keep only some rows.";
    return `
        <div class="explorer-bar">
            <label class="explorer-version"><span class="field-label">Sample data from</span>
                <select id="explore-version" aria-label="List version">${versions}</select>
            </label>
            <div class="explorer-count" aria-live="polite">${count}${ex.loading ? '<span class="spinner" aria-hidden="true"></span>' : ""}</div>
        </div>
        <div class="filter-bar">
            <span class="field-label">Row filters</span>
            ${chips}
            <button type="button" class="chip-add" data-add-filter>＋ Add filter</button>
            ${filters.length ? '<button type="button" class="link-btn" data-clear-filters>Clear all</button>' : ""}
        </div>
        <p class="muted hint">${hint} Each subscriber's scope, business line and region still apply on top.</p>
        ${ex.error ? `<div class="banner banner-error">${esc(ex.error)}</div>` : ""}
        <div class="grid-wrap ${ex.loading ? "is-loading" : ""}" id="grid-wrap">
            <table class="grid">
                <thead><tr><th class="rownum">#</th>${headers}</tr></thead>
                <tbody id="grid-body">${gridRowsHtml(ex.rows, 0)}</tbody>
            </table>
            ${empty}
            <div class="grid-more" id="grid-more">${ex.more ? "Scroll for more rows" : ""}</div>
        </div>
        <div class="grid-foot" id="grid-foot">${gridFootHtml()}</div>`;
}

function gridRowsHtml(rows, start) {
    const kinds = state.templateDraft.fields.map(field => sourceColumnInfo(field.sourceTable, field.sourceColumn).kind);
    return rows.map((row, offset) => `<tr><td class="rownum">${num(start + offset + 1)}</td>${row.cells.map((cell, index) => cell === null || cell === undefined
        ? '<td class="nul">(empty)</td>'
        : `<td class="${kinds[index] === "number" ? "num" : ""}" title="${esc(cell)}">${esc(cell)}</td>`).join("")}</tr>`).join("");
}

function gridFootHtml() {
    const ex = state.explore;
    if (!ex || ex.total === null || ex.total === undefined) return "";
    let text = `Showing ${num(ex.rows.length)} of ${num(ex.total)} rows`;
    if (ex.sort) {
        const field = state.templateDraft.fields[ex.sort.index];
        if (field) text += ` · sorted by ${esc(fieldHeader(field))} ${ex.sort.dir === "asc" ? "▲" : "▼"} <span class="muted">(on screen only: the file keeps list order)</span>`;
    }
    return text + ' <span class="muted">· Click a cell and press Ctrl+C to copy it</span>';
}

/* The filter editor: a panel anchored to a column header */

function openEditor(index) {
    const draft = state.templateDraft;
    const field = draft.fields[index];
    if (!field) return;
    const kind = sourceColumnInfo(field.sourceTable, field.sourceColumn).kind;
    if (kind === "other") return;
    const existing = draft.filters[filterIndexFor(field.sourceTable, field.sourceColumn)];
    const op = existing ? existing.op : (kind === "text" ? "in" : kind === "number" ? "gte" : "between");
    const listOp = op === "in" || op === "not_in";
    state.editor = {
        index: index,
        kind: kind,
        op: op,
        values: new Set(existing && listOp ? existing.values : []),
        blank: existing ? !!existing.blank : false,
        a: existing && !listOp ? (existing.values[0] || "") : "",
        b: existing && !listOp ? (existing.values[1] || "") : "",
        search: "",
        facet: null,
        loading: true,
        existing: !!existing,
        seq: 0
    };
    revealAnchor(`#explorer [data-filter-col="${index}"]`);
    renderEditor();
    loadFacet();
}

async function loadFacet() {
    const editor = state.editor;
    if (!editor || editor.menu) return;
    const seq = ++editor.seq;
    editor.loading = true;
    try {
        const facet = await api("/api/format-templates/explore/facet", {
            method: "POST",
            body: exploreBody({ column: editor.index, search: editor.search })
        });
        if (state.editor !== editor || seq !== editor.seq) return;
        editor.facet = facet;
    } catch (error) {
        if (state.editor !== editor) return;
        editor.facet = { kind: editor.kind, values: [], blank: 0, error: error.message };
    }
    editor.loading = false;
    const list = document.getElementById("pop-values");
    if (list) list.innerHTML = editorValuesHtml();
    const range = document.getElementById("pop-range");
    if (range) range.innerHTML = editorRangeHtml();
}

function closeEditor() {
    state.editor = null;
    const pop = document.getElementById("filter-pop");
    if (pop) pop.remove();
}

function editorFilter() {
    const editor = state.editor;
    const field = state.templateDraft.fields[editor.index];
    const base = { sourceTable: field.sourceTable, sourceColumn: field.sourceColumn, op: editor.op, blank: false };
    if (editor.op === "in" || editor.op === "not_in") return Object.assign(base, { values: [...editor.values], blank: editor.blank });
    if (editor.op === "empty" || editor.op === "not_empty") return Object.assign(base, { values: [] });
    if (editor.op === "between") return Object.assign(base, { values: [editor.a.trim(), editor.b.trim()] });
    return Object.assign(base, { values: [editor.a.trim()] });
}

/** Why the filter cannot be applied yet, or null. */
function editorProblem() {
    const editor = state.editor;
    const filter = editorFilter();
    const values = filter.values;
    if (filter.op === "in" || filter.op === "not_in") return values.length || filter.blank ? null : "Choose at least one value.";
    if (filter.op === "contains") return values[0] ? null : "Type the text to look for.";
    if (filter.op === "empty" || filter.op === "not_empty") return null;
    if (filter.op === "between") {
        if (editor.kind === "date") {
            if (!values[0] && !values[1]) return "Enter a start date, an end date, or both.";
            return values[0] && values[1] && values[0] > values[1] ? "The start date is after the end date." : null;
        }
        if (values[0] === "" || values[1] === "") return "Enter both numbers.";
        return Number(values[0]) > Number(values[1]) ? "The first number is larger than the second." : null;
    }
    return values[0] === "" || Number.isNaN(Number(values[0])) ? "Enter a number." : null;
}

function editorSummaryHtml() {
    const editor = state.editor;
    const field = state.templateDraft.fields[editor.index];
    const problem = editorProblem();
    if (problem) return `<span class="muted">${esc(problem)}</span>`;
    const filter = editorFilter();
    return `Keeps rows where <strong>${esc(fieldHeader(field))}</strong> ${esc(filterPhrase(filter.op, filter.values, filter.blank))}`;
}

function editorValuesHtml() {
    const editor = state.editor;
    const facet = editor.facet;
    if (!facet) return '<div class="pop-note">Loading values…</div>';
    if (facet.error) return `<div class="pop-note error-text">${esc(facet.error)}</div>`;
    const values = facet.values || [];
    const rows = [];
    if (!editor.search && (facet.blank > 0 || editor.blank)) {
        rows.push(`<label class="pop-value"><input type="checkbox" data-pop-blank ${editor.blank ? "checked" : ""}><span class="pop-text nul">(empty)</span><span class="pop-count">${num(facet.blank)}</span></label>`);
    }
    values.forEach(item => rows.push(`<label class="pop-value"><input type="checkbox" data-pop-value="${esc(item.value)}" ${editor.values.has(item.value) ? "checked" : ""}><span class="pop-text" title="${esc(item.value)}">${esc(item.value)}</span><span class="pop-count">${num(item.count)}</span></label>`));
    if (!rows.length) rows.push(`<div class="pop-note">${editor.search ? "No value contains that text." : "This column has no values here."}</div>`);
    if (facet.more) rows.push(`<div class="pop-note">Showing the first ${values.length} values. Search to find others.</div>`);
    return rows.join("");
}

function editorRangeHtml() {
    const facet = state.editor && state.editor.facet;
    if (!facet || facet.error) return "";
    const parts = [];
    if (facet.min !== null && facet.min !== undefined) parts.push(`In this data: <strong>${esc(facet.min)}</strong> to <strong>${esc(facet.max)}</strong>`);
    if (facet.blank) parts.push(`${num(facet.blank)} row${facet.blank === 1 ? "" : "s"} empty`);
    return parts.join(" · ");
}

function editorHtml() {
    const editor = state.editor;
    if (editor.menu) {
        const items = state.templateDraft.fields.map((field, index) => {
            const kind = sourceColumnInfo(field.sourceTable, field.sourceColumn).kind;
            if (kind === "other") return "";
            const filtered = filterIndexFor(field.sourceTable, field.sourceColumn) >= 0;
            return `<button type="button" class="menu-item" data-filter-col="${index}"><span class="menu-text"><strong>${esc(fieldHeader(field))}</strong><small>${esc(field.sourceTable)}.${esc(field.sourceColumn)}</small></span><span class="kind kind-${kind}">${filtered ? "filtered" : kindTag(kind)}</span></button>`;
        }).join("");
        return `<div class="pop-head"><div class="pop-title">Filter on a column</div><button type="button" class="pop-x" data-pop-close aria-label="Close">×</button></div><div class="menu-list">${items || '<div class="pop-note">None of these columns can be filtered.</div>'}</div>`;
    }
    const field = state.templateDraft.fields[editor.index];
    const ops = FILTER_OPS[editor.kind].map(pair => `<option value="${pair[0]}" ${pair[0] === editor.op ? "selected" : ""}>${esc(pair[1])}</option>`).join("");
    let body = "";
    if (editor.op === "in" || editor.op === "not_in") {
        body = `<input id="pop-search" class="pop-search" placeholder="Search values" value="${esc(editor.search)}" autocomplete="off" aria-label="Search values">
            <div class="pop-tools"><span id="pop-selected">${num(editor.values.size + (editor.blank ? 1 : 0))} selected</span>
                <span><button type="button" class="link-btn" data-pop-all>Select all shown</button><button type="button" class="link-btn" data-pop-none>Clear</button></span></div>
            <div class="pop-values" id="pop-values">${editorValuesHtml()}</div>`;
    } else if (editor.op === "contains") {
        body = `<input id="pop-a" class="pop-input" placeholder="Text to look for" value="${esc(editor.a)}" autocomplete="off" aria-label="Text to look for"><p class="pop-note">Not case-sensitive. A row matches when any of its values contains the text.</p>`;
    } else if (editor.op === "between") {
        const type = editor.kind === "date" ? "date" : "number";
        body = `<div class="pop-range-inputs">
                <label>${editor.kind === "date" ? "From" : "From"}<input id="pop-a" class="pop-input" type="${type}" step="any" value="${esc(editor.a)}"></label>
                <label>To<input id="pop-b" class="pop-input" type="${type}" step="any" value="${esc(editor.b)}"></label>
            </div><p class="pop-note">${editor.kind === "date" ? "Leave one side empty for \"on or after\" or \"on or before\". Both days are included." : "Both ends are included."}</p>`;
    } else if (editor.op !== "empty" && editor.op !== "not_empty") {
        body = `<input id="pop-a" class="pop-input" type="number" step="any" value="${esc(editor.a)}" placeholder="Number" aria-label="Number">`;
    }
    return `<div class="pop-head">
            <div><div class="pop-title">Filter ${esc(fieldHeader(field))}</div><div class="pop-src">${esc(field.sourceTable)}.${esc(field.sourceColumn)}</div></div>
            <button type="button" class="pop-x" data-pop-close aria-label="Close">×</button>
        </div>
        <label class="pop-op"><span>Keep rows where it</span><select id="pop-op" aria-label="Condition">${ops}</select></label>
        ${body}
        <div class="pop-range" id="pop-range">${editorRangeHtml()}</div>
        <div class="pop-summary" id="pop-summary">${editorSummaryHtml()}</div>
        <div class="pop-actions">
            ${editor.existing ? '<button type="button" class="btn-delete" data-pop-remove>Remove filter</button>' : ""}
            <span class="spacer"></span>
            <button type="button" class="btn-ghost" data-pop-close>Cancel</button>
            <button type="button" class="btn-primary" data-pop-apply>Apply</button>
        </div>`;
}

function renderEditor() {
    if (!state.editor) return;
    let pop = document.getElementById("filter-pop");
    if (!pop) {
        pop = document.createElement("div");
        pop.id = "filter-pop";
        pop.className = "filter-pop";
        pop.setAttribute("role", "dialog");
        document.body.appendChild(pop);
    }
    const focused = document.activeElement && pop.contains(document.activeElement) ? document.activeElement.id : null;
    pop.innerHTML = editorHtml();
    placeEditor();
    const again = focused && document.getElementById(focused);
    if (again) {
        again.focus();
    } else {
        const first = pop.querySelector("#pop-search, #pop-a");
        if (first) first.focus();
    }
}

function placeEditor() {
    const pop = document.getElementById("filter-pop");
    if (!pop || !state.editor) return;
    const anchor = state.editor.menu
        ? document.querySelector("[data-add-filter]")
        : document.querySelector(`#explorer [data-filter-col="${state.editor.index}"]`);
    const rect = anchor ? anchor.getBoundingClientRect() : { left: 24, top: 100, bottom: 120 };
    const width = pop.offsetWidth || 340;
    pop.style.maxHeight = "";
    const height = pop.scrollHeight;
    const below = window.innerHeight - rect.bottom - 18;
    const above = rect.top - 18;
    let top;
    if (height <= below || below >= above) {
        top = rect.bottom + 6;
        pop.style.maxHeight = Math.max(160, below) + "px";
    } else {
        // Not enough room under the column: open above it instead.
        const shown = Math.min(height, above);
        top = rect.top - 6 - shown;
        pop.style.maxHeight = shown + "px";
    }
    pop.style.top = Math.max(12, top) + "px";
    pop.style.left = Math.max(12, Math.min(rect.left - 8, window.innerWidth - width - 12)) + "px";
}

/** Brings a column header into view before its filter panel opens, so the panel has room. */
function revealAnchor(selector) {
    const anchor = document.querySelector(selector);
    if (!anchor) return;
    const rect = anchor.getBoundingClientRect();
    if (rect.top < 80 || rect.bottom > window.innerHeight * 0.45) {
        // The page scrolls inside <main>, not the window.
        const scroller = document.getElementById("main");
        if (scroller && scroller.scrollHeight > scroller.clientHeight) scroller.scrollTop += rect.top - 96;
        else window.scrollBy(0, rect.top - 96);
    }
}

function applyEditor() {
    const problem = editorProblem();
    if (problem) {
        toast(problem, "error");
        return;
    }
    const filter = editorFilter();
    const filters = state.templateDraft.filters;
    const at = filterIndexFor(filter.sourceTable, filter.sourceColumn);
    if (at >= 0) filters[at] = filter;
    else filters.push(filter);
    closeEditor();
    loadExplore();
}

function refreshEditorBits() {
    const summary = document.getElementById("pop-summary");
    if (summary) summary.innerHTML = editorSummaryHtml();
    const selected = document.getElementById("pop-selected");
    if (selected) selected.textContent = num(state.editor.values.size + (state.editor.blank ? 1 : 0)) + " selected";
}

/** Clicks in the template steps, the data explorer and its filter panel. Returns true when handled. */
function onExplorerClick(event) {
    const target = event.target;
    if (state.editor && !target.closest("#filter-pop") && !target.closest("[data-filter-col]") && !target.closest("[data-add-filter]") && !target.closest("[data-edit-filter]")) {
        closeEditor();
    }
    const step = target.closest("[data-step]");
    if (step) {
        if (step.disabled) return true;
        readDraftFromForm();
        closeEditor();
        state.templateStep = step.dataset.step;
        renderTemplatesKeepingFilter();
        if (state.templateStep === "data") loadExplore();
        return true;
    }
    const filterCol = target.closest("[data-filter-col]");
    if (filterCol) {
        if (!filterCol.disabled) openEditor(Number(filterCol.dataset.filterCol));
        return true;
    }
    if (target.closest("[data-add-filter]")) {
        if (state.editor && state.editor.menu) closeEditor();
        else {
            state.editor = { menu: true };
            revealAnchor("[data-add-filter]");
            renderEditor();
        }
        return true;
    }
    const editFilter = target.closest("[data-edit-filter]");
    if (editFilter) {
        const filter = state.templateDraft.filters[Number(editFilter.dataset.editFilter)];
        const index = filter ? state.templateDraft.fields.findIndex(field => fieldKey(field.sourceTable, field.sourceColumn) === fieldKey(filter.sourceTable, filter.sourceColumn)) : -1;
        if (index >= 0) openEditor(index);
        return true;
    }
    const removeFilter = target.closest("[data-remove-filter]");
    if (removeFilter) {
        state.templateDraft.filters.splice(Number(removeFilter.dataset.removeFilter), 1);
        closeEditor();
        loadExplore();
        return true;
    }
    if (target.closest("[data-clear-filters]")) {
        state.templateDraft.filters = [];
        closeEditor();
        loadExplore();
        return true;
    }
    const sort = target.closest("[data-sort]");
    if (sort) {
        if (sort.disabled) return true;
        const index = Number(sort.dataset.sort);
        const ex = ensureExplore();
        if (!ex.sort || ex.sort.index !== index) ex.sort = { index: index, dir: "asc" };
        else if (ex.sort.dir === "asc") ex.sort = { index: index, dir: "desc" };
        else ex.sort = null;
        loadExplore();
        return true;
    }
    if (target.closest("[data-pop-close]")) {
        closeEditor();
        return true;
    }
    if (target.closest("[data-pop-apply]")) {
        applyEditor();
        return true;
    }
    if (target.closest("[data-pop-remove]")) {
        const field = state.templateDraft.fields[state.editor.index];
        const at = filterIndexFor(field.sourceTable, field.sourceColumn);
        if (at >= 0) state.templateDraft.filters.splice(at, 1);
        closeEditor();
        loadExplore();
        return true;
    }
    if (target.matches && target.matches("input[data-pop-value]")) {
        const value = target.dataset.popValue;
        if (target.checked) state.editor.values.add(value);
        else state.editor.values.delete(value);
        refreshEditorBits();
        return true;
    }
    if (target.matches && target.matches("input[data-pop-blank]")) {
        state.editor.blank = target.checked;
        refreshEditorBits();
        return true;
    }
    const bulk = target.closest("[data-pop-all], [data-pop-none]");
    if (bulk) {
        const all = bulk.hasAttribute("data-pop-all");
        const editor = state.editor;
        const facet = editor.facet || { values: [] };
        if (!all && !editor.search) {
            editor.values.clear();
            editor.blank = false;
        } else {
            facet.values.forEach(item => all ? editor.values.add(item.value) : editor.values.delete(item.value));
            if (all && !editor.search && facet.blank) editor.blank = true;
        }
        const list = document.getElementById("pop-values");
        if (list) list.innerHTML = editorValuesHtml();
        refreshEditorBits();
        return true;
    }
    if (target.closest("#filter-pop")) return true;
    const cell = target.closest("#grid-body td");
    if (cell) {
        document.querySelectorAll("#grid-body td.sel").forEach(item => item.classList.remove("sel"));
        if (!cell.classList.contains("rownum")) cell.classList.add("sel");
        return true;
    }
    return false;
}

let facetTimer;
/** Typing in the data explorer and its filter panel. Returns true when handled. */
function onExplorerInput(event) {
    const target = event.target;
    if (target.id === "explore-version") {
        ensureExplore().versionId = Number(target.value);
        closeEditor();
        loadExplore();
        return true;
    }
    if (!state.editor || state.editor.menu) return false;
    if (target.id === "pop-op") {
        const wasList = state.editor.op === "in" || state.editor.op === "not_in";
        state.editor.op = target.value;
        const isList = target.value === "in" || target.value === "not_in";
        if (wasList !== isList) {
            state.editor.a = "";
            state.editor.b = "";
        }
        renderEditor();
        return true;
    }
    if (target.id === "pop-search") {
        state.editor.search = target.value;
        clearTimeout(facetTimer);
        facetTimer = setTimeout(loadFacet, 250);
        return true;
    }
    if (target.id === "pop-a" || target.id === "pop-b") {
        state.editor[target.id === "pop-a" ? "a" : "b"] = target.value;
        refreshEditorBits();
        return true;
    }
    return false;
}

function onExplorerKey(event) {
    if (state.editor && event.key === "Escape") {
        closeEditor();
        return;
    }
    if (state.editor && !state.editor.menu && event.key === "Enter" && event.target.closest && event.target.closest("#filter-pop")
            && event.target.tagName === "INPUT" && event.target.type !== "checkbox") {
        event.preventDefault();
        applyEditor();
        return;
    }
    if ((event.ctrlKey || event.metaKey) && event.key && event.key.toLowerCase() === "c") {
        const cell = document.querySelector("#grid-body td.sel");
        const selection = window.getSelection ? String(window.getSelection()) : "";
        if (cell && !selection && navigator.clipboard) {
            event.preventDefault();
            navigator.clipboard.writeText(cell.classList.contains("nul") ? "" : cell.textContent)
                .then(() => toast("Cell copied", "success"))
                .catch(() => toast("Copying is not allowed here", "error"));
        }
    }
}

document.addEventListener("scroll", event => {
    if (state.editor) placeEditor();
    const wrap = event.target;
    if (wrap && wrap.id === "grid-wrap" && wrap.scrollTop + wrap.clientHeight > wrap.scrollHeight - 240) {
        loadMoreRows();
    }
}, true);
window.addEventListener("resize", () => placeEditor());

/* ---------- Template review: what an approver sees and decides on ---------- */

const OP_SYMBOL = {
    in: "IN", not_in: "NOT IN", contains: "CONTAINS", eq: "=", ne: "≠", gt: ">", gte: "≥", lt: "<", lte: "≤",
    between: "BETWEEN", empty: "IS EMPTY", not_empty: "IS NOT EMPTY"
};

const OP_WORDS = {
    in: "is one of", not_in: "is none of", contains: "contains the text", eq: "equals", ne: "does not equal",
    gt: "is greater than", gte: "is at least", lt: "is less than", lte: "is at most", between: "is between",
    empty: "is empty", not_empty: "has a value"
};

const STATUS_INFO = {
    PENDING: ["Waiting for review", "wait"],
    APPROVED: ["Approved", "on"],
    CHANGES_REQUESTED: ["Changes requested", "warn"],
    REJECTED: ["Rejected", "bad"],
    SUPERSEDED: ["Replaced before review", "off"]
};

function familyVersions(familyId) {
    return state.templates.filter(item => String(item.familyId) === String(familyId))
        .sort((a, b) => a.versionNumber - b.versionNumber);
}

function familyInUse(familyId) {
    return state.templates.find(item => String(item.familyId) === String(familyId) && item.active) || null;
}

function statusPill(template) {
    if (template.status === "APPROVED") {
        return template.active ? '<span class="pill pill-on">Approved · in use</span>' : '<span class="pill pill-off">Approved · no longer in use</span>';
    }
    const info = STATUS_INFO[template.status] || [template.status, "off"];
    return `<span class="pill pill-${info[1]}">${esc(info[0])}</span>`;
}

function headerOf(template, filter) {
    const field = template.fields.find(item => fieldKey(item.sourceTable, item.sourceColumn) === fieldKey(filter.sourceTable, filter.sourceColumn));
    return field ? field.headerName : filter.sourceColumn;
}

function columnKindOf(table, column) {
    return sourceColumnInfo(table, column);
}

function tablesOf(template) {
    const tables = new Map();
    template.fields.forEach(field => {
        if (!tables.has(field.sourceTable)) tables.set(field.sourceTable, []);
        tables.get(field.sourceTable).push(field);
    });
    return tables;
}

/** The whole template in one or two plain sentences. */
function plainSummary(template) {
    const tables = tablesOf(template);
    const columns = template.fields.length;
    let text = `Builds a <strong>${esc(template.formatType)}</strong> file with <strong>${columns} column${columns === 1 ? "" : "s"}</strong> from <strong>${tables.size} source table${tables.size === 1 ? "" : "s"}</strong>. Each row is one entry of the exported list version; the tables are matched to it on its BNP ID.`;
    const filters = template.filters || [];
    if (!filters.length) {
        text += " <strong>Every list entry</strong> is included.";
    } else {
        text += " Only entries where " + filters.map(filter =>
            `<strong>${esc(headerOf(template, filter))}</strong> ${esc(filterPhrase(filter.op, filter.values, filter.blank))}`).join(" <em>and</em> ") + " are included.";
    }
    return text;
}

function impactHtml(template) {
    const inUse = familyInUse(template.familyId);
    const users = template.usedBy || [];
    if (!users.length) {
        return `<span class="impact-chip">No subscriber uses this template yet. Once approved, makers can choose it for ${esc(template.formatType)} subscribers.</span>`;
    }
    const from = inUse && inUse.id !== template.id ? ` move from version ${inUse.versionNumber} to version ${template.versionNumber}` : " use it";
    return `<span class="impact-chip impact-live"><strong>${users.length} subscriber${users.length === 1 ? "" : "s"}</strong> (${esc(users.join(", "))}) will${from} from their next export once approved.</span>`;
}

/** What changed between two versions, keyed by source table and column. */
function diffTemplates(base, next) {
    const key = field => fieldKey(field.sourceTable, field.sourceColumn);
    const baseFields = new Map(base.fields.map((field, index) => [key(field), { field, index }]));
    const nextFields = new Map(next.fields.map((field, index) => [key(field), { field, index }]));
    const columns = { added: [], removed: [], renamed: [], moved: [], same: 0 };
    next.fields.forEach((field, index) => {
        const before = baseFields.get(key(field));
        if (!before) {
            columns.added.push(field);
            return;
        }
        let changed = false;
        if (before.field.headerName !== field.headerName) {
            columns.renamed.push({ field, from: before.field.headerName });
            changed = true;
        }
        if (before.index !== index) {
            columns.moved.push({ field, from: before.index + 1, to: index + 1 });
            changed = true;
        }
        if (!changed) columns.same++;
    });
    base.fields.forEach(field => {
        if (!nextFields.has(key(field))) columns.removed.push(field);
    });
    const phrase = filter => filterPhrase(filter.op, filter.values, filter.blank);
    const baseFilters = new Map((base.filters || []).map(filter => [key(filter), filter]));
    const nextFilters = new Map((next.filters || []).map(filter => [key(filter), filter]));
    const filters = { added: [], removed: [], changed: [], same: 0 };
    (next.filters || []).forEach(filter => {
        const before = baseFilters.get(key(filter));
        if (!before) filters.added.push(filter);
        else if (phrase(before) !== phrase(filter)) filters.changed.push({ filter, from: before });
        else filters.same++;
    });
    (base.filters || []).forEach(filter => {
        if (!nextFilters.has(key(filter))) filters.removed.push(filter);
    });
    const count = columns.added.length + columns.removed.length + columns.renamed.length + columns.moved.length
        + filters.added.length + filters.removed.length + filters.changed.length;
    return { columns, filters, count };
}

function reviewQueue() {
    return state.templates.filter(item => item.status === "PENDING")
        .sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)));
}

function recentDecisions() {
    return state.templates.filter(item => item.reviewedAt && ["APPROVED", "REJECTED", "CHANGES_REQUESTED"].includes(item.status))
        .sort((a, b) => String(b.reviewedAt).localeCompare(String(a.reviewedAt)))
        .slice(0, 8);
}

function ensureReview() {
    if (!state.review) state.review = { id: null, tab: null, baseline: null, preview: null };
    const known = state.templates.some(item => String(item.id) === String(state.review.id));
    if (!known) {
        const first = reviewQueue()[0] || recentDecisions()[0];
        selectReview(first ? first.id : null);
    }
    return state.review;
}

function selectReview(id) {
    const template = state.templates.find(item => String(item.id) === String(id));
    const previous = template ? familyVersions(template.familyId).filter(item => item.versionNumber < template.versionNumber).pop() : null;
    const inUse = template ? familyInUse(template.familyId) : null;
    // A resubmission is compared with the version that was sent back; anything else with the version in use.
    let baseline = null;
    if (previous && previous.status === "CHANGES_REQUESTED") baseline = previous.id;
    else if (inUse && template && inUse.id !== template.id) baseline = inUse.id;
    else if (previous) baseline = previous.id;
    state.review = {
        id: template ? template.id : null,
        tab: baseline ? "changes" : "columns",
        baseline: baseline,
        preview: null,
        versionId: state.review && state.review.versionId ? state.review.versionId : (state.versions[0] ? state.versions[0].versionId : null)
    };
}

function approvalsHtml() {
    const review = ensureReview();
    const queue = reviewQueue();
    const decided = recentDecisions();
    const template = state.templates.find(item => String(item.id) === String(review.id));
    const queueItem = item => {
        const inUse = familyInUse(item.familyId);
        const previous = familyVersions(item.familyId).filter(other => other.versionNumber < item.versionNumber).pop();
        const kind = previous && previous.status === "CHANGES_REQUESTED" ? '<span class="tag tag-warn">Resubmitted</span>'
            : inUse ? `<span class="tag">Update to v${inUse.versionNumber}</span>` : '<span class="tag tag-new">New template</span>';
        const filters = (item.filters || []).length;
        return `<button type="button" class="queue-item ${String(item.id) === String(review.id) ? "active" : ""}" data-review="${item.id}">
            <span class="queue-name">${esc(item.name)} <span class="muted">v${item.versionNumber}</span></span>
            <span class="queue-meta"><span class="fmt-${esc(item.formatType)}">${esc(item.formatType)}</span> · ${item.fields.length} columns · ${filters ? filters + " filter" + (filters === 1 ? "" : "s") : "all rows"}</span>
            <span class="queue-meta">${kind} by ${esc(item.createdByName || item.createdBy)} · ${when(item.createdAt)}</span>
        </button>`;
    };
    const decidedItem = item => `<button type="button" class="queue-item compact ${String(item.id) === String(review.id) ? "active" : ""}" data-review="${item.id}">
            <span class="queue-name">${esc(item.name)} <span class="muted">v${item.versionNumber}</span></span>
            <span class="queue-meta">${statusPill(item)} ${esc(item.reviewedByName || "")}</span>
        </button>`;
    return `
        ${head("Review", "Approvals", "See exactly what each submission will put in subscribers' files, then decide.")}
        ${endpointApprovals()}
        <div class="review-layout">
            <aside class="review-queue" aria-label="Review queue">
                <div class="field-label">Waiting for you · ${queue.length}</div>
                ${queue.length ? queue.map(queueItem).join("") : '<div class="queue-empty">Nothing is waiting. You are all caught up.</div>'}
                ${decided.length ? `<div class="field-label" style="margin-top:18px">Recently decided</div>${decided.map(decidedItem).join("")}` : ""}
            </aside>
            <section class="review-detail">${template ? reviewDetailHtml(template) : '<div class="empty">Choose a submission to review.</div>'}</section>
        </div>`;
}

function reviewDetailHtml(template) {
    const review = state.review;
    const versions = familyVersions(template.familyId);
    const previous = versions.filter(item => item.versionNumber < template.versionNumber).pop();
    const resubmitted = previous && previous.status === "CHANGES_REQUESTED";
    const baseline = state.templates.find(item => String(item.id) === String(review.baseline));
    const diff = baseline ? diffTemplates(baseline, template) : null;
    const tabs = [
        ["changes", "Changes", diff ? String(diff.count) : "new"],
        ["columns", "Tables & columns", String(template.fields.length)],
        ["filters", "Row filters", String((template.filters || []).length)],
        ["data", "Data preview", ""],
        ["history", "History", String(versions.length)]
    ];
    let body = "";
    if (review.tab === "changes") body = changesTabHtml(template, baseline, diff, versions);
    else if (review.tab === "columns") body = columnsTabHtml(template);
    else if (review.tab === "filters") body = filtersTabHtml(template);
    else if (review.tab === "data") body = dataTabHtml(template);
    else body = templateHistoryHtml(template.familyId, template.id);
    const kicker = `${esc(template.formatType)} template · version ${template.versionNumber}${resubmitted ? " · resubmitted" : ""}`;
    return `
        <header class="review-head">
            <div>
                <p class="kicker">${kicker}</p>
                <h2>${esc(template.name)}</h2>
                <p class="muted">Submitted by <strong>${esc(template.createdByName || template.createdBy)}</strong> · ${when(template.createdAt)}</p>
            </div>
            <div>${statusPill(template)}</div>
        </header>
        ${resubmitted ? `<div class="review-callout warn"><strong>You asked for changes on version ${previous.versionNumber}:</strong> “${esc(previous.reviewComment || "")}” <span class="muted">— ${esc(previous.reviewedByName || "")}, ${when(previous.reviewedAt)}</span><br>The Changes tab compares this version with the one that was sent back.</div>` : ""}
        ${template.status !== "PENDING" && template.reviewedAt ? `<div class="review-callout ${template.status === "APPROVED" ? "good" : template.status === "REJECTED" ? "bad" : "warn"}"><strong>${esc((STATUS_INFO[template.status] || [template.status])[0])}</strong> by ${esc(template.reviewedByName || template.approvedByName || "")} · ${when(template.reviewedAt)}${template.reviewComment ? `<br>“${esc(template.reviewComment)}”` : ""}</div>` : ""}
        <div class="review-plain">
            <p>${plainSummary(template)}</p>
            ${template.status === "PENDING" ? impactHtml(template) : ""}
        </div>
        <nav class="review-tabs" role="tablist">
            ${tabs.map(([id, label, count]) => `<button type="button" role="tab" class="review-tab ${review.tab === id ? "active" : ""}" aria-selected="${review.tab === id}" data-review-tab="${id}">${esc(label)}${count ? `<span class="tab-count">${esc(count)}</span>` : ""}</button>`).join("")}
        </nav>
        <div class="review-body" id="review-body">${body}</div>
        ${decisionBarHtml(template)}`;
}

function changesTabHtml(template, baseline, diff, versions) {
    const options = versions.filter(item => item.id !== template.id).map(item =>
        `<option value="${item.id}" ${baseline && baseline.id === item.id ? "selected" : ""}>Version ${item.versionNumber} · ${esc((STATUS_INFO[item.status] || [item.status])[0])}${item.active ? " · in use" : ""}</option>`).join("");
    if (!baseline) {
        return `<div class="review-section"><h3>A new template</h3>
            <p>Nothing of this template is in use yet, so everything in it is new. Check the <button type="button" class="link-btn" data-review-tab="columns">tables and columns</button>, the <button type="button" class="link-btn" data-review-tab="filters">row filters</button> and the <button type="button" class="link-btn" data-review-tab="data">data it produces</button>.</p></div>`;
    }
    const line = (tone, text) => `<li class="change change-${tone}"><span class="change-mark">${tone === "add" ? "+" : tone === "remove" ? "−" : "~"}</span><span>${text}</span></li>`;
    const col = field => `<strong>${esc(field.headerName)}</strong> <span class="muted mono">${esc(field.sourceTable)}.${esc(field.sourceColumn)}</span>`;
    const columnLines = [
        ...diff.columns.added.map(field => line("add", "Column added: " + col(field))),
        ...diff.columns.removed.map(field => line("remove", "Column removed: " + col(field))),
        ...diff.columns.renamed.map(item => line("change", `Renamed in the file: <strong>${esc(item.from)}</strong> → <strong>${esc(item.field.headerName)}</strong> <span class="muted mono">${esc(item.field.sourceTable)}.${esc(item.field.sourceColumn)}</span>`)),
        ...diff.columns.moved.map(item => line("change", `Moved: <strong>${esc(item.field.headerName)}</strong> from position ${item.from} to ${item.to}`))
    ];
    const filterLines = [
        ...diff.filters.added.map(filter => line("add", `Filter added: <strong>${esc(headerOf(template, filter))}</strong> ${esc(filterPhrase(filter.op, filter.values, filter.blank))}`)),
        ...diff.filters.removed.map(filter => line("remove", `Filter removed: <strong>${esc(headerOf(baseline, filter))}</strong> ${esc(filterPhrase(filter.op, filter.values, filter.blank))}`)),
        ...diff.filters.changed.map(item => line("change", `Filter changed on <strong>${esc(headerOf(template, item.filter))}</strong>: <span class="was">${esc(filterPhrase(item.from.op, item.from.values, item.from.blank))}</span> → ${esc(filterPhrase(item.filter.op, item.filter.values, item.filter.blank))}`))
    ];
    return `<div class="review-section">
            <div class="section-head">
                <h3>${diff.count ? `${diff.count} change${diff.count === 1 ? "" : "s"}` : "No changes"}</h3>
                <label class="compare">Compared with <select id="review-baseline">${options}</select></label>
            </div>
            ${diff.count ? "" : `<p class="muted">This version has the same columns, names, order and filters as version ${baseline.versionNumber}.</p>`}
            <h4>Columns <span class="muted">· ${diff.columns.same} unchanged</span></h4>
            ${columnLines.length ? `<ul class="change-list">${columnLines.join("")}</ul>` : '<p class="muted">No column changes.</p>'}
            <h4>Row filters <span class="muted">· ${diff.filters.same} unchanged</span></h4>
            ${filterLines.length ? `<ul class="change-list">${filterLines.join("")}</ul>` : '<p class="muted">No filter changes.</p>'}
        </div>`;
}

function columnsTabHtml(template) {
    const tables = tablesOf(template);
    const filtered = new Set((template.filters || []).map(filter => fieldKey(filter.sourceTable, filter.sourceColumn)));
    const cards = [...tables.entries()].map(([table, fields]) => `<div class="rel-row">
            <span class="rel-link" title="Rows of ${esc(table)} belong to the list entry with the same BNP ID">BNP_ID = BNP_ID</span>
            <div class="rel-table">
                <div class="rel-table-name">${esc(table)}</div>
                <ul>${fields.map(field => `<li><span class="mono">${esc(field.sourceColumn)}</span> <span class="muted">→</span> <strong>${esc(field.headerName)}</strong></li>`).join("")}</ul>
            </div>
        </div>`).join("");
    const rows = template.fields.map((field, index) => {
        const info = columnKindOf(field.sourceTable, field.sourceColumn);
        return `<tr>
            <td class="muted">${index + 1}</td>
            <td><strong>${esc(field.headerName)}</strong>${field.headerName.toLowerCase() !== field.sourceColumn.toLowerCase() ? ' <span class="tag">renamed</span>' : ""}</td>
            <td class="mono">${esc(field.sourceTable)}.${esc(field.sourceColumn)}</td>
            <td><span class="kind kind-${info.kind}">${kindTag(info.kind)}</span> <span class="muted">${esc(info.type || "")}</span></td>
            <td>${filtered.has(fieldKey(field.sourceTable, field.sourceColumn)) ? '<span class="filtered-tag">filtered</span>' : ""}</td>
        </tr>`;
    }).join("");
    const headers = template.fields.map(field => field.headerName);
    const sample = template.formatType === "CSV"
        ? esc(headers.map(header => /[",\n]/.test(header) ? '"' + header.replace(/"/g, '""') + '"' : header).join(","))
        : esc(`<Record id="…">\n${headers.map(header => `  <${header}>…</${header}>`).join("\n")}\n</Record>`);
    return `<div class="review-section">
            <h3>How the data is joined</h3>
            <p class="muted">Each row of the file is one entry of the exported list version. Every table is matched to the entry on its BNP ID. When a table has several rows for one entry, their values are joined with “; ”. An entry with no matching row gets an empty cell.</p>
            <div class="rel-diagram">
                <div class="rel-hub"><strong>List entry</strong><span>one row per entry</span><span class="mono">BNP_ID</span></div>
                <div class="rel-tables">${cards}</div>
            </div>
            <h3>File layout · in order</h3>
            <table class="detail-table review-table">
                <thead><tr><th>#</th><th>Name in the file</th><th>Comes from</th><th>Type</th><th></th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            <h4>${template.formatType === "CSV" ? "First line of the file" : "Each record in the file"}</h4>
            <pre class="file-sample">${sample}</pre>
        </div>`;
}

function filtersTabHtml(template) {
    const filters = template.filters || [];
    if (!filters.length) {
        return `<div class="review-section"><h3>No row filters</h3>
            <p>Every entry of the exported list version goes in the file. Each subscriber's own scope, business line and region still apply.</p></div>`;
    }
    const valueChips = filter => {
        if (filter.op === "empty" || filter.op === "not_empty") return "";
        if (filter.op === "between") {
            const [from, to] = filter.values;
            return `<span class="value-chip">${esc(from || "any")}</span><span class="muted">and</span><span class="value-chip">${esc(to || "any")}</span>`;
        }
        const values = filter.values || [];
        const shown = values.slice(0, 12).map(value => `<span class="value-chip">${esc(value)}</span>`).join("");
        const rest = values.length > 12 ? `<details class="more-values"><summary>+ ${values.length - 12} more</summary>${values.slice(12).map(value => `<span class="value-chip">${esc(value)}</span>`).join("")}</details>` : "";
        const blank = filter.blank ? `<span class="value-chip empty-chip">${filter.op === "not_in" ? "and not empty" : "or empty"}</span>` : "";
        return shown + rest + blank;
    };
    const cards = filters.map((filter, index) => `${index ? '<div class="logic-and">AND</div>' : ""}
        <div class="condition">
            <div class="condition-col"><strong>${esc(headerOf(template, filter))}</strong><span class="mono muted">${esc(filter.sourceTable)}.${esc(filter.sourceColumn)}</span></div>
            <div class="condition-op"><span class="op-badge">${esc(OP_SYMBOL[filter.op] || filter.op)}</span><span>${esc(OP_WORDS[filter.op] || filter.op)}</span></div>
            <div class="condition-values">${valueChips(filter)}</div>
        </div>`).join("");
    const expression = filters.map(filter => {
        const name = headerOf(template, filter);
        const quote = value => "'" + String(value).replace(/'/g, "''") + "'";
        switch (filter.op) {
            case "in": return `(${name} IN (${filter.values.map(quote).join(", ")})${filter.blank ? ` OR ${name} IS EMPTY` : ""})`;
            case "not_in": return `(${name} NOT IN (${filter.values.map(quote).join(", ")})${filter.blank ? ` AND ${name} IS NOT EMPTY` : ""})`;
            case "contains": return `(${name} CONTAINS ${quote(filter.values[0])})`;
            case "between": return filter.values[0] && filter.values[1] ? `(${name} BETWEEN ${filter.values[0]} AND ${filter.values[1]})`
                : filter.values[0] ? `(${name} ≥ ${filter.values[0]})` : `(${name} ≤ ${filter.values[1]})`;
            case "empty": return `(${name} IS EMPTY)`;
            case "not_empty": return `(${name} IS NOT EMPTY)`;
            default: return `(${name} ${OP_SYMBOL[filter.op]} ${filter.values[0]})`;
        }
    }).join("\nAND ");
    return `<div class="review-section">
            <h3>A list entry goes in the file only when <span class="all-tag">all</span> of these are true</h3>
            <div class="conditions">${cards}</div>
            <p class="muted small-note">A filter on a column with several values per entry matches when any of them matches; “is none of” and “does not equal” need none of them to match. On top of these, each subscriber's scope, business line and region apply.</p>
            <details class="expression"><summary>Show as a single expression</summary><pre class="file-sample">${esc(expression)}</pre></details>
        </div>`;
}

function dataTabHtml(template) {
    const review = state.review;
    const preview = review.preview;
    const versions = state.versions.map(version => `<option value="${version.versionId}" ${String(version.versionId) === String(review.versionId) ? "selected" : ""}>${esc(version.listName)} · version ${esc(version.versionId)}</option>`).join("");
    let summary = '<span class="muted">Loading…</span>';
    let grid = "";
    if (preview && preview.error) {
        summary = "";
        grid = `<div class="banner banner-error">${esc(preview.error)}</div>`;
    } else if (preview && preview.page) {
        const page = preview.page;
        const share = page.entries ? Math.round(page.total * 100 / page.entries) : 0;
        summary = `<strong>${num(page.total)}</strong> of ${num(page.entries)} list entries (${share}%) go in a full file`;
        if (preview.inUseTotal !== null && preview.inUseTotal !== undefined) {
            const delta = page.total - preview.inUseTotal;
            summary += ` <span class="delta ${delta < 0 ? "down" : delta > 0 ? "up" : ""}">${delta === 0 ? "same as" : (delta > 0 ? "+" : "−") + num(Math.abs(delta)) + " vs"} version ${preview.inUseVersion} in use (${num(preview.inUseTotal)})</span>`;
        }
        const kinds = template.fields.map(field => columnKindOf(field.sourceTable, field.sourceColumn).kind);
        grid = `<div class="grid-wrap review-grid"><table class="grid">
                <thead><tr><th class="rownum">#</th>${template.fields.map((field, index) => `<th class="${kinds[index] === "number" ? "num" : ""}"><div class="gh"><span class="gh-title">${esc(field.headerName)}</span></div><div class="gh-src"><span class="kind kind-${kinds[index]}">${kindTag(kinds[index])}</span>${esc(field.sourceTable)}.${esc(field.sourceColumn)}</div></th>`).join("")}</tr></thead>
                <tbody>${preview.rows.map((row, index) => `<tr><td class="rownum">${index + 1}</td>${row.cells.map((cell, column) => cell === null || cell === undefined ? '<td class="nul">(empty)</td>' : `<td class="${kinds[column] === "number" ? "num" : ""}" title="${esc(cell)}">${esc(cell)}</td>`).join("")}</tr>`).join("")}</tbody>
            </table>${page.total === 0 ? '<div class="empty">No list entry of this version passes the filters.</div>' : ""}</div>
            <div class="grid-foot">Showing ${num(preview.rows.length)} of ${num(page.total)} rows ${preview.more ? '<button type="button" class="link-btn" data-review-more>Show 100 more</button>' : ""}</div>`;
    }
    return `<div class="review-section">
            <div class="section-head">
                <div class="explorer-count">${summary}</div>
                <label class="compare">Sample data from <select id="review-version">${versions}</select></label>
            </div>
            <p class="muted small-note">Exactly the rows and values this version would write, before each subscriber's own scope, business line and region. Read-only.</p>
            ${grid}
        </div>`;
}

function templateConfig(template) {
    return {
        fields: template.fields.map(field => ({ sourceTable: field.sourceTable, sourceColumn: field.sourceColumn, headerName: field.headerName })),
        filters: (template.filters || []).map(filter => ({ sourceTable: filter.sourceTable, sourceColumn: filter.sourceColumn, op: filter.op, values: filter.values, blank: filter.blank }))
    };
}

async function loadReviewPreview(more) {
    const review = state.review;
    const template = state.templates.find(item => String(item.id) === String(review.id));
    if (!template) return;
    const id = template.id;
    const offset = more && review.preview ? review.preview.rows.length : 0;
    try {
        const body = Object.assign(templateConfig(template), { versionId: review.versionId, offset: offset, limit: 100, withTotal: !more });
        const page = await api("/api/format-templates/explore", { method: "POST", body: body });
        if (!state.review || state.review.id !== id) return;
        if (more) {
            review.preview.rows = review.preview.rows.concat(page.rows);
            review.preview.more = page.more;
        } else {
            review.preview = { page: page, rows: page.rows, more: page.more, inUseTotal: null };
            const inUse = familyInUse(template.familyId);
            if (inUse && inUse.id !== template.id) {
                const current = await api("/api/format-templates/explore", { method: "POST", body: Object.assign(templateConfig(inUse), { versionId: review.versionId, limit: 1, withTotal: true }) });
                if (state.review && state.review.id === id) {
                    review.preview.inUseTotal = current.total;
                    review.preview.inUseVersion = inUse.versionNumber;
                }
            }
        }
    } catch (error) {
        if (state.review && state.review.id === id) review.preview = { error: error.message };
    }
    if (state.review && state.review.id === id && state.review.tab === "data") renderReviewBody();
}

function renderReviewBody() {
    const detail = document.querySelector(".review-detail");
    const template = state.templates.find(item => String(item.id) === String(state.review.id));
    if (detail && template) detail.innerHTML = reviewDetailHtml(template);
}

/** Every step of a template's life, oldest first, from its versions. */
function historyEvents(familyId) {
    const events = [];
    const versions = familyVersions(familyId);
    versions.forEach((version, index) => {
        const previous = versions[index - 1];
        const resubmitted = previous && previous.status === "CHANGES_REQUESTED";
        events.push({
            at: version.createdAt, tone: "submit", version: version,
            title: version.createdBy === "system" ? `Version ${version.versionNumber} set up with the demo data`
                : `${resubmitted ? "Resubmitted" : "Submitted"} version ${version.versionNumber}`,
            who: version.createdByName || version.createdBy,
            detail: `${version.fields.length} columns · ${(version.filters || []).length ? (version.filters || []).length + " row filters" : "every list entry"}`
        });
        if (version.status === "APPROVED" && (version.reviewedAt || version.approvedAt)) {
            events.push({ at: version.reviewedAt || version.approvedAt, tone: "good", version: version, title: `Approved version ${version.versionNumber}`, who: version.reviewedByName || version.approvedByName, comment: version.reviewComment });
        } else if (version.status === "REJECTED") {
            events.push({ at: version.reviewedAt, tone: "bad", version: version, title: `Rejected version ${version.versionNumber}`, who: version.reviewedByName, comment: version.reviewComment });
        } else if (version.status === "CHANGES_REQUESTED") {
            events.push({ at: version.reviewedAt, tone: "warn", version: version, title: `Asked for changes to version ${version.versionNumber}`, who: version.reviewedByName, comment: version.reviewComment });
        } else if (version.status === "SUPERSEDED") {
            events.push({ at: version.reviewedAt, tone: "off", version: version, title: `Version ${version.versionNumber} replaced by a newer submission before review`, who: version.reviewedByName });
        }
    });
    return events.sort((a, b) => String(a.at).localeCompare(String(b.at)));
}

function templateHistoryHtml(familyId, currentId) {
    const events = historyEvents(familyId);
    if (!events.length) return '<div class="empty">No history yet.</div>';
    return `<ol class="timeline">${events.map(event => `<li class="tl tl-${event.tone} ${event.version.id === currentId ? "tl-current" : ""}">
            <div class="tl-title">${esc(event.title)}${event.version.active && event.tone === "good" ? ' <span class="pill pill-on">in use</span>' : ""}</div>
            <div class="tl-meta">${esc(event.who || "")} · ${when(event.at)}${event.detail ? " · " + esc(event.detail) : ""}</div>
            ${event.comment ? `<blockquote>${esc(event.comment)}</blockquote>` : ""}
        </li>`).join("")}</ol>`;
}

function decisionBarHtml(template) {
    if (template.status !== "PENDING" || !state.user || state.user.role !== "APPROVER") return "";
    const own = template.createdBy && state.user.username && template.createdBy.toLowerCase() === state.user.username.toLowerCase();
    if (own) {
        return `<div class="decision-bar"><span class="muted">You submitted this version, so another approver must review it.</span></div>`;
    }
    return `<div class="decision-bar">
            <span class="muted">Your decision on version ${template.versionNumber}</span>
            <span class="spacer"></span>
            <button type="button" class="btn-ghost" data-decide="request-changes">Request changes</button>
            <button type="button" class="btn-delete" data-decide="reject">Reject</button>
            <button type="button" class="btn-primary" data-decide="approve">Approve</button>
        </div>`;
}

function openDecision(action) {
    const template = state.templates.find(item => String(item.id) === String(state.review.id));
    if (!template) return;
    const inUse = familyInUse(template.familyId);
    const users = template.usedBy || [];
    const keeps = inUse && inUse.id !== template.id ? `Version ${inUse.versionNumber} stays in use.` : "Nothing is in use for this template yet.";
    const copy = {
        approve: {
            title: `Approve ${template.name} version ${template.versionNumber}?`,
            text: (users.length ? `${users.join(", ")} will use version ${template.versionNumber} from their next export.` : "It becomes available for makers to choose on the Subscribers page.")
                + ` Its exports produce data versions ${template.versionNumber}.0, ${template.versionNumber}.1 and so on.`,
            label: "Comment (optional)", confirm: "Approve", tone: "btn-primary", required: false
        },
        "request-changes": {
            title: "Request changes",
            text: `Version ${template.versionNumber} goes back to ${template.createdByName || template.createdBy}, who can edit it and resubmit. ${keeps}`,
            label: "What should change? (required)", confirm: "Send back to the maker", tone: "btn-primary", required: true
        },
        reject: {
            title: `Reject version ${template.versionNumber}?`,
            text: `The version is closed for good and the maker sees your reason. ${keeps}`,
            label: "Reason (required)", confirm: "Reject", tone: "btn-danger", required: true
        }
    }[action];
    document.getElementById("modal-root").innerHTML = `
        <div class="modal-back" data-modal-close>
            <form class="modal decision-modal" id="decision-form" data-action="${action}">
                <h2>${esc(copy.title)}</h2>
                <p class="muted">${esc(copy.text)}</p>
                <label>${esc(copy.label)}<textarea name="comment" rows="5" maxlength="2000" ${copy.required ? 'required minlength="10"' : ""} placeholder="${copy.required ? "At least 10 characters. The maker will see this." : "Visible in the template's history."}"></textarea></label>
                <div class="counter" id="decision-counter">${copy.required ? "0 / at least 10 characters" : ""}</div>
                <div class="modal-actions">
                    <button type="button" class="btn-ghost" data-modal-close>Cancel</button>
                    <button type="submit" class="${copy.tone}" ${copy.required ? "disabled" : ""}>${esc(copy.confirm)}</button>
                </div>
            </form>
        </div>`;
    const area = document.querySelector("#decision-form textarea");
    if (area) area.focus();
}

async function submitDecision(form) {
    const action = form.dataset.action;
    const comment = new FormData(form).get("comment");
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    try {
        const saved = await api(`/api/format-templates/${state.review.id}/${action}`, { method: "POST", body: { comment: comment } });
        document.getElementById("modal-root").innerHTML = "";
        const words = { approve: "approved", reject: "rejected", "request-changes": "sent back to the maker" }[action];
        toast(`${saved.name} version ${saved.versionNumber} ${words}`, "success");
        state.templates = await api("/api/format-templates");
        const next = reviewQueue()[0];
        selectReview(next ? next.id : saved.id);
        render();
        updateBadges();
    } catch (error) {
        toast(error.message, "error");
        button.disabled = false;
    }
}

/** Returns true when the click belonged to the review screen. */
function onReviewClick(event) {
    const target = event.target;
    // The backdrop itself or a Cancel button closes the dialog; clicks inside the form do not.
    if ((target.matches && target.matches(".modal-back[data-modal-close]")) || target.closest("button[data-modal-close]")) {
        document.getElementById("modal-root").innerHTML = "";
        return true;
    }
    const pick = target.closest("[data-review]");
    if (pick) {
        selectReview(pick.dataset.review);
        render();
        return true;
    }
    const tab = target.closest("[data-review-tab]");
    if (tab) {
        state.review.tab = tab.dataset.reviewTab;
        renderReviewBody();
        if (state.review.tab === "data" && !state.review.preview) loadReviewPreview(false);
        return true;
    }
    if (target.closest("[data-review-more]")) {
        loadReviewPreview(true);
        return true;
    }
    const decide = target.closest("[data-decide]");
    if (decide) {
        openDecision(decide.dataset.decide);
        return true;
    }
    return false;
}

function onReviewInput(event) {
    const target = event.target;
    if (target.id === "review-baseline") {
        state.review.baseline = Number(target.value);
        renderReviewBody();
        return true;
    }
    if (target.id === "review-version") {
        state.review.versionId = Number(target.value);
        state.review.preview = null;
        renderReviewBody();
        loadReviewPreview(false);
        return true;
    }
    if (target.closest && target.closest("#decision-form") && target.name === "comment") {
        const counter = document.getElementById("decision-counter");
        const form = target.closest("#decision-form");
        if (counter && form.dataset.action !== "approve") {
            const length = target.value.trim().length;
            counter.textContent = length < 10 ? `${length} / at least 10 characters` : `${length} / 2,000`;
            counter.classList.toggle("ok", length >= 10);
            form.querySelector('button[type="submit"]').disabled = length < 10;
        }
        return true;
    }
    return false;
}

/** Counts on the sidebar: submissions waiting for an approver, templates sent back to a maker. */
async function updateBadges(templates) {
    if (!state.user) return;
    try {
        const list = templates || await api("/api/format-templates");
        const latest = new Map();
        list.forEach(item => {
            const current = latest.get(item.familyId);
            if (!current || item.versionNumber > current.versionNumber) latest.set(item.familyId, item);
        });
        const waiting = list.filter(item => item.status === "PENDING").length;
        const returned = [...latest.values()].filter(item => item.status === "CHANGES_REQUESTED"
            && (!state.user.username || String(item.createdBy).toLowerCase() === state.user.username.toLowerCase())).length;
        setBadge('[data-page="approvals"]', state.user.role === "APPROVER" ? waiting : 0, "waiting for review");
        setBadge('[data-page="templates"]', state.user.role !== "APPROVER" ? returned : 0, "sent back to you");
    } catch (error) {
        /* badges are a convenience */
    }
}

function setBadge(selector, count, label) {
    const link = document.querySelector(".nav " + selector);
    if (!link) return;
    let badge = link.querySelector(".nav-badge");
    if (!count) {
        if (badge) badge.remove();
        return;
    }
    if (!badge) {
        badge = document.createElement("span");
        badge.className = "nav-badge";
        link.appendChild(badge);
    }
    badge.textContent = count;
    badge.title = count + " " + label;
}

/* ---------- Data versions: every dataset an export produced, kept as it was ---------- */

function seriesKey(snapshot) {
    return snapshot.familyId + "|" + snapshot.listCode;
}

/** Data versions grouped by template and list, newest version first in each. */
function dataSeries() {
    const groups = new Map();
    (state.dataVersions || []).forEach(snapshot => {
        const key = seriesKey(snapshot);
        if (!groups.has(key)) groups.set(key, { key, familyId: snapshot.familyId, listCode: snapshot.listCode, listName: snapshot.listName, versions: [] });
        groups.get(key).versions.push(snapshot);
    });
    const list = [...groups.values()];
    list.forEach(group => {
        group.versions.sort((a, b) => b.id - a.id);
        group.latest = group.versions[0];
        group.name = group.latest.templateName;
    });
    return list.sort((a, b) => a.name.localeCompare(b.name) || a.listName.localeCompare(b.listName));
}

function ensureDataView() {
    if (!state.dv) state.dv = { key: null, id: null, tab: "overview", detail: null, rows: null, compare: null, compareWith: null, format: null, audience: "" };
    const series = dataSeries();
    let group = series.find(item => item.key === state.dv.key);
    if (!group && state.dv.familyId) group = series.find(item => String(item.familyId) === String(state.dv.familyId));
    if (!group) group = series[0];
    state.dv.key = group ? group.key : null;
    if (group && !group.versions.some(item => String(item.id) === String(state.dv.id))) {
        state.dv.id = group.latest.id;
        state.dv.detail = null;
        state.dv.rows = null;
        state.dv.compare = null;
        state.dv.compareWith = null;
    }
    return group;
}

function versionsHtml() {
    const group = ensureDataView();
    const series = dataSeries();
    const intro = `<details class="dv-explain"><summary>How data versions work</summary>
        <ul>
            <li>Every export keeps an exact copy of the rows it produced: a <strong>data version</strong>.</li>
            <li>When the data is the same as an existing version, that version is <strong>reused</strong>; nothing is stored twice. When any row was added, removed or changed, the next number is given.</li>
            <li>The first number is the <strong>template version</strong> (its columns and row filters), the second counts <strong>data changes</strong>: 1.0, 1.1, 1.2… and 2.0 once a new template version is approved.</li>
            <li>Any version can be downloaded as CSV or XML at any time. It comes from the stored copy, so it is exactly what was sent, even after the source data changed.</li>
        </ul></details>`;
    if (!group) {
        return `${head("Data", "Data Versions", "Every export keeps an exact copy of the data it produced.")}${intro}
            <section class="panel"><div class="empty">No data versions yet. They are created the first time a template is exported.</div></section>`;
    }
    const options = series.map(item => `<option value="${esc(item.key)}" ${item.key === group.key ? "selected" : ""}>${esc(item.name)} · ${esc(item.listName)} (${item.versions.length})</option>`).join("");
    const versions = group.versions;
    const totalRows = versions.reduce((sum, item) => sum + item.rowCount, 0);
    const storedRows = versions.reduce((sum, item) => sum + item.storedRows, 0);
    const shared = totalRows ? Math.round((totalRows - storedRows) * 100 / totalRows) : 0;
    const served = versions.reduce((sum, item) => sum + 1 + item.reuseCount, 0);
    const stats = `<section class="stats dv-stats">
            ${statCard("Latest version", group.latest.label, when(group.latest.createdAt), "green")}
            ${statCard("Versions", num(versions.length), versions.length === 1 ? "one dataset so far" : "distinct datasets", "")}
            ${statCard("Exports served", num(served), num(served - versions.length) + " reused an existing version", "")}
            ${statCard("Storage shared", shared + "%", `${num(storedRows)} rows stored for ${num(totalRows)} in all versions`, "")}
        </section>`;
    let lastMajor = null;
    const timeline = versions.map((item, index) => {
        const divider = item.major !== lastMajor
            ? `<div class="dv-major">Template version ${item.major}${index === 0 ? "" : ""}</div>` : "";
        lastMajor = item.major;
        const changes = item.previousLabel
            ? `<span class="dv-delta"><span class="add">+${num(item.insertedRows)}</span> <span class="del">−${num(item.deletedRows)}</span> <span class="chg">~${num(item.changedRows)}</span> vs ${esc(item.previousLabel)}</span>`
            : '<span class="dv-delta">First version</span>';
        return `${divider}<button type="button" class="dv-item ${String(item.id) === String(state.dv.id) ? "active" : ""}" data-dv="${item.id}">
            <span class="dv-label">${esc(item.label)}${index === 0 ? ' <span class="pill pill-on">Latest</span>' : ""}</span>
            <span class="dv-meta">${when(item.createdAt)} · ${num(item.rowCount)} rows</span>
            <span class="dv-meta">${changes}</span>
            <span class="dv-meta">${item.reuseCount ? `Reused ${item.reuseCount}×` : "Used once"} · list version ${esc(item.sourceVersionId)}</span>
        </button>`;
    }).join("");
    const selected = versions.find(item => String(item.id) === String(state.dv.id)) || group.latest;
    return `
        ${head("Data", "Data Versions", "Every export keeps an exact copy of the data it produced. Identical data reuses its version; changed data gets the next number.")}
        ${intro}
        <div class="dv-toolbar"><label class="field-label">Template and list<select id="dv-series">${options}</select></label></div>
        ${stats}
        <div class="dv-layout">
            <aside class="dv-timeline" aria-label="Versions">${timeline}</aside>
            <section class="dv-detail" id="dv-detail">${versionDetailHtml(selected, group)}</section>
        </div>`;
}

function statCard(label, value, note, tone) {
    return `<article class="stat ${tone}"><div class="stat-label">${esc(label)}</div><div class="stat-value">${esc(value)}</div><div class="stat-note">${esc(note)}</div></article>`;
}

function versionDetailHtml(snapshot, group) {
    const view = state.dv;
    const tabs = [["overview", "Overview"], ["data", "Data"], ["compare", "Compare"], ["exports", "Exports"]];
    let body;
    if (view.tab === "data") body = versionRowsHtml(snapshot);
    else if (view.tab === "compare") body = versionCompareHtml(snapshot, group);
    else if (view.tab === "exports") body = versionExportsHtml();
    else body = versionOverviewHtml(snapshot, group);
    const latest = group.latest.id === snapshot.id;
    return `<header class="review-head">
            <div>
                <p class="kicker">${esc(snapshot.templateName)} · ${esc(snapshot.listName)}</p>
                <h2>Data version ${esc(snapshot.label)} ${latest ? '<span class="pill pill-on">Latest</span>' : ""}</h2>
                <p class="muted">Created ${when(snapshot.createdAt)}${snapshot.createdJobId ? ` by export job #${snapshot.createdJobId}` : ""} · ${num(snapshot.rowCount)} rows</p>
            </div>
        </header>
        <nav class="review-tabs" role="tablist">${tabs.map(([id, label]) => `<button type="button" role="tab" class="review-tab ${view.tab === id ? "active" : ""}" aria-selected="${view.tab === id}" data-dv-tab="${id}">${esc(label)}</button>`).join("")}</nav>
        <div class="review-body">${body}</div>`;
}

function versionOverviewHtml(snapshot, group) {
    const detail = state.dv.detail;
    const subscribers = (state.subscribers || []).filter(item => String(item.templateFamilyId) === String(snapshot.familyId));
    const format = state.dv.format || snapshot.formatType;
    const audience = state.dv.audience || "";
    const href = `/api/data-versions/${snapshot.id}/file?format=${encodeURIComponent(format)}${audience ? "&subscriberId=" + encodeURIComponent(audience) : ""}`;
    const changes = snapshot.previousLabel
        ? `<strong>+${num(snapshot.insertedRows)}</strong> added, <strong>−${num(snapshot.deletedRows)}</strong> removed, <strong>~${num(snapshot.changedRows)}</strong> changed since ${esc(snapshot.previousLabel)} <button type="button" class="link-btn" data-dv-tab="compare">See the changes</button>`
        : "The first version of this template and list.";
    const columns = detail ? detail.columns.map((column, index) => `<li><span class="muted">${index + 1}.</span> <strong>${esc(column.header)}</strong> <span class="mono muted">${esc(column.table)}.${esc(column.column)}</span></li>`).join("") : "<li class='muted'>Loading…</li>";
    const filters = detail ? (detail.filters.length ? detail.filters.map(text => `<li>${esc(text)}</li>`).join("") : "<li class='muted'>None: every list entry</li>") : "";
    return `<div class="review-section">
            <div class="dv-download">
                <div>
                    <h3>Download this version</h3>
                    <p class="muted small-note">Built from the stored copy: exactly the data of ${esc(snapshot.label)}, even after the source changed. The source tables are not read.</p>
                </div>
                <div class="dv-download-form">
                    <label>Format<select id="dv-format">${["CSV", "XML"].map(value => `<option ${value === format ? "selected" : ""}>${value}</option>`).join("")}</select></label>
                    <label>Rows<select id="dv-audience">
                        <option value="">All ${num(snapshot.rowCount)} rows</option>
                        ${subscribers.map(item => `<option value="${item.id}" ${String(item.id) === String(audience) ? "selected" : ""}>As ${esc(item.name)} receives it</option>`).join("")}
                    </select></label>
                    <a class="btn-primary" id="dv-download" href="${href}" download>Download</a>
                </div>
            </div>
            <dl class="facts">
                <div><dt>Changes</dt><dd>${changes}</dd></div>
                <div><dt>Template</dt><dd>${esc(snapshot.templateName)} version ${snapshot.major} <span class="muted">(${esc(snapshot.formatType)})</span></dd></div>
                <div><dt>Source</dt><dd>${esc(snapshot.listName)} list, certified version ${esc(snapshot.sourceVersionId)}</dd></div>
                <div><dt>Used</dt><dd>${1 + snapshot.reuseCount} export${snapshot.reuseCount ? "s" : ""}; last ${when(snapshot.lastUsedAt)}</dd></div>
                <div><dt>Storage</dt><dd>${num(snapshot.storedRows)} new rows stored; ${num(snapshot.rowCount - snapshot.storedRows)} shared with earlier versions</dd></div>
                <div><dt>Fingerprint</dt><dd class="mono" title="${esc(snapshot.fingerprint)}">${esc(snapshot.fingerprint.slice(0, 16))}…</dd></div>
            </dl>
            <div class="dv-columns">
                <div><h4>Columns</h4><ul>${columns}</ul></div>
                <div><h4>Row filters</h4><ul>${filters}</ul></div>
            </div>
        </div>`;
}

function versionRowsHtml(snapshot) {
    const rows = state.dv.rows;
    if (!rows) return '<div class="empty">Loading rows…</div>';
    if (rows.error) return `<div class="banner banner-error">${esc(rows.error)}</div>`;
    return `<div class="review-section">
            <p class="muted small-note">The rows of ${esc(snapshot.label)} as stored, in file order. Read-only.</p>
            <div class="grid-wrap review-grid"><table class="grid">
                <thead><tr><th class="rownum">#</th>${rows.columns.map(column => `<th><div class="gh"><span class="gh-title">${esc(column.header)}</span></div><div class="gh-src">${esc(column.table)}.${esc(column.column)}</div></th>`).join("")}</tr></thead>
                <tbody>${rows.rows.map(row => `<tr><td class="rownum">${num(row.line)}</td>${row.cells.map(cell => cell === null || cell === undefined ? '<td class="nul">(empty)</td>' : `<td title="${esc(cell)}">${esc(cell)}</td>`).join("")}</tr>`).join("")}</tbody>
            </table></div>
            <div class="grid-foot">Showing ${num(rows.rows.length)} of ${num(rows.total)} rows ${rows.rows.length < rows.total ? '<button type="button" class="link-btn" data-dv-more-rows>Show 100 more</button>' : ""}</div>
        </div>`;
}

function versionCompareHtml(snapshot, group) {
    const others = group.versions.filter(item => item.id !== snapshot.id);
    if (!others.length) return '<div class="empty">This is the only version, so there is nothing to compare with yet.</div>';
    const compare = state.dv.compare;
    const options = others.map(item => `<option value="${item.id}" ${String(item.id) === String(state.dv.compareWith) ? "selected" : ""}>${esc(item.label)} · ${when(item.createdAt)}</option>`).join("");
    let body = '<div class="empty">Loading the comparison…</div>';
    if (compare && compare.error) body = `<div class="banner banner-error">${esc(compare.error)}</div>`;
    else if (compare) {
        const c = compare.counts;
        const rows = compare.changes.map(change => `<tr>
                <td class="mono">${esc(change.recordId)}<div class="muted">${esc(change.bnpId || "")}</div></td>
                <td><span class="tag tag-${change.kind === "ADDED" ? "new" : change.kind === "REMOVED" ? "bad" : "warn"}">${change.kind === "ADDED" ? "Added" : change.kind === "REMOVED" ? "Removed" : "Changed"}</span></td>
                <td>${change.kind === "CHANGED" ? change.cells.map(cell => `<div class="cell-change"><strong>${esc(cell.column)}</strong>: <span class="was">${esc(cell.before ?? "(empty)")}</span> → ${esc(cell.after ?? "(empty)")}</div>`).join("")
                    : `<span class="muted">${change.kind === "ADDED" ? "In " + esc(compare.newerLabel) + " only" : "In " + esc(compare.olderLabel) + " only"}</span>`}</td>
            </tr>`).join("");
        body = `<div class="dv-counts">
                <span class="count add">+${num(c.inserted)} added</span>
                <span class="count del">−${num(c.deleted)} removed</span>
                <span class="count chg">~${num(c.changed)} changed</span>
                <span class="count">${num(c.unchanged)} unchanged</span>
            </div>
            ${compare.sameLayout ? "" : '<div class="review-callout warn">These versions come from different template versions. Columns are matched by their name in the file; a column only in one version shows as empty in the other.</div>'}
            ${rows ? `<table class="detail-table review-table"><thead><tr><th>Record</th><th>Change</th><th>What changed</th></tr></thead><tbody>${rows}</tbody></table>
                ${compare.more ? '<button type="button" class="link-btn" data-dv-more-changes>Show more changes</button>' : ""}`
                : '<p class="muted">The two versions hold exactly the same rows.</p>'}`;
    }
    return `<div class="review-section">
            <div class="section-head">
                <h3>${esc(snapshot.label)} compared with</h3>
                <label class="compare"><select id="dv-compare">${options}</select></label>
            </div>
            ${body}
        </div>`;
}

function versionExportsHtml() {
    const detail = state.dv.detail;
    if (!detail) return '<div class="empty">Loading…</div>';
    if (!detail.usage.length) return '<div class="empty">No export has used this version yet.</div>';
    const rows = detail.usage.map(use => `<tr>
            <td><a class="file-link" href="#/history" data-history="${use.jobId}">#${use.jobId}</a></td>
            <td>${when(use.at)}</td>
            <td>${esc(use.subscriberName)} <span class="fmt-${esc(use.formatType)}">${esc(use.formatType)}</span></td>
            <td>${use.outcome === "CREATED" ? '<span class="tag tag-new">Created this version</span>' : '<span class="tag">Reused: data unchanged</span>'}</td>
            <td>${badge(use.status)} <span class="muted">${num(use.records)} records</span></td>
        </tr>`).join("");
    return `<div class="review-section">
            <p class="muted small-note">Every subscriber file built from this version. Retries rebuild from the same version, so they always send the same data.</p>
            <table class="detail-table review-table"><thead><tr><th>Job</th><th>When</th><th>Subscriber</th><th>Version</th><th>File</th></tr></thead><tbody>${rows}</tbody></table>
        </div>`;
}

async function loadVersionDetail() {
    const id = state.dv.id;
    if (!id) return;
    try {
        const detail = await api("/api/data-versions/" + id);
        if (state.dv.id !== id) return;
        state.dv.detail = detail;
    } catch (error) {
        toast(error.message, "error");
    }
    renderVersionDetail();
}

async function loadVersionRows(more) {
    const id = state.dv.id;
    const offset = more && state.dv.rows ? state.dv.rows.rows.length : 0;
    try {
        const page = await api(`/api/data-versions/${id}/rows?offset=${offset}&limit=100`);
        if (state.dv.id !== id) return;
        state.dv.rows = more ? Object.assign(state.dv.rows, { rows: state.dv.rows.rows.concat(page.rows) }) : page;
    } catch (error) {
        state.dv.rows = { error: error.message };
    }
    renderVersionDetail();
}

async function loadVersionCompare(more) {
    const id = state.dv.id;
    const group = dataSeries().find(item => item.key === state.dv.key);
    if (!group) return;
    if (!state.dv.compareWith) {
        const current = group.versions.find(item => item.id === id);
        const fallback = group.versions.find(item => item.id !== id);
        state.dv.compareWith = current && current.previousSnapshotId && group.versions.some(item => item.id === current.previousSnapshotId)
            ? current.previousSnapshotId : (fallback ? fallback.id : null);
    }
    if (!state.dv.compareWith) return renderVersionDetail();
    const other = state.dv.compareWith;
    const newerFirst = id > other;
    const offset = more && state.dv.compare ? state.dv.compare.changes.length : 0;
    try {
        const result = await api(`/api/data-versions/${newerFirst ? id : other}/compare/${newerFirst ? other : id}?offset=${offset}&limit=50`);
        if (state.dv.id !== id || state.dv.compareWith !== other) return;
        state.dv.compare = more ? Object.assign(state.dv.compare, { changes: state.dv.compare.changes.concat(result.changes), more: result.more }) : result;
    } catch (error) {
        state.dv.compare = { error: error.message };
    }
    renderVersionDetail();
}

function renderVersionDetail() {
    const host = document.getElementById("dv-detail");
    const group = dataSeries().find(item => item.key === state.dv.key);
    const snapshot = group && group.versions.find(item => item.id === state.dv.id);
    if (host && snapshot) host.innerHTML = versionDetailHtml(snapshot, group);
}

function loadVersionTab() {
    if (state.dv.tab === "data" && !state.dv.rows) loadVersionRows(false);
    if (state.dv.tab === "compare" && !state.dv.compare) loadVersionCompare(false);
    if ((state.dv.tab === "overview" || state.dv.tab === "exports") && !state.dv.detail) loadVersionDetail();
}

function onVersionsClick(event) {
    const target = event.target;
    const item = target.closest("[data-dv]");
    if (item) {
        state.dv.id = Number(item.dataset.dv);
        state.dv.detail = null;
        state.dv.rows = null;
        state.dv.compare = null;
        state.dv.compareWith = null;
        render();
        loadVersionTab();
        return true;
    }
    const tab = target.closest("[data-dv-tab]");
    if (tab) {
        state.dv.tab = tab.dataset.dvTab;
        renderVersionDetail();
        loadVersionTab();
        return true;
    }
    if (target.closest("[data-dv-more-rows]")) {
        loadVersionRows(true);
        return true;
    }
    if (target.closest("[data-dv-more-changes]")) {
        loadVersionCompare(true);
        return true;
    }
    return false;
}

function onVersionsInput(event) {
    const target = event.target;
    if (target.id === "dv-series") {
        state.dv.key = target.value;
        state.dv.id = null;
        state.dv.familyId = null;
        render();
        loadVersionTab();
        return true;
    }
    if (target.id === "dv-compare") {
        state.dv.compareWith = Number(target.value);
        state.dv.compare = null;
        renderVersionDetail();
        loadVersionCompare(false);
        return true;
    }
    if (target.id === "dv-format" || target.id === "dv-audience") {
        if (target.id === "dv-format") state.dv.format = target.value;
        else state.dv.audience = target.value;
        const link = document.getElementById("dv-download");
        if (link) {
            link.href = `/api/data-versions/${state.dv.id}/file?format=${encodeURIComponent(state.dv.format || document.getElementById("dv-format").value)}${state.dv.audience ? "&subscriberId=" + encodeURIComponent(state.dv.audience) : ""}`;
        }
        return true;
    }
    return false;
}

/** Opens the Data Versions page on a template (and optionally ready to download for a subscriber). */
function openDataVersions(familyId, subscriberId) {
    state.dv = { key: null, familyId: familyId, id: null, tab: "overview", detail: null, rows: null, compare: null, compareWith: null, format: null, audience: subscriberId ? String(subscriberId) : "" };
    location.hash = "#/versions";
}
