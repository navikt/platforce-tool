const container = document.getElementById("container");
const bar = document.getElementById("progressBar");
const text = document.getElementById("progressText");

let progressInterval = null;
let isRefreshing = false;
let hasActiveScan = false;
let isStartingScan = false;

async function fetchProgress() {
    const res = await fetch("/internal/api/dependency-scan/progress");
    return await res.json();
}

async function fetchData() {
    const res = await fetch("/internal/api/dependency-scan");
    return await res.json();
}

function groupByRepo(data) {
    const map = new Map();

    (data || []).forEach(item => {
        if (!item?.repository) return;

        if (!map.has(item.repository)) {
            map.set(item.repository, []);
        }
        map.get(item.repository).push(item);
    });

    return map;
}

function render(data) {

    console.log("📦 RAW API DATA RECEIVED:", data);

    container.innerHTML = "";

    if (!data) {
        console.warn("⚠️ render() called with null/undefined data");
        return;
    }

    if (!Array.isArray(data)) {
        console.error("❌ Expected array but got:", typeof data, data);
        return;
    }

    console.log(`📊 Rendering ${data.length} repository scans`);

    data.forEach((repoScan, index) => {

        console.log(`\n🔎 Repo [${index}] raw object:`, repoScan);

        const repo = repoScan?.repository;
        const repoUrl = `https://github.com/${repo}`;
        const [owner, repoName] = (repo || "unknown/unknown").split("/");
        const findingsRaw = repoScan?.findings;

        if (!repo) {
            console.warn("⚠️ Missing repository field:", repoScan);
        }

        if (!Array.isArray(findingsRaw)) {
            console.error("❌ findings is not array for repo:", repo, findingsRaw);
        }

        const findings = (findingsRaw || []).filter(f => {
            const valid = f && f.kind && f.status;

            if (!valid) {
                console.warn("⚠️ Dropping invalid finding:", f);
            }

            return valid;
        });

        const actionableFindings = findings.filter(f =>
            f.status === "UPDATE" || f.status === "AHEAD"
        );

        const hasActionable = actionableFindings.length > 0;

        const branchName = `dependency-update-${repoName}`;

        console.log(`📁 ${repo}: ${findings.length}/${findingsRaw?.length ?? 0} valid findings`);

        const ok = findings.filter(f => f.status === "OK").length;
        const update = findings.filter(f => f.status === "UPDATE").length;
        const ahead = findings.filter(f => f.status === "AHEAD").length;

        console.log(`📈 ${repo} stats -> OK:${ok}, UPDATE:${update}, AHEAD:${ahead}`);

        const el = document.createElement("div");
        el.className = "repo";

        el.innerHTML = `
            <div class="repo-header">
                <div>
                    <a class="repo-name-link" href="${repoUrl}">${repo || "UNKNOWN"}</a>
                    <span class="badge ok ${ok === 0 ? 'zero' : ''}">${ok} OK</span>
                    <span class="badge update ${update === 0 ? 'zero' : ''}">${update} UPDATE</span>
                    <span class="badge ahead ${ahead === 0 ? 'zero' : ''}">${ahead} AHEAD</span>
                </div>
                <div class="repo-actions">
                    ${
                        hasActionable
                ? `<button
                        class="pr-button"
                        onclick="event.stopPropagation(); createPr('${repo}')"
                    >
                        Create PR
                    </button>`
                : `<span class="pr-button disabled">Create PR</span>`
                     }

                    <div class="repo-toggle">▼</div>
                </div>
            </div>

            <div class="table">
                ${findings.map(f => {

            const kind = (f.kind || "UNKNOWN").toLowerCase();
            const status = (f.status || "UNKNOWN");

            const rowClass =
                kind === "plugin"
                    ? "plugin-row"
                    : "dependency-row";

            return `
    <div class="row ${rowClass}">
        <div class="pill ${kind}">
            ${f.kind || "UNKNOWN"}
        </div>

        <div>
            ${f.key || "-"}
        </div>

        <div>
            ${f.currentVersion || "-"}
        </div>

        <div>
            <span class="status-pill status-${status.toLowerCase()}">
                ${status}
            </span>
        </div>
    </div>
`;
        }).join("")}
            </div>
        `;

        const header = el.querySelector(".repo-header");

        header.addEventListener("click", () => {
            el.classList.toggle("open");
        });

        el.querySelector(".repo-name-link")
            ?.addEventListener("click", event => {
                event.stopPropagation();
            });

        container.appendChild(el);
    });

    console.log("✅ Render complete");
}

async function refresh() {
    if (isRefreshing) return;

    isRefreshing = true;
    hasActiveScan = true;
    isStartingScan = true;

    text.innerText = "Starting scan...";
    bar.style.width = "0%";

    startProgressPolling()

    await fetch("/internal/api/dependency-scan/refresh", {
        method: "POST"
    });

    await loadData();

    isRefreshing = false;
    // NOTE: we do NOT stop hasActiveScan here
    // it is stopped by pollProgress when backend finishes
}

async function loadData() {
    const data = await fetchData();
    render(data);
}

async function pollProgress() {
    try {
        if (!hasActiveScan) return;

        const p = await fetchProgress();

        if (!p || !p.total || p.total === 0) {
            bar.style.width = "0%";
            if (!isStartingScan) {
                text.innerText = "Idle";
            }

            return;
        }

        const percent = (p.done / p.total) * 100;
        if (isStartingScan && p.total > 0) {
            isStartingScan = false;
        }
        bar.style.width = percent + "%";

        if (!p.running && p.done >= p.total) {
            text.innerText = "Done";

            hasActiveScan = false;

            // stop polling when finished
            clearInterval(progressInterval);
            progressInterval = null;
            return;
        }

        text.innerText = `Scanning ${p.done}/${p.total}`;

    } catch (e) {
        console.warn("progress error", e);
    }
}

function startProgressPolling() {
    if (progressInterval) return;

    progressInterval = setInterval(pollProgress, 500);
}

async function createPr(repo) {
    const [owner, name] = repo.split("/");

    const res = await fetch(
        `/internal/api/dependency-scan/pr/${owner}/${name}`,
        { method: "POST" }
    );

    const url = await res.text();

    window.open(url, "_blank");
}

async function fetchTargetVersions() {
    const res = await fetch("/internal/api/target-versions");
    return await res.json();
}

let targetState = null;

function renderTargets(data) {
    targetState = data;

    const plugins = Object.entries(data.plugins || {});
    const deps = Object.entries(data.dependencies || {});

    renderTable("pluginsTable", plugins, "plugin");
    renderTable("depsTable", deps, "dependency");
}

function renderTable(containerId, entries, type) {
    const container = document.getElementById(containerId);

    container.innerHTML = "";

    entries.forEach(([key, version]) => {

        const row = document.createElement("div");
        row.className = "target-row";

        row.innerHTML = `
            <input class="key" value="${key}" />
            <input class="version" value="${version}" />

            <button class="remove">✕</button>
        `;

        row.querySelector(".remove").onclick = () => {
            row.remove();
        };

        container.appendChild(row);
    });

    // add new row
    const addRow = document.createElement("div");
    addRow.className = "target-row";

    addRow.innerHTML = `
        <input class="key" placeholder="group:name or plugin.id" />
        <input class="version" placeholder="version" />
        <button class="add">+</button>
    `;

    addRow.querySelector(".add").onclick = () => {
        const k = addRow.querySelector(".key").value;
        const v = addRow.querySelector(".version").value;

        if (!k || !v) return;

        const newRow = document.createElement("div");
        newRow.className = "target-row";

        newRow.innerHTML = `
            <div class="pill ${type}">${type}</div>
            <input class="key" value="${k}" />
            <input class="version" value="${v}" />
            <button class="remove">✕</button>
        `;

        newRow.querySelector(".remove").onclick = () => newRow.remove();

        container.insertBefore(newRow, addRow);
    };

    container.appendChild(addRow);
}

document.getElementById("saveTargets")
    .addEventListener("click", async () => {

        function read(containerId) {
            const rows = document.querySelectorAll(`#${containerId} .target-row`);

            const map = {};

            rows.forEach(r => {
                const key = r.querySelector(".key")?.value;
                const version = r.querySelector(".version")?.value;

                if (key && version) {
                    map[key] = version;
                }
            });

            return map;
        }

        const payload = {
            plugins: read("pluginsTable"),
            dependencies: read("depsTable")
        };

        await fetch("/internal/api/target-versions/update", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });

        alert("Target versions updated");
    });

document.getElementById("refreshBtn")
    .addEventListener("click", refresh);

const targetCard =
    document.getElementById("targetVersionsCard");

const targetToggle =
    targetCard.querySelector(".toggle");

targetCard
    .querySelector(".repo-header")
    .addEventListener("click", () => {

        const isOpen =
            targetCard.classList.toggle("open");

        targetToggle.textContent =
            isOpen ? "▲" : "▼";
    });

document
    .getElementById("jumpToTargetsBtn")
    .addEventListener("click", () => {

        document
            .getElementById("targetVersionsCard")
            .scrollIntoView({
                behavior: "smooth"
            });
    });


// INIT
async function initTargets() {
    const data = await fetchTargetVersions();
    renderTargets(data);
}

initTargets();
loadData();
startProgressPolling();