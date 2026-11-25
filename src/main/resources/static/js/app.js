const state = {
    page: "dashboard",
    dashboard: null,
    subscribers: [],
    jobs: null,
    /** Where the History page starts (row offset); back to 0 when its search changes. */
    listOffset: { history: 0 },
    system: null,
    search: { history: "", subscribers: "" },
    openJobId: null,
    jobDetail: null,
    templates: [],
    templateDraft: null,
    /** The tables of every group (discovered from the foreign keys), for the list builder. */
    sources: [],
    sourcesError: null,
    activeTable: null,
    tableFilter: "",
    user: null,
    templateStep: "columns",
    groupPreview: null,
    /** The open filter dialog: which column, the values offered, and the ones ticked. */
    filterEdit: null,
    /** The formats a subscriber can be given. */
    formats: null,
    /** The open send page of the Export section (null: the tabs are shown). */
    xpDialog: null,
    exportMessage: null
};

const pages = ["dashboard", "export", "history", "subscribers", "templates", "approvals"];

document.getElementById("db-h2").addEventListener("click", () => explainDatabase("h2"));
document.getElementById("db-postgres").addEventListener("click", () => explainDatabase("postgres"));
window.addEventListener("hashchange", () => navigate());
document.addEventListener("click", onClick);
document.addEventListener("submit", onSubmit);
document.addEventListener("input", onInput);
document.addEventListener("keydown", event => {
    if (event.key === "Escape" && state.filterEdit) closeFilterMenu();
    if (event.key === "Enter" && event.target.matches && event.target.matches("[data-table-filter]")) {
        event.preventDefault();
    }
});

boot();

function pageFromHash() {
    const name = (location.hash || "#/dashboard").replace("#/", "");
    if (name === "subscribers/new") return "subscriber-new";
    return pages.includes(name) ? name : "dashboard";
}

async function navigate() {
    state.page = pageFromHash();
    if (state.page !== "export") {
        state.xpDialog = null;
        state.exportMessage = null;
    }
    document.querySelectorAll(".nav a").forEach(link => {
        const current = link.dataset.page === state.page
            || (state.page === "subscriber-new" && link.dataset.page === "subscribers");
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
        if (state.page === "export" && !state.xpDialog) startExportPage();
        void main.offsetWidth;
        main.classList.add("is-entering");
        schedulePoll();
    } catch (error) {
        document.getElementById("main").innerHTML = `<div class="banner banner-error">${esc(error.message)}</div>`;
    }
}

async function refresh() {
    if (state.page === "dashboard") {
        state.dashboard = await api("/api/dashboard");
    } else if (state.page === "subscribers") {
        [state.subscribers, state.templates, state.formats] = await Promise.all([
            api("/api/subscribers?q=" + encodeURIComponent(state.search.subscribers)), api("/api/format-templates"), api("/api/subscribers/formats")]);
    } else if (state.page === "subscriber-new") {
        [state.templates, state.formats] = await Promise.all([api("/api/format-templates"), api("/api/subscribers/formats")]);
    } else if (state.page === "history") {
        state.jobs = await loadJobs();
        if (state.openJobId) state.jobDetail = await api("/api/jobs/" + state.openJobId);
    } else if (state.page === "templates") {
        const [templates, sources] = await Promise.all([
            api("/api/format-templates"),
            api("/api/format-templates/tables").catch(error => ({ error }))
        ]);
        state.templates = templates;
        state.sources = sources.error ? [] : sources;
        state.sourcesError = sources.error ? sources.error.message : null;
        if (!state.templateDraft) state.templateDraft = draftFromActive();
        if (state.activeTable === null || !state.sources.some(source => tableKey(source.table) === state.activeTable)) {
            state.activeTable = state.sources[0] ? tableKey(state.sources[0].table) : null;
        }
    } else if (state.page === "approvals" && state.user && state.user.role === "APPROVER") {
        [state.templates, state.subscribers] = await Promise.all([api("/api/format-templates"), api("/api/subscribers")]);
    }
    if (state.page === "templates" || state.page === "approvals") updateBadges(state.templates);
}

function render() {
    const main = document.getElementById("main");
    if (state.page === "dashboard") main.innerHTML = dashboardHtml();
    if (state.page === "export") main.innerHTML = exportHtml();
    if (state.page === "history") main.innerHTML = historyHtml();
    if (state.page === "subscribers") main.innerHTML = subscribersHtml();
    if (state.page === "subscriber-new") main.innerHTML = subscriberNewHtml();
    if (state.page === "templates") main.innerHTML = templatesHtml();
    if (state.page === "approvals") {
        main.innerHTML = state.user && state.user.role === "APPROVER"
            ? approvalsHtml()
            : `${head("Review", "Approvals", "Only a List Owner can open this page.")}<section class="panel"><div class="empty">Only a List Owner can open this page.</div></section>`;
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
                if (!(form && form.contains(document.activeElement))) {
                    if (state.page === "templates") renderTemplatesKeepingFilter();
                    else render();
                }
            }
        } catch (error) {
            /* keep the current screen if a refresh fails */
        }
    }
    if (state.page !== "dashboard" && state.page !== "history") return;
    try {
        const dashboard = await api("/api/dashboard");
        const busy = dashboard.inProgressJobs > 0;
        const snapshot = JSON.stringify(dashboard);
        const changed = snapshot !== state.dashboardJson;
        state.dashboard = dashboard;
        state.dashboardJson = snapshot;
        if (!changed && !busy) return;
        if (state.page === "dashboard") {
            document.getElementById("main").innerHTML = dashboardHtml();
        } else {
            state.jobs = await loadJobs();
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

/* ---------- Dashboard and History ---------- */

function dashboardHtml() {
    const data = state.dashboard || {};
    const recent = data.recentJobs || [];
    return `
        ${head("Overview", "Dashboard", "Files still to deliver, and what is running.")}
        <section class="stats">
            ${stat("Not delivered", data.undeliveredFiles, "red")}
            ${stat("In progress", data.inProgressJobs, "orange")}
            ${stat("Active subscribers", data.activeSubscribers, "green")}
            ${stat("Successful sends", data.successfulJobs, "green")}
        </section>
        <section class="panel">
            <h2>Recent activity</h2>
            ${recent.length ? `<div class="activity-head"><span>List</span><span>Status</span><span>Records</span><span>Sent by</span><span>When</span></div>${recent.map(activityRow).join("")}` : '<div class="empty">Nothing sent yet</div>'}
        </section>`;
}

function stat(label, value, tone) {
    return `<article class="stat ${tone}"><div class="stat-label">${label}</div><div class="stat-value">${num(value)}</div></article>`;
}

function activityRow(job) {
    return `<button class="activity-row" data-history="${job.id}" type="button">
        <span><strong>${esc(job.listName || "")}</strong><br><span class="muted">data version ${esc(job.dataVersionNumber)} · ${num(job.totalSubscribers)} subscriber(s)</span></span>
        ${badge(job.status)}
        <span>${num(job.totalRecords)} records</span>
        <span class="muted">${esc(job.triggeredBy || "")}</span>
        <span class="muted">${when(job.createdAt)}</span>
    </button>`;
}

const LIST_PAGE = 25;

/** One page of sends (History), for the current search. */
function loadJobs() {
    return api(`/api/jobs?q=${encodeURIComponent(state.search.history)}&offset=${state.listOffset.history}&limit=${LIST_PAGE}`);
}

/** "1–25 of 312 sends" with Previous and Next. */
function listPagerHtml(page, shown, key, noun) {
    if (!page.total) return "";
    const from = page.offset + 1;
    const to = page.offset + shown;
    return `<div class="pager xp-pager">
            <span class="muted">${num(from)}–${num(to)} of ${num(page.total)} ${noun}</span>
            <button type="button" class="btn-ghost btn-small" data-list-page="${key}:-1" ${page.offset === 0 ? "disabled" : ""}>‹ Previous</button>
            <button type="button" class="btn-ghost btn-small" data-list-page="${key}:1" ${to >= page.total ? "disabled" : ""}>Next ›</button>
        </div>`;
}

function historyHtml() {
    const page = state.jobs || { total: 0, offset: 0, jobs: [] };
    const rows = page.jobs.map(job => {
        const open = String(state.openJobId) === String(job.id);
        return `<tr class="clickable" data-job="${job.id}">
            <td>${job.id}</td>
            <td>${esc(job.listName || "")}</td>
            <td>v${esc(job.dataVersionNumber)}</td>
            <td>${badge(job.status)}</td>
            <td>${esc(job.triggeredBy || "")}</td>
            <td>${num(job.totalRecords)}</td>
            <td>${num(job.completedSubscribers)} of ${num(job.totalSubscribers)} delivered${job.failedSubscribers ? " · " + num(job.failedSubscribers) + " failed" : ""}</td>
            <td>${when(job.createdAt)}</td>
        </tr>
        ${open ? `<tr><td colspan="8">${detailHtml(state.jobDetail)}</td></tr>` : ""}`;
    }).join("");
    return `
        ${head("Jobs", "Sends", "Open a send to see each subscriber's file.", `<input class="search" data-search="history" placeholder="Search by list, sender or status" value="${esc(state.search.history)}">`)}
        <section class="table-card">
            <table>
                <thead><tr><th>ID</th><th>List</th><th>Data version</th><th>Status</th><th>Sent by</th><th>Records</th><th>Delivery</th><th>Created</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            ${page.jobs.length ? "" : '<div class="empty">Nothing sent yet.</div>'}
            ${listPagerHtml(page, page.jobs.length, "history", "sends")}
        </section>`;
}

function detailHtml(detail) {
    if (!detail) return '<div class="detail">Loading…</div>';
    const rows = (detail.subscribers || []).map(file => `<tr>
        <td>${esc(file.subscriberName)}</td>
        <td>${badge(file.status)}</td>
        <td>${num(file.records)}</td>
        <td>${file.fileName ? `<a class="file-link" href="/api/jobs/${detail.id}/files/${file.id}">${esc(file.fileName)}</a>` : "—"}</td>
        <td class="error-text">${esc(file.errorMessage || "")}</td>
        <td class="actions">${fileActions(detail, file)}</td>
    </tr>`).join("");
    return `<div class="detail">
        ${detail.errorMessage ? `<p class="error-text">${esc(detail.errorMessage)}</p>` : ""}
        <table class="detail-table">
            <thead><tr><th>Subscriber</th><th>Status</th><th>Records</th><th>File</th><th>Note</th><th></th></tr></thead>
            <tbody>${rows || '<tr><td colspan="6" class="muted">Waiting for subscribers…</td></tr>'}</tbody>
        </table>
    </div>`;
}

function fileActions(detail, file) {
    const buttons = [];
    if (file.status === "FAILED") {
        buttons.push(`<button class="btn-edit" type="button" data-retry="${detail.id}:${file.id}">Retry</button>`);
    }
    if (file.archiveFileName) {
        buttons.push(`<button class="btn-ghost" type="button" data-proof="${detail.id}:${file.id}">Check proof</button>`);
    }
    return buttons.join(" ");
}

/* ---------- Subscribers ---------- */

const SUBSCRIBER_SCOPES = ["ALL", "SANCTIONS", "WORLDCHECK", "PFA"];
const SUBSCRIBER_MODES = ["FULL", "DELTA"];

/** The formats a subscriber can be given (loaded from the server; XML is the one the module writes). */
function formatChoices() {
    return state.formats && state.formats.length ? state.formats : ["XML"];
}

/** A subscriber's formats, one or more, such as XML or CSV,XML. */
function ownFormats(value) {
    return String(value || "").split(",").map(part => part.trim()).filter(Boolean);
}

function formatCell(value) {
    return `<span class="fmt-cell">${ownFormats(value).map(format => `<span class="fmt-${esc(format)}">${esc(format)}</span>`).join("")}</span>`;
}

function formatBoxes(name, selected) {
    const on = new Set(ownFormats(selected));
    return `<span class="add-formats" role="group" aria-label="Formats">${formatChoices().map(format => {
        const ticked = on.has(format) || (!on.size && format === formatChoices()[0]);
        return `<label class="xp-fmt fmt-${esc(format)}${ticked ? " on" : ""}"><input type="checkbox" name="${name}" value="${esc(format)}" ${ticked ? "checked" : ""}> ${esc(format)}</label>`;
    }).join("")}</span>`;
}

function select(name, values, selected) {
    return `<select name="${name}">${values.map(value => `<option ${value === selected ? "selected" : ""}>${esc(value)}</option>`).join("")}</select>`;
}

const SUBSCRIBER_NOTE = "Format, mode, scope, business line and region are recorded for each subscriber. Today every subscriber receives the whole list, every record of every group, as an XML file.";

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
            <button class="btn-delete" type="button" data-delete="${subscriber.id}">Delete</button>
        </td>
    </tr>`).join("");
    const tools = `<div class="page-tools">
            <input class="search" data-search="subscribers" placeholder="Search subscribers..." value="${esc(state.search.subscribers)}">
            <a class="btn-primary" href="#/subscribers/new">Add subscriber</a>
        </div>`;
    return `
        ${head("Recipients", "Subscribers", "Who receives a file, in which formats, and where it is delivered.", tools)}
        <section class="table-card">
            <table>
                <thead><tr><th>ID</th><th>Name</th><th>Format</th><th>Mode</th><th>Scope</th><th>Business line</th><th>Region</th><th>Delivery</th><th>List</th><th>Last file</th><th>Active</th><th></th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
            ${state.subscribers.length ? "" : '<div class="empty">No subscribers found.</div>'}
        </section>`;
}

function subscriberNewHtml() {
    return `
        ${head("Recipients", "Add subscriber", "Choose the formats, the list, and where the file is delivered.", `<a class="btn-ghost" href="#/subscribers">Back to subscribers</a>`)}
        <section class="export-card subscriber-card">
            <form id="add-form" class="subscriber-form">
                <div class="subscriber-grid">
                    <label class="span-2">Subscriber name<input name="name" placeholder="Subscriber name" required autofocus></label>
                    <div class="field">
                        <span class="field-label">Formats</span>
                        ${formatBoxes("formatType", formatChoices()[0])}
                    </div>
                    <label>List<select name="templateId" required>${templateOptions(null)}</select></label>
                    <label>Scope${select("scope", SUBSCRIBER_SCOPES, "ALL")}</label>
                    <label>Mode${select("exportMode", SUBSCRIBER_MODES, "FULL")}</label>
                    <label>Business line<input name="businessLine" placeholder="Business line"></label>
                    <label>Region<input name="region" placeholder="Region"></label>
                    <label class="span-2">Delivery endpoint<input name="cftEndpoint" placeholder="Delivery endpoint"></label>
                    <p class="muted span-2" style="margin:0">${SUBSCRIBER_NOTE}</p>
                </div>
                <div class="modal-actions">
                    <a class="btn-ghost" href="#/subscribers">Cancel</a>
                    <button class="btn-primary" type="submit">Add subscriber</button>
                </div>
            </form>
        </section>`;
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
                <label>Scope<select name="scope">${options(SUBSCRIBER_SCOPES, subscriber.scope)}</select></label>
                <label>Mode<select name="exportMode">${options(SUBSCRIBER_MODES, subscriber.exportMode)}</select></label>
                <label>Business line<input name="businessLine" value="${esc(subscriber.businessLine || "")}"></label>
                <label>Region<input name="region" value="${esc(subscriber.region || "")}"></label>
                <label>Delivery endpoint<input name="cftEndpoint" value="${esc(subscriber.cftEndpoint || "")}"></label>
                <label class="check"><input type="checkbox" name="active" ${subscriber.active ? "checked" : ""}> Active</label>
                <p class="muted small-note">${SUBSCRIBER_NOTE}</p>
                <div class="modal-actions">
                    <button class="btn-ghost" id="edit-cancel" type="button">Cancel</button>
                    <button class="btn-primary" type="submit">Save</button>
                </div>
            </form>
        </div>`;
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

/** One entry per list: its name, the version in use (if approved) and the newest version. */
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

/** List choices for a subscriber: only lists with an approved version can be picked. */
function templateOptions(selected) {
    const families = templateFamilies();
    if (!families.length) return '<option value="">No list yet</option>';
    return `<option value="" ${selected ? "" : "selected"}>Choose a list…</option>` + families.map(family =>
        `<option value="${family.id}" ${String(family.id) === String(selected) ? "selected" : ""} ${family.inUse ? "" : "disabled"}>${esc(family.name)}${family.inUse ? "" : " (" + ({ PENDING: "waiting for approval", CHANGES_REQUESTED: "changes requested", REJECTED: "rejected" }[family.latest.status] || "not approved") + ")"}</option>`
    ).join("");
}

function badge(status) {
    return `<span class="badge badge-${esc(status)}">${esc(label(status))}</span>`;
}

function label(status) {
    return ({ PENDING: "Pending", IN_PROGRESS: "In progress", COMPLETED: "Completed", FAILED: "Failed", SUCCESS: "Success" })[status] || status || "";
}


/* ---------- Create a List: columns from the table groups, a preview per group ---------- */

function tableKey(table) {
    return table ? String(table).toLowerCase() : "";
}

function fieldKey(table, column) {
    return tableKey(table) + "." + String(column).toLowerCase();
}

function sourceOf(table) {
    return state.sources.find(source => tableKey(source.table) === tableKey(table));
}

function groupRoot(group) {
    return state.sources.find(source => source.group === group && source.root);
}

function groupOfField(field) {
    const source = sourceOf(field.sourceTable);
    return source ? source.group : null;
}

/** The group's common key: always on the list while the group is used, and cannot be removed. */
function isGroupKey(field) {
    const source = sourceOf(field.sourceTable);
    return !!(source && source.root && String(field.sourceColumn).toLowerCase() === String(source.groupKey).toLowerCase());
}

function groupUsed(group) {
    return state.templateDraft.fields.some(field => groupOfField(field) === group);
}

/** Puts the group's key on the list, in front of the group's first column, when it is missing. */
function ensureGroupKey(group) {
    const root = groupRoot(group);
    if (!root) return;
    const fields = state.templateDraft.fields;
    if (fields.some(field => fieldKey(field.sourceTable, field.sourceColumn) === fieldKey(root.table, root.groupKey))) return;
    const key = { sourceTable: root.table, sourceColumn: root.groupKey, headerName: root.groupKey };
    const at = fields.findIndex(field => groupOfField(field) === group);
    if (at < 0) fields.push(key); else fields.splice(at, 0, key);
}

/** Removes the group's key once no other column of the group is left. */
function dropGroupKeyIfAlone(group) {
    const fields = state.templateDraft.fields;
    if (fields.some(field => groupOfField(field) === group && !isGroupKey(field))) return;
    const at = fields.findIndex(field => groupOfField(field) === group && isGroupKey(field));
    if (at >= 0) fields.splice(at, 1);
}

function tablePickerHtml(draft) {
    if (state.sourcesError) return `<div class="banner banner-error">${esc(state.sourcesError)}.</div>`;
    const picked = new Set(draft.fields.map(field => fieldKey(field.sourceTable, field.sourceColumn)));
    const groups = [];
    state.sources.forEach(source => {
        let group = groups.find(item => item.name === source.group);
        if (!group) groups.push(group = { name: source.group, tables: [] });
        group.tables.push(source);
    });
    const list = groups.map(group => {
        const root = group.tables.find(source => source.root);
        const buttons = group.tables.map(source => {
            const key = tableKey(source.table);
            const count = source.columns.filter(column => picked.has(fieldKey(source.table, column.name))).length;
            return `<button type="button" class="table-option ${source.root ? "root" : "related"} ${key === state.activeTable ? "active" : ""}" data-pick-table="${esc(key)}" data-table-name="${esc((group.name + " " + source.table).toLowerCase())}">
                <span class="table-name">${esc(source.table)}</span>
                ${count ? `<span class="table-count">${count}</span>` : ""}
            </button>`;
        }).join("");
        return `<div class="table-group">
            <div class="table-group-label"><strong>${esc(group.name)}</strong> <span class="muted">· ${group.tables.length} table${group.tables.length === 1 ? "" : "s"} · key <code>${esc(root ? root.groupKey : "")}</code></span></div>
            ${buttons}
        </div>`;
    }).join("");
    const active = state.sources.find(source => tableKey(source.table) === state.activeTable);
    let columns = '<div class="empty">Choose a table of a group.</div>';
    if (active) {
        const used = groupUsed(active.group);
        const allPicked = active.columns.every(column => picked.has(fieldKey(active.table, column.name)));
        const headHtml = `<div class="columns-head">
            <div>
                <h3>${esc(active.table)} <span class="group-tag">${esc(active.group)}</span></h3>
                <p class="muted">${active.root
                    ? `Root table of ${esc(active.group)}: one record per row. Its key <code>${esc(active.groupKey)}</code> identifies each record and is always on the list.`
                    : `Joined to ${esc(active.group)} where <code>${esc(active.keyColumn)}</code> = <code>${esc(active.joinsTo)}</code>.`}</p>
            </div>
            <button type="button" class="btn-ghost" data-pick-all="${allPicked ? "none" : "all"}">${allPicked ? "Clear all" : "Select all"}</button>
        </div>`;
        const items = active.columns.map(column => {
            const locked = active.root && String(column.name).toLowerCase() === String(active.groupKey).toLowerCase() && used;
            return `<label class="column-option ${locked ? "locked" : ""}" ${locked ? 'title="The group\'s key: always on the list while the group is used"' : ""}>
                <input type="checkbox" data-pick-column="${esc(column.name)}" ${picked.has(fieldKey(active.table, column.name)) ? "checked" : ""} ${locked ? "disabled" : ""}>
                <span class="column-name">${esc(column.name)}${locked ? ' <span class="key-tag">key</span>' : ""}</span>
                <span class="column-type">${esc(column.type || "")}</span>
            </label>`;
        }).join("");
        columns = headHtml + `<div class="column-list">${items || '<div class="empty">This table has no columns.</div>'}</div>`;
    }
    return `<div class="picker">
        <div class="picker-tables">
            <div class="field-label">Table groups · ${groups.length} group${groups.length === 1 ? "" : "s"}</div>
            <input class="table-filter" data-table-filter placeholder="Filter tables">
            <div class="table-list">${list || '<p class="muted">No table group found. A group is a table with a one-column primary key and no foreign key, plus the tables whose foreign key points at it.</p>'}</div>
        </div>
        <div class="picker-columns">${columns}</div>
    </div>`;
}

function exportColumnsHtml(draft) {
    if (!draft.fields.length) return '<div class="empty">No columns yet. Tick columns in a table above to add them.</div>';
    return draft.fields.map((field, index) => {
        const renamed = field.headerName !== field.sourceColumn;
        const group = groupOfField(field);
        const key = isGroupKey(field);
        return `<div class="field-row export-col ${key ? "key-col" : ""}">
            <span class="col-order">${index + 1}</span>
            <span class="col-source" title="${esc(field.sourceTable + "." + field.sourceColumn)}">${group ? `<span class="group-tag">${esc(group)}</span>` : ""}<span class="col-table">${esc(field.sourceTable)}</span><strong>${esc(field.sourceColumn)}</strong></span>
            <span class="muted">→</span>
            <input data-draft-header="${index}" value="${esc(field.headerName)}" placeholder="${esc(field.sourceColumn)}" aria-label="Export name for ${esc(field.sourceColumn)}">
            <button class="btn-ghost btn-small" type="button" data-reset-header="${index}" ${renamed ? "" : "disabled"} title="Use the column name as the export name">Keep original</button>
            <button class="btn-ghost btn-icon" type="button" data-move="${index}:-1" ${index === 0 ? "disabled" : ""} aria-label="Move up">↑</button>
            <button class="btn-ghost btn-icon" type="button" data-move="${index}:1" ${index === draft.fields.length - 1 ? "disabled" : ""} aria-label="Move down">↓</button>
            ${key ? '<span class="key-tag" title="The group\'s key: identifies each record; removed with the group\'s last column">key</span>' : `<button class="btn-delete" type="button" data-remove-field="${index}">Remove</button>`}
            ${(field.filterValues || []).length ? `<div class="filter-summary">Filtered, keeps only: ${esc(field.filterValues.slice(0, 6).join(", "))}${field.filterValues.length > 6 ? " … +" + (field.filterValues.length - 6) : ""}</div>` : ""}
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
        <td>${filtersText(template) ? esc(filtersText(template)) : '<span class="muted">—</span>'}</td>
        <td><a class="btn-ghost btn-small" href="/api/format-templates/${template.id}/xml" download title="The list's current data, every group, as XML">XML</a></td>
    </tr>`).join("");
    let waiting = "";
    if (current && current.latest.status === "PENDING") {
        waiting = `<div class="banner">Version ${current.latest.versionNumber} of ${esc(current.name)} is waiting for approval. ${current.inUse ? `Its subscribers keep version ${current.inUse.versionNumber} until it is approved.` : "Subscribers can choose it once it is approved."} Saving again replaces it in the List Owner's queue.</div>`;
    } else if (current && current.latest.status === "CHANGES_REQUESTED") {
        waiting = `<div class="review-callout warn"><strong>${esc(current.latest.reviewedByName || "A List Owner")} asked for changes to version ${current.latest.versionNumber}:</strong> “${esc(current.latest.reviewComment || "")}”<br>The editor below holds version ${current.latest.versionNumber}. Make the changes and save to resubmit it as version ${current.latest.versionNumber + 1}.</div>`;
    } else if (current && current.latest.status === "REJECTED") {
        waiting = `<div class="review-callout bad"><strong>Version ${current.latest.versionNumber} was rejected</strong> by ${esc(current.latest.reviewedByName || "a List Owner")}: “${esc(current.latest.reviewComment || "")}”<br>${current.inUse ? `Version ${current.inUse.versionNumber} stays in use.` : ""} Saving starts a new version for approval.</div>`;
    }
    const onPreview = state.templateStep === "data" && draft.fields.length;
    return `
        ${head("Formats", "Create a List", "Pick columns from the table groups (Person, Entity…). Each group's key is always included; its related tables are joined by the foreign keys declared in the database.")}
        <section class="export-card template-card">
            <h2>${current ? "Edit " + esc(current.name) : "New list"}</h2>
            <p class="muted">${current
                ? `Saving creates version ${current.latest.versionNumber + 1} for approval. Its subscribers keep the version in use until a List Owner accepts it.`
                : "Saving sends version 1 for approval. Once approved, subscribers can receive it."}</p>
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
                ${onPreview ? `<div id="group-preview">${groupPreviewHtml()}</div>` : `
                ${tablePickerHtml(draft)}
                <div class="field-label">Export columns · ${draft.fields.length} · in file order</div>
                <p class="muted hint">Each column keeps its own name in the file unless you type a new one.</p>
                <div class="field-list">${exportColumnsHtml(draft)}</div>`}
                <div class="builder-foot">
                    <span class="muted" id="builder-summary">${builderSummary(draft)}</span>
                    <span class="spacer"></span>
                    ${onPreview
                        ? '<button type="button" class="btn-ghost" data-step="columns">← Back to columns</button>'
                        : `<button type="button" class="btn-ghost" data-step="data" ${draft.fields.length ? "" : "disabled"}>Preview each group →</button>`}
                    <button class="btn-primary" type="submit" ${draft.fields.length ? "" : "disabled"}>${current ? "Save new version" : "Save list"}</button>
                </div>
            </form>
            ${current ? `<details class="template-history"><summary>History of ${esc(current.name)}</summary>${templateHistoryHtml(current.id, null)}</details>` : ""}
        </section>
        <section class="table-card" style="margin-top:16px">
            <table>
                <thead><tr><th>Name</th><th>Version</th><th>Status</th><th>Used by</th><th>Headers</th><th>Filters</th><th>File</th></tr></thead>
                <tbody>${saved}</tbody>
            </table>
            ${state.templates.length ? "" : '<div class="empty">No lists yet.</div>'}
        </section>`;
}

function groupsOf(draft) {
    return new Set(draft.fields.map(groupOfField).filter(Boolean));
}

function builderSummary(draft) {
    const groups = groupsOf(draft);
    return num(draft.fields.length) + " column" + (draft.fields.length === 1 ? "" : "s") + " · " + (groups.size ? [...groups].join(", ") : "no group yet");
}

function stepperHtml(draft) {
    const onData = state.templateStep === "data" && draft.fields.length;
    return `<div class="stepper" role="tablist" aria-label="List steps">
        <button type="button" role="tab" class="step ${onData ? "" : "active"}" aria-selected="${onData ? "false" : "true"}" data-step="columns">
            <span class="step-no">1</span><span><strong>Columns</strong><small>${num(draft.fields.length)} picked</small></span>
        </button>
        <span class="step-line" aria-hidden="true"></span>
        <button type="button" role="tab" class="step ${onData ? "active" : ""}" aria-selected="${onData ? "true" : "false"}" data-step="data" ${draft.fields.length ? "" : "disabled"}>
            <span class="step-no">2</span><span><strong>Preview per group</strong><small>${num(groupsOf(draft).size)} group(s)</small></span>
        </button>
    </div>`;
}

/** A draft to edit: the newest version of an existing list, or an empty new list. */
function draftFromActive(familyId = null) {
    const family = familyId ? templateFamilies().find(item => String(item.id) === String(familyId)) : null;
    if (!family) return { familyId: null, name: "", fields: [] };
    return {
        familyId: family.id,
        name: family.name,
        fields: family.latest.fields.map(field => ({ sourceTable: field.sourceTable, sourceColumn: field.sourceColumn, headerName: field.headerName, filterValues: [...(field.filterValues || [])] }))
    };
}

function templateStatus(template) {
    const comment = template.reviewComment && (template.status === "CHANGES_REQUESTED" || template.status === "REJECTED")
        ? `<div class="row-rule" title="${esc(template.reviewedByName || "")}">“${esc(template.reviewComment)}”</div>` : "";
    return statusPill(template) + comment;
}

/* One preview per group: the key, the chosen columns, the first records. */
async function loadGroupPreview() {
    const draft = state.templateDraft;
    if (!draft || !draft.fields.length) return;
    const seq = (state.groupPreview && state.groupPreview.seq || 0) + 1;
    state.groupPreview = { seq, loading: true, groups: state.groupPreview ? state.groupPreview.groups : [] };
    renderGroupPreview();
    try {
        const groups = await api("/api/format-templates/preview", {
            method: "POST",
            body: { fields: draftFields(draft), limit: 20 }
        });
        if (seq !== state.groupPreview.seq) return;
        state.groupPreview = { seq, loading: false, groups };
    } catch (error) {
        if (seq !== state.groupPreview.seq) return;
        state.groupPreview = { seq, loading: false, groups: [], error: error.message };
    }
    renderGroupPreview();
}

function renderGroupPreview() {
    const host = document.getElementById("group-preview");
    if (host) host.innerHTML = groupPreviewHtml();
}

function groupPreviewHtml() {
    const view = state.groupPreview;
    if (!view || (view.loading && !view.groups.length)) return '<div class="queue-empty">Loading the preview of each group…</div>';
    if (view.error) return `<div class="banner banner-error">${esc(view.error)}</div>`;
    if (!view.groups.length) return '<div class="empty">Pick columns of a group to preview it.</div>';
    return filterBarHtml() + view.groups.map((group, index) => {
        const arrow = (table, columnName) => {
            const at = draftIndexOf(table, columnName);
            if (at < 0) return "";
            const on = (state.templateDraft.fields[at].filterValues || []).length;
            return ` <button type="button" class="th-filter ${on ? "on" : ""}" data-th-filter="${at}" title="${on ? "Filtered: " + esc(state.templateDraft.fields[at].filterValues.join(", ")) : "Filter this column"}">${on ? "▾ " + on : "▾"}</button>`;
        };
        const headRow = `<th title="The group's key">${esc(group.keyColumn)} <span class="key-tag">key</span>${arrow(group.rootTable, group.keyColumn)}</th>`
            + group.columns.map(column => `<th title="${esc(column.table + "." + column.column)}${column.root ? "" : " (related table: every value of the record)"}">${esc(column.header)}${column.root ? "" : ' <span class="muted">· ' + esc(column.table) + "</span>"}${arrow(column.table, column.column)}</th>`).join("");
        const rows = group.rows.map(row => `<tr><td><strong>${esc(row.key)}</strong></td>${row.values.map(value => `<td>${value ? esc(value) : '<span class="muted">—</span>'}</td>`).join("")}</tr>`).join("");
        return `<section class="group-preview">
            <div class="group-preview-head">
                <h3>Preview ${index + 1}: ${esc(group.group)}</h3>
                <span class="muted">${num(group.total)} record${group.total === 1 ? "" : "s"} in ${esc(group.rootTable)}${group.total > group.rows.length ? " · first " + num(group.rows.length) + " shown" : ""}</span>
            </div>
            <div class="table-scroll"><table>
                <thead><tr>${headRow}</tr></thead>
                <tbody>${rows || `<tr><td colspan="${group.columns.length + 1}" class="muted">No records.</td></tr>`}</tbody>
            </table></div>
        </section>`;
    }).join("");
}

function draftFields(draft) {
    return draft.fields.map(field => ({
        sourceTable: field.sourceTable,
        sourceColumn: field.sourceColumn,
        headerName: (field.headerName || "").trim() || field.sourceColumn,
        filterValues: field.filterValues || []
    }));
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
    const fields = state.templateDraft.fields;
    const index = fields.findIndex(field => fieldKey(field.sourceTable, field.sourceColumn) === fieldKey(source.table, column));
    if (!on && isGroupKey({ sourceTable: source.table, sourceColumn: column })
        && fields.some(field => groupOfField(field) === source.group && !isGroupKey(field))) {
        return; // the key stays while the group has other columns
    }
    if (on && index < 0) {
        fields.push({ sourceTable: source.table, sourceColumn: column, headerName: column });
        ensureGroupKey(source.group);
    } else if (!on && index >= 0) {
        fields.splice(index, 1);
        dropGroupKeyIfAlone(source.group);
    }
}

function applyTableFilter(value) {
    const needle = String(value || "").trim().toLowerCase();
    state.tableFilter = value;
    document.querySelectorAll("[data-pick-table]").forEach(button => {
        button.hidden = needle !== "" && !button.dataset.tableName.includes(needle);
    });
}

/**
 * Draws the List page again without losing the user's place: the table list, the column list and the page keep their scroll
 * position, and the table filter keeps its text. {@code newTable}: a different table was opened, so its columns start at the top.
 */
function renderTemplatesKeepingFilter(newTable = false) {
    const tableList = document.querySelector(".table-list");
    const columnList = document.querySelector(".column-list");
    const tablesAt = tableList ? tableList.scrollTop : 0;
    const columnsAt = columnList && !newTable ? columnList.scrollTop : 0;
    const pageAt = window.scrollY;
    render();
    const filter = document.querySelector("[data-table-filter]");
    if (filter && state.tableFilter) {
        filter.value = state.tableFilter;
        applyTableFilter(state.tableFilter);
    }
    const tablesAgain = document.querySelector(".table-list");
    if (tablesAgain) tablesAgain.scrollTop = tablesAt;
    const columnsAgain = document.querySelector(".column-list");
    if (columnsAgain) columnsAgain.scrollTop = columnsAt;
    window.scrollTo(0, pageAt);
}


/* ---------- Filters: Excel-style, on the column headers of the group previews ---------- */

/** "city: Mumbai, Delhi · country: FR" for a list version, or "" when it has no filter. */
function filtersText(template) {
    return template.fields.filter(field => (field.filterValues || []).length)
        .map(field => field.headerName + ": " + field.filterValues.join(", ")).join(" · ");
}

/** The place of a column in the list being edited (-1 when it is not on it). */
function draftIndexOf(table, column) {
    return state.templateDraft.fields.findIndex(field => tableKey(field.sourceTable) === tableKey(table)
        && String(field.sourceColumn).toLowerCase() === String(column).toLowerCase());
}

/** The chips above the previews: each active filter, to remove one by one. */
function filterBarHtml() {
    const active = state.templateDraft.fields.map((field, index) => ({ field, index })).filter(item => (item.field.filterValues || []).length);
    if (!active.length) return '<p class="muted filter-hint">Click ▾ on a column header to keep only some of its values.</p>';
    return `<div class="filter-bar"><span class="muted">Filters:</span>${active.map(({ field, index }) =>
        `<span class="filter-chip" title="${esc(field.sourceTable + "." + field.sourceColumn)}"><b>${esc(field.headerName || field.sourceColumn)}</b> = ${esc(field.filterValues.slice(0, 4).join(", "))}${field.filterValues.length > 4 ? " +" + (field.filterValues.length - 4) : ""}<button type="button" data-filter-remove="${index}" aria-label="Remove filter">×</button></span>`).join("")}
        <button type="button" class="btn-ghost btn-small" data-filter-remove="all">Clear all filters</button></div>`;
}

function closeFilterMenu() {
    state.filterEdit = null;
    const menu = document.getElementById("filter-menu");
    if (menu) menu.remove();
}

/** Opens the value list under a column header, like the arrow of an Excel table. */
async function openFilterMenu(index, anchor) {
    readDraftFromForm();
    const field = state.templateDraft.fields[index];
    if (!field) return;
    closeFilterMenu();
    state.filterEdit = { index, selected: new Set(field.filterValues || []), values: [], truncated: false, q: "", loading: true, error: null };
    const menu = document.createElement("div");
    menu.id = "filter-menu";
    menu.className = "filter-menu";
    menu.innerHTML = `
        <div class="filter-menu-title">${esc(field.headerName || field.sourceColumn)} <span class="muted">${esc(field.sourceTable)}</span></div>
        <input class="search" data-filter-search placeholder="Search" autocomplete="off">
        <div class="filter-links"><button type="button" data-filter-all>Select all shown</button><button type="button" data-filter-clear>Clear</button></div>
        <div id="filter-list"></div>
        <div class="filter-menu-actions"><button type="button" class="btn-ghost btn-small" data-filter-cancel>Cancel</button><button type="button" class="btn-primary btn-small" data-filter-apply>OK</button></div>`;
    document.body.appendChild(menu);
    const box = anchor.getBoundingClientRect();
    menu.style.top = Math.min(box.bottom + 4, window.innerHeight - 40) + "px";
    menu.style.left = Math.max(8, Math.min(box.left, window.innerWidth - 280)) + "px";
    renderFilterList();
    menu.querySelector("[data-filter-search]").focus();
    await loadFilterValues("");
}

/** The distinct values the column holds (or those containing the search text), from the data. */
async function loadFilterValues(q) {
    const edit = state.filterEdit;
    if (!edit) return;
    const field = state.templateDraft.fields[edit.index];
    edit.q = q;
    edit.loading = true;
    const seq = (edit.seq || 0) + 1;
    edit.seq = seq;
    try {
        const result = await api(`/api/format-templates/column-values?table=${encodeURIComponent(field.sourceTable)}&column=${encodeURIComponent(field.sourceColumn)}&q=${encodeURIComponent(q)}`);
        if (state.filterEdit !== edit || seq !== edit.seq) return;
        edit.values = result.values;
        edit.truncated = result.truncated;
        edit.error = null;
    } catch (error) {
        if (state.filterEdit !== edit || seq !== edit.seq) return;
        edit.error = error.message;
    }
    edit.loading = false;
    renderFilterList();
}

function filterCountText(edit) {
    return num(edit.selected.size) + " ticked" + (edit.truncated ? " · more values exist: search to find them" : "");
}

function renderFilterList() {
    const edit = state.filterEdit;
    const host = document.getElementById("filter-list");
    if (!edit || !host) return;
    // The ticked values stay on the list even when a search hides them.
    const shown = [...new Set([...edit.selected, ...edit.values])].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    host.innerHTML = edit.error ? `<div class="banner banner-error">${esc(edit.error)}</div>`
        : edit.loading && !shown.length ? '<div class="queue-empty">Reading the values…</div>'
        : `<div class="filter-count muted">${filterCountText(edit)}</div>
           <div class="filter-values">${shown.map(value => `<label class="column-option"><input type="checkbox" data-filter-value="${esc(value)}" ${edit.selected.has(value) ? "checked" : ""}><span class="column-name">${esc(value)}</span></label>`).join("") || '<div class="empty">No value.</div>'}</div>`;
}

function applyFilterChange() {
    renderTemplatesKeepingFilter();
    loadGroupPreview();
}

/** Clicks on the filter arrows, the open list, and the filter chips. Returns true when the click was one of them. */
function onFilterClick(target) {
    const arrow = target.closest("[data-th-filter]");
    if (arrow) {
        const wasOpen = state.filterEdit && String(state.filterEdit.index) === arrow.dataset.thFilter;
        if (wasOpen) closeFilterMenu(); else openFilterMenu(Number(arrow.dataset.thFilter), arrow);
        return true;
    }
    const remove = target.closest("[data-filter-remove]");
    if (remove) {
        readDraftFromForm();
        if (remove.dataset.filterRemove === "all") state.templateDraft.fields.forEach(field => { field.filterValues = []; });
        else state.templateDraft.fields[Number(remove.dataset.filterRemove)].filterValues = [];
        applyFilterChange();
        return true;
    }
    const edit = state.filterEdit;
    if (!edit) return false;
    if (!target.closest("#filter-menu")) {   // a click anywhere else closes the list without applying, like Excel
        closeFilterMenu();
        return false;
    }
    if (target.matches && target.matches("input[data-filter-value]")) {
        if (target.checked) edit.selected.add(target.dataset.filterValue); else edit.selected.delete(target.dataset.filterValue);
        const count = document.querySelector(".filter-count");
        if (count) count.textContent = filterCountText(edit);
        return true;
    }
    if (target.closest("[data-filter-all]")) {
        edit.values.forEach(value => edit.selected.add(value));
        renderFilterList();
        return true;
    }
    if (target.closest("[data-filter-clear]")) {
        edit.selected.clear();
        renderFilterList();
        return true;
    }
    if (target.closest("[data-filter-cancel]")) {
        closeFilterMenu();
        return true;
    }
    if (target.closest("[data-filter-apply]")) {
        state.templateDraft.fields[edit.index].filterValues = [...edit.selected];
        closeFilterMenu();
        applyFilterChange();
        return true;
    }
    return false;
}


/* ---------- Events ---------- */

async function onClick(event) {
    const target = event.target;
    if (state.page === "templates" && onFilterClick(target)) return;
    if (state.page === "export" && onExportClick(target)) return;
    if (state.page === "approvals" && onReviewClick(event)) return;
    const historyButton = target.closest("[data-history]");
    if (historyButton) {
        state.listOffset.history = 0;
        state.openJobId = historyButton.dataset.history;
        location.hash = "#/history";
        return;
    }
    const listPage = target.closest("[data-list-page]");
    if (listPage) {
        const [key, step] = listPage.dataset.listPage.split(":");
        state.listOffset[key] = Math.max(0, state.listOffset[key] + Number(step) * LIST_PAGE);
        try {
            state.jobs = await loadJobs();
            render();
            document.getElementById("main").scrollIntoView({ block: "start" });
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    const jobRow = target.closest("[data-job]");
    if (jobRow && !target.closest("a")) {
        const id = jobRow.dataset.job;
        state.openJobId = String(state.openJobId) === id ? null : id;
        if (state.openJobId) state.jobDetail = await api("/api/jobs/" + id);
        render();
        return;
    }
    const retry = target.closest("[data-retry]");
    if (retry) {
        const [jobId, fileId] = retry.dataset.retry.split(":");
        try {
            state.jobDetail = await api(`/api/jobs/${jobId}/files/${fileId}/retry`, { method: "POST" });
            state.jobs = await loadJobs();
            toast("File sent again", "success");
            render();
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    const proof = target.closest("[data-proof]");
    if (proof) {
        const [jobId, fileId] = proof.dataset.proof.split(":");
        try {
            const result = await api(`/api/jobs/${jobId}/files/${fileId}/proof`, { method: "POST" });
            toast(result.result + ". " + result.message, result.result === "MATCH" ? "success" : "error");
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    const endpoint = target.closest("[data-endpoint]");
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
    const edit = target.closest("[data-edit]");
    if (edit) {
        openEdit(state.subscribers.find(subscriber => String(subscriber.id) === edit.dataset.edit));
        return;
    }
    const remove = target.closest("[data-delete]");
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
    if (target.id === "edit-cancel" || target.id === "modal-back") {
        document.getElementById("modal-root").innerHTML = "";
        return;
    }
    if (onListBuilderClick(target)) return;
    const fill = target.closest(".demo-accounts button");
    if (fill) {
        const form = document.getElementById("login-form");
        form.username.value = fill.dataset.username;
        form.password.value = fill.dataset.password;
        form.username.focus();
        return;
    }
    if (target.id === "logout") {
        await api("/api/auth/logout", { method: "POST" });
        showLogin();
    }
}

/** Clicks on Create a List. Returns true when the click was one of its controls. */
function onListBuilderClick(target) {
    const step = target.closest("[data-step]");
    if (step) {
        if (step.disabled) return true;
        readDraftFromForm();
        state.templateStep = step.dataset.step;
        renderTemplatesKeepingFilter();
        if (state.templateStep === "data") loadGroupPreview();
        return true;
    }
    const pickTable = target.closest("[data-pick-table]");
    if (pickTable) {
        readDraftFromForm();
        state.activeTable = pickTable.dataset.pickTable;
        renderTemplatesKeepingFilter(true);
        return true;
    }
    if (target.matches && target.matches("input[data-pick-column]")) {
        readDraftFromForm();
        toggleColumn(target.dataset.pickColumn, target.checked);
        renderTemplatesKeepingFilter();
        return true;
    }
    const pickAll = target.closest("[data-pick-all]");
    if (pickAll) {
        readDraftFromForm();
        const source = state.sources.find(item => tableKey(item.table) === state.activeTable);
        if (source) source.columns.forEach(column => toggleColumn(column.name, pickAll.dataset.pickAll === "all"));
        renderTemplatesKeepingFilter();
        return true;
    }
    const resetHeader = target.closest("[data-reset-header]");
    if (resetHeader) {
        readDraftFromForm();
        const field = state.templateDraft.fields[Number(resetHeader.dataset.resetHeader)];
        if (field) field.headerName = field.sourceColumn;
        renderTemplatesKeepingFilter();
        return true;
    }
    const move = target.closest("[data-move]");
    if (move) {
        readDraftFromForm();
        const [index, stepBy] = move.dataset.move.split(":").map(Number);
        const fields = state.templateDraft.fields;
        const to = index + stepBy;
        if (to >= 0 && to < fields.length) [fields[index], fields[to]] = [fields[to], fields[index]];
        renderTemplatesKeepingFilter();
        return true;
    }
    const removeField = target.closest("[data-remove-field]");
    if (removeField) {
        readDraftFromForm();
        const [removed] = state.templateDraft.fields.splice(Number(removeField.dataset.removeField), 1);
        if (removed && groupOfField(removed)) dropGroupKeyIfAlone(groupOfField(removed));
        renderTemplatesKeepingFilter();
        return true;
    }
    return false;
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
            state.user = await api("/api/auth/login", { method: "POST", body: { username: form.get("username"), password: form.get("password") } });
            enterApp(true);
        } catch (loginError) {
            error.textContent = loginError.message;
            error.hidden = false;
        }
        return;
    }
    if (formId === "add-form" || formId === "edit-form") {
        event.preventDefault();
        const form = new FormData(event.target);
        const adding = formId === "add-form";
        try {
            await api(adding ? "/api/subscribers" : "/api/subscribers/" + form.get("id"), {
                method: adding ? "POST" : "PUT",
                body: {
                    name: form.get("name"),
                    formatType: form.getAll("formatType").join(","),
                    scope: form.get("scope"),
                    exportMode: form.get("exportMode"),
                    businessLine: form.get("businessLine"),
                    region: form.get("region"),
                    cftEndpoint: form.get("cftEndpoint"),
                    templateId: Number(form.get("templateId")) || null,
                    active: adding ? true : form.get("active") === "on"
                }
            });
            if (adding) {
                toast("Subscriber added", "success");
                state.search.subscribers = "";
                if (location.hash === "#/subscribers") navigate(); else location.hash = "#/subscribers";
            } else {
                document.getElementById("modal-root").innerHTML = "";
                toast("Subscriber updated", "success");
                await refresh();
                render();
            }
        } catch (error) {
            toast(error.message, "error");
        }
        return;
    }
    if (formId === "template-form") {
        event.preventDefault();
        readDraftFromForm();
        const draft = state.templateDraft;
        const clash = !draft.familyId && templateFamilies().find(family => family.name.toLowerCase() === (draft.name || "").trim().toLowerCase());
        if (clash) {
            toast(`A list named "${clash.name}" already exists. Pick it in the list menu to save a new version of it.`, "error");
            return;
        }
        try {
            const saved = await api("/api/format-templates", { method: "POST", body: { name: draft.name, fields: draftFields(draft) } });
            toast(saved.name + " version " + saved.versionNumber + " is pending for approval", "success");
            await refresh();
            // Stay on the list just saved, so its next version can be edited straight away.
            state.templateDraft = draftFromActive(saved.familyId);
            renderTemplatesKeepingFilter();
            if (state.templateStep === "data") loadGroupPreview();
        } catch (error) {
            toast(error.message, "error");
        }
    }
}

let searchTimer;
function onInput(event) {
    const target = event.target;
    if (target.matches && target.matches("[data-filter-search]")) {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => loadFilterValues(target.value), 250);
        return;
    }
    if (state.page === "export" && onExportInput(target)) return;
    if (state.page === "approvals" && onReviewInput(event)) return;
    if (target.id === "template-pick") {
        state.templateDraft = draftFromActive(target.value || null);
        state.templateStep = "columns";
        renderTemplatesKeepingFilter();
        return;
    }
    if (target.matches("[data-table-filter]")) {
        applyTableFilter(target.value);
        return;
    }
    if (target.matches("[data-draft-header]") && state.templateDraft) {
        const index = Number(target.dataset.draftHeader);
        const field = state.templateDraft.fields[index];
        if (field) {
            field.headerName = target.value;
            const reset = document.querySelector(`[data-reset-header="${index}"]`);
            if (reset) reset.disabled = target.value === field.sourceColumn;
        }
        return;
    }
    if (target.closest("#template-form") && target.name === "name" && state.templateDraft) {
        state.templateDraft.name = target.value;
        return;
    }
    const field = target.closest("[data-search]");
    if (!field) return;
    state.search[field.dataset.search] = field.value;
    if (field.dataset.search in state.listOffset) state.listOffset[field.dataset.search] = 0;
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


/* ---------- Session, database switch and helpers ---------- */

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
            `<button type="button" data-username="${esc(account.username)}" data-password="${esc(account.password)}">${esc(account.role)}</button>`
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
    document.getElementById("signed-role").textContent = state.user.role === "APPROVER" ? "List Owner" : "List Manager";
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
        const error = state.system.switchError;
        let shown = null;
        try { shown = sessionStorage.getItem("db-switch-error"); } catch (ignored) { /* storage may be blocked */ }
        if (error && shown !== error) {
            toast(error + " The module is on H2.", "error");
            try { sessionStorage.setItem("db-switch-error", error); } catch (ignored) { /* storage may be blocked */ }
        }
    } catch (error) {
        /* the sidebar still shows the default H2 selection */
    }
}

/** The sidebar's Database switch: H2 or PostgreSQL. Switching restarts the module on the chosen database. */
function explainDatabase(mode) {
    const system = state.system || { mode: "h2" };
    if (mode === system.mode) return;
    if (!system.switchable) {
        toast("The database is fixed for this run: the module was started with a profile. Start it without one to use this switch.", "info");
        return;
    }
    const root = document.getElementById("modal-root");
    if (mode === "h2") {
        root.innerHTML = `
            <div class="modal-back" data-db-close>
                <form class="modal" id="db-form">
                    <h2>Switch to H2?</h2>
                    <p class="muted">The module restarts on the built-in H2 demo database, in about 15 seconds. You then sign in again.
                        Your PostgreSQL data stays where it is; switch back any time.</p>
                    <div class="modal-actions">
                        <button type="button" class="btn-ghost" data-db-close>Cancel</button>
                        <button type="submit" class="btn-primary">Switch to H2</button>
                    </div>
                </form>
            </div>`;
    } else if (!system.postgresConfigured) {
        root.innerHTML = `
            <div class="modal-back" data-db-close>
                <div class="modal" id="db-form">
                    <h2>Set up the PostgreSQL connection</h2>
                    <p class="muted">The connection is read from a file in the project folder, so no password is typed here:</p>
                    <ol class="db-steps">
                        <li>Open <code>config/database.properties</code> in the project folder.</li>
                        <li>Fill in host, port, database, username and password.</li>
                        <li>Click <strong>PostgreSQL</strong> again.</li>
                    </ol>
                    <div class="modal-actions">
                        <button type="button" class="btn-primary" data-db-close>OK</button>
                    </div>
                </div>
            </div>`;
    } else {
        root.innerHTML = `
            <div class="modal-back" data-db-close>
                <form class="modal" id="db-form">
                    <h2>Switch to PostgreSQL?</h2>
                    <p>Database <strong>${esc(system.postgresDatabase)}</strong> on <strong>${esc(system.postgresHost)}:${esc(String(system.postgresPort))}</strong>, user <strong>${esc(system.postgresUsername)}</strong>.</p>
                    <p class="muted">The module restarts on it in about 20 seconds (longer the first time, while a dump in db/dumps is imported). You then sign in again.</p>
                    <p class="muted small-note">From config/database.properties. To connect elsewhere, edit that file.</p>
                    <div class="modal-actions">
                        <button type="button" class="btn-ghost" data-db-close>Cancel</button>
                        <button type="submit" class="btn-primary">Switch to PostgreSQL</button>
                    </div>
                </form>
            </div>`;
    }
    root.querySelectorAll("[data-db-close]").forEach(element => element.addEventListener("click", event => {
        if (event.target === element) root.innerHTML = "";
    }));
    const form = document.getElementById("db-form");
    const first = form.querySelector("button[type=submit]") || form.querySelector("button");
    if (first) first.focus();
    if (form.tagName !== "FORM") {
        form.querySelectorAll("[data-db-close]").forEach(button => button.addEventListener("click", () => { root.innerHTML = ""; }));
        return;
    }
    form.addEventListener("submit", async event => {
        event.preventDefault();
        const button = form.querySelector("button[type=submit]");
        button.disabled = true;
        const body = { mode };
        button.textContent = mode === "h2" ? "Switching…" : "Checking the connection…";
        try {
            const result = await api("/api/system/database", { method: "POST", body });
            root.innerHTML = `
                <div class="modal-back">
                    <div class="modal db-restarting">
                        <h2>${mode === "h2" ? "Restarting on H2" : "Restarting on PostgreSQL"}</h2>
                        <p class="muted">${esc(result.message)}</p>
                        <div class="db-spinner" aria-hidden="true"></div>
                    </div>
                </div>`;
            waitForRestart();
        } catch (error) {
            toast(error.message, "error");
            button.disabled = false;
            button.textContent = mode === "h2" ? "Switch to H2" : "Switch to PostgreSQL";
        }
    });
}

/** Waits until the module has stopped and answers again, then reloads the page (to the sign-in screen). */
async function waitForRestart() {
    const started = Date.now();
    let wentDown = false;
    while (Date.now() - started < 15 * 60 * 1000) {
        await new Promise(resolve => setTimeout(resolve, 1500));
        try {
            const response = await fetch("/api/auth/config", { cache: "no-store" });
            if (response.ok && (wentDown || Date.now() - started > 8000)) {
                location.reload();
                return;
            }
            if (!response.ok) wentDown = true;
        } catch (error) {
            wentDown = true;
        }
    }
    toast("The module has not come back yet. Check the Run window, then reload the page.", "error");
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
            `<li><strong>${esc(table)}</strong>: ${esc(fields.map(field => field.sourceColumn + ((field.filterValues || []).length ? " [only " + field.filterValues.join(", ") + "]" : "")).join(", "))}</li>`).join("");
        const actions = own
            ? '<span class="muted">You submitted this; another List Owner must review it.</span>'
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
                <label>${esc(copy.label)}<textarea name="comment" rows="4" maxlength="2000" required minlength="10" placeholder="At least 10 characters. The List Manager will see this."></textarea></label>
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
        const words = { approve: "approved", reject: "rejected", "request-changes": "sent back to the List Manager" }[action];
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


/* ---------- Export page: Export Latest, Previous Versions, Synthesis ----------
 *
 * Export Latest: take a data version of a list now (or reuse the newest one when nothing changed), then send it.
 * Previous Versions: a list's stored data versions, each downloadable as XML or sendable.
 * Synthesis: what changed between two data versions, per group, table, column and record.
 * Every subscriber receives the whole version, as XML.
 */

const SYNTHESIS_PAGE = 50;

function xp() {
    if (!state.xp) {
        state.xp = {
            tab: "latest",
            lists: null,          // lists with their newest data version
            subscribers: null,    // active subscribers, for the send panel
            familyId: null,       // Previous Versions: the list shown
            versions: null,
            busy: false,
            send: null,           // the send panel: { version, chosen: Set, review, busy }
            syn: null             // Synthesis: { familyId, versions, after, before, summary, group, change, q, offset, page, detail, seq }
        };
    }
    return state.xp;
}

function exportHtml() {
    if (state.xpDialog) return dialogHtml();
    const view = xp();
    const tab = (name, text) => `<button type="button" role="tab" class="xp-tab ${view.tab === name ? "active" : ""}" aria-selected="${view.tab === name}" data-xp-tab="${name}">${text}</button>`;
    return `
        ${head("Delivery", "Export", "Export the latest data of a list, or any of its earlier versions, and compare versions.")}
        ${state.exportMessage ? `<div class="banner">${state.exportMessage}</div>` : ""}
        <nav class="xp-tabs" role="tablist">${tab("latest", "Export Latest")}${tab("previous", "Previous Versions")}${tab("synthesis", "Synthesis")}</nav>
        <div id="xp-body">${xpBodyHtml()}</div>`;
}

function xpBodyHtml() {
    const view = xp();
    if (view.tab === "previous") return previousHtml();
    if (view.tab === "synthesis") return synthesisHtml();
    return latestHtml();
}

function renderXp() {
    const body = document.getElementById("xp-body");
    if (state.xpDialog) renderDialog();
    else if (body) body.innerHTML = xpBodyHtml();
}

async function startExportPage() {
    const view = xp();
    if (!view.lists) await loadLists();
    if (view.tab === "previous" && !view.familyId && view.lists.length) view.familyId = view.lists[0].familyId;
    if (view.tab === "previous" && view.familyId && !view.versions) {
        view.versions = await api("/api/data-versions?familyId=" + view.familyId).catch(() => []);
    }
    renderXp();
}

/** Every list (its newest version) with its newest data version. */
async function loadLists() {
    const view = xp();
    try {
        const [templates, versions] = await Promise.all([api("/api/format-templates"), api("/api/data-versions")]);
        const families = new Map();
        templates.forEach(template => {
            const known = families.get(template.familyId);
            if (!known || template.versionNumber > known.versionNumber) families.set(template.familyId, template);
        });
        view.lists = [...families.values()].sort((a, b) => a.name.localeCompare(b.name)).map(template => {
            const own = versions.filter(version => version.familyId === template.familyId);
            return { familyId: template.familyId, name: template.name, versions: own.length, latest: own[0] || null };
        });
    } catch (error) {
        view.lists = [];
        toast(error.message, "error");
    }
}

function latestHtml() {
    const view = xp();
    if (!view.lists) return '<section class="table-card xp-card"><div class="queue-empty">Loading lists…</div></section>';
    const rows = view.lists.map(list => `<tr>
            <td><strong>${esc(list.name)}</strong></td>
            <td class="muted">${list.latest ? "v" + list.latest.number + " · " + when(list.latest.createdAt) + " · " + num(list.latest.records) + " records" : "No data version yet"}</td>
            <td class="actions"><button type="button" class="btn-primary btn-small" data-xp-export="${list.familyId}">Export</button></td>
        </tr>`).join("");
    return `<section class="table-card xp-card">
            <p class="muted" style="padding:12px 16px 0">Export reads the list's current data from its table groups and stores it as a data version (or reuses the newest one when nothing changed), then lets you tick the subscribers who receive it.</p>
            <table><thead><tr><th>List</th><th>Newest data version</th><th></th></tr></thead>
            <tbody>${rows || '<tr><td colspan="3" class="muted">No list yet. Create one on the List page.</td></tr>'}</tbody></table>
        </section>`;
}

function previousHtml() {
    const view = xp();
    if (!view.lists) return '<section class="table-card xp-card"><div class="queue-empty">Loading lists…</div></section>';
    const picker = `<select data-xp-family aria-label="List">${view.lists.map(list => `<option value="${list.familyId}" ${list.familyId === view.familyId ? "selected" : ""}>${esc(list.name)}</option>`).join("")}</select>`;
    const rows = (view.versions || []).map(version => `<tr>
            <td><strong>v${version.number}</strong></td>
            <td class="muted">${when(version.createdAt)}${version.createdBy ? " · " + esc(version.createdBy) : ""}</td>
            <td>${groupCounts(version)}</td>
            <td class="actions">
                <a class="btn-ghost btn-small" href="/api/data-versions/${version.id}/xml" download>XML</a>
                <button type="button" class="btn-primary btn-small" data-xp-send="${version.id}">Send</button>
            </td>
        </tr>`).join("");
    return `<section class="table-card xp-card">
            <div class="xp-toolbar">${picker}</div>
            ${view.versions === null ? '<div class="queue-empty">Loading versions…</div>' : `<table>
                <thead><tr><th>Version</th><th>Stored</th><th>Records</th><th></th></tr></thead>
                <tbody>${rows || '<tr><td colspan="4" class="muted">No data version yet. Use Export Latest to take one.</td></tr>'}</tbody>
            </table>`}
        </section>`;
}

function groupCounts(version) {
    return Object.entries(version.groups || {}).map(([group, count]) => esc(group) + " " + num(count)).join(" · ");
}

/** The send panel: who receives the version (ticked: the subscribers of its list), the review, then Send. */
const SEND_PAGE = 10;

/** The send page: the version that was prepared, then every active subscriber to tick, then one Send button. */
function dialogHtml() {
    const d = state.xpDialog;
    if (!d) return "";
    const previous = d.kind === "previous";
    const backLabel = previous ? "Back to versions" : "Back to lists";
    let lede = "Tick the subscribers who receive this version, or download it.";
    if (d.step === "checking") lede = "Reading the current data and preparing a version.";
    if (d.step === "error") lede = "This export could not be prepared.";
    let body;
    if (d.step === "checking") {
        body = `<div class="xp-checking"><span class="spinner" aria-hidden="true"></span> Checking ${esc(d.name || "this list")} for data changes…</div>`;
    } else if (d.step === "error") {
        body = `<div class="banner banner-error">${esc(d.error)}</div>`;
    } else {
        const v = d.version;
        const outcome = d.kind === "latest"
            ? (d.created && v.number === 1
                ? `<div class="review-callout good"><strong>First export of this list.</strong> Data version ${v.number} was created.</div>`
                : d.created
                ? `<div class="review-callout good"><strong>The data changed.</strong> New data version ${v.number} was created.</div>`
                : `<div class="review-callout"><strong>No change in the data.</strong> The existing data version ${v.number} is exported again.</div>`)
            : "";
        body = `${outcome}
            <div class="xp-version-card">
                <span class="ver-chip big">v${v.number}</span>
                <span>${num(v.records)} records · ${groupCounts(v)} · created ${when(v.createdAt)}</span>
            </div>
            <div class="xp-download"><span>Download</span><a class="btn-ghost btn-small" href="/api/data-versions/${v.id}/xml" download>XML</a></div>
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
    const onList = rows.filter(row => row.onList);
    const matching = rows.filter(row => !send.q || row.name.toLowerCase().includes(send.q.toLowerCase()));
    const page = matching.slice(send.page * SEND_PAGE, (send.page + 1) * SEND_PAGE);
    const pages = Math.max(1, Math.ceil(matching.length / SEND_PAGE));
    const list = page.map(row => {
        const on = send.chosen.has(row.id);
        return `<div class="xp-sub ${on ? "selected" : ""}">
            <input type="checkbox" id="xp-sub-${row.id}" data-xp-sub="${row.id}" ${on ? "checked" : ""}>
            <label class="xp-sub-name" for="xp-sub-${row.id}">${esc(row.name)}</label>
            <span class="muted">${esc(row.scope)} · ${esc(row.exportMode)} · ${row.onList ? '<span class="tag tag-new">This list</span>' : esc(row.templateName || "No list")}</span>
            <span class="xp-sub-format">${formatCell(row.formatType)}</span>
        </div>`;
    }).join("");
    const allChosen = rows.length && send.chosen.size === rows.length;
    const v = d.version;
    return `<div class="xp-send">
            <div class="xp-send-head">
                <strong>Send to subscribers</strong>
                <span class="muted">${num(send.chosen.size)} of ${num(rows.length)} selected</span>
            </div>
            <p class="xp-format-note">Each subscriber receives the whole version as one XML file. ${SUBSCRIBER_NOTE.split(". ")[0]}.</p>
            <div class="xp-quick">
                <button type="button" class="btn-ghost btn-small ${allChosen ? "on" : ""}" data-xp-sub-all="all">Select all (${num(rows.length)})</button>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-all="list" ${onList.length ? "" : "disabled"}>Only this list's (${num(onList.length)})</button>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-all="none" ${send.chosen.size ? "" : "disabled"}>Clear</button>
            </div>
            <div class="xp-send-options">
                <label class="xp-format">Find a subscriber
                    <input class="search" id="xp-sub-search" placeholder="Name" value="${esc(send.q)}" autocomplete="off" aria-label="Find a subscriber">
                </label>
            </div>
            <div class="xp-subs">${list || '<div class="muted xp-add-none">No subscriber matches.</div>'}</div>
            ${pages > 1 ? `<div class="pager"><span class="muted">Page ${send.page + 1} of ${pages} · ${num(matching.length)} subscribers</span>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-page="-1" ${send.page === 0 ? "disabled" : ""}>‹</button>
                <button type="button" class="btn-ghost btn-small" data-xp-sub-page="1" ${send.page + 1 >= pages ? "disabled" : ""}>›</button></div>` : ""}
            ${send.chosen.size ? `<div class="muted xp-send-summary">${num(send.chosen.size)} XML file${send.chosen.size === 1 ? "" : "s"} of ${num(v.records)} records</div>` : ""}
            <div class="modal-actions">
                <button type="button" class="btn-ghost" data-xp-close>${d.kind === "previous" ? "Back to versions" : "Back to lists"}</button>
                <button type="button" class="btn-primary" data-xp-confirm ${send.chosen.size && !send.busy ? "" : "disabled"}>${send.busy ? "Sending…" : `Send v${v.number} to ${num(send.chosen.size)} subscriber${send.chosen.size === 1 ? "" : "s"}`}</button>
            </div>
        </div>`;
}

function renderDialog() {
    const main = document.getElementById("main");
    // Keep the cursor where it was when the page redraws (the search box).
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
    if (state.page === "export") {
        render();
        startExportPage();
    }
}

/** Export: reads the list's current data into a data version (or reuses the newest), then opens the send page. */
async function openExportLatest(familyId) {
    const view = xp();
    const list = view.lists.find(entry => entry.familyId === familyId);
    const d = { kind: "latest", step: "checking", name: list.name, title: "Export " + list.name };
    state.xpDialog = d;
    state.exportMessage = null;
    renderDialog();
    try {
        const taken = await api("/api/data-versions?familyId=" + familyId, { method: "POST" });
        if (state.xpDialog !== d) return;
        Object.assign(d, { step: "ready", version: taken.version, created: taken.created });
        view.lists = null;
        view.versions = null;
        renderDialog();
        await loadSendRows();
    } catch (error) {
        if (state.xpDialog !== d) return;
        Object.assign(d, { step: "error", error: error.message });
        renderDialog();
    }
}

/** Previous Versions → Send: opens the send page for a stored version. */
async function openSendVersion(versionId) {
    const view = xp();
    const version = (view.versions || []).find(entry => entry.id === versionId);
    if (!version) return;
    state.xpDialog = { kind: "previous", step: "ready", version, title: `Send ${version.listName} data version ${version.number}` };
    state.exportMessage = null;
    renderDialog();
    await loadSendRows();
}

/** Every active subscriber: those on the version's list first (ticked), then the others. */
async function loadSendRows() {
    const d = state.xpDialog;
    d.send = { loading: true };
    renderDialog();
    try {
        if (!state.formats) state.formats = await api("/api/subscribers/formats");
        const all = (await api("/api/subscribers")).filter(item => item.active);
        if (state.xpDialog !== d) return;
        const rows = all.map(item => ({
            id: item.id, name: item.name, formatType: item.formatType, scope: item.scope, exportMode: item.exportMode,
            templateName: item.templateName, onList: item.templateFamilyId === d.version.familyId
        })).sort((a, b) => (b.onList - a.onList) || a.name.localeCompare(b.name));
        d.send = { rows, chosen: new Set(rows.filter(row => row.onList).map(row => row.id)), q: "", page: 0, busy: false };
    } catch (error) {
        if (state.xpDialog !== d) return;
        d.send = { error: error.message };
    }
    renderDialog();
}

/** Reviews what the ticked subscribers get (nothing is sent yet), then sends it: one click for the user. */
async function confirmSend() {
    const d = state.xpDialog;
    const send = d.send;
    send.busy = true;
    renderDialog();
    try {
        const ids = [...send.chosen];
        const review = await api(`/api/data-versions/${d.version.id}/send/review`, { method: "POST", body: { subscriberIds: ids } });
        const job = await api(`/api/data-versions/${d.version.id}/send`, { method: "POST", body: { subscriberIds: ids, reviewToken: review.reviewToken } });
        state.exportMessage = `Send #${job.id} is sending ${esc(d.version.listName)} data version ${d.version.number} to ${num(ids.length)} subscriber${ids.length === 1 ? "" : "s"} (${num(d.version.records)} records each). <a href="#/history">View history</a>`;
        toast("Send " + job.id + " started", "success");
        closeDialog();
    } catch (error) {
        toast(error.message, "error");
        send.busy = false;
        if (state.xpDialog === d) renderDialog();
    }
}

/** Clicks on the Export page. Returns true when the click was one of its controls. */
function onExportClick(target) {
    const handled = "[data-xp-tab],[data-xp-export],[data-xp-send],[data-xp-close],[data-xp-sub],[data-xp-sub-all],[data-xp-sub-page],"
        + "[data-xp-confirm],[data-syn-list],[data-syn-back],[data-syn-take],[data-syn-group],[data-syn-change],[data-syn-page],"
        + "[data-syn-record],[data-syn-close]";
    if (!target.closest(handled)) return false;
    handleExportClick(target);
    return true;
}

async function handleExportClick(target) {
    const view = xp();
    const tab = target.closest("[data-xp-tab]");
    if (tab) {
        view.tab = tab.dataset.xpTab;
        render();
        startExportPage();
        return;
    }
    const exportButton = target.closest("[data-xp-export]");
    if (exportButton) return openExportLatest(Number(exportButton.dataset.xpExport));
    const sendButton = target.closest("[data-xp-send]");
    if (sendButton) return openSendVersion(Number(sendButton.dataset.xpSend));
    if (target.closest("[data-xp-close]")) return closeDialog();
    const send = state.xpDialog && state.xpDialog.send;
    if (send && send.rows) {
        const box = target.closest("[data-xp-sub]");
        if (box) {
            const id = Number(box.dataset.xpSub);
            if (box.checked) send.chosen.add(id); else send.chosen.delete(id);
            return renderDialog();
        }
        const all = target.closest("[data-xp-sub-all]");
        if (all) {
            const mode = all.dataset.xpSubAll;
            send.chosen = new Set(mode === "all" ? send.rows.map(row => row.id) : mode === "list" ? send.rows.filter(row => row.onList).map(row => row.id) : []);
            return renderDialog();
        }
        const pager = target.closest("[data-xp-sub-page]");
        if (pager) {
            send.page = Math.max(0, send.page + Number(pager.dataset.xpSubPage));
            return renderDialog();
        }
        if (target.closest("[data-xp-confirm]") && !send.busy) return confirmSend();
    }
    onSynthesisClick(target);
}

/** Inputs on the Export page. Returns true when the input was one of its controls. */
function onExportInput(target) {
    if (target.id === "xp-sub-search" && state.xpDialog && state.xpDialog.send) {
        state.xpDialog.send.q = target.value;
        state.xpDialog.send.page = 0;
        renderDialog();
        return true;
    }
    if (target.matches("[data-xp-family]")) {
        const view = xp();
        view.familyId = Number(target.value);
        view.versions = null;
        renderXp();
        api("/api/data-versions?familyId=" + view.familyId).catch(() => []).then(versions => {
            view.versions = versions;
            renderXp();
        });
        return true;
    }
    return onSynthesisInput(target);
}

/* --- Synthesis --- */

function syn() {
    const view = xp();
    if (!view.syn) {
        view.syn = { familyId: null, versions: [], after: null, before: null, summary: null, group: null, change: "CHANGES", q: "",
            offset: 0, page: null, detail: null, busy: false, error: null, seq: 0 };
    }
    return view.syn;
}

async function selectSynthesisList(familyId) {
    Object.assign(syn(), { familyId, versions: [], after: null, before: null, summary: null, group: null, page: null, detail: null,
        offset: 0, change: "CHANGES", q: "", error: null });
    renderXp();
    await loadSynthesisVersions();
}

async function loadSynthesisVersions() {
    const s = syn();
    s.versions = await api("/api/data-versions?familyId=" + s.familyId);
    s.after = s.versions[0] ? s.versions[0].id : null;
    s.before = s.versions[1] ? s.versions[1].id : null;
    renderXp();
    await loadSynthesisSummary();
}

async function takeSynthesisVersion() {
    const s = syn();
    s.busy = true;
    renderXp();
    try {
        const taken = await api("/api/data-versions?familyId=" + s.familyId, { method: "POST" });
        toast(taken.created ? "Data version " + taken.version.number + " stored (" + num(taken.version.records) + " records)"
            : "The data has not changed since version " + taken.version.number + ": nothing new was stored", taken.created ? "success" : "info");
        xp().lists = null;
        await loadLists();
        await loadSynthesisVersions();
    } catch (error) {
        toast(error.message, "error");
    }
    s.busy = false;
    renderXp();
}

async function loadSynthesisSummary() {
    const s = syn();
    Object.assign(s, { summary: null, page: null, detail: null, error: null });
    if (!s.after || !s.before || s.after === s.before) return renderXp();
    const seq = ++s.seq;
    renderXp();
    try {
        const summary = await api(`/api/data-versions/${s.after}/compare/${s.before}/summary`);
        if (seq !== s.seq) return;
        s.summary = summary;
        if (!s.group || !summary.groups.some(group => group.group === s.group)) s.group = summary.groups[0] ? summary.groups[0].group : null;
        s.offset = 0;
        renderXp();
        await loadSynthesisRecords();
    } catch (error) {
        if (seq !== s.seq) return;
        s.error = error.message;
        renderXp();
    }
}

async function loadSynthesisRecords() {
    const s = syn();
    if (!s.summary || !s.group) return;
    const seq = ++s.seq;
    try {
        const page = await api(`/api/data-versions/${s.after}/compare/${s.before}/records?group=${encodeURIComponent(s.group)}`
            + `&change=${s.change}&q=${encodeURIComponent(s.q)}&offset=${s.offset}&limit=${SYNTHESIS_PAGE}`);
        if (seq !== s.seq) return;
        s.page = page;
    } catch (error) {
        s.page = { total: 0, offset: 0, records: [], error: error.message };
    }
    const host = document.getElementById("syn-records");
    if (host) host.innerHTML = synthesisRecordsHtml(); else renderXp();
}

async function openSynthesisRecord(key) {
    const s = syn();
    try {
        s.detail = await api(`/api/data-versions/${s.after}/compare/${s.before}/record?group=${encodeURIComponent(s.group)}&key=${encodeURIComponent(key)}`);
    } catch (error) {
        return toast(error.message, "error");
    }
    renderXp();
    const panel = document.getElementById("syn-detail");
    if (panel) panel.scrollIntoView({ block: "start", behavior: "smooth" });
}

function synthesisHtml() {
    const view = xp();
    const s = syn();
    if (!s.familyId) {
        if (!view.lists) return '<section class="table-card xp-card"><div class="queue-empty">Loading lists…</div></section>';
        const rows = view.lists.map(list => `<tr class="clickable" data-syn-list="${list.familyId}">
                <td><strong>${esc(list.name)}</strong></td>
                <td>${list.versions ? num(list.versions) + " data version" + (list.versions === 1 ? "" : "s") : '<span class="muted">None yet</span>'}</td>
                <td class="muted">${list.latest ? "v" + list.latest.number + " · " + when(list.latest.createdAt) : "—"}</td>
            </tr>`).join("");
        return `<section class="table-card xp-card">
                <p class="muted" style="padding:12px 16px 0">Compare two data versions of a list: what changed in each group, each table and each record.</p>
                <table><thead><tr><th>List</th><th>Data versions</th><th>Newest</th></tr></thead>
                <tbody>${rows || '<tr><td colspan="3" class="muted">No list yet. Create one on the List page.</td></tr>'}</tbody></table>
            </section>`;
    }
    const list = (view.lists || []).find(item => item.familyId === s.familyId) || { name: "" };
    const options = selected => s.versions.map(version => `<option value="${version.id}" ${version.id === selected ? "selected" : ""}>v${version.number} · ${when(version.createdAt)} · ${num(version.records)} records</option>`).join("");
    const headHtml = `<section class="table-card xp-card syn-head">
            <div class="syn-title">
                <h2>${esc(list.name)}</h2>
                <span class="muted">${num(s.versions.length)} data version${s.versions.length === 1 ? "" : "s"}</span>
                <span class="spacer"></span>
                <button type="button" class="btn-primary" data-syn-take ${s.busy ? "disabled" : ""}>${s.busy ? "Reading the data…" : "Take a version now"}</button>
            </div>
            ${s.versions.length >= 2 ? `<div class="syn-pick">
                <label>Before <select data-syn-before>${options(s.before)}</select></label>
                <span class="muted">→</span>
                <label>After <select data-syn-after>${options(s.after)}</select></label>
                <a class="btn-ghost btn-small" href="/api/data-versions/${s.before}/xml" download>XML before</a>
                <a class="btn-ghost btn-small" href="/api/data-versions/${s.after}/xml" download>XML after</a>
            </div>` : `<p class="muted">${s.versions.length
                ? "One data version so far. Take another one after the data changes, then compare them here."
                : "No data version yet. Take one now; take another after the data changes, then compare them here."}</p>`}
        </section>`;
    return `<button type="button" class="btn-ghost xp-back-link" data-syn-back>Back to lists</button>
        ${headHtml}
        ${s.before && s.after && s.before === s.after ? '<div class="banner">Choose two different versions.</div>' : ""}
        ${s.error ? `<div class="banner banner-error">${esc(s.error)}</div>` : ""}
        ${synthesisSummaryHtml()}`;
}

function synthesisSummaryHtml() {
    const s = syn();
    if (!s.summary) return s.versions.length >= 2 && s.before !== s.after ? '<div class="queue-empty">Comparing…</div>' : "";
    const cards = s.summary.groups.map(group => `<button type="button" class="syn-card ${group.group === s.group ? "active" : ""}" data-syn-group="${esc(group.group)}">
            <span class="syn-card-name">${esc(group.group)}</span>
            <span class="syn-card-count">${num(group.before)} → ${num(group.after)}</span>
            <span class="syn-chips">
                <span class="chip added">+${num(group.added)} added</span>
                <span class="chip removed">−${num(group.removed)} removed</span>
                <span class="chip changed">~${num(group.changed)} changed</span>
                <span class="chip same">${num(group.unchanged)} unchanged</span>
            </span>
        </button>`).join("");
    const group = s.summary.groups.find(item => item.group === s.group);
    let body = "";
    if (group) {
        const tables = group.tables.map(table => `<tr>
                <td>${table.root ? "<strong>" + esc(table.label) + "</strong> <span class=\"muted\">(own values)</span>" : esc(table.label)} <span class="muted">· ${esc(table.table)}</span></td>
                <td>${num(table.records)}</td>
                <td>${table.root ? '<span class="muted">—</span>' : (table.rowsAdded ? '<span class="chip added">+' + num(table.rowsAdded) + "</span>" : "0")}</td>
                <td>${table.root ? '<span class="muted">—</span>' : (table.rowsRemoved ? '<span class="chip removed">−' + num(table.rowsRemoved) + "</span>" : "0")}</td>
                <td>${table.valuesChanged ? '<span class="chip changed">~' + num(table.valuesChanged) + "</span>" : "0"}</td>
            </tr>`).join("");
        const columns = group.columns.slice(0, 12).map(column => `<span class="chip changed" title="${esc(column.table)}">${esc(column.column)} · ${num(column.changed)}</span>`).join(" ");
        const layout = [
            group.columnsAdded.length ? "Columns added: " + esc(group.columnsAdded.join(", ")) : "",
            group.columnsRemoved.length ? "Columns removed: " + esc(group.columnsRemoved.join(", ")) : ""
        ].filter(Boolean).join(" · ");
        body = `${layout ? `<div class="banner">${layout}</div>` : ""}
            <section class="table-card xp-card">
                <h3 class="syn-h3">What changed in ${esc(group.group)}, table by table <span class="muted">(${num(group.changed)} changed record${group.changed === 1 ? "" : "s"})</span></h3>
                <table><thead><tr><th>Table</th><th>Records touched</th><th>Rows added</th><th>Rows removed</th><th>Values changed</th></tr></thead>
                <tbody>${tables}</tbody></table>
                ${columns ? `<div class="syn-columns"><span class="muted">Most changed columns:</span> ${columns}</div>` : ""}
            </section>
            <section class="table-card xp-card" id="syn-records">${synthesisRecordsHtml()}</section>
            ${s.detail ? `<section class="table-card xp-card" id="syn-detail">${synthesisDetailHtml()}</section>` : ""}`;
    }
    return `<div class="syn-cards">${cards}</div>${body}`;
}

function changeChip(change) {
    return `<span class="chip ${({ ADDED: "added", REMOVED: "removed", CHANGED: "changed" })[change] || "same"}">${esc(String(change).toLowerCase())}</span>`;
}

function synthesisRecordsHtml() {
    const s = syn();
    const filters = [["CHANGES", "All changes"], ["ADDED", "Added"], ["REMOVED", "Removed"], ["CHANGED", "Changed"], ["ALL", "Every record"]];
    const toolbar = `<div class="xp-toolbar syn-toolbar">
            ${filters.map(([value, text]) => `<button type="button" class="btn-ghost btn-small ${s.change === value ? "active" : ""}" data-syn-change="${value}">${text}</button>`).join("")}
            <input class="search" id="syn-search" placeholder="Search a key" value="${esc(s.q)}" autocomplete="off" aria-label="Search a key">
        </div>`;
    if (!s.page) return toolbar + '<div class="queue-empty">Loading records…</div>';
    if (s.page.error) return toolbar + `<div class="banner banner-error">${esc(s.page.error)}</div>`;
    const rows = s.page.records.map(record => `<tr class="clickable ${s.detail && s.detail.key === record.key ? "selected" : ""}" data-syn-record="${esc(record.key)}">
            <td><strong>${esc(record.key)}</strong></td><td>${changeChip(record.change)}</td><td class="muted">${esc(record.summary)}</td>
        </tr>`).join("");
    const from = s.page.total ? s.page.offset + 1 : 0;
    const to = s.page.offset + s.page.records.length;
    return `${toolbar}
        <table><thead><tr><th>Key</th><th>Change</th><th>What changed</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="3" class="muted">No record matches.</td></tr>'}</tbody></table>
        <div class="pager xp-pager">
            <span class="muted">${num(from)}–${num(to)} of ${num(s.page.total)} records</span>
            <button type="button" class="btn-ghost btn-small" data-syn-page="-1" ${s.page.offset === 0 ? "disabled" : ""}>‹ Previous</button>
            <button type="button" class="btn-ghost btn-small" data-syn-page="1" ${to >= s.page.total ? "disabled" : ""}>Next ›</button>
        </div>`;
}

function synthesisDetailHtml() {
    const d = syn().detail;
    const rows = d.differences.map(difference => {
        const where = difference.path ? esc(difference.path) : '<span class="muted">record</span>';
        if (difference.kind === "ROW_ADDED") return `<tr><td>${where}</td><td><span class="chip added">row added</span></td><td></td><td>${esc(difference.after)}</td></tr>`;
        if (difference.kind === "ROW_REMOVED") return `<tr><td>${where}</td><td><span class="chip removed">row removed</span></td><td>${esc(difference.before)}</td><td></td></tr>`;
        return `<tr><td>${where}</td><td><strong>${esc(difference.column)}</strong></td><td class="was">${difference.before == null ? '<span class="muted">empty</span>' : esc(difference.before)}</td><td class="now">${difference.after == null ? '<span class="muted">empty</span>' : esc(difference.after)}</td></tr>`;
    }).join("");
    return `<div class="syn-title"><h3 class="syn-h3">${esc(d.group)} ${esc(d.key)} · ${esc(d.change.toLowerCase())}</h3><span class="spacer"></span><button type="button" class="btn-ghost btn-small" data-syn-close>Close</button></div>
        <table><thead><tr><th>Where</th><th>Column / row</th><th>Before</th><th>After</th></tr></thead>
        <tbody>${rows || '<tr><td colspan="4" class="muted">No difference.</td></tr>'}</tbody></table>
        <div class="syn-docs">
            <div><div class="field-label">Before</div>${recordTreeHtml(d.before)}</div>
            <div><div class="field-label">After</div>${recordTreeHtml(d.after)}</div>
        </div>`;
}

/** A stored record as a readable tree: its values, then its related rows per table. */
function recordTreeHtml(node) {
    if (!node) return '<div class="muted">Not in this version.</div>';
    const values = Object.entries(node.values || {}).map(([column, value]) => `<li><span class="muted">${esc(column)}:</span> ${esc(value)}</li>`).join("");
    const tables = Object.entries(node.tables || {}).map(([table, rows]) => `<li><strong>${esc(table)}</strong> <span class="muted">(${rows.length})</span>
            <ul>${rows.map(row => `<li>${row._id != null ? '<span class="muted">id ' + esc(row._id) + "</span>" : ""}${recordTreeHtml(row)}</li>`).join("")}</ul></li>`).join("");
    return `<ul class="syn-tree">${values}${tables}</ul>`;
}

function onSynthesisClick(target) {
    const s = syn();
    const list = target.closest("[data-syn-list]");
    if (list) return selectSynthesisList(Number(list.dataset.synList));
    if (target.closest("[data-syn-back]")) {
        s.familyId = null;
        return renderXp();
    }
    if (target.closest("[data-syn-take]")) return takeSynthesisVersion();
    const group = target.closest("[data-syn-group]");
    if (group) {
        Object.assign(s, { group: group.dataset.synGroup, change: "CHANGES", q: "", offset: 0, page: null, detail: null });
        renderXp();
        return loadSynthesisRecords();
    }
    const change = target.closest("[data-syn-change]");
    if (change) {
        Object.assign(s, { change: change.dataset.synChange, offset: 0, page: null });
        renderXp();
        return loadSynthesisRecords();
    }
    const page = target.closest("[data-syn-page]");
    if (page && !page.disabled) {
        s.offset = Math.max(0, s.offset + Number(page.dataset.synPage) * SYNTHESIS_PAGE);
        return loadSynthesisRecords();
    }
    const record = target.closest("[data-syn-record]");
    if (record) return openSynthesisRecord(record.dataset.synRecord);
    if (target.closest("[data-syn-close]")) {
        s.detail = null;
        renderXp();
    }
}

let synthesisTimer;
function onSynthesisInput(target) {
    const s = syn();
    if (target.matches("[data-syn-before]") || target.matches("[data-syn-after]")) {
        if (target.matches("[data-syn-before]")) s.before = Number(target.value); else s.after = Number(target.value);
        loadSynthesisSummary();
        return true;
    }
    if (target.id === "syn-search") {
        s.q = target.value;
        s.offset = 0;
        clearTimeout(synthesisTimer);
        synthesisTimer = setTimeout(loadSynthesisRecords, 250);
        return true;
    }
    return false;
}
