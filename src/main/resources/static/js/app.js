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
    previewRequest: null
};

const pages = ["dashboard", "export", "history", "subscribers", "quarantine", "templates", "approvals"];

document.getElementById("db-h2").addEventListener("click", () => explainDatabase("h2"));
document.getElementById("db-oracle").addEventListener("click", () => explainDatabase("oracle"));
window.addEventListener("hashchange", () => navigate());
document.addEventListener("click", onClick);
document.addEventListener("submit", onSubmit);
document.addEventListener("input", onInput);
document.addEventListener("keydown", event => {
    if (event.key === "Enter" && event.target.matches && event.target.matches("[data-table-filter]")) {
        event.preventDefault();
    }
});

boot();

function pageFromHash() {
    const name = (location.hash || "#/dashboard").replace("#/", "");
    return pages.includes(name) ? name : "dashboard";
}

async function navigate() {
    state.page = pageFromHash();
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
    } else if (state.page === "quarantine") {
        state.quarantine = await api("/api/quarantine?q=" + encodeURIComponent(state.search.quarantine));
    } else if (state.page === "templates") {
        const [templates, sources] = await Promise.all([
            api("/api/format-templates"),
            api("/api/format-templates/sources").catch(error => ({ error }))
        ]);
        state.templates = templates;
        state.sources = sources.error ? [] : sources;
        state.sourcesError = sources.error ? sources.error.message : null;
        if (!state.templateDraft) {
            state.templateDraft = draftFromActive();
        }
        if (state.activeTable === null || !state.sources.some(source => !source.builtIn && tableKey(source.table) === state.activeTable)) {
            const firstGs = state.sources.find(source => !source.builtIn);
            state.activeTable = firstGs ? tableKey(firstGs.table) : null;
        }
    } else if (state.page === "approvals" && state.user && state.user.role === "APPROVER") {
        state.templates = await api("/api/format-templates");
        state.subscribers = await api("/api/subscribers");
    }
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
            const after = templates.map(item => item.id + ":" + item.status + ":" + item.active).join();
            if (before !== after) {
                state.templates = templates;
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
        <td>${row.templateName ? `${esc(row.templateName)} <span class="muted">v${row.templateVersion}</span>` : '<span class="error-text">No approved template</span>'}</td>
    </tr>`).join("");
    return `<div class="detail">
        <p>${preview.fromDate ? `Delta window ${esc(preview.fromDate)} to ${esc(preview.toDate)}.` : "No date window."}</p>
        <table class="detail-table">
            <thead><tr><th>Subscriber</th><th>Scope</th><th>File</th><th>Template</th></tr></thead>
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
        <td class="error-text">${esc([subscriber.holdReason || subscriber.errorMessage || "", subscriber.acceptedBy ? "Accepted by " + subscriber.acceptedBy : ""].filter(Boolean).join(" "))}</td>
        <td class="actions">${subscriberActions(detail, subscriber)}</td>
    </tr>`).join("");
    return `<div class="detail">
        ${detail.errorMessage ? `<p class="error-text">${esc(detail.errorMessage)}</p>` : ""}
        <table class="detail-table">
            <thead><tr><th>Subscriber</th><th>Format</th><th>Status</th><th>Processed</th><th>Quarantined</th><th>File</th><th>Note</th><th></th></tr></thead>
            <tbody>${rows || '<tr><td colspan="8" class="muted">Waiting for subscribers…</td></tr>'}</tbody>
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
    return table || "List record";
}

function tablePickerHtml(draft) {
    if (state.sourcesError) {
        return `<div class="banner banner-error">${esc(state.sourcesError)}. Built-in list record columns are not shown either.</div>`;
    }
    const picked = new Set(draft.fields.map(field => fieldKey(field.sourceTable, field.sourceColumn)));
    const gsCount = state.sources.filter(source => !source.builtIn).length;
    const tables = state.sources.filter(source => !source.builtIn).map(source => {
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
                <span class="column-type">${esc(column.type || column.description || "")}</span>
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
            <span class="col-source" title="${esc(sourceLabel(field.sourceTable) + "." + field.sourceColumn)}"><span class="col-table">${esc(sourceLabel(field.sourceTable))}</span><strong>${esc(field.sourceColumn)}</strong></span>
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
        <td>${esc(template.name)}</td>
        <td class="fmt-${esc(template.formatType)}">${esc(template.formatType)}</td>
        <td>${template.versionNumber}</td>
        <td>${templateStatus(template)}</td>
        <td>${template.active ? esc((template.usedBy || []).join(", ") || "—") : ""}</td>
        <td>${esc(template.fields.map(field => field.headerName).join(", "))}</td>
    </tr>`).join("");
    const waiting = current && current.latest.status === "PENDING"
        ? `<div class="banner">Version ${current.latest.versionNumber} of ${esc(current.name)} is waiting for approval. ${current.inUse ? `Its subscribers keep version ${current.inUse.versionNumber} until it is approved.` : "Subscribers can choose it once it is approved."}</div>`
        : "";
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
                ${tablePickerHtml(draft)}
                <div class="field-label">Export columns · ${draft.fields.length} · in file order</div>
                <p class="muted hint">Each column keeps its own name in the file unless you type a new one.</p>
                <div class="field-list">${exportColumnsHtml(draft)}</div>
                <div style="margin-top:14px"><button class="btn-primary" type="submit" ${draft.fields.length ? "" : "disabled"}>${current ? "Save new version" : "Save template"}</button></div>
            </form>
        </section>
        <section class="table-card" style="margin-top:16px">
            <table>
                <thead><tr><th>Name</th><th>Format</th><th>Version</th><th>Status</th><th>Used by</th><th>Headers</th></tr></thead>
                <tbody>${saved}</tbody>
            </table>
            ${state.templates.length ? "" : '<div class="empty">No format templates yet.</div>'}
        </section>`;
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
        return { familyId: family.id, name: family.name, formatType, fields };
    }
    return { familyId: null, name: "", formatType, fields: [] };
}

function templateStatus(template) {
    if (template.status === "PENDING") return '<span class="pill pill-wait">Pending for approval</span>';
    if (template.active) return '<span class="pill pill-on">Approved · in use</span>';
    if (template.status === "APPROVED") return '<span class="pill pill-off">Approved</span>';
    return '<span class="pill pill-off">Previous</span>';
}

function approvalsHtml() {
    const pending = state.templates.filter(template => template.status === "PENDING");
    const cards = pending.map(template => {
        const tables = new Map();
        template.fields.forEach(field => {
            if (!tables.has(field.sourceTable)) tables.set(field.sourceTable, []);
            tables.get(field.sourceTable).push(field.sourceColumn);
        });
        return `<article class="approval-card">
            <div class="approval-head">
                <h2>${esc(template.name)} · version ${template.versionNumber}</h2>
                <button class="btn-primary" type="button" data-approve="${template.id}">Approve</button>
            </div>
            <p>Created by <strong>${esc(template.createdByName || template.createdBy)}</strong></p>
            <ul class="approval-tables">${[...tables.entries()].map(([table, columns]) =>
                `<li><strong>${esc(table)}</strong>: ${esc(columns.join(", "))}</li>`).join("")}</ul>
        </article>`;
    }).join("");
    return `
        ${head("Review", "Approvals", "Templates waiting for your approval.")}
        ${endpointApprovals()}
        ${pending.length ? `<div class="approval-list">${cards}</div>` : '<section class="panel"><div class="empty">No templates are waiting for approval.</div></section>'}`;
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
        `<option value="${family.id}" ${String(family.id) === String(selected) ? "selected" : ""} ${family.inUse ? "" : "disabled"}>${esc(family.name)}${family.inUse ? "" : " (waiting for approval)"}</option>`
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
        state.templateDraft.fields.splice(Number(removeField.dataset.removeField), 1);
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
                    }))
                }
            });
            toast(saved.name + " version " + saved.versionNumber + " is pending for approval", "success");
            await refresh();
            // Stay on the template just saved, so its next version can be edited straight away.
            state.templateDraft = draftFromActive(saved.formatType, saved.familyId);
            renderTemplatesKeepingFilter();
        } catch (error) {
            toast(error.message, "error");
        }
    }
}

let searchTimer;
function onInput(event) {
    if (event.target.name === "formatType" && event.target.closest("#add-form, #edit-form")) {
        const pick = event.target.form.querySelector('select[name="templateId"]');
        if (pick) pick.innerHTML = templateOptions(event.target.value, null);
        return;
    }
    if (event.target.id === "template-format") {
        state.templateDraft = draftFromActive(event.target.value);
        renderTemplatesKeepingFilter();
        return;
    }
    if (event.target.id === "template-pick") {
        state.templateDraft = draftFromActive(state.templateDraft ? state.templateDraft.formatType : "CSV", event.target.value || null);
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
    if (source.builtIn || !source.keyColumn) return;
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
    }
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
        document.getElementById("db-oracle").classList.toggle("selected", state.system.mode === "oracle");
    } catch (error) {
        /* the sidebar still shows the default H2 selection */
    }
}

function explainDatabase(mode) {
    const current = state.system ? state.system.mode : "h2";
    if (mode === current) return;
    if (mode === "oracle") {
        toast("To use Oracle, restart with the oracle profile. See docs/oracle-testing.md.", "info");
    } else {
        toast("H2 Mock is the default. Restart without the oracle profile.", "info");
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
