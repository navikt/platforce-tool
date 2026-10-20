function render({ repos, scans }) {

    console.log("📦 RAW API DATA RECEIVED:", { repos, scans });

    const ignoredSet = new Set(ignoredRepositories);

    const visibleRepos =
        repos.filter(repo =>
            !ignoredSet.has(repo.name)
        );

    const grouped = groupRepos(visibleRepos);

    const scanMap = (scans || []).reduce((acc, scan) => {
        if (scan?.repository) {
            acc[scan.repository] = scan;
        }
        return acc;
    }, {});

    const teams =
        Object.keys(grouped)
            .sort();

    // first render
    if (!selectedTeam || !grouped[selectedTeam]) {
        selectedTeam = teams[0];
    }

    renderTeamTabs(teams);

    container.innerHTML = "";

    (grouped[selectedTeam] || [])
        .forEach(repoView => {
            renderRepo(
                repoView,
                scanMap,
                container
            );
        });

    console.log("✅ Render complete");
}

function renderTeamTabs(teams) {

    const tabs =
        document.getElementById("teamTabs");

    tabs.innerHTML = "";

    teams.forEach(team => {

        const tab =
            document.createElement("button");

        tab.className =
            "team-pill" +
            (team === selectedTeam
                ? " active"
                : "");

        tab.textContent = team;

        tab.addEventListener("click", () => {

            selectedTeam = team;

            saveSelectedTeam(team);

            render(lastLoadedData);
        });

        tabs.appendChild(tab);
    });
}

function renderRepo(repoView, scanMap, container) {
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

    const untrackedDependencies = scan?.untrackedDependencies ?? [];
    const untrackedPlugins = scan?.untrackedPlugins ?? [];
    const untrackedPlatformBoms = scan?.untrackedPlatformBoms ?? [];

    const ok = findings.filter(f => f.status === "OK").length;
    const update = findings.filter(f => f.status === "UPDATE").length;
    const ahead = findings.filter(f => f.status === "AHEAD").length;
    const add = findings.filter(f => f.status === "ADD").length;
    const remove = findings.filter(f => f.status === "REMOVE").length;
    const vulnerable = findings.filter(f => f.status === "VULNERABLE").length;


    const hasActionable = findings.some(f =>
        f.status === "UPDATE" || f.status === "AHEAD" || f.status === "ADD" || f.status === "REMOVE"
    );
    const prPrerequisite = !normalSecurityReady ? "Run security scan first"
        : ((findings.some(f => f.kind === "PLATFORM_BOM") || untrackedPlatformBoms.length) && !bomSecurityReady)
            ? "Run BOM scan first" : null;

    const addBadge =
        add > 0
            ? `<span class="badge add">${add} ADD</span>`
            : "";

    const removeBadge =
        remove > 0
            ? `<span class="badge remove">${remove} REMOVE</span>`
            : "";

    const vulnerableBadge =
        vulnerable > 0
            ? `<span class="badge vulnerable">${vulnerable} VULNER</span>`
            : "";

    const noteText = notes?.[repo]?.trim() || "";
    const noteExists = noteText.length > 0;

    const isScanned = state === "SCANNED";
    const isMissing = state === "NOT_IN_GITHUB";
    const isNotScanned = state === "NOT_SCANNED";

    const untrackedCount = untrackedDependencies.length + untrackedPlugins.length + untrackedPlatformBoms.length;
    const untrackedBadge =
        untrackedCount > 0
            ? `<span class="badge untracked">${untrackedCount} UNTRKD</span>`
            : "";

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
                            <span class="badge ahead ${ahead === 0 ? 'zero' : ''}">${ahead} AHEAD</span>
                            ${addBadge}
                            ${removeBadge}
                            ${untrackedBadge}
                            ${vulnerableBadge}
                    `
            : isMissing
                ? `<span class="badge missing">NOT INSTALLED</span>`
                : `<span class="badge pending">NOT SCANNED</span>`
    }
                    ${
        isScanned
            ? (
                noteExists
                    ? `<span class="note-icon" data-has-note="true" data-repo="${repo}">
                        ${EDIT_NOTE_SVG}
                   </span>`
                    : `<span class="note-icon" data-repo="${repo}">
                        ${CREATE_NOTE_SVG}
                   </span>`
            )
            : ''
    }
                </div>

                <div class="repo-actions">
                    ${
        isMissing
            ? `<button class="pr-button ignore-button" onclick="event.stopPropagation(); ignoreRepository('${repo}')">Ignore</button>
                <button
    class="pr-button install-button"
    onclick="event.stopPropagation(); window.open('https://github.com/apps/platforce-tooling', '_blank')"
>
    Grant App Access
</button>`
            : `
                <button
                    data-repo="${repo}"
                    class="pr-button refresh-button"
                    onclick="event.stopPropagation(); refreshRepository('${repo}', this)">
                    Refresh
                </button>

                <span class="pr-prerequisite-slot">
                    ${prPrerequisite ? `<span class="pr-prerequisite-icon" role="img" tabindex="0"
                        aria-label="${prPrerequisite}" title="${prPrerequisite}">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                            stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <circle cx="12" cy="12" r="9"/>
                            ${normalSecurityReady
                                ? `<path d="M12 6v4l3 1"/>
                                   <text x="12" y="17" text-anchor="middle" fill="currentColor" stroke="none"
                                       font-family="sans-serif" font-size="6.5" font-weight="700">BOM</text>`
                                : `<path d="M12 7v5l3 2"/>`}
                        </svg>
                    </span>` : ""}
                </span>

                ${
                prPrerequisite
                    ? `<button class="pr-button create-pr-button disabled" disabled>Create PR</button>`
                    : hasActionable
                    ? `<button
                               data-repo="${repo}"
                               class="pr-button create-pr-button"
                               onclick="event.stopPropagation(); createPr('${repo}')">
                               Create PR
                           </button>`
                    : `<button class="pr-button create-pr-button disabled" disabled>Create PR</button>`
            }
            `
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

                    const displayStatus =
                        status === "OK_OVERRIDDEN"
                            ? "OK *"
                            : status === "OK_TRANSIENT"
                                ? `OK ${BANDAGE_SVG}`
                                : status === "OK_WITH_ADD"
                                    ? "OK +ADD"
                                    : status;

                    const statusClass =
                        status === "OK_OVERRIDDEN"
                            ? "target-status-overridden"
                            : status === "VULNERABLE"
                                ? "target-status-vulnerable"
                                : `status-${status.toLowerCase()}`;

                    const hasDetails =
                        !!f.platformBomSecurity ||
                        (status === "OK_OVERRIDDEN" && f.relatedTo?.length > 0) ||
                        (status === "OK_WITH_ADD" && f.relatedTo?.length > 0) ||
                        (status === "OK_TRANSIENT" && f.relatedTo?.length > 0) ||
                        (status === "ADD" && f.relatedTo?.length > 0);

                const details =
                    f.platformBomSecurity
                        ? `<div class="finding-details hidden">
                            ${f.currentVersion !== f.targetVersion
                                ? "<p>Security below describes the target BOM version, not the current repository version.</p>"
                                : ""}
                            ${platformBomSecurityDetailsHtml(f.platformBomSecurity)}
                           </div>`
                        : hasDetails
                        ? `
                <div class="finding-details hidden">
                    ${
                            status === "OK_OVERRIDDEN"
                                ? `
                                <strong>Vulnerability overridden by:</strong>
                                <ul>
                                    ${f.relatedTo.map(r =>
                                    `<li>${r.key} ${r.version ? `(${r.version})` : ""}</li>`
                                ).join("")}
                                </ul>
                              `
                                : status === "OK_WITH_ADD"
                                    ? `
                                    <strong>Required dependency overrides:</strong>
                                    <ul>
                                        ${f.relatedTo.filter(r => r.kind !== "PLATFORM_BOM").map(r =>
                                        `<li>${r.key} ${r.version ? `(${r.version})` : ""}</li>`
                                    ).join("")}
                                    </ul>
                                  `
                                    : status === "ADD"
                                        ? `
                                        <strong>Transitive override for:</strong>
                                        <ul>
                                            ${f.relatedTo.map(r =>
                                            `<li>${r.key} ${r.version ? `(${r.version})` : ""}</li>`
                                        ).join("")}
                                        </ul>
                                      `
                                        : status === "OK_TRANSIENT"
                                        ? `
                                                        <strong>Transitive override for:</strong>
                                                        <ul>
                                                            ${f.relatedTo.map(r =>
                                    `<li>${r.key} ${r.version ? `(${r.version})` : ""}</li>`
                                    ).join("")}
                                                        </ul>
                                                      `
                                : ""
                        }
                    ${f.bomCoverage?.length ? `
                        <p>Already covered by platform BOM:
                            ${f.bomCoverage.map(r => `${escapeHtml(r.key)} (${escapeHtml(r.version)})`).join(", ")}.
                            Corresponding dependency ADDs are not needed.</p>` : ""}
                </div>
              `
                        : "";
            
                    return `
                        <div class="finding-container">
                            <div class="row ${kind}-row">
                                <div class="pill">${f.kind === "PLATFORM_BOM" ? "PLATFORM BOM" : f.kind}</div>
                                <div>${f.key || ""}</div>
                
                                <div class="version-cell">
                                    <span class="current-version">
                                        ${f.currentVersion || ""}
                                    </span>
                
                                    ${
                                    (status === "UPDATE" || status === "AHEAD" || status === "ADD") && f.targetVersion
                                        ? `<span class="target-version-pill">→ ${f.targetVersion}</span>`
                                        : ""
                                }
                                </div>
                
                                <div>
                                    <span
                                        class="status-pill ${statusClass} ${status === "AHEAD" ? "target-draft-pill" : ""}"
                                        data-kind="${f.kind}"
                                        data-key="${f.key}"
                                        data-version="${f.currentVersion || ""}"
                                        ${hasDetails ? 'data-toggle-details="true"' : ""}>
                                        ${displayStatus}
                                    </span>
                                    ${f.platformBomSecurity?.status === "VULNERABLE" && status !== "VULNERABLE"
                                        ? '<span class="status-pill target-status-vulnerable" data-toggle-details="true">TARGET VULNERABLE</span>'
                                        : ""}
                                </div>
                            </div>
                            ${details}
                        </div>
                    `;
                        }).join("")}
            </div>

            ${
                (untrackedDependencies.length || untrackedPlugins.length || untrackedPlatformBoms.length)
                    ? `
                        <div class="untracked-section">
                            <div class="untracked-toggle">
                                Untracked plugins (${untrackedPlugins.length}), dependencies (${untrackedDependencies.length}), platform BOMs (${untrackedPlatformBoms.length})
                            </div>
                        
                            <div class="untracked-content">
                                <div class="compact-table">
                                    ${untrackedPlugins.map(plugin => `
                                    <div class="row untracked-row plugin-row">
                                        <div class="pill">PLUGIN</div>
                                        <div>${plugin.key}</div>

                                        <div class="version-cell">
                                            <span class="current-version">
                                                ${plugin.version || ""}
                                            </span>
                                        </div>

                                        <div>
                                            <span class="status-pill status-unknown target-draft-pill"
                                                data-kind="PLUGIN"
                                                data-key="${plugin.key}"
                                                data-version="${plugin.version}">
                                                UNTRACKED
                                            </span>
                                        </div>
                                    </div>
                                `).join("")}
                                
                                ${untrackedDependencies.map(dep => `
                                    <div class="row untracked-row dependency-row">
                                        <div class="pill">DEPENDENCY</div>
                                        <div>${dep.key}</div>

                                        <div class="version-cell">
                                            <span class="current-version">
                                                ${dep.version || ""}
                                            </span>
                                        </div>

                                        <div>
                                            <span class="status-pill status-unknown target-draft-pill"
                                                data-kind="DEPENDENCY"
                                                data-key="${dep.key}"
                                                data-version="${dep.version}">
                                                UNTRACKED
                                            </span>
                                        </div>
                                    </div>
                                `).join("")}
                                ${untrackedPlatformBoms.map(bom => `
                                    <div class="row untracked-row platform_bom-row">
                                        <div class="pill">PLATFORM BOM</div>
                                        <div>${escapeHtml(bom.key)}</div>
                                        <div class="version-cell">
                                            <span class="current-version">${escapeHtml(bom.version)}</span>
                                        </div>
                                        <div>
                                            <span class="status-pill status-unknown target-draft-pill"
                                                data-kind="PLATFORM_BOM"
                                                data-key="${escapeHtml(bom.key)}"
                                                data-version="${escapeHtml(bom.version)}">
                                                UNTRACKED
                                            </span>
                                        </div>
                                    </div>
                                `).join("")}
                                </div>
                            </div>
                        </div>
                    `
                    : ""
            }
        `
            : ``
    }
        `;



    // interactions unchanged
    const noteIcon = el.querySelector(".note-icon");

    if (noteIcon) {
        noteIcon.addEventListener("click", e => {
            e.stopPropagation();
            el.classList.add("open");
            el.querySelector(".repo-note-editor").classList.toggle("hidden");
        });
    }

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
    refreshDependencyUsageButtons();

    if (targetSecurityScan?.targets?.some(target => target.status === "TRANSIENT_UNUSED")) {
        captureTargetDrafts();
        renderTargetTables();
    }
    render(lastLoadedData);

    renderIgnoredRepositories();
}

async function createPr(repo) {

    const button =
        document.querySelector(
            `.create-pr-button[data-repo="${repo}"]`
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

function groupRepos(data) {
    const stateRank = (state) => {
        switch (state) {
            case "SCANNED": return 0;
            case "NOT_SCANNED": return 1;
            case "NOT_IN_GITHUB": return 2;
            default: return 3;
        }
    };

    const grouped = {};

    for (const repo of data) {
        const team = repo.team || "unknown";

        if (!grouped[team]) {
            grouped[team] = [];
        }

        grouped[team].push(repo);
    }

    // sort inside groups
    for (const team of Object.keys(grouped)) {
        grouped[team].sort((a, b) => {
            const stateDiff =
                stateRank(a.state) - stateRank(b.state);

            if (stateDiff !== 0) return stateDiff;

            return a.name.localeCompare(b.name);
        });
    }

    return grouped;
}

async function refreshRepository(repo, button) {
    const [owner, repository] = repo.split("/", 2);

    button.textContent = "Scanning...";
    button.disabled = true;

    try {
        const response =
            await fetch(
                `/internal/api/dependency-scan/refresh/${encodeURIComponent(owner)}/${encodeURIComponent(repository)}`,
                {
                    method: "POST"
                }
            );

        if (response.status === 404) {
            throw new Error(
                `Repository ${repo} could not be scanned`
            );
        }

        if (!response.ok) {
            const body = await response.text();

            throw new Error(
                `Failed to scan ${repo}: ${response.status} ${body}`
            );
        }

        await loadData();
    } catch (error) {
        console.error(
            `Failed to refresh repository ${repo}`,
            error
        );

        button.textContent = "Refresh";
        button.disabled = false;
    }
}
