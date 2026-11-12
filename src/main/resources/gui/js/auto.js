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
    button.disabled = !autoRepositoriesLoaded || autoRepositoriesSaving.has(repository);
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
    if (!autoRepositoriesLoaded || autoRepositoriesSaving.has(repository)) return;
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
        if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
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
let autoLogsLoaded = false;
let autoNextBefore = null;
let autoRenderedStep = null;
const autoLogEntries = new Map();
const autoLogNodes = new Map();

function autoLogTimestamp(timestamp) {
    const time = document.createElement("time");
    time.dateTime = timestamp;
    time.textContent = new Date(timestamp).toLocaleString();
    return time;
}

function autoLogNode(entry) {
    const article = document.createElement("article");
    article.className = `auto-log-entry${entry.level === "INFO" ? "" : " auto-log-warning"}`;
    const header = document.createElement("div");
    header.className = "auto-log-meta";
    header.appendChild(autoLogTimestamp(entry.timestamp));
    const context = document.createElement("span");
    context.textContent = [entry.username, entry.repository, entry.step].filter(Boolean).join(" / ");
    header.appendChild(context);
    const message = document.createElement("p");
    message.textContent = entry.message;
    article.append(header, message);
    if (entry.url) {
        const url = new URL(entry.url);
        if (url.protocol !== "https:" || url.hostname !== "github.com" || !/^\/[^/]+\/[^/]+\/pull\/\d+$/.test(url.pathname)) {
            throw new Error("Invalid pull request link in Auto log");
        }
        const link = document.createElement("a");
        link.href = url.href;
        link.textContent = entry.url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        article.appendChild(link);
    }
    if (entry.details) {
        const details = document.createElement("details");
        const summary = document.createElement("summary");
        summary.textContent = "Report";
        const report = document.createElement("pre");
        report.textContent = entry.details;
        details.append(summary, report);
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
    const statusText = autoProcessRun
        ? `${autoProcessRun.status} / ${autoProcessRun.username}`
        : "No Auto runs yet.";
    if (status.textContent !== statusText) status.textContent = statusText;
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
            step.textContent = [autoProcessRun.step || "Starting Auto process", autoProcessRun.repository].filter(Boolean).join(" / ");
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
        renderAutoProcess();
        document.getElementById("autoProcessError").textContent = "";
    } catch (failure) {
        console.error("Could not load Auto process", failure);
        document.getElementById("autoProcessError").textContent =
            `Could not load Auto process: ${failure.message}. Displayed status and logs may be stale; retry with Refresh.`;
    } finally {
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
