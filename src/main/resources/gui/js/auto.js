let autoRepositories = new Set();
let autoRepositoriesLoaded = false;
let autoReadGeneration = 0;
const autoRepositoriesSaving = new Set();

function showAutoError(message) {
    document.getElementById("autoError").textContent = message;
    document.getElementById("scannerAutoError").textContent = message;
}

function createAutoToggle(repository) {
    const name = repository.toLowerCase();
    const button = document.createElement("button");
    button.type = "button";
    button.className = "auto-toggle";
    button.dataset.repository = name;
    button.setAttribute("role", "switch");
    button.setAttribute("aria-label", `Auto dependency management for ${repository}`);
    button.title = "Global opt-in shared by all users";
    const track = document.createElement("span");
    track.className = "auto-toggle-track";
    track.setAttribute("aria-hidden", "true");
    const label = document.createElement("span");
    label.textContent = "auto";
    button.append(track, label);
    button.addEventListener("click", event => {
        event.stopPropagation();
        return saveAutoRepository(name);
    });
    updateAutoToggle(button);
    return button;
}

function updateAutoToggle(button) {
    const repository = button.dataset.repository;
    button.setAttribute("aria-checked", String(autoRepositories.has(repository)));
    const running = autoProcessRun?.status === "RUNNING";
    button.disabled = !autoRepositoriesLoaded || !autoProcessStatusLoaded || autoProcessStarting || running || autoRepositoriesSaving.has(repository);
    button.title = running ? "Auto settings are locked while the process is running"
        : !autoProcessStatusLoaded ? "Waiting for confirmed Auto process status" : "Global opt-in shared by all users";
}

function updateAutoToggles() {
    document.querySelectorAll(".auto-toggle").forEach(updateAutoToggle);
    updateStartAutoButton();
}

function renderAutoRepositories() {
    const list = document.getElementById("autoRepositories");
    list.replaceChildren();
    document.getElementById("autoStatus").textContent = !autoRepositoriesLoaded
        ? "Auto settings not loaded."
        : autoRepositories.size ? `${autoRepositories.size} repositories opted in.` : "No repositories opted in.";
    if (!autoRepositoriesLoaded) return;
    [...autoRepositories].sort().forEach(repository => {
        const row = document.createElement("div");
        row.className = "auto-repository-row";
        const link = document.createElement("a");
        link.className = "repo-name-link";
        link.href = `https://github.com/${repository}`;
        link.textContent = repository;
        row.append(link, createAutoToggle(repository));
        list.appendChild(row);
    });
}

async function loadAutoRepositories() {
    const generation = ++autoReadGeneration;
    document.getElementById("autoStatus").textContent = "Loading Auto settings...";
    try {
        const data = await fetchJson("/internal/api/auto-repositories");
        if (!Array.isArray(data.repositories) || data.repositories.some(repository => typeof repository !== "string")) {
            throw new Error("Invalid Auto settings response");
        }
        if (generation !== autoReadGeneration) return;
        autoRepositories = new Set(data.repositories.map(repository => repository.toLowerCase()));
        autoRepositoriesLoaded = true;
        showAutoError("");
    } catch (failure) {
        if (generation !== autoReadGeneration) return;
        console.error("Could not load Auto settings", failure);
        autoRepositoriesLoaded = false;
        showAutoError(`Could not load Auto settings: ${failure.message}. Refresh the Auto tab to retry.`);
    }
    if (generation !== autoReadGeneration) return;
    renderAutoRepositories();
    updateAutoToggles();
}

async function saveAutoRepository(repository) {
    if (!autoRepositoriesLoaded || !autoProcessStatusLoaded || autoProcessStarting || autoProcessRun?.status === "RUNNING" || autoRepositoriesSaving.has(repository)) return;
    const enabled = !autoRepositories.has(repository);
    autoRepositoriesSaving.add(repository);
    autoReadGeneration++;
    updateAutoToggles();
    showAutoError("");
    try {
        const response = await fetch("/internal/api/auto-repositories", {
            method: "POST",
            cache: "no-store",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ repository, enabled })
        });
        const data = await response.json();
        if (!response.ok) {
            if (response.status === 409) {
                autoProcessStatusLoaded = false;
                updateAutoToggles();
                await loadAutoProcess();
            }
            throw new Error(data.error || `HTTP ${response.status}`);
        }
        if (data.repository !== repository || data.enabled !== enabled) throw new Error("Invalid Auto setting response");
        autoReadGeneration++;
        if (enabled) autoRepositories.add(repository);
        else autoRepositories.delete(repository);
    } catch (failure) {
        console.error("Could not save Auto setting", failure);
        showAutoError(`Could not save Auto for ${repository}: ${failure.message}. Refresh the Auto tab to verify the saved setting.`);
    } finally {
        autoRepositoriesSaving.delete(repository);
        renderAutoRepositories();
        updateAutoToggles();
    }
}

let autoProcessTimer = null;
let autoProcessLoading = false;
let autoProcessStarting = false;
let autoProcessRun = null;
let autoProcessStatusLoaded = false;
let autoLogsLoaded = false;
let autoNextBefore = null;
let autoRenderedStep = null;
let autoRenderedLatestRun = null;
const autoLogEntries = new Map();
const autoLogNodes = new Map();
const autoRuns = new Map();
const autoRunNodes = new Map();
const autoRunDeleting = new Set();

function updateStartAutoButton() {
    const button = document.getElementById("startAuto");
    button.disabled = !autoProcessStatusLoaded || autoProcessLoading || autoProcessStarting ||
        autoProcessRun?.status === "RUNNING" || autoRepositoriesSaving.size > 0 || autoRunDeleting.size > 0;
    button.textContent = autoProcessStarting ? "Starting Auto..." : "Start Auto";
}

async function startAuto() {
    if (!autoProcessStatusLoaded || autoProcessLoading || autoProcessStarting ||
        autoProcessRun?.status === "RUNNING" || autoRepositoriesSaving.size || autoRunDeleting.size) return;
    autoProcessStarting = true;
    updateStartAutoButton();
    updateAutoToggles();
    document.getElementById("autoStartError").textContent = "";
    try {
        const response = await fetch("/internal/triggerAuto", { method: "GET", cache: "no-store" });
        const message = await response.text();
        if (!response.ok) throw new Error(response.status === 409 ? message : `HTTP ${response.status}`);
        if (message !== "process started") throw new Error("Unexpected Auto trigger response");
    } catch (failure) {
        console.error("Could not start Auto process", failure);
        document.getElementById("autoStartError").textContent =
            `Could not start Auto process: ${failure.message}. Check the refreshed process status before retrying.`;
    } finally {
        autoProcessStatusLoaded = false;
        autoProcessStarting = false;
        await loadAutoProcess();
        updateStartAutoButton();
    }
}

function autoLogTimestamp(timestamp) {
    const time = document.createElement("time");
    time.dateTime = timestamp;
    time.textContent = formatDeploymentTimestamp(timestamp);
    return time;
}

function autoUserPill(username) {
    const pill = document.createElement("span");
    pill.className = "auto-log-pill auto-log-user";
    pill.textContent = "user";
    pill.title = username;
    return pill;
}

function autoLogText(node, text) {
    const scans = /\b(?:dependency scan|dependencies scan|scan dependencies|plugin scan|scan plugins|BOM scan|scan BOMs)\b/gi;
    let offset = 0;
    for (const match of text.matchAll(scans)) {
        const before = document.createElement("span");
        before.textContent = text.slice(offset, match.index);
        const pill = document.createElement("span");
        const kind = /dependenc/i.test(match[0]) ? "dependency" : /plugin/i.test(match[0]) ? "plugin" : "platform-bom";
        pill.className = `auto-log-pill auto-scan-pill ${kind}-pill`;
        pill.textContent = match[0];
        node.append(before, pill);
        offset = match.index + match[0].length;
    }
    if (!offset) {
        node.textContent = text;
    } else {
        const after = document.createElement("span");
        after.textContent = text.slice(offset);
        node.appendChild(after);
    }
}

function autoLogPresentation(entry) {
    let { message, details } = entry;
    if (message === `Saved Target Versions as ${entry.username}`) message = "Saved Target Versions";
    if (message === `Skipped: repository is ignored by ${entry.username}`) message = "Skipped: repository is ignored by this user";
    if (entry.level === "INFO") {
        const dependency = details?.match(/^(\d+ targets checked), including transitive dependencies and configured overrides\.$/);
        if (message === "Dependency scan finished: no unresolved target vulnerabilities" && dependency) {
            message = `Dependency scan finished: ${dependency[1]}; no unresolved target vulnerabilities`;
            details = null;
        }
        const bom = details?.match(/^(\d+ saved BOMs checked for coverage)\. This is coverage analysis, not a full managed-catalog vulnerability audit\.$/);
        if (message === "BOM scan finished" && bom) {
            message = `BOM scan finished: ${bom[1]}`;
            details = null;
        }
        if (/^Plugin scan finished: \d+ plugins checked$/.test(message) &&
            details === "Published plugin metadata and transitive build-time dependencies checked without applying plugins.") {
            details = null;
        }
    }
    return { message, details };
}

function autoPullRequestLink(value) {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.hostname !== "github.com" || !/^\/[^/]+\/[^/]+\/pull\/\d+$/.test(url.pathname)) {
        throw new Error("Invalid pull request link in Auto log");
    }
    const [, owner, repository, , number] = url.pathname.split("/");
    const link = document.createElement("a");
    link.href = url.href;
    link.className = "pr-button auto-pr-link";
    link.textContent = `${repository} #${number}`;
    link.setAttribute("aria-label", `${owner}/${repository} #${number} (opens in a new tab)`);
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    const icon = document.createElement("img");
    icon.src = "/internal/gui/icons/external-link.svg";
    icon.alt = "";
    icon.width = 18;
    icon.height = 18;
    icon.setAttribute("aria-hidden", "true");
    link.appendChild(icon);
    return link;
}

function autoLogReport(text) {
    const report = document.createElement("div");
    report.className = "auto-log-report";
    const users = text.startsWith("Users in database:\n");
    text.split("\n").forEach((line, index) => {
        const row = document.createElement("div");
        if (users && index > 0 && line) {
            row.appendChild(autoUserPill(line));
        } else if (/^https:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+$/.test(line)) {
            row.appendChild(autoPullRequestLink(line));
        } else {
            const action = line.match(/^(UPDATE|AHEAD|ADD|REMOVE|OK|VULNERABLE|UNRESOLVED|UNSUPPORTED)\b/);
            if (action) {
                const pill = document.createElement("span");
                const status = action[1] === "VULNERABLE" ? "target-status-vulnerable" : `status-${action[1].toLowerCase()}`;
                pill.className = `status-pill ${status}`;
                pill.textContent = action[1];
                const rest = document.createElement("span");
                rest.textContent = line.slice(action[0].length);
                row.append(pill, rest);
            } else {
                autoLogText(row, line);
            }
        }
        report.appendChild(row);
    });
    return report;
}

function autoLogNode(entry) {
    const article = document.createElement("article");
    article.dataset.logId = entry.id;
    article.className = `auto-log-entry${entry.level === "INFO" ? "" : " auto-log-warning"}`;
    const header = document.createElement("div");
    header.className = "auto-log-meta";
    header.appendChild(autoLogTimestamp(entry.timestamp));
    if (entry.repository) {
        const repository = document.createElement("span");
        repository.textContent = entry.repository;
        header.appendChild(repository);
    }
    if (entry.step && entry.level !== "INFO") {
        const step = document.createElement("span");
        autoLogText(step, entry.step);
        header.appendChild(step);
    }
    const presentation = autoLogPresentation(entry);
    const message = document.createElement("p");
    autoLogText(message, presentation.message);
    if (entry.message === "Auto process started") {
        const triggered = document.createElement("span");
        triggered.className = "auto-log-triggered";
        triggered.textContent = ` Triggered by ${entry.username}`;
        message.appendChild(triggered);
    }
    article.append(header, message);
    if (entry.url) {
        article.appendChild(autoPullRequestLink(entry.url));
    }
    if (presentation.details) {
        const details = document.createElement("details");
        details.open = false;
        const summary = document.createElement("summary");
        summary.textContent = entry.message === "Auto process started" ? "Opted-in repositories" : "Report";
        details.append(summary, autoLogReport(presentation.details));
        article.appendChild(details);
    }
    return article;
}

function createAutoRunGroup(run) {
    const section = document.createElement("section");
    section.className = "auto-run";
    section.dataset.runId = run.id;
    const details = document.createElement("details");
    details.open = false;
    const summary = document.createElement("summary");
    summary.className = "auto-run-summary";
    const heading = document.createElement("span");
    heading.className = "auto-run-heading";
    summary.appendChild(heading);
    const body = document.createElement("div");
    body.className = "auto-run-logs";
    const partial = document.createElement("p");
    partial.className = "auto-run-partial";
    partial.textContent = "Earlier log entries are not loaded. Use Load older logs to see the rest.";
    partial.hidden = true;
    details.append(summary, body, partial);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "icon-btn remove auto-run-delete";
    remove.innerHTML = TRASH_SVG;
    remove.title = "Delete this Auto process and its logs";
    remove.setAttribute("aria-label", `Delete Auto process started ${formatDeploymentTimestamp(run.startedAt)}`);
    remove.addEventListener("click", event => {
        event.stopPropagation();
        return deleteAutoRun(run.id);
    });
    section.append(details, remove);
    return { section, details, heading, body, partial, remove, headerKey: null };
}

function updateAutoRunButtons() {
    autoRunNodes.forEach((group, id) => {
        group.remove.hidden = id === autoProcessRun?.id || autoRuns.get(id)?.status === "RUNNING";
        group.remove.disabled = autoProcessLoading || autoProcessStarting || !autoProcessStatusLoaded || autoRunDeleting.size > 0;
    });
    updateStartAutoButton();
}

function renderAutoRunGroups(current) {
    const list = document.getElementById("autoProcessLogs");
    const grouped = new Map();
    [...autoLogEntries.values()].sort((a, b) => a.id - b.id).forEach(entry => {
        if (!grouped.has(entry.runId)) grouped.set(entry.runId, []);
        grouped.get(entry.runId).push(entry);
    });
    if (autoProcessRun && !grouped.has(autoProcessRun.id)) grouped.set(autoProcessRun.id, []);
    if (!grouped.size) {
        if (current.parentNode && current.parentNode !== list.parentNode) list.parentNode.insertBefore(current, list);
        list.textContent = "No log entries yet.";
        autoRunNodes.clear();
        return;
    }
    if (!list.children.length) list.textContent = "";
    const ids = [...grouped.keys()].sort((a, b) => {
        if (a === autoProcessRun?.id) return -1;
        if (b === autoProcessRun?.id) return 1;
        return grouped.get(b).at(-1).id - grouped.get(a).at(-1).id;
    });
    const latestChanged = autoRenderedLatestRun !== (autoProcessRun?.id ?? null);
    ids.forEach((id, index) => {
        const run = autoRuns.get(id);
        if (!autoRunNodes.has(id)) autoRunNodes.set(id, createAutoRunGroup(run));
        const group = autoRunNodes.get(id);
        const headerKey = JSON.stringify([run.status, run.startedAt, run.finishedAt]);
        if (headerKey !== group.headerKey) {
            const title = document.createElement("span");
            title.textContent = "Auto process";
            const state = document.createElement("span");
            state.className = `auto-log-pill auto-run-state${["RUNNING", "COMPLETED"].includes(run.status) ? "" : " auto-run-state-warning"}`;
            state.textContent = run.status;
            const started = document.createElement("span");
            started.className = "auto-run-time";
            started.textContent = "Started ";
            started.appendChild(autoLogTimestamp(run.startedAt));
            const finished = document.createElement("span");
            finished.className = "auto-run-time";
            finished.textContent = run.finishedAt ? "Finished " : run.status === "RUNNING" ? "In progress" : "End time unavailable";
            if (run.finishedAt) finished.appendChild(autoLogTimestamp(run.finishedAt));
            group.heading.replaceChildren(title, state, started, finished);
            group.headerKey = headerKey;
        }
        if (latestChanged && (id === autoProcessRun?.id || id === autoRenderedLatestRun)) {
            group.details.open = id === autoProcessRun?.id;
        }
        group.partial.hidden = !grouped.get(id).length || grouped.get(id)[0].message === "Auto process started" || autoNextBefore === null;
        [...group.body.children].forEach(node => {
            if (node.dataset.logId !== undefined && !autoLogEntries.has(Number(node.dataset.logId))) node.remove();
        });
        grouped.get(id).filter(entry => entry.level !== "INFO" || !entry.repository || entry.url).forEach((entry, logIndex) => {
            const node = autoLogNodes.get(entry.id);
            const existing = group.body.children[logIndex] || null;
            if (existing !== node) group.body.insertBefore(node, existing);
        });
        autoRunNodes.forEach((group, id) => {
            if (!grouped.has(id)) {
                group.section.remove();
                autoRunNodes.delete(id);
            }
        });
        if (id === autoProcessRun?.id && current.parentNode !== group.body) group.body.appendChild(current);
        const existing = list.children[index] || null;
        if (existing !== group.section) list.insertBefore(group.section, existing);
    });
    autoRenderedLatestRun = autoProcessRun?.id ?? null;
    updateAutoRunButtons();
}

async function deleteAutoRun(id) {
    if (autoProcessLoading || autoProcessStarting || !autoProcessStatusLoaded || autoRunDeleting.size ||
        id === autoProcessRun?.id || autoRuns.get(id)?.status === "RUNNING") return;
    autoRunDeleting.add(id);
    updateAutoRunButtons();
    try {
        const response = await fetch(`/internal/api/auto/runs/${encodeURIComponent(id)}`, { method: "DELETE", cache: "no-store" });
        if (response.status !== 204) {
            const data = await response.json();
            throw new Error(data.error || `HTTP ${response.status}`);
        }
        autoLogEntries.forEach((entry, logId) => {
            if (entry.runId === id) {
                autoLogEntries.delete(logId);
                autoLogNodes.delete(logId);
            }
        });
        autoRuns.delete(id);
        autoRunNodes.get(id)?.section.remove();
        autoRunNodes.delete(id);
        renderAutoProcess();
        document.getElementById("autoProcessError").textContent = "";
    } catch (failure) {
        console.error("Could not delete Auto process", failure);
        document.getElementById("autoProcessError").textContent =
            `Could not delete Auto process: ${failure.message}. Refresh to verify the retained history before retrying.`;
    } finally {
        autoRunDeleting.delete(id);
        updateAutoRunButtons();
    }
}

function renderAutoProcess() {
    const running = autoProcessRun?.status === "RUNNING";
    const tab = document.getElementById("autoTabButton");
    tab.classList.toggle("auto-running", running);
    tab.setAttribute("aria-label", running ? "Auto: process running" : "Auto");
    const status = document.getElementById("autoProcessStatus");
    const statusKey = JSON.stringify([autoProcessRun?.id, autoProcessRun?.status]);
    if (status.dataset.stateKey !== statusKey) {
        status.textContent = autoProcessRun ? autoProcessRun.status : "No Auto runs yet.";
        status.dataset.stateKey = statusKey;
    }
    const current = document.getElementById("autoCurrentStep");
    current.hidden = !running;
    const stepKey = running ? JSON.stringify([autoProcessRun.id, autoProcessRun.step, autoProcessRun.repository, autoProcessRun.stepStartedAt]) : null;
    if (stepKey !== autoRenderedStep) {
        current.replaceChildren();
        if (running) {
            const spinner = document.createElement("span");
            spinner.className = "spinner";
            spinner.setAttribute("aria-hidden", "true");
            const step = document.createElement("span");
            autoLogText(step, [autoProcessRun.step || "Starting Auto process", autoProcessRun.repository].filter(Boolean).join(" / "));
            current.append(spinner, step, autoLogTimestamp(autoProcessRun.stepStartedAt || autoProcessRun.startedAt));
        }
        autoRenderedStep = stepKey;
    }
    renderAutoRunGroups(current);
    document.getElementById("loadOlderAutoLogs").hidden = autoNextBefore === null;
}

async function fetchAutoProcess(before = null) {
    const response = await fetch(`/internal/api/auto/status${before === null ? "" : `?before=${before}`}`, { cache: "no-store" });
    const snapshot = await response.json();
    if (!response.ok) throw new Error(snapshot.error || `HTTP ${response.status}`);
    if (!Array.isArray(snapshot.logs) || !Array.isArray(snapshot.runs) ||
        (snapshot.run && !["RUNNING", "COMPLETED", "STOPPED", "FAILED", "INTERRUPTED"].includes(snapshot.run.status))) {
        throw new Error("Invalid Auto process response");
    }
    return snapshot;
}

function mergeAutoSnapshot(snapshot) {
    snapshot.runs.forEach(run => {
        if (typeof run.id !== "string" || !run.id || !Number.isFinite(Date.parse(run.startedAt)) ||
            (run.finishedAt && !Number.isFinite(Date.parse(run.finishedAt))) ||
            !["RUNNING", "COMPLETED", "STOPPED", "FAILED", "INTERRUPTED"].includes(run.status)) {
            throw new Error("Invalid Auto run metadata");
        }
        autoRuns.set(run.id, run);
    });
    if ((snapshot.run && !autoRuns.has(snapshot.run.id)) || snapshot.logs.some(entry => !autoRuns.has(entry.runId))) {
        throw new Error("Missing Auto run metadata");
    }
    autoProcessRun = snapshot.run ?? null;
    mergeAutoLogs(snapshot.logs);
}

function mergeAutoLogs(logs) {
    logs.forEach(entry => {
        if (!Number.isSafeInteger(entry.id) || !Number.isFinite(Date.parse(entry.timestamp)) || typeof entry.message !== "string") {
            throw new Error("Invalid Auto log entry");
        }
        if (!autoLogEntries.has(entry.id)) {
            autoLogNodes.set(entry.id, autoLogNode(entry));
            autoLogEntries.set(entry.id, entry);
        }
    });
}

async function loadAutoProcess(before = null, reset = false) {
    if (autoProcessLoading || autoProcessStarting || autoRunDeleting.size) return;
    autoProcessLoading = true;
    updateAutoRunButtons();
    const older = document.getElementById("loadOlderAutoLogs");
    older.disabled = true;
    try {
        const previousNewest = !reset && autoLogEntries.size ? Math.max(...autoLogEntries.keys()) : null;
        let snapshot = await fetchAutoProcess(before);
        mergeAutoSnapshot(snapshot);
        if (reset) {
            const ids = new Set(snapshot.logs.map(entry => entry.id));
            autoLogEntries.forEach((entry, id) => {
                if (!ids.has(id)) {
                    autoLogEntries.delete(id);
                    autoLogNodes.delete(id);
                }
            });
            const runs = new Set(snapshot.runs.map(run => run.id));
            autoRuns.forEach((run, id) => { if (!runs.has(id)) autoRuns.delete(id); });
            autoLogsLoaded = false;
        }
        if (!autoLogsLoaded || before !== null) autoNextBefore = snapshot.nextBefore ?? null;
        while (before === null && previousNewest !== null && snapshot.nextBefore != null &&
            snapshot.logs.length && snapshot.logs[0].id > previousNewest) {
            snapshot = await fetchAutoProcess(snapshot.nextBefore);
            mergeAutoSnapshot(snapshot);
        }
        autoLogsLoaded = true;
        autoProcessStatusLoaded = true;
        renderAutoProcess();
        document.getElementById("autoProcessError").textContent = "";
    } catch (failure) {
        autoProcessStatusLoaded = false;
        console.error("Could not load Auto process", failure);
        document.getElementById("autoProcessError").textContent =
            `Could not load Auto process: ${failure.message}. Displayed status and logs may be stale; retry with Refresh.`;
    } finally {
        updateAutoToggles();
        autoProcessLoading = false;
        updateAutoRunButtons();
        older.disabled = false;
    }
}

function startAutoProcessPolling() {
    if (autoProcessTimer !== null) clearTimeout(autoProcessTimer);
    const poll = async () => {
        await loadAutoProcess();
        const visible = document.getElementById("autoTab").style.display !== "none";
        autoProcessTimer = setTimeout(poll, visible && autoProcessRun?.status === "RUNNING" ? 2000 : 10000);
    };
    poll();
}

function refreshAutoTab() {
    loadAutoRepositories();
    loadAutoProcess(null, true);
}
