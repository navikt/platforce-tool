const container = document.getElementById("container");
const bar = document.getElementById("progressBar");
const text = document.getElementById("progressText");

let progressInterval = null;
let isRefreshing = false;
let hasActiveScan = false;
let isStartingScan = false;

let lastLoadedData = [];

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

        const noteExists =
            notes[repo] &&
            notes[repo].trim().length > 0;

        const el = document.createElement("div");
        el.className = "repo";

        const noteText = notes?.[repo]?.trim() || "";

        el.innerHTML = `
            <div class="repo-header">
                <div>
                    <a class="repo-name-link" href="${repoUrl}">${repo || "UNKNOWN"}</a>
                    <span class="badge ok ${ok === 0 ? 'zero' : ''}">${ok} OK</span>
                    <span class="badge update ${update === 0 ? 'zero' : ''}">${update} UPDATE</span>
                    <span class="badge ahead ${ahead === 0 ? 'zero' : ''}">${ahead} AHEAD</span>
                    <span class="note-icon" data-repo="${repo}">${noteExists ? "📝" : "📄"}</span>
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
            
            ${noteText ? `
                <div class="repo-note-view">${noteText}</div>
                ` : ""}

            <div class="repo-note-editor hidden">
                <textarea>${notes[repo] || ""}</textarea>
            
                <button class="save-note-btn">
                    Save
                </button>
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

        <div class="version-cell">
            <span class="current-version">${f.currentVersion || "-"}</span>
            ${
                (f.status === "UPDATE" || f.status === "AHEAD") && f.targetVersion
                    ? `<span class="target-version-pill">→ ${f.targetVersion}</span>`
                    : ""
            }
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

        const noteIcon =
            el.querySelector(".note-icon");

        noteIcon.addEventListener("click", e => {

            e.stopPropagation();

            el.classList.add("open");

            const editor =
                el.querySelector(".repo-note-editor");

            editor.classList.toggle("hidden");
        });

        el.querySelector(".save-note-btn")
            .addEventListener("click", async () => {

                const note =
                    el.querySelector("textarea").value;

                await saveNote(
                    repo,
                    note
                );

                notes[repo] = note;

                render(lastLoadedData);
            });

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

    const [scanData, noteData] =
        await Promise.all([
            fetchData(),
            fetchNotes()
        ]);

    notes = noteData || {};

    lastLoadedData = scanData || [];

    render(scanData);
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

let notes = {};

async function fetchNotes() {
    const res =
        await fetch("/internal/api/repository-notes");

    return await res.json();
}

async function saveNote(repository, note) {
    await fetch("/internal/api/repository-notes", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            repository,
            note
        })
    });
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

            <button class="remove-btn" title="Remove">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
            xmlns="http://www.w3.org/2000/svg">
            <path fill-rule="evenodd" clip-rule="evenodd"
                d="M4.5 6.25C4.08579 6.25 3.75 6.58579 3.75 7C3.75 7.41421 4.08579 7.75 4.5 7.75H5.30548L6.18119 19.1342C6.25132 20.046 7.01159 20.75 7.92603 20.75H16.074C16.9884 20.75 17.7487 20.046 17.8188 19.1342L18.6945 7.75H19.5C19.9142 7.75 20.25 7.41421 20.25 7C20.25 6.58579 19.9142 6.25 19.5 6.25H16.75V6C16.75 4.48122 15.5188 3.25 14 3.25H10C8.48122 3.25 7.25 4.48122 7.25 6V6.25H4.5ZM10 4.75C9.30964 4.75 8.75 5.30964 8.75 6V6.25H15.25V6C15.25 5.30964 14.6904 4.75 14 4.75H10ZM6.80991 7.75L7.67677 19.0192C7.68679 19.1494 7.7954 19.25 7.92603 19.25H16.074C16.2046 19.25 16.3132 19.1494 16.3232 19.0192L17.1901 7.75H6.80991ZM10 9.75C10.4142 9.75 10.75 10.0858 10.75 10.5V16.5C10.75 16.9142 10.4142 17.25 10 17.25C9.58579 17.25 9.25 16.9142 9.25 16.5V10.5C9.25 10.0858 9.58579 9.75 10 9.75ZM14 9.75C14.4142 9.75 14.75 10.0858 14.75 10.5V16.5C14.75 16.9142 14.4142 17.25 14 17.25C13.5858 17.25 13.25 16.9142 13.25 16.5V10.5C13.25 10.0858 13.5858 9.75 14 9.75Z"
                fill="currentColor"/>
        </svg>
    </button>
        `;

        row.querySelector(".remove-btn").onclick = () => {
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
        <button class="add">  
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<path fill-rule="evenodd" clip-rule="evenodd" d="M3.75 12C3.75 7.44365 7.44365 3.75 12 3.75C16.5563 3.75 20.25 7.44365 20.25 12C20.25 16.5563 16.5563 20.25 12 20.25C7.44365 20.25 3.75 16.5563 3.75 12ZM12 2.25C6.61522 2.25 2.25 6.61522 2.25 12C2.25 17.3848 6.61522 21.75 12 21.75C17.3848 21.75 21.75 17.3848 21.75 12C21.75 6.61522 17.3848 2.25 12 2.25ZM12 6.75C12.4142 6.75 12.75 7.08579 12.75 7.5V11.25H16.5C16.9142 11.25 17.25 11.5858 17.25 12C17.25 12.4142 16.9142 12.75 16.5 12.75H12.75V16.5C12.75 16.9142 12.4142 17.25 12 17.25C11.5858 17.25 11.25 16.9142 11.25 16.5V12.75H7.5C7.08579 12.75 6.75 12.4142 6.75 12C6.75 11.5858 7.08579 11.25 7.5 11.25H11.25V7.5C11.25 7.08579 11.5858 6.75 12 6.75Z" fill="#202733"/>
</svg>
</button>
    `;

    addRow.querySelector(".add").onclick = () => {
        const k = addRow.querySelector(".key").value;
        const v = addRow.querySelector(".version").value;

        if (!k || !v) return;

        const newRow = document.createElement("div");
        newRow.className = "target-row";

        newRow.innerHTML = `
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

document
    .querySelectorAll(".tab")
    .forEach(tab => {

        tab.addEventListener("click", () => {

            document
                .querySelectorAll(".tab")
                .forEach(t => t.classList.remove("active"));

            tab.classList.add("active");

            const selected =
                tab.dataset.tab;

            document
                .getElementById("scannerTab")
                .style.display =
                selected === "scanner"
                    ? "block"
                    : "none";

            document
                .getElementById("targetsTab")
                .style.display =
                selected === "targets"
                    ? "block"
                    : "none";
        });
    });

async function initTargets() {
    const data = await fetchTargetVersions();
    renderTargets(data);
}

initTargets();
loadData();
startProgressPolling();