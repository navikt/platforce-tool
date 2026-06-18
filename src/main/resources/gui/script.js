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

let progressInterval = null;
let isRefreshing = false;
let hasActiveScan = false;
let isStartingScan = false;

let refreshEpoch = 0;

let lastLoadedData = [];

let ignoredRepositories = [];

async function fetchRepoView() {
    const res = await fetch("/internal/repos/view");
    return await res.json();
}

async function fetchProgress() {
    const res = await fetch("/internal/api/dependency-scan/progress");
    return await res.json();
}

async function fetchData() {
    const res = await fetch("/internal/api/dependency-scan");
    return await res.json();
}

async function fetchIgnoredRepositories() {
    const res =
        await fetch(
            "/internal/api/ignored-repositories"
        );
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

function render({ repos, scans }) {

    console.log("📦 RAW API DATA RECEIVED:", { repos, scans });

    container.innerHTML = "";

    const ignoredSet = new Set(ignoredRepositories);

    if (!Array.isArray(repos)) {
        console.error("❌ Expected repos array but got:", repos);
        return;
    }

    // Build lookup map for scans (IMPORTANT)
    const scanMap = (scans || []).reduce((acc, scan) => {
        if (scan?.repository) {
            acc[scan.repository] = scan;
        }
        return acc;
    }, {});

    console.log(`📊 Rendering ${repos.length} repos`);

    repos.filter(repoView =>
        !ignoredSet.has(repoView.name)
    ).forEach(repoView => {

        const repo = repoView?.name;
        const state = repoView?.state;
        const team = repoView?.team;

        if (!repo) {
            console.warn("⚠️ Missing repo name:", repoView);
            return;
        }

        const repoUrl = `https://github.com/${repo}`;
        const [owner, repoName] = repo.split("/");

        const scan = scanMap[repo];
        const findingsRaw = scan?.findings || [];

        const findings = findingsRaw.filter(f => f && f.kind && f.status);

        const ok = findings.filter(f => f.status === "OK").length;
        const update = findings.filter(f => f.status === "UPDATE").length;
        const ahead = findings.filter(f => f.status === "AHEAD").length;

        const hasActionable = findings.some(f =>
            f.status === "UPDATE" || f.status === "AHEAD"
        );

        const noteText = notes?.[repo]?.trim() || "";
        const noteExists = noteText.length > 0;

        const isScanned = state === "SCANNED";
        const isMissing = state === "NOT_IN_GITHUB";
        const isNotScanned = state === "NOT_SCANNED";

        const el = document.createElement("div");
        el.className =
            "repo " +
            (isNotScanned ? "not-scanned" : "") +
            (isMissing ? "missing" : "");

        el.innerHTML = `
            <div class="repo-header">
                <div>
                    <div class="repo-namecell">
                        <a class="repo-name-link" href="${repoUrl}">
                            ${repo}
                        </a>
                    </div>

                    ${
                        isScanned ? `
                            <span class="badge ok ${ok === 0 ? 'zero' : ''}">${ok} OK</span>
                            <span class="badge update ${update === 0 ? 'zero' : ''}">${update} UPDATE</span>
                            <span class="badge ahead ${ahead === 0 ? 'zero' : ''}">${ahead} AHEAD</span>`
                        : isMissing
                            ? `<span class="badge missing">NOT INSTALLED</span>`
                            : `<span class="badge pending">NOT SCANNED</span>`
                    }
                    ${
            noteExists
                ? `<span class="note-icon" data-repo="${repo}">
                                    ${EDIT_NOTE_SVG}
                               </span>`
                : `<span class="note-icon" data-repo="${repo}">
                                    ${CREATE_NOTE_SVG}
                               </span>`
        }
                </div>

                <div class="repo-actions">
                    ${
            isMissing
                ? `<button class="pr-button ignore-button" onclick="event.stopPropagation(); ignoreRepository('${repo}')">Ignore</button>
                <button class="pr-button install-button">Install App</button>`
                : hasActionable
                    ? `<button class="pr-button"
                                           onclick="event.stopPropagation(); createPr('${repo}')">
                                        Create PR
                                   </button>`
                    : `<span class="pr-button disabled">Create PR</span>`
        }

                    ${isScanned ? `<div class="repo-toggle">▼</div>` : ``}
                </div>
            </div>

            ${
            noteText
                ? `<div class="repo-note-view">${noteText}</div>`
                : ""
        }

            <div class="repo-note-editor hidden">
                <textarea>${noteText}</textarea>
                <button class="save-note-btn">Save</button>
            </div>

            ${
            isScanned
                ? `
                    <div class="table">
                        ${findings.map(f => {
                    const kind = (f.kind || "UNKNOWN").toLowerCase();
                    const status = (f.status || "UNKNOWN");

                    return `
                                <div class="row ${kind}-row">
                                    <div class="pill">${f.kind}</div>
                                    <div>${f.key || "-"}</div>

                                    <div class="version-cell">
                                        <span class="current-version">
                                            ${f.currentVersion || "-"}
                                        </span>

                                        ${
                        (status === "UPDATE" || status === "AHEAD") && f.targetVersion
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
                    `
                : ``
        }
        `;

        // interactions unchanged
        const noteIcon = el.querySelector(".note-icon");

        noteIcon.addEventListener("click", e => {
            e.stopPropagation();
            el.classList.add("open");
            el.querySelector(".repo-note-editor").classList.toggle("hidden");
        });

        el.querySelector(".save-note-btn")?.addEventListener("click", async () => {
            const note = el.querySelector("textarea").value;

            await saveNote(repo, note);

            notes[repo] = note;

            render(lastLoadedData);
        });

        el.querySelector(".repo-header").addEventListener("click", () => {
            if (isScanned) el.classList.toggle("open");
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

    const [repoViewData, scanData, noteData, ignoredData] =
        await Promise.all([
            fetchRepoView(),
            fetchData(),
            fetchNotes(),
            fetchIgnoredRepositories()
        ]);

    notes = noteData || {};

    ignoredRepositories = ignoredData?.repositories || [];

    lastLoadedData = {
        repos: repoViewData || [],
        scans: scanData || []
    };

    render(lastLoadedData);

    renderIgnoredRepositories();
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
                .getElementById("ignoredTab")
                .style.display =
                selected === "ignored"
                    ? "block"
                    : "none";
        });
    });

async function initTargets() {
    const data = await fetchTargetVersions();
    renderTargets(data);
}

async function ignoreRepository(repo) {

    if (
        ignoredRepositories.includes(repo)
    ) {
        return;
    }

    ignoredRepositories.push(repo);

    await saveIgnoredRepositories();

    await loadData();
}

async function saveIgnoredRepositories() {

    await fetch(
        "/internal/api/ignored-repositories/update",
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                repositories:
                ignoredRepositories
            })
        }
    );
}

function renderIgnoredRepositories() {

    const container =
        document.getElementById(
            "ignoredReposTable"
        );

    container.innerHTML = "";

    ignoredRepositories
        .sort()
        .forEach(repo => {

            const row =
                document.createElement("div");

            row.className =
                "target-row";

            row.innerHTML = `
                <input
                    class="key"
                    value="${repo}"
                />

                <button class="remove">
                    ${TRASH_SVG}
                </button>
            `;

            row.querySelector(".remove")
                .addEventListener(
                    "click",
                    async () => {

                        ignoredRepositories =
                            ignoredRepositories
                                .filter(
                                    r => r !== repo
                                );

                        await saveIgnoredRepositories();

                        renderIgnoredRepositories();
                    }
                );

            container.appendChild(row);
        });

    const addRow =
        document.createElement("div");

    addRow.className =
        "add-row";

    addRow.innerHTML = `
        <button class="add-btn">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
            xmlns="http://www.w3.org/2000/svg">
            <path fill-rule="evenodd" clip-rule="evenodd" 
                d="M4.5 6.25C4.08579 6.25 3.75 6.58579 3.75 7C3.75 7.41421 4.08579 7.75 4.5 7.75H5.30548L6.18119 19.1342C6.25132 20.046 7.01159 20.75 7.92603 20.75H16.074C16.9884 20.75 17.7487 20.046 17.8188 19.1342L18.6945 7.75H19.5C19.9142 7.75 20.25 7.41421 20.25 7C20.25 6.58579 19.9142 6.25 19.5 6.25H16.75V6C16.75 4.48122 15.5188 3.25 14 3.25H10C8.48122 3.25 7.25 4.48122 7.25 6V6.25H4.5ZM10 4.75C9.30964 4.75 8.75 5.30964 8.75 6V6.25H15.25V6C15.25 5.30964 14.6904 4.75 14 4.75H10ZM6.80991 7.75L7.67677 19.0192C7.68679 19.1494 7.7954 19.25 7.92603 19.25H16.074C16.2046 19.25 16.3132 19.1494 16.3232 19.0192L17.1901 7.75H6.80991ZM10 9.75C10.4142 9.75 10.75 10.0858 10.75 10.5V16.5C10.75 16.9142 10.4142 17.25 10 17.25C9.58579 17.25 9.25 16.9142 9.25 16.5V10.5C9.25 10.0858 9.58579 9.75 10 9.75ZM14 9.75C14.4142 9.75 14.75 10.0858 14.75 10.5V16.5C14.75 16.9142 14.4142 17.25 14 17.25C13.5858 17.25 13.25 16.9142 13.25 16.5V10.5C13.25 10.0858 13.5858 9.75 14 9.75Z"
                fill="currentColor"/>
        </svg>
        </button>
    `;

    addRow.querySelector(".add-btn")
        .addEventListener(
            "click",
            async () => {

                const repo =
                    prompt(
                        "Repository name (owner/repo)"
                    );

                if (!repo) {
                    return;
                }

                ignoredRepositories.push(
                    repo.trim()
                );

                await saveIgnoredRepositories();

                renderIgnoredRepositories();
            }
        );

    container.appendChild(addRow);
}

initTargets();
loadData();
startProgressPolling();