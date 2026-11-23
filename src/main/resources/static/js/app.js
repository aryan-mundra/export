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

const pages = ["dashboard", "export", "history", "subscribers", "quarantine", "templates", "approvals"];

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
    const next = pageFromHash();
    if (next !== "export") state.xpDialog = null;
    state.page = next;
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
        if (state.page === "export" && (!state.xpDialog || (state.xp && state.xp.pendingSend))) startExportPage();
        void main.offsetWidth;
        main.classList.add("is-entering");
        schedulePoll();
    } catch (error) {
        document.getElementById("main").innerHTML = `<div class="banner" style="border-color:#fecaca;background:#fef2f2;color:#991b1b">${esc(error.message)}</div>`;
    }
}

/** The file formats the module can write (from the server), for subscriber forms and downloads. */
function formatChoices() {
    return state.formatList && state.formatList.length ? state.formatList : ["CSV", "XML"];
}

async function refresh() {
    if (!state.formatList) state.formatList = await api("/api/formats").catch(() => null);
    if (state.page === "dashboard") {
        state.dashboard = await api("/api/dashboard");
    } else if (state.page === "export") {
        // Only the recent exports; lists and subscribers are searched and paged on demand.
        state.dashboard = await api("/api/dashboard");
    } else if (state.page === "subscribers") {
        state.subscribers = await api("/api/subscribers?q=" + encodeURIComponent(state.search.subscribers));
        state.templates = await api("/api/format-templates");
    } else if (state.page === "history") {
        state.jobs = await api("/api/jobs?q=" + encodeURIComponent(state.search.history));
        if (state.openJobId) {
            state.jobDetail = await api("/api/jobs/" + state.openJobId);
        }
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
        const [templates, subscribers] = await Promise.all([api("/api/format-templates"), api("/api/subscribers")]);
        state.templates = templates;
        state.subscribers = subscribers;
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

function historyHtml() {
    const rows = state.jobs.map(job => {
        const open = String(state.openJobId) === String(job.id);
        return `<tr class="clickable" data-job="${job.id}">
            <td>${job.id}</td>
            <td>${esc(job.versionId)}</td>
            <td>${badge(job.status)}</td>
            <td>${esc(job.listName || "")}${job.mode === "RESEND" ? `<div class="muted">Sent ${esc(job.snapshotTemplate || "")} version ${esc(job.snapshotLabel || "")}</div>` : ""}</td>
            <td>${job.mode === "RESEND" ? '<span class="muted">Stored version</span>' : job.fromDate ? esc(job.fromDate) + " → " + esc(job.toDate) : "All changes"}</td>
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
        <td>${subscriber.snapshotLabel ? `<span class="dv-chip" title="${subscriber.snapshotOutcome === "CREATED" ? "This export found changed data and created this data version" : "The data was identical to this existing data version, so it was reused"}">${esc(subscriber.snapshotLabel)} · ${subscriber.snapshotOutcome === "CREATED" ? "new" : subscriber.snapshotOutcome === "RESENT" ? "resent" : "reused"}</span>` : '<span class="muted">—</span>'}</td>
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
    if (subscriber.snapshotId) {
        buttons.push(`<button class="btn-ghost" type="button" data-resend-snapshot="${subscriber.snapshotId}" data-resend-to="${subscriber.subscriberId}" title="Send data version ${esc(subscriber.snapshotLabel || "")} to this subscriber again">Send again</button>`);
    }
    return buttons.join(" ");
}

function subscribersHtml() {
    const rows = state.subscribers.map(subscriber => `<tr>
        <td>${subscriber.id}</td>
        <td>${esc(subscriber.name)}</td>
        <td>${formatCell(subscriber.formatType)}</td>
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
            <button class="btn-delete" type="button" data-delete="${subscriber.id}">Delete</button>
        </td>
    </tr>`).join("");
    return `
        ${head("Recipients", "Subscribers", "Who receives a file, in which formats, and where it is delivered.", `<input class="search" data-search="subscribers" placeholder="Search subscribers..." value="${esc(state.search.subscribers)}">`)}
        <section class="table-card">
            <form class="add-form" id="add-form">
                <input name="name" placeholder="Subscriber name" required>
                ${formatBoxes("formatType", "CSV")}
                <select name="templateId" required aria-label="List">${templateOptions(null)}</select>
                ${select("scope", ["ALL", "SANCTIONS", "WORLDCHECK", "PFA"], "ALL")}
                ${select("exportMode", ["DELTA", "FULL"], "DELTA")}
                <input name="businessLine" placeholder="Business line">
                <input name="region" placeholder="Region">
                <input name="cftEndpoint" placeholder="Delivery endpoint">
                <button class="btn-primary" type="submit">+ Add</button>
            </form>
            <table>
                <thead><tr><th>ID</th><th>Name</th><th>Format</th><th>Mode</th><th>Scope</th><th>Business line</th><th>Region</th><th>Delivery</th><th>List</th><th>Last file</th><th>Active</th><th></th></tr></thead>
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
    const families = templateFamilies();
    const current = families.find(family => String(family.id) === String(draft.familyId));
    const saved = state.templates.map(template => `<tr>
        <td>${esc(template.name)}</td>
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
        ${head("Formats", "Create a List", "Pick the gs_ columns and the rows. A list is only data: you choose CSV, XML or any other format when you send it, subscriber by subscriber.")}
        <section class="export-card template-card">
            <h2>${current ? "Edit " + esc(current.name) : "New list"}</h2>
            <p class="muted">${current
                ? `Saving creates version ${current.latest.versionNumber + 1} for approval. Its subscribers keep the version in use until an approver accepts it.`
                : "Saving sends version 1 for approval. Once approved, any subscriber can receive it, in any format."}</p>
            ${waiting}
            <form id="template-form">
                <div class="add-form" style="padding:12px 0;border:0">
                    <select id="template-pick" aria-label="List">
                        <option value="" ${current ? "" : "selected"}>＋ New list</option>
                        ${families.map(family => `<option value="${family.id}" ${current && current.id === family.id ? "selected" : ""}>${esc(family.name)}</option>`).join("")}
                    </select>
                    <input name="name" value="${esc(draft.name)}" placeholder="List name" required ${current ? "readonly" : ""} aria-label="List name">
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
                    <button class="btn-primary" type="submit" ${draft.fields.length ? "" : "disabled"}>${current ? "Save new version" : "Save list"}</button>
                </div>
            </form>
            ${current ? `<details class="template-history"><summary>History of ${esc(current.name)}</summary>${templateHistoryHtml(current.id, null)}</details>` : ""}
        </section>
        <section class="table-card" style="margin-top:16px">
            <table>
                <thead><tr><th>Name</th><th>Version</th><th>Status</th><th>Used by</th><th>Headers</th><th>Rows</th></tr></thead>
                <tbody>${saved}</tbody>
            </table>
            ${state.templates.length ? "" : '<div class="empty">No lists yet.</div>'}
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
    return `<div class="stepper" role="tablist" aria-label="List steps">
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
function draftFromActive(familyId = null) {
    const family = familyId ? templateFamilies().find(item => String(item.id) === String(familyId)) : null;
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
        return { familyId: family.id, name: family.name, fields, filters };
    }
    return { familyId: null, name: "", fields: [], filters: [] };
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

/** One entry per template: its name, the version in use (if approved) and the newest version. */
function templateFamilies() {
    const families = new Map();
    state.templates.forEach(template => {
        const family = families.get(template.familyId) || { id: template.familyId, name: template.name, inUse: null, latest: template };
        if (template.active) family.inUse = template;
        if (template.versionNumber > family.latest.versionNumber) family.latest = template;
        families.set(template.familyId, family);
    });
    return [...families.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/** Template choices for a subscriber: only templates with an approved version can be picked. */
function templateOptions(selected) {
    const families = templateFamilies();
    if (!families.length) return '<option value="">No list yet</option>';
    return `<option value="" ${selected ? "" : "selected"}>Choose a list…</option>` + families.map(family =>
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
    if (onSendClick(event)) return;
    if (state.page === "approvals" && onReviewClick(event)) return;
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
            const formats = form.getAll("formatType");
            if (!formats.length) {
                toast("Choose at least one format", "error");
                return;
            }
            await api("/api/subscribers", {
                method: "POST",
                body: {
                    name: form.get("name"),
                    formatType: formats.join(","),
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
            const formats = form.getAll("formatType");
            if (!formats.length) {
                toast("Choose at least one format", "error");
                return;
            }
            await api("/api/subscribers/" + form.get("id"), {
                method: "PUT",
                body: {
                    name: form.get("name"),
                    formatType: formats.join(","),
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
        const clash = !draft.familyId && templateFamilies()
            .find(family => family.name.toLowerCase() === (draft.name || "").trim().toLowerCase());
        if (clash) {
            toast(`A list named "${clash.name}" already exists. Pick it in the list menu to save a new version of it.`, "error");
            return;
        }
        try {
            const saved = await api("/api/format-templates", {
                method: "POST",
                body: {
                    name: state.templateDraft.name,
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
            state.templateDraft = draftFromActive(saved.familyId);
            renderTemplatesKeepingFilter();
            if (state.templateStep === "data") loadExplore();
        } catch (error) {
            toast(error.message, "error");
        }
    }
}

let searchTimer;
function onInput(event) {
    if (state.page === "export" && onSendInput(event)) return;
    if (state.page === "approvals" && onReviewInput(event)) return;
    if (onExplorerInput(event)) return;
    if (event.target.id === "template-pick") {
        state.templateDraft = draftFromActive(event.target.value || null);
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
    if (event.target.name === "formatType" && event.target.type === "checkbox") {
        const chip = event.target.closest(".xp-fmt");
        if (chip) chip.classList.toggle("on", event.target.checked);
    }
    if (event.target.closest("#template-form") && event.target.name === "name" && state.templateDraft) {
        state.templateDraft.name = event.target.value;
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
                <label>Usual formats${formatBoxes("formatType", subscriber.formatType)}</label>
                <label>List<select name="templateId" required>${templateOptions(subscriber.templateFamilyId)}</select></label>
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
    else if (filters.length) hint = "Only rows that match go in the file. The filter is saved with the list.";
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

/* ---------- Approvals: a simple card per submission ---------- */

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

function tablesOf(template) {
    const tables = new Map();
    template.fields.forEach(field => {
        if (!tables.has(field.sourceTable)) tables.set(field.sourceTable, []);
        tables.get(field.sourceTable).push(field);
    });
    return tables;
}

function approvalsHtml() {
    const pending = state.templates.filter(item => item.status === "PENDING")
        .sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)));
    const cards = pending.map(template => {
        const own = state.user && template.createdBy && state.user.username
            && template.createdBy.toLowerCase() === state.user.username.toLowerCase();
        const previous = familyVersions(template.familyId).filter(item => item.versionNumber < template.versionNumber).pop();
        const returned = previous && previous.status === "CHANGES_REQUESTED"
            ? `<p class="approval-note">Resubmitted after you asked: “${esc(previous.reviewComment || "")}”</p>` : "";
        const tables = [...tablesOf(template).entries()].map(([table, fields]) =>
            `<li><strong>${esc(table)}</strong>: ${esc(fields.map(field => field.sourceColumn).join(", "))}</li>`).join("");
        const filters = template.filters || [];
        const rows = filters.length
            ? `<div class="approval-rows-simple"><strong>Rows:</strong> only where<ul>${filters.map(filter => `<li>${esc(filter.summary)}</li>`).join("")}</ul></div>`
            : '<p class="muted"><strong>Rows:</strong> all list entries</p>';
        const actions = own
            ? '<span class="muted">You submitted this; another approver must review it.</span>'
            : `<button class="btn-ghost" type="button" data-decide="request-changes" data-id="${template.id}">Request changes</button>
               <button class="btn-delete" type="button" data-decide="reject" data-id="${template.id}">Reject</button>
               <button class="btn-primary" type="button" data-decide="approve" data-id="${template.id}">Approve</button>`;
        return `<article class="approval-card">
            <div class="approval-head">
                <div>
                    <h2>${esc(template.name)} · version ${template.versionNumber}</h2>
                    <p class="muted">Created by <strong>${esc(template.createdByName || template.createdBy)}</strong> · ${when(template.createdAt)}</p>
                </div>
                <div class="approval-actions">${actions}</div>
            </div>
            ${returned}
            <ul class="approval-tables">${tables}</ul>
            ${rows}
        </article>`;
    }).join("");
    return `
        ${head("Review", "Approvals", "Lists waiting for your approval.")}
        ${endpointApprovals()}
        ${pending.length ? `<div class="approval-list">${cards}</div>` : '<section class="panel"><div class="empty">No lists are waiting for approval.</div></section>'}`;
}

/** Every step of a template's life, oldest first, from its versions (shown to makers on Create a List). */
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
            who: version.createdByName || version.createdBy
        });
        if (version.status === "APPROVED" && (version.reviewedAt || version.approvedAt)) {
            events.push({ at: version.reviewedAt || version.approvedAt, tone: "good", version: version, title: `Approved version ${version.versionNumber}`, who: version.reviewedByName || version.approvedByName, comment: version.reviewComment });
        } else if (version.status === "REJECTED") {
            events.push({ at: version.reviewedAt, tone: "bad", version: version, title: `Rejected version ${version.versionNumber}`, who: version.reviewedByName, comment: version.reviewComment });
        } else if (version.status === "CHANGES_REQUESTED") {
            events.push({ at: version.reviewedAt, tone: "warn", version: version, title: `Changes requested on version ${version.versionNumber}`, who: version.reviewedByName, comment: version.reviewComment });
        } else if (version.status === "SUPERSEDED") {
            events.push({ at: version.reviewedAt, tone: "off", version: version, title: `Version ${version.versionNumber} replaced before review`, who: version.reviewedByName });
        }
    });
    return events.sort((a, b) => String(a.at).localeCompare(String(b.at)));
}

function templateHistoryHtml(familyId, currentId) {
    const events = historyEvents(familyId);
    if (!events.length) return '<div class="empty">No history yet.</div>';
    return `<ol class="timeline">${events.map(event => `<li class="tl tl-${event.tone} ${event.version.id === currentId ? "tl-current" : ""}">
            <div class="tl-title">${esc(event.title)}${event.version.active && event.tone === "good" ? ' <span class="pill pill-on">in use</span>' : ""}</div>
            <div class="tl-meta">${esc(event.who || "")} · ${when(event.at)}</div>
            ${event.comment ? `<blockquote>${esc(event.comment)}</blockquote>` : ""}
        </li>`).join("")}</ol>`;
}

/** Rejecting or sending back asks for a reason the maker will see. */
function openDecision(action, id) {
    const template = state.templates.find(item => String(item.id) === String(id));
    if (!template) return;
    const copy = action === "reject"
        ? { title: `Reject ${template.name} version ${template.versionNumber}?`, label: "Reason (required)", confirm: "Reject", tone: "btn-danger" }
        : { title: `Request changes to ${template.name}`, label: "What should change? (required)", confirm: "Send back", tone: "btn-primary" };
    document.getElementById("modal-root").innerHTML = `
        <div class="modal-back" data-modal-close>
            <form class="modal decision-modal" id="decision-form" data-action="${action}" data-id="${template.id}">
                <h2>${esc(copy.title)}</h2>
                <label>${esc(copy.label)}<textarea name="comment" rows="4" maxlength="2000" required minlength="10" placeholder="At least 10 characters. The maker will see this."></textarea></label>
                <div class="modal-actions">
                    <button type="button" class="btn-ghost" data-modal-close>Cancel</button>
                    <button type="submit" class="${copy.tone}" disabled>${esc(copy.confirm)}</button>
                </div>
            </form>
        </div>`;
    document.querySelector("#decision-form textarea").focus();
}

async function decide(action, id, comment, button) {
    if (button) button.disabled = true;
    try {
        const saved = await api(`/api/format-templates/${id}/${action}`, { method: "POST", body: { comment: comment } });
        document.getElementById("modal-root").innerHTML = "";
        const words = { approve: "approved", reject: "rejected", "request-changes": "sent back to the maker" }[action];
        toast(`${saved.name} version ${saved.versionNumber} ${words}`, "success");
        state.templates = await api("/api/format-templates");
        render();
        updateBadges(state.templates);
    } catch (error) {
        toast(error.message, "error");
        if (button) button.disabled = false;
    }
}

async function submitDecision(form) {
    await decide(form.dataset.action, form.dataset.id, new FormData(form).get("comment"), form.querySelector('button[type="submit"]'));
}

/** Returns true when the click belonged to the approvals page. */
function onReviewClick(event) {
    const target = event.target;
    // The backdrop itself or a Cancel button closes the dialog; clicks inside the form do not.
    if ((target.matches && target.matches(".modal-back[data-modal-close]")) || target.closest("button[data-modal-close]")) {
        document.getElementById("modal-root").innerHTML = "";
        return true;
    }
    const button = target.closest("[data-decide]");
    if (button) {
        if (button.dataset.decide === "approve") decide("approve", button.dataset.id, null, button);
        else openDecision(button.dataset.decide, button.dataset.id);
        return true;
    }
    return false;
}

function onReviewInput(event) {
    const target = event.target;
    if (target.closest && target.closest("#decision-form") && target.name === "comment") {
        target.closest("#decision-form").querySelector('button[type="submit"]').disabled = target.value.trim().length < 10;
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

/* ---------- Data version search helpers (used by "Send a stored version" on the Export page) ---------- */

const CATALOG_PAGE = 50;

function seriesKeyOf(item) {
    return item.familyId + "|" + item.listCode;
}

function seriesUrl(q, sort, familyId, offset, limit) {
    const params = new URLSearchParams({ sort: sort || "recent", offset: String(offset || 0), limit: String(limit || CATALOG_PAGE) });
    if (q) params.set("q", q);
    if (familyId) params.set("familyId", familyId);
    return "/api/data-versions/series?" + params.toString();
}

function versionsUrl(series, offset, limit) {
    return `/api/data-versions/series/${encodeURIComponent(series.familyId)}/${encodeURIComponent(series.listCode)}?offset=${offset}&limit=${limit}`;
}

/* ---------- Export page: by template, with two tabs ----------
 *
 * Export Latest: every approved template; "Export" reads the current data, keeps the version when
 * nothing changed or creates the next one, then sends or downloads it.
 * Previous Versions: find a template, see its versions (1.1, 1.2, ...), send or download any of them.
 * Templates and versions are searched and paged on the server, so hundreds of templates and
 * thousands of versions stay quick.
 */

const TEMPLATE_PAGE = 25;
const VERSIONS_PAGE = 20;
const SEND_PAGE = 10;

function ensureXp() {
    if (!state.xp) {
        state.xp = {
            tab: "latest",
            latest: { q: "", format: "", offset: 0, page: null, seq: 0 },
            prev: { q: "", offset: 0, page: null, seq: 0, selected: null, lists: null, listCode: null, versions: null }
        };
    }
    return state.xp;
}

function exportHtml() {
    if (state.xpDialog) return dialogHtml();
    const xp = ensureXp();
    return `
        ${head("Delivery", "Export", "Export the latest data of a list, or any of its earlier versions.")}
        ${state.exportMessage ? `<div class="banner">${state.exportMessage}</div>` : ""}
        <nav class="xp-tabs" role="tablist">
            <button type="button" role="tab" class="xp-tab ${xp.tab === "latest" ? "active" : ""}" aria-selected="${xp.tab === "latest"}" data-xp-tab="latest">Export Latest</button>
            <button type="button" role="tab" class="xp-tab ${xp.tab === "previous" ? "active" : ""}" aria-selected="${xp.tab === "previous"}" data-xp-tab="previous">Previous Versions</button>
        </nav>
        <div id="xp-body">${xp.tab === "previous" ? previousHtml() : latestHtml()}</div>`;
}

function renderXp() {
    const body = document.getElementById("xp-body");
    if (body) body.innerHTML = state.xp.tab === "previous" ? previousHtml() : latestHtml();
}

function templateQuery(view, withVersions, limit) {
    const params = new URLSearchParams({ offset: String(view.offset), limit: String(limit), withVersions: String(withVersions) });
    if (view.q) params.set("q", view.q);
    return "/api/export-templates?" + params.toString();
}

function versionChip(label) {
    return label ? `<span class="ver-chip">${esc(label)}</span>` : "";
}

/* --- Tab 1: Export Latest --- */

async function loadLatest() {
    const view = state.xp.latest;
    const seq = ++view.seq;
    try {
        const page = await api(templateQuery(view, false, TEMPLATE_PAGE));
        if (seq !== view.seq) return;
        view.page = page;
    } catch (error) {
        view.page = { total: 0, offset: 0, templates: [], error: error.message };
    }
    const host = document.getElementById("xp-latest-table");
    if (host) host.innerHTML = latestTableHtml();
    else renderXp();
}

function latestHtml() {
    const view = state.xp.latest;
    return `<section class="table-card xp-card">
            <div class="xp-toolbar">
                <input class="search" id="xp-latest-search" placeholder="Search lists" value="${esc(view.q)}" autocomplete="off" aria-label="Search lists">
            </div>
            <div id="xp-latest-table">${latestTableHtml()}</div>
        </section>`;
}

function latestTableHtml() {
    const page = state.xp.latest.page;
    if (!page) return '<div class="empty">Loading lists…</div>';
    if (page.error) return `<div class="banner banner-error">${esc(page.error)}</div>`;
    if (!page.templates.length) return `<div class="empty">${state.xp.latest.q ? "No list matches." : "No approved list yet. Create one under List."}</div>`;
    const rows = page.templates.map(item => `<tr>
            <td><strong>${esc(item.name)}</strong><div class="muted small-note">List version ${item.templateVersion}</div></td>
            <td>${item.latestLabel ? `${versionChip(item.latestLabel)} <span class="muted">${when(item.latestAt)}</span>` : '<span class="muted">Not exported yet</span>'}</td>
            <td>${num(item.subscribers)}</td>
            <td class="muted">${item.lastExport ? when(item.lastExport) : "—"}</td>
            <td class="actions"><button type="button" class="btn-primary btn-small" data-xp-export="${item.familyId}">Export</button></td>
        </tr>`).join("");
    return `<table>
            <thead><tr><th>List</th><th>Latest version</th><th>Subscribers</th><th>Last exported</th><th></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>
        ${pagerHtml(page, TEMPLATE_PAGE, "latest", "lists")}`;
}

function pagerHtml(page, size, key, noun) {
    const from = page.total ? page.offset + 1 : 0;
    const to = page.offset + page.templates.length;
    return `<div class="pager xp-pager">
            <span class="muted">${num(from)}–${num(to)} of ${num(page.total)} ${noun}</span>
            <button type="button" class="btn-ghost btn-small" data-xp-page="${key}:-1" ${page.offset === 0 ? "disabled" : ""}>‹ Previous</button>
            <button type="button" class="btn-ghost btn-small" data-xp-page="${key}:1" ${to >= page.total ? "disabled" : ""}>Next ›</button>
        </div>`;
}

/* --- Tab 2: Previous Versions --- */

async function loadPrevTemplates() {
    const view = state.xp.prev;
    const seq = ++view.seq;
    try {
        const page = await api(templateQuery(view, true, TEMPLATE_PAGE));
        if (seq !== view.seq) return;
        view.page = page;
    } catch (error) {
        view.page = { total: 0, offset: 0, templates: [], error: error.message };
    }
    const host = document.getElementById("xp-prev-list");
    if (host) host.innerHTML = prevListHtml();
    else if (!view.selected) renderXp();
}

function previousHtml() {
    const view = state.xp.prev;
    if (view.selected) return prevVersionsPageHtml();
    return `<section class="table-card xp-card">
            <div class="xp-toolbar">
                <input class="search" id="xp-prev-search" placeholder="Search lists" value="${esc(view.q)}" autocomplete="off" aria-label="Search lists">
            </div>
            <div id="xp-prev-list">${prevListHtml()}</div>
        </section>`;
}

function prevVersionsPageHtml() {
    const item = state.xp.prev.selected;
    return `<div class="xp-versions-head">
            <button type="button" class="btn-ghost" data-xp-back-lists>Back to lists</button>
            <div>
                <h2>${esc(item.name)}</h2>
                <span class="muted">Select the version to send or download.</span>
            </div>
        </div>
        <section class="table-card xp-card" id="xp-prev-detail">${prevDetailHtml()}</section>`;
}

function prevListHtml() {
    const view = state.xp.prev;
    const page = view.page;
    if (!page) return '<div class="queue-empty">Loading…</div>';
    if (page.error) return `<div class="banner banner-error">${esc(page.error)}</div>`;
    if (!page.templates.length) return `<div class="empty">${view.q ? "No list matches." : "No versions yet. Export a list first."}</div>`;
    const rows = page.templates.map(item => `<tr class="xp-version-row" data-xp-template="${item.familyId}">
            <td><strong>${esc(item.name)}</strong></td>
            <td>${num(item.versionCount)} version${item.versionCount === 1 ? "" : "s"}</td>
            <td>${item.latestLabel ? versionChip(item.latestLabel) : '<span class="muted">—</span>'}</td>
            <td class="actions"><button type="button" class="btn-primary btn-small" data-xp-template="${item.familyId}">Versions</button></td>
        </tr>`).join("");
    return `<table>
            <thead><tr><th>List</th><th>Versions</th><th>Latest</th><th></th></tr></thead>
            <tbody>${rows}</tbody>
        </table>
        ${pagerHtml(page, TEMPLATE_PAGE, "prev", "lists")}`;
}

async function selectPrevTemplate(item) {
    const view = state.xp.prev;
    view.selected = item;
    view.pickedId = null;
    view.lists = null;
    view.listCode = null;
    view.versions = null;
    if (!state.xpDialog) renderXp();
    try {
        const series = await api(seriesUrl("", "recent", item.familyId, 0, 50));
        if (view.selected !== item) return;
        view.lists = series.series;
        view.listCode = view.lists.length ? view.lists[0].listCode : null;
    } catch (error) {
        view.lists = [];
    }
    await loadPrevVersions(false);
}

async function loadPrevVersions(more) {
    const view = state.xp.prev;
    const series = view.selected && view.listCode ? { familyId: view.selected.familyId, listCode: view.listCode } : null;
    if (!series) return renderPrevDetail();
    const offset = more && view.versions ? view.versions.items.length : 0;
    try {
        const page = await api(versionsUrl(series, offset, VERSIONS_PAGE));
        if (!view.selected || view.selected.familyId !== series.familyId || view.listCode !== series.listCode) return;
        view.versions = more && view.versions
            ? { total: page.total, latestId: page.series.latestId, items: view.versions.items.concat(page.versions) }
            : { total: page.total, latestId: page.series.latestId, items: page.versions };
    } catch (error) {
        view.versions = { total: 0, items: [], error: error.message };
    }
    renderPrevDetail();
}

function renderPrevDetail() {
    const host = document.getElementById("xp-prev-detail");
    if (host) host.innerHTML = prevDetailHtml();
}

function prevDetailHtml() {
    const view = state.xp.prev;
    const item = view.selected;
    if (!item) return '<div class="empty">Choose a list to see its versions.</div>';
    const lists = view.lists && view.lists.length > 1
        ? `<label class="compare">List <select id="xp-prev-list-code">${view.lists.map(entry => `<option value="${esc(entry.listCode)}" ${entry.listCode === view.listCode ? "selected" : ""}>${esc(entry.listName)}</option>`).join("")}</select></label>`
        : (view.lists && view.lists.length ? `<span class="muted">${esc(view.lists[0].listName)} list</span>` : "");
    let table = '<div class="empty">Loading versions…</div>';
    if (view.versions && view.versions.error) table = `<div class="banner banner-error">${esc(view.versions.error)}</div>`;
    else if (view.versions && !view.versions.items.length) table = '<div class="empty">This list has no versions yet.</div>';
    else if (view.versions) {
        const picked = view.versions.items.find(version => version.id === view.pickedId);
        const rows = view.versions.items.map(version => {
            const on = version.id === view.pickedId;
            const changes = version.previousLabel
                ? `<span class="dv-delta"><span class="add">+${num(version.insertedRows)}</span> <span class="del">−${num(version.deletedRows)}</span> <span class="chg">~${num(version.changedRows)}</span> vs ${esc(version.previousLabel)}</span>`
                : '<span class="muted">First version</span>';
            return `<tr class="xp-version-row${on ? " selected" : ""}" data-xp-pick="${version.id}">
                <td><input type="radio" name="xp-version" ${on ? "checked" : ""} tabindex="-1" aria-label="Select version ${esc(version.label)}"></td>
                <td>${versionChip(version.label)}${version.id === view.versions.latestId ? ' <span class="pill pill-on">Latest</span>' : ""}${version.tableName ? `<div class="muted small-note xp-table-name" title="Database table holding this version">${esc(version.tableName)}</div>` : ""}</td>
                <td>${when(version.createdAt)}</td>
                <td>${num(version.rowCount)}</td>
                <td>${changes}</td>
            </tr>`;
        }).join("");
        const more = view.versions.items.length < view.versions.total
            ? `<button type="button" class="btn-ghost btn-small" data-xp-older>Show older versions (${num(view.versions.total - view.versions.items.length)} more)</button>` : "";
        const bar = picked
            ? `<div class="xp-version-bar">
                <span>Version ${versionChip(picked.label)} selected${picked.tableName ? ` · <code>${esc(picked.tableName)}</code>` : ""}</span>
                ${formatChoices().map(f => `<a class="btn-ghost btn-small" href="/api/data-versions/${picked.id}/file?format=${encodeURIComponent(f)}" download>${esc(f)}</a>`).join("")}
                <button type="button" class="btn-primary" data-xp-send="${picked.id}">Send this version</button>
               </div>`
            : `<div class="xp-version-bar"><span class="muted">Select a version.</span></div>`;
        table = `<table>
                <thead><tr><th></th><th>Version</th><th>Created</th><th>Rows</th><th>Changes</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            <div class="pager xp-pager"><span class="muted">${num(view.versions.items.length)} of ${num(view.versions.total)} versions</span>${more}</div>
            ${bar}`;
    }
    return `<div class="xp-detail-head">
            <div><h2>${esc(item.name)}</h2><span class="muted">List version ${item.templateVersion} · ${num(item.subscribers)} subscriber${item.subscribers === 1 ? "" : "s"}</span></div>
            ${lists}
        </div>
        ${table}`;
}

/* --- Export latest, or send a previous version: a normal page, not a popup --- */

function dialogHtml() {
    const d = state.xpDialog;
    if (!d) return "";
    const previous = d.kind === "previous";
    const backLabel = previous ? "Back to versions" : "Back to lists";
    let lede = "Send this version to subscribers, or download it.";
    if (d.step === "checking") lede = previous ? "Opening this stored version." : "Reading the current data and preparing a version.";
    if (d.step === "error") lede = "This export could not be prepared.";
    let body = "";
    if (d.step === "checking") {
        body = previous
            ? `<div class="xp-checking"><span class="spinner" aria-hidden="true"></span> Opening this version…</div>`
            : `<div class="xp-checking"><span class="spinner" aria-hidden="true"></span> Checking ${esc(d.name || "this list")} for data changes…</div>`;
    } else if (d.step === "error") {
        body = `<div class="banner banner-error">${esc(d.error)}</div>`;
    } else {
        const s = d.snapshot;
        const outcome = d.kind === "latest"
            ? (d.created && !s.previousLabel
                ? `<div class="review-callout good"><strong>First export of this list.</strong> Version ${esc(s.label)} was created.</div>`
                : d.created
                ? `<div class="review-callout good"><strong>The data changed.</strong> New version ${esc(s.label)} was created (+${num(s.insertedRows)} added, −${num(s.deletedRows)} removed, ~${num(s.changedRows)} changed since ${esc(s.previousLabel)}).</div>`
                : `<div class="review-callout"><strong>No change in the data.</strong> The existing version ${esc(s.label)} is exported again.</div>`)
            : "";
        body = `${outcome}
            <div class="xp-version-card">
                <span class="ver-chip big">${esc(s.label)}</span>
                <span>${num(s.rowCount)} rows · created ${when(s.createdAt)}${s.tableName ? ` · table <code>${esc(s.tableName)}</code>` : ""}</span>
            </div>
            <div class="xp-download">
                <span>Download</span>
                ${formatChoices().map(f => `<a class="btn-ghost btn-small" href="/api/data-versions/${s.id}/file?format=${encodeURIComponent(f)}" download>${esc(f)}</a>`).join("")}
            </div>
            ${sendSectionHtml()}`;
    }
    return `${head(previous ? "Previous versions" : "Export latest", esc(d.title), lede, `<button type="button" class="btn-ghost xp-back" data-xp-close>${backLabel}</button>`)}
        <section class="panel xp-page">${body}</section>`;
}

function sendSectionHtml() {
    const d = state.xpDialog;
    const send = d.send;
    if (!send) return "";
    if (send.loading) return '<div class="empty">Loading subscribers…</div>';
    if (send.error) return `<div class="banner banner-error">${esc(send.error)}</div>`;
    const rows = send.rows;
    const formats = send.formatList;
    const onTemplate = rows.filter(row => row.onTemplate);
    const matching = rows.filter(row => !send.q || row.name.toLowerCase().includes(send.q.toLowerCase()));
    const page = matching.slice(send.page * SEND_PAGE, (send.page + 1) * SEND_PAGE);
    const pages = Math.max(1, Math.ceil(matching.length / SEND_PAGE));
    const list = page.map(row => {
        const picked = sendFormatsOf(row);
        const on = send.chosen.has(row.subscriberId);
        const boxes = formats.map(format => {
            const ticked = picked.includes(format);
            return `<label class="xp-fmt fmt-${esc(format)}${ticked ? " on" : ""}">
                <input type="checkbox" data-xp-row-format="${row.subscriberId}" value="${esc(format)}" ${ticked ? "checked" : ""} aria-label="${esc(format)} for ${esc(row.name)}">
                ${esc(format)}
            </label>`;
        }).join("");
        const own = sameFormatSet(picked, ownFormats(row.formatType));
        return `<div class="xp-sub ${on ? "selected" : ""}">
            <input type="checkbox" id="xp-sub-${row.subscriberId}" data-xp-sub="${row.subscriberId}" ${on ? "checked" : ""}>
            <label class="xp-sub-name" for="xp-sub-${row.subscriberId}">${esc(row.name)}</label>
            <span class="muted">${esc(row.scope)} · ${esc(row.exportMode)} · ${row.onTemplate ? '<span class="tag tag-new">This list</span>' : "Other list"}</span>
            <span class="xp-sub-format">
                ${boxes}
                ${own ? "" : `<span class="was">usually ${formatCell(row.formatType)}</span>`}
            </span>
        </div>`;
    }).join("");
    const allChosen = rows.length && send.chosen.size === rows.length;
    const chosenRows = rows.filter(row => send.chosen.has(row.subscriberId));
    const byFormat = {};
    let fileCount = 0;
    chosenRows.forEach(row => sendFormatsOf(row).forEach(format => { byFormat[format] = (byFormat[format] || 0) + 1; fileCount++; }));
    const summary = Object.keys(byFormat).sort().map(f => `${num(byFormat[f])} ${esc(f)}`).join(" · ");
    const changed = chosenRows.filter(row => {
        const picked = sendFormatsOf(row);
        return !sameFormatSet(picked, ownFormats(row.formatType));
    }).length;
    return `<div class="xp-send">
            <div class="xp-send-head">
                <strong>Send to subscribers</strong>
                <span class="muted">${num(send.chosen.size)} of ${num(rows.length)} selected</span>
            </div>
            <p class="xp-format-note">Each subscriber starts with the formats saved on Subscribers. Change the ticks to send something else, for this send only.</p>
            <div class="xp-quick">
                <button type="button" class="btn-ghost btn-small ${allChosen ? "on" : ""}" data-xp-sub-all="all">Select all (${num(rows.length)})</button>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-all="template" ${onTemplate.length ? "" : "disabled"}>Only this list's (${num(onTemplate.length)})</button>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-all="none" ${send.chosen.size ? "" : "disabled"}>Clear</button>
            </div>
            <div class="xp-send-options">
                <label class="xp-format">Set the format of the ${num(send.chosen.size)} selected
                    <select id="xp-format" aria-label="Set the format of the selected subscribers" ${send.chosen.size ? "" : "disabled"}>
                        <option value="" selected>Choose…</option>
                        <option value="__own">Each subscriber's formats</option>
                        ${formats.map(format => `<option value="${esc(format)}">${esc(format)} for all selected</option>`).join("")}
                    </select>
                </label>
                <label class="xp-format">Find a subscriber
                    <input class="search" id="xp-sub-search" placeholder="Name" value="${esc(send.q)}" autocomplete="off" aria-label="Find a subscriber">
                </label>
            </div>
            <div class="xp-subs">${list || '<div class="muted xp-add-none">No subscriber matches.</div>'}</div>
            ${pages > 1 ? `<div class="pager"><span class="muted">Page ${send.page + 1} of ${pages} · ${num(matching.length)} subscribers</span>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-page="-1" ${send.page === 0 ? "disabled" : ""}>‹</button>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-page="1" ${send.page + 1 >= pages ? "disabled" : ""}>›</button></div>` : ""}
            ${chosenRows.length ? `<div class="muted xp-send-summary">Files: ${summary}${fileCount !== send.chosen.size ? ` · ${num(fileCount)} files` : ""}${changed ? ` · ${num(changed)} different from the subscriber's format` : ""}</div>` : ""}
            <div class="modal-actions">
                <button type="button" class="btn-ghost" data-xp-close>${d.kind === "previous" ? "Back to versions" : "Back to lists"}</button>
                <button type="button" class="btn-primary" data-xp-confirm ${send.chosen.size ? "" : "disabled"}>Send ${esc(d.snapshot.label)} to ${num(send.chosen.size)} subscriber${send.chosen.size === 1 ? "" : "s"}${fileCount !== send.chosen.size ? ` (${num(fileCount)} files)` : ""}</button>
            </div>
        </div>`;
}

/** The formats saved for a subscriber, one or more, such as CSV or CSV,XML. */
function ownFormats(value) {
    return String(value || "").split(",").map(part => part.trim()).filter(Boolean);
}

function sameFormatSet(left, right) {
    if (left.length !== right.length) return false;
    const set = new Set(right);
    return left.every(format => set.has(format));
}

function formatCell(value) {
    return `<span class="fmt-cell">${ownFormats(value).map(format => `<span class="fmt-${esc(format)}">${esc(format)}</span>`).join("")}</span>`;
}

function formatBoxes(name, selected) {
    const on = new Set(ownFormats(selected));
    return `<span class="add-formats" role="group" aria-label="Formats">${formatChoices().map(format => {
        const ticked = on.has(format);
        return `<label class="xp-fmt fmt-${esc(format)}${ticked ? " on" : ""}"><input type="checkbox" name="${name}" value="${esc(format)}" ${ticked ? "checked" : ""}> ${esc(format)}</label>`;
    }).join("")}</span>`;
}

/** The formats a subscriber will be sent: the ones ticked on this page, or their formats from Subscribers. */
function sendFormatsOf(row) {
    const picked = state.xpDialog.send.formats[row.subscriberId];
    if (Array.isArray(picked) && picked.length) return picked;
    const own = ownFormats(row.formatType);
    return own.length ? own : [row.formatType];
}

function renderDialog() {
    const main = document.getElementById("main");
    // Keep the cursor where it was when the page redraws (search boxes).
    const focused = document.activeElement && main.contains(document.activeElement) ? document.activeElement.id : null;
    const caret = focused && document.activeElement.selectionStart;
    main.innerHTML = dialogHtml();
    const again = focused && document.getElementById(focused);
    if (again) {
        again.focus();
        if (typeof caret === "number" && again.setSelectionRange) again.setSelectionRange(caret, caret);
    }
}

function closeDialog() {
    state.xpDialog = null;
    const root = document.getElementById("modal-root");
    if (root) root.innerHTML = "";
    if (state.page === "export") {
        render();
        startExportPage();
    }
}

async function openExportLatest(familyId) {
    const item = state.xp.latest.page.templates.find(entry => entry.familyId === familyId);
    state.xpDialog = { kind: "latest", step: "checking", name: item.name, title: "Export " + item.name };
    renderDialog();
    try {
        const result = await api(`/api/export-templates/${familyId}/latest`, { method: "POST" });
        if (!state.xpDialog) return;
        Object.assign(state.xpDialog, { step: "ready", snapshot: result.snapshot, created: result.created });
        renderDialog();
        loadLatest();
        await loadSendRows(null);
    } catch (error) {
        if (!state.xpDialog) return;
        Object.assign(state.xpDialog, { step: "error", error: error.message });
        renderDialog();
    }
}

async function openSendVersion(snapshotId, subscriberId) {
    const item = state.xp.prev.versions ? state.xp.prev.versions.items.find(entry => entry.id === snapshotId) : null;
    let snapshot = item;
    if (!snapshot) {
        try {
            snapshot = (await api("/api/data-versions/" + snapshotId)).snapshot;
        } catch (error) {
            toast(error.message, "error");
            return;
        }
    }
    state.xpDialog = { kind: "previous", step: "ready", snapshot, title: `Send ${snapshot.templateName} version ${snapshot.label}` };
    renderDialog();
    await loadSendRows(subscriberId);
}

/**
 * Lists every active subscriber: those on the version's template first (ticked), then all others.
 * Loaded a page of 100 at a time from the server.
 */
async function loadSendRows(onlySubscriber) {
    const d = state.xpDialog;
    d.send = { loading: true };
    renderDialog();
    try {
        if (!state.formatList) state.formatList = await api("/api/formats");
        const all = [];
        for (let offset = 0; ; offset += 100) {
            const page = await api(`/api/subscribers/search?limit=100&offset=${offset}`);
            all.push(...page.subscribers);
            if (all.length >= page.total || !page.subscribers.length) break;
        }
        if (state.xpDialog !== d) return;
        const rows = all.map(item => ({
            subscriberId: item.id, name: item.name, formatType: item.formatType, scope: item.scope, exportMode: item.exportMode,
            onTemplate: String(item.templateFamilyId) === String(d.snapshot.familyId)
        })).sort((a, b) => (b.onTemplate - a.onTemplate) || a.name.localeCompare(b.name));
        const chosen = onlySubscriber
            ? new Set(rows.some(row => row.subscriberId === onlySubscriber) ? [onlySubscriber] : [])
            : new Set(rows.filter(row => row.onTemplate).map(row => row.subscriberId));
        d.send = { rows, chosen, q: "", page: 0, formats: {}, formatList: state.formatList };
    } catch (error) {
        if (state.xpDialog !== d) return;
        d.send = { error: error.message };
    }
    renderDialog();
}

/** Counts what each chosen subscriber receives, then sends. */
async function confirmSend(button) {
    const d = state.xpDialog;
    const send = d.send;
    button.disabled = true;
    try {
        const chosen = [...send.chosen];
        const formats = {};
        send.rows.filter(row => send.chosen.has(row.subscriberId)).forEach(row => { formats[row.subscriberId] = sendFormatsOf(row).join(","); });
        const preview = await api("/api/exports/resend/preview", {
            method: "POST", body: { snapshotId: d.snapshot.id, subscriberIds: chosen, formats }
        });
        const job = await api("/api/exports/resend", {
            method: "POST",
            body: { snapshotId: d.snapshot.id, subscriberIds: chosen, reviewToken: preview.reviewToken, formats }
        });
        const records = preview.subscribers.reduce((sum, row) => sum + row.records, 0);
        const held = preview.subscribers.reduce((sum, row) => sum + row.quarantined, 0);
        state.exportMessage = `Job #${job.id} is sending ${esc(d.snapshot.templateName)} version ${esc(d.snapshot.label)} to ${num(chosen.length)} subscriber${chosen.length === 1 ? "" : "s"} (${num(records)} records${held ? `, ${num(held)} held for review` : ""}). <a href="#/history">View history</a>`;
        toast("Job #" + job.id + " started", "success");
        closeDialog();
    } catch (error) {
        toast(error.message, "error");
        button.disabled = false;
    }
}

/** From History: open the version's template under Previous Versions and the send page for one subscriber. */
async function openResend(snapshotId, subscriberId) {
    const xp = ensureXp();
    xp.tab = "previous";
    state.exportMessage = null;
    xp.pendingSend = { snapshotId: Number(snapshotId), subscriberId: subscriberId ? Number(subscriberId) : null };
    state.xpDialog = { kind: "previous", step: "checking", name: "this version", title: "Send version" };
    if (location.hash === "#/export") navigate();
    else location.hash = "#/export";
}

/** Called after the page is drawn: loads only what the open tab shows. */
async function startExportPage() {
    const xp = ensureXp();
    if (xp.tab === "latest") {
        if (!xp.latest.page) loadLatest();
        return;
    }
    if (xp.pendingSend) {
        const pending = xp.pendingSend;
        xp.pendingSend = null;
        try {
            const s = (await api("/api/data-versions/" + pending.snapshotId)).snapshot;
            const page = await api(`/api/export-templates?withVersions=true&q=${encodeURIComponent(s.templateName)}&limit=50`);
            xp.prev.page = page;
            const item = page.templates.find(entry => entry.familyId === s.familyId);
            if (item) await selectPrevTemplate(item);
            renderXp();
            await openSendVersion(pending.snapshotId, pending.subscriberId);
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    if (!xp.prev.page) loadPrevTemplates();
}

/* --- events --- */

let xpTimer;
function onSendClick(event) {
    const target = event.target;
    const exportNav = target.closest && target.closest('.nav a[data-page="export"]');
    if (exportNav && state.xpDialog && state.page === "export") {
        event.preventDefault();
        closeDialog();
        return true;
    }
    if (target.closest && target.closest("button[data-xp-close]")) {
        closeDialog();
        return true;
    }
    const tab = target.closest("[data-xp-tab]");
    if (tab) {
        ensureXp().tab = tab.dataset.xpTab;
        state.exportMessage = null;
        render();
        startExportPage();
        return true;
    }
    const exportButton = target.closest("[data-xp-export]");
    if (exportButton) {
        openExportLatest(Number(exportButton.dataset.xpExport));
        return true;
    }
    const page = target.closest("[data-xp-page]");
    if (page) {
        const [key, step] = page.dataset.xpPage.split(":");
        const view = state.xp[key];
        view.offset = Math.max(0, view.offset + Number(step) * TEMPLATE_PAGE);
        if (key === "latest") loadLatest();
        else loadPrevTemplates();
        return true;
    }
    if (target.closest("[data-xp-back-lists]")) {
        const view = ensureXp().prev;
        view.selected = null;
        view.pickedId = null;
        view.versions = null;
        renderXp();
        if (!view.page) loadPrevTemplates();
        return true;
    }
    const pick = target.closest("[data-xp-pick]");
    if (pick && !target.closest("a, button")) {
        state.xp.prev.pickedId = Number(pick.dataset.xpPick);
        renderPrevDetail();
        return true;
    }
    const template = target.closest("[data-xp-template]");
    if (template) {
        const item = state.xp.prev.page.templates.find(entry => String(entry.familyId) === template.dataset.xpTemplate);
        if (item) selectPrevTemplate(item);
        return true;
    }
    if (target.closest("[data-xp-older]")) {
        loadPrevVersions(true);
        return true;
    }
    const send = target.closest("[data-xp-send]");
    if (send) {
        openSendVersion(Number(send.dataset.xpSend), null);
        return true;
    }
    if (target.matches && target.matches("input[data-xp-sub]")) {
        const id = Number(target.dataset.xpSub);
        const chosen = state.xpDialog.send.chosen;
        if (target.checked) chosen.add(id);
        else chosen.delete(id);
        renderDialog();
        return true;
    }
    const all = target.closest("[data-xp-sub-all]");
    if (all) {
        const send = state.xpDialog.send;
        const which = all.dataset.xpSubAll;
        send.chosen = new Set(send.rows.filter(row => which === "all" || (which === "template" && row.onTemplate)).map(row => row.subscriberId));
        renderDialog();
        return true;
    }
    const subPage = target.closest("[data-xp-sub-page]");
    if (subPage) {
        state.xpDialog.send.page += Number(subPage.dataset.xpSubPage);
        renderDialog();
        return true;
    }
    const confirmButton = target.closest("[data-xp-confirm]");
    if (confirmButton) {
        confirmSend(confirmButton);
        return true;
    }
    const resend = target.closest("[data-resend-snapshot]");
    if (resend) {
        openResend(resend.dataset.resendSnapshot, resend.dataset.resendTo || null);
        return true;
    }
    return false;
}

function onSendInput(event) {
    const target = event.target;
    if (target.id === "xp-latest-search" || target.id === "xp-prev-search") {
        const view = target.id === "xp-latest-search" ? state.xp.latest : state.xp.prev;
        view.q = target.value.trim();
        view.offset = 0;
        clearTimeout(xpTimer);
        xpTimer = setTimeout(() => (target.id === "xp-latest-search" ? loadLatest() : loadPrevTemplates()), 300);
        return true;
    }
    if (target.id === "xp-prev-list-code") {
        state.xp.prev.listCode = target.value;
        state.xp.prev.pickedId = null;
        state.xp.prev.versions = null;
        renderPrevDetail();
        loadPrevVersions(false);
        return true;
    }
    if (target.id === "xp-format") {
        const send = state.xpDialog.send;
        if (!target.value) return true;
        send.rows.filter(row => send.chosen.has(row.subscriberId)).forEach(row => {
            if (target.value === "__own" || sameFormatSet([target.value], ownFormats(row.formatType))) delete send.formats[row.subscriberId];
            else send.formats[row.subscriberId] = [target.value];
        });
        renderDialog();
        return true;
    }
    if (target.dataset && target.dataset.xpRowFormat) {
        const send = state.xpDialog.send;
        const id = Number(target.dataset.xpRowFormat);
        const row = send.rows.find(item => item.subscriberId === id);
        const current = new Set(sendFormatsOf(row));
        if (target.checked) current.add(target.value);
        else current.delete(target.value);
        if (!current.size) {
            delete send.formats[id];
            send.chosen.delete(id);
        } else {
            const ownOnly = sameFormatSet([...current], ownFormats(row.formatType));
            if (ownOnly) delete send.formats[id];
            else send.formats[id] = [...current];
            send.chosen.add(id);
        }
        renderDialog();
        return true;
    }
    if (target.id === "xp-sub-search") {
        const send = state.xpDialog.send;
        send.q = target.value;
        send.page = 0;
        const caret = target.selectionStart;
        renderDialog();
        const again = document.getElementById("xp-sub-search");
        if (again) {
            again.focus();
            again.setSelectionRange(caret, caret);
        }
        return true;
    }
    return false;
}
