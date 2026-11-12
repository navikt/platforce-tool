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
    button.disabled = !autoRepositoriesLoaded || !autoProcessStatusLoaded || running || autoRepositoriesSaving.has(repository);
    button.title = running ? "Auto settings are locked while the process is running"
        : !autoProcessStatusLoaded ? "Waiting for confirmed Auto process status" : "Global opt-in shared by all users";
}

function updateAutoToggles() {
    document.querySelectorAll(".auto-toggle").forEach(updateAutoToggle);
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
    if (!autoRepositoriesLoaded || !autoProcessStatusLoaded || autoProcessRun?.status === "RUNNING" || autoRepositoriesSaving.has(repository)) return;
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
let autoProcessRun = null;
let autoProcessStatusLoaded = false;
let autoLogsLoaded = false;
let autoNextBefore = null;
let autoRenderedStep = null;
const autoLogEntries = new Map();
const autoLogNodes = new Map();

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

function autoLogReport(text) {
    const report = document.createElement("div");
    report.className = "auto-log-report";
    const users = text.startsWith("Users in database:\n");
    text.split("\n").forEach((line, index) => {
        const row = document.createElement("div");
        if (users && index > 0 && line) {
            row.appendChild(autoUserPill(line));
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
    article.className = `auto-log-entry${entry.level === "INFO" ? "" : " auto-log-warning"}`;
    const header = document.createElement("div");
    header.className = "auto-log-meta";
    header.appendChild(autoLogTimestamp(entry.timestamp));
    header.appendChild(autoUserPill(entry.username));
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
    article.append(header, message);
    if (entry.url) {
        const url = new URL(entry.url);
        if (url.protocol !== "https:" || url.hostname !== "github.com" || !/^\/[^/]+\/[^/]+\/pull\/\d+$/.test(url.pathname)) {
            throw new Error("Invalid pull request link in Auto log");
        }
        const link = document.createElement("a");
        link.href = url.href;
        const [, owner, repository, , number] = url.pathname.split("/");
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
        article.appendChild(link);
    }
    if (presentation.details) {
        const details = document.createElement("details");
        const summary = document.createElement("summary");
        summary.textContent = entry.message === "Auto process started" ? "Opted-in repositories" : "Report";
        details.append(summary, autoLogReport(presentation.details));
        article.appendChild(details);
    }
    return article;
}

function renderAutoProcess() {
    const running = autoProcessRun?.status === "RUNNING";
    const tab = document.getElementById("autoTabButton");
    tab.classList.toggle("auto-running", running);
    tab.setAttribute("aria-label", running ? "Auto: process running" : "Auto");
    const status = document.getElementById("autoProcessStatus");
    const statusKey = JSON.stringify([autoProcessRun?.id, autoProcessRun?.status, autoProcessRun?.username]);
    if (status.dataset.stateKey !== statusKey) {
        status.textContent = autoProcessRun ? autoProcessRun.status : "No Auto runs yet.";
        if (autoProcessRun) status.appendChild(autoUserPill(autoProcessRun.username));
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
    const list = document.getElementById("autoProcessLogs");
    if (!autoLogEntries.size) {
        list.textContent = "No log entries yet.";
    } else {
        if (!list.children.length) list.textContent = "";
        [...autoLogEntries.keys()].sort((a, b) => b - a).forEach((id, index) => {
            const node = autoLogNodes.get(id);
            const existing = list.children[index] || null;
            if (existing !== node) list.insertBefore(node, existing);
        });
    }
    document.getElementById("loadOlderAutoLogs").hidden = autoNextBefore === null;
}

async function fetchAutoProcess(before = null) {
    const response = await fetch(`/internal/api/auto/status${before === null ? "" : `?before=${before}`}`, { cache: "no-store" });
    const snapshot = await response.json();
    if (!response.ok) throw new Error(snapshot.error || `HTTP ${response.status}`);
    if (!Array.isArray(snapshot.logs) ||
        (snapshot.run && !["RUNNING", "COMPLETED", "STOPPED", "FAILED", "INTERRUPTED"].includes(snapshot.run.status))) {
        throw new Error("Invalid Auto process response");
    }
    return snapshot;
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

async function loadAutoProcess(before = null) {
    if (autoProcessLoading) return;
    autoProcessLoading = true;
    const older = document.getElementById("loadOlderAutoLogs");
    older.disabled = true;
    try {
        const previousNewest = autoLogEntries.size ? Math.max(...autoLogEntries.keys()) : null;
        let snapshot = await fetchAutoProcess(before);
        autoProcessRun = snapshot.run;
        mergeAutoLogs(snapshot.logs);
        if (!autoLogsLoaded || before !== null) autoNextBefore = snapshot.nextBefore ?? null;
        while (before === null && previousNewest !== null && snapshot.nextBefore != null &&
            snapshot.logs.length && snapshot.logs[0].id > previousNewest) {
            snapshot = await fetchAutoProcess(snapshot.nextBefore);
            mergeAutoLogs(snapshot.logs);
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
    loadAutoProcess();
}
