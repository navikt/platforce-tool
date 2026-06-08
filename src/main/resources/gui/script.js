const container = document.getElementById("container");
const bar = document.getElementById("progressBar");
const text = document.getElementById("progressText");

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

    data.forEach(item => {
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

    grouped.forEach((findings, repo) => {

            const safeFindings = findings || [];

            const ok = safeFindings.filter(f => f?.status === "OK").length;
            const update = safeFindings.filter(f => f?.status === "UPDATE").length;
            const ahead = safeFindings.filter(f => f?.status === "AHEAD").length;

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
            ${(safeFindings || []).map(f => {

                const kind = (f?.kind || "UNKNOWN").toLowerCase();
                const key = f?.key || "-";
                const current = f?.currentVersion || "-";
                const status = f?.status || "UNKNOWN";

                return `
                    <div class="row">
                        <div class="pill ${kind}">${f?.kind || "UNKNOWN"}</div>
                        <div>${key}</div>
                        <div>${current}</div>
                        <div>${status}</div>
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
        }

    );
}

async function refresh() {

    await fetch("/internal/api/dependency-scan/refresh", {
        method: "POST"
    });

    const data = await fetchData();
    render(data);
}

async function pollProgress() {
    const p = await fetchProgress();

    if (!p || !p.total || p.total === 0) {
        bar.style.width = "0%";
        text.innerText = "Idle";
        return;
    }

    const percent = (p.done / p.total) * 100;

    bar.style.width = percent + "%";

    text.innerText =
        p.running
            ? `Scanning ${p.done}/${p.total}`
            : "Idle";
}

document.getElementById("refreshBtn")
    .addEventListener("click", async () => {
        refresh();
    });

setInterval(pollProgress, 500);
refresh();