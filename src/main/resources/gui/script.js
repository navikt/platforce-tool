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
                        hasUpdates
                ? `<a class="pr-button" href="https://google.com" target="_blank">
                        Create PR
                   </a>`
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
        const toggle = el.querySelector(".repo-toggle");

        header.addEventListener("click", () => {
            const isOpen = el.classList.toggle("open");

            toggle.textContent = isOpen ? "▲" : "▼";
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

// UI binding
document.getElementById("refreshBtn")
    .addEventListener("click", refresh);

// INIT
loadData();
startProgressPolling();