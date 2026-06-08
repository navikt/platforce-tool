const container = document.getElementById("container");
const bar = document.getElementById("progressBar");
const text = document.getElementById("progressText");

let progressInterval = null;
let isRefreshing = false;

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
    container.innerHTML = "";

    const grouped = groupByRepo(data);

    grouped.forEach((findingsRaw, repo) => {

        const safeFindings = (findingsRaw || []).filter(f =>
            f && f.kind && f.status
        );

        const ok = safeFindings.filter(f => f.status === "OK").length;
        const update = safeFindings.filter(f => f.status === "UPDATE").length;
        const ahead = safeFindings.filter(f => f.status === "AHEAD").length;

        const el = document.createElement("div");
        el.className = "repo";

        el.innerHTML = `
            <div class="repo-header">
                <div>
                    <div class="repo-name">${repo}</div>

                    <span class="badge ok">${ok} OK</span>
                    <span class="badge update">${update} UPDATE</span>
                    <span class="badge ahead">${ahead} AHEAD</span>
                </div>
                <div>▼</div>
            </div>

            <div class="table">
                ${safeFindings.map(f => {

            const kind = (f.kind || "UNKNOWN").toLowerCase();

            return `
                        <div class="row">
                            <div class="pill ${kind}">${f.kind || "UNKNOWN"}</div>
                            <div>${f.key || "-"}</div>
                            <div>${f.currentVersion || "-"}</div>
                            <div>${f.status || "UNKNOWN"}</div>
                        </div>
                    `;
        }).join("")}
            </div>
        `;

        el.querySelector(".repo-header")
            .addEventListener("click", () => {
                el.classList.toggle("open");
            });

        container.appendChild(el);
    });
}

async function refresh() {
    if (isRefreshing) return;

    isRefreshing = true;

    text.innerText = "Starting scan...";
    bar.style.width = "0%";

    await fetch("/internal/api/dependency-scan/refresh", {
        method: "POST"
    });

    await loadData();

    isRefreshing = false;
}

async function loadData() {
    const data = await fetchData();
    render(data);
}

async function pollProgress() {
    try {
        const p = await fetchProgress();

        if (!p || !p.total || p.total === 0) {
            bar.style.width = "0%";
            text.innerText = "Idle";
            return;
        }

        const percent = (p.done / p.total) * 100;
        bar.style.width = percent + "%";

        if (!p.running && p.done >= p.total) {
            text.innerText = "Done";

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
startProgressPolling();
loadData();