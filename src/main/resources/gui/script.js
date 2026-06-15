const container = document.getElementById("container");
const bar = document.getElementById("progressBar");
const text = document.getElementById("progressText");

const EDIT_NOTE_SVG = `
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<path fill-rule="evenodd" clip-rule="evenodd" d="M16.9697 2.96968C18.091 1.84836 19.909 1.84836 21.0303 2.96968C22.1517 4.091 22.1517 5.90902 21.0303 7.03034L19.5303 8.53033L15.1973 12.8634C15.0052 13.0555 14.771 13.2003 14.5132 13.2862L10.2372 14.7115C9.96767 14.8014 9.67055 14.7312 9.46968 14.5303C9.2688 14.3295 9.19866 14.0323 9.28849 13.7628L10.7138 9.48679C10.7998 9.22906 10.9445 8.99486 11.1366 8.80276L15.4666 4.47277C15.4676 4.47174 15.4686 4.4707 15.4697 4.46967C15.4707 4.46864 15.4717 4.46761 15.4728 4.46658L16.9697 2.96968ZM16 6.06067L12.1973 9.86342C12.1698 9.89086 12.1491 9.92432 12.1369 9.96114L11.1859 12.8142L14.0389 11.8632C14.0757 11.8509 14.1092 11.8302 14.1366 11.8028L17.9393 8.00001L16 6.06067ZM19 6.93935L17.0607 5.00001L18.0303 4.03034C18.5659 3.49481 19.4341 3.49481 19.9697 4.03034C20.5052 4.56588 20.5052 5.43415 19.9697 5.96968L19 6.93935ZM3.25 5C3.25 4.0335 4.0335 3.25 5 3.25H12C12.4142 3.25 12.75 3.58579 12.75 4C12.75 4.41421 12.4142 4.75 12 4.75H5C4.86193 4.75 4.75 4.86193 4.75 5V19C4.75 19.1381 4.86193 19.25 5 19.25H19C19.1381 19.25 19.25 19.1381 19.25 19V12C19.25 11.5858 19.5858 11.25 20 11.25C20.4142 11.25 20.75 11.5858 20.75 12V19C20.75 19.9665 19.9665 20.75 19 20.75H5C4.0335 20.75 3.25 19.9665 3.25 19V5Z" fill="currentColor"/>
</svg>

`;

const CREATE_NOTE_SVG = `
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<path fill-rule="evenodd" clip-rule="evenodd" d="M16.9697 2.96968C18.091 1.84836 19.909 1.84836 21.0303 2.96968C22.1517 4.091 22.1517 5.90902 21.0303 7.03034L19.5303 8.53033L15.1973 12.8634C15.0052 13.0555 14.771 13.2003 14.5132 13.2862L10.2372 14.7115C9.96767 14.8014 9.67055 14.7312 9.46968 14.5303C9.2688 14.3295 9.19866 14.0323 9.28849 13.7628L10.7138 9.48679C10.7998 9.22906 10.9445 8.99486 11.1366 8.80276L15.4666 4.47277C15.4676 4.47174 15.4686 4.4707 15.4697 4.46967C15.4707 4.46864 15.4717 4.46761 15.4728 4.46658L16.9697 2.96968ZM16 6.06067L12.1973 9.86342C12.1698 9.89086 12.1491 9.92432 12.1369 9.96114L11.1859 12.8142L14.0389 11.8632C14.0757 11.8509 14.1092 11.8302 14.1366 11.8028L17.9393 8.00001L16 6.06067ZM19 6.93935L17.0607 5.00001L18.0303 4.03034C18.5659 3.49481 19.4341 3.49481 19.9697 4.03034C20.5052 4.56588 20.5052 5.43415 19.9697 5.96968L19 6.93935ZM3.25 5C3.25 4.0335 4.0335 3.25 5 3.25H12C12.4142 3.25 12.75 3.58579 12.75 4C12.75 4.41421 12.4142 4.75 12 4.75H5C4.86193 4.75 4.75 4.86193 4.75 5V19C4.75 19.1381 4.86193 19.25 5 19.25H19C19.1381 19.25 19.25 19.1381 19.25 19V12C19.25 11.5858 19.5858 11.25 20 11.25C20.4142 11.25 20.75 11.5858 20.75 12V19C20.75 19.9665 19.9665 20.75 19 20.75H5C4.0335 20.75 3.25 19.9665 3.25 19V5Z" fill="currentColor"/>
</svg>
`;

const CREATE_NOTE_SVG_OLD = `
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<path fill-rule="evenodd" clip-rule="evenodd" d="M19.6381 4.41742C18.3693 3.13949 16.3036 3.13578 15.0302 4.40915L5.65182 13.7876C5.56169 13.8777 5.49602 13.9893 5.461 14.1118L4.04679 19.0616C3.97205 19.3232 4.04482 19.6047 4.23693 19.7973C4.42905 19.9899 4.7104 20.0634 4.97215 19.9893L9.91261 18.5912C10.0359 18.5563 10.1481 18.4904 10.2387 18.3999L19.6298 9.00875C20.8967 7.74183 20.9004 5.68889 19.6381 4.41742ZM16.0909 5.46981C16.777 4.78372 17.89 4.78571 18.5736 5.47427C19.2538 6.15934 19.2518 7.26547 18.5692 7.94809L18.3397 8.17752L15.8615 5.69924L16.0909 5.46981ZM14.8008 6.7599L6.8499 14.7108L5.85885 18.1795L9.31619 17.2011L17.2791 9.23818L14.8008 6.7599Z" fill="currentColor"/>
</svg>
`;

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
                    <span class="note-icon" data-repo="${repo}" data-has-note="${noteExists}">${noteExists ? EDIT_NOTE_SVG : CREATE_NOTE_SVG}</span>
                </div>
                <div class="repo-actions">
                    ${
                        hasActionable
                ? `<button
                        class="pr-button"
                        data-repo="${repo}"
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

    const button =
        document.querySelector(
            `.pr-button[data-repo="${repo}"]`
        );

    if (!button) {
        return;
    }

    try {
        button.disabled = true;
        button.innerHTML = `
            <span class="spinner"></span>
            Creating...
        `;
        const [owner, name] = repo.split("/");
        const res = await fetch(
            `/internal/api/dependency-scan/pr/${owner}/${name}`,
            {
                method: "POST"
            }
        );
        if (!res.ok) {
            throw new Error(await res.text());
        }
        const url = await res.text();
        button.innerHTML = `
            <span class="pr-done">
                ✓ Done
            </span>
        `;
        setTimeout(() => {
            window.open(url, "_blank");
        }, 300);
    } catch (e) {
        console.error(e);
        button.disabled = false;
        button.innerHTML = "Create PR";
        alert("Failed to create PR");
    }
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

            <button class="icon-btn remove-btn" title="Remove">
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
        <button class="icon-btn add-btn" title="add">  
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<path d="M12.75 5.5C12.75 5.08579 12.4142 4.75 12 4.75C11.5858 4.75 11.25 5.08579 11.25 5.5V11.25H5.5C5.08579 11.25 4.75 11.5858 4.75 12C4.75 12.4142 5.08579 12.75 5.5 12.75H11.25V18.5C11.25 18.9142 11.5858 19.25 12 19.25C12.4142 19.25 12.75 18.9142 12.75 18.5V12.75H18.5C18.9142 12.75 19.25 12.4142 19.25 12C19.25 11.5858 18.9142 11.25 18.5 11.25H12.75V5.5Z" fill="currentColor"/>
</svg>
</button>
    `;

    addRow.querySelector(".add-btn").onclick = () => {
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