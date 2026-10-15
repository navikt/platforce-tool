async function renderTargets(data) {
    const drafted = targetState?.drafted ?? new Set();

    targetState = {
        plugins: data.plugins || {},
        dependencies: data.dependencies || {},
        platformBoms: data.platformBoms || {},
        gradleVersion: data.gradleVersion || "",
        transientDependencies: new Set(data.transientDependencies || []),
        drafted
    };

    //renderTargetTables()

}

function renderTargetTables() {
    console.log(
        "RENDER TARGET TABLES:",
        targetSecurityScan?.targets?.length
    );
    renderTable("pluginsTable", Object.entries(targetState.plugins), "plugin");
    renderTable("depsTable", Object.entries(targetState.dependencies), "dependency");
    renderTable("platformBomsTable", Object.entries(targetState.platformBoms), "platform_bom");
    renderGradleVersion(targetState.gradleVersion);
}

function addDraftTarget(kind, key, version) {
    captureTargetDrafts();
    if (kind === "PLUGIN") {
        targetState.plugins[key] = version;
    }

    if (kind === "DEPENDENCY") {
        targetState.dependencies[key] = version;
    }

    if (kind === "PLATFORM_BOM") {
        targetState.platformBoms[key] = version;
    }

    if (kind === "GRADLE") {
        targetState.gradleVersion = version;
    }

    targetState.drafted.add(`${kind}:${key}`);

    console.log("Draft target:", {
        kind,
        key,
        version
    });

    renderTargetTables();
}

function renderGradleVersion(version) {
    const container = document.getElementById("gradleTable");
    container.innerHTML = "";

    const drafted =
        targetState.drafted?.has("GRADLE:gradle-wrapper");

    const row = document.createElement("div");
    row.className = `target-row${drafted ? " drafted" : ""}`;

    const keyInput = document.createElement("input");
    keyInput.className = "key";
    keyInput.value = "gradle-wrapper";

    const versionInput = document.createElement("input");
    versionInput.className = "version";
    versionInput.value = version;

    row.replaceChildren(keyInput, versionInput);

    container.appendChild(row);
}

function renderTable(containerId, entries, type) {
    const container = document.getElementById(containerId);
    container.innerHTML = "";
    entries.forEach(([key, version]) => {
        const drafted =
            targetState.drafted?.has(`${type.toUpperCase()}:${key}`);
        const isTransient =
            type === "dependency" &&
            targetState.transientDependencies?.has(key);
        const securityTarget = findTargetSecurity(type, key, version);

        const row = document.createElement("div");
        row.className = `target-row${drafted ? " drafted" : ""}`;
        row.innerHTML = `
        
            <input class="key" value="${key}" />
            <input class="version" value="${version}" />
            ${securityStatusHtml(securityTarget, type === "platform_bom" ? "NOT SCANNED" : null)}
            ${
            type === "dependency"
                ? `
                    <button
                        class="icon-btn transient-btn ${isTransient ? "active" : ""}"
                        title="${isTransient ? "Transient dependency" : "Mark as transient"}">
                        ${BANDAGE_SVG}
                    </button>
                `
                : ""
        }
            <button class="icon-btn remove-btn" title="Remove">
                ${TRASH_SVG}
            </button>
        
        ${
            securityTarget
                ? `
                <div class="target-security-details hidden">
                    ${type === "platform_bom" ? platformBomSecurityDetailsHtml(securityTarget) : targetSecurityDetailsHtml(securityTarget)}
                </div>
            `
                : ""
        }
        `;
        if (type === "dependency") {
            row.querySelector(".transient-btn").onclick = () => {
                const transient =
                    targetState.transientDependencies;

                if (transient.has(key)) {
                    transient.delete(key);
                } else {
                    transient.add(key);
                }

                const button =
                    row.querySelector(".transient-btn");

                const nowTransient =
                    transient.has(key);

                button.classList.toggle(
                    "active",
                    nowTransient
                );

                button.title =
                    nowTransient
                        ? "Transient dependency"
                        : "Mark as transient";
            };
        }

        row.querySelector(".remove-btn").onclick = () => {
            if (drafted) {
                targetState.drafted.delete(`${type.toUpperCase()}:${key}`);
            }
            row.remove();
        };
        const statusButton =
            row.querySelector(".target-status-pill");
        if (statusButton) {
            statusButton.addEventListener("click", () => {
                toggleTargetSecurityDetails(statusButton);
            });
        }
        container.appendChild(row);
    });
    const addRow = document.createElement("div");
    addRow.className = "target-row";
    addRow.innerHTML = `
        <input class="key" placeholder="group:name or plugin.id" />
        <input class="version" placeholder="version" />
        <button class="icon-btn add-btn" title="add">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12.75 5.5C12.75 5.08579 12.4142 4.75 12 4.75C11.5858 4.75 11.25 5.08579 11.25 5.5V11.25H5.5C5.08579 11.25 4.75 11.5858 4.75 12C4.75 12.4142 5.08579 12.75 12.75 12.75H11.25V18.5C11.25 18.9142 11.5858 19.25 12 19.25C12.4142 19.25 12.75 18.9142 12.75 18.5V12.75H18.5C18.9142 12.75 19.25 12.4142 19.25 12C19.25 11.5858 18.9142 11.25 18.5 11.25H12.75V5.5Z" fill="currentColor"/>
            </svg>
        </button>
    `;
    addRow.querySelector(".add-btn").onclick = () => {
        const kInput = addRow.querySelector(".key");
        const vInput = addRow.querySelector(".version");
        const k = kInput.value.trim();
        const v = vInput.value.trim();
        if (!k || !v) return;
        const securityTarget = findTargetSecurity(type, k, v);
        const newRow = document.createElement("div");
        newRow.className = "target-row";
        newRow.innerHTML = `
       
            <input class="key" value="${k}" />
            <input class="version" value="${v}" />
            ${securityStatusHtml(securityTarget, type === "platform_bom" ? "NOT SCANNED" : null)}
            <button class="icon-btn remove-btn" title="Remove">
                ${TRASH_SVG}
            </button>
        
        ${
            securityTarget
                ? `
                <div class="target-security-details hidden">
                    ${type === "platform_bom" ? platformBomSecurityDetailsHtml(securityTarget) : targetSecurityDetailsHtml(securityTarget)}
                </div>
            `
                : ""
        }
        `;
        newRow.querySelector(".remove-btn").onclick = () => newRow.remove();
        const statusButton =
            newRow.querySelector(".target-status-pill");
        if (statusButton) {
            statusButton.addEventListener("click", () => {
                toggleTargetSecurityDetails(statusButton);
            });
        }
        container.insertBefore(newRow, addRow);
        kInput.value = "";
        vInput.value = "";
    };
    container.appendChild(addRow);
}

function readTargetInputs() {
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

    function readSingleVersion(containerId) {
        const row = document.querySelector(`#${containerId} .target-row`);
        if (!row) return "";

        return row.querySelector(".version")?.value ?? "";
    }

    return {
        plugins: read("pluginsTable"),
        dependencies: read("depsTable"),
        platformBoms: read("platformBomsTable"),
        gradleVersion: readSingleVersion("gradleTable"),
        transientDependencies:
            Array.from(targetState.transientDependencies)
    };
}

function captureTargetDrafts() {
    const inputs = readTargetInputs();
    targetState.plugins = inputs.plugins;
    targetState.dependencies = inputs.dependencies;
    targetState.platformBoms = inputs.platformBoms;
    targetState.gradleVersion = inputs.gradleVersion;
}

async function initTargets() {
    const data = await fetchTargetVersions();
    await renderTargets(data);
}

async function applySecurityUpgrade(button, key, version) {
    addDraftTarget("DEPENDENCY", key, version);
    button.remove();
}

let targetSaveMessageTimer = null;
let targetSaveMessageHideTimer = null;

function closeTargetSaveMessage(immediate = false) {
    clearTimeout(targetSaveMessageTimer);
    clearTimeout(targetSaveMessageHideTimer);
    targetSaveMessageTimer = null;
    targetSaveMessageHideTimer = null;
    const message = document.getElementById("targetSaveMessage");
    const finish = () => {
        message.hidden = true;
        document.getElementById("targetSaveMessageText").textContent = "";
        targetSaveMessageHideTimer = null;
    };
    if (immediate === true || message.hidden) {
        finish();
    } else {
        message.style.setProperty("--save-banner-height", `${message.scrollHeight}px`);
        message.classList.add("save-banner-leaving");
        targetSaveMessageHideTimer = setTimeout(finish, 300);
    }
}

function showTargetSaveMessage(text, success) {
    closeTargetSaveMessage(true);
    const message = document.getElementById("targetSaveMessage");
    message.className = `save-banner ${success ? "save-banner-success" : "save-banner-error"}`;
    document.getElementById("targetSaveMessageText").textContent = text;
    message.hidden = false;
    message.style.setProperty("--save-banner-height", `${message.scrollHeight}px`);
    if (success) {
        targetSaveMessageTimer = setTimeout(closeTargetSaveMessage, 6000);
    }
}

async function saveTargets() {
    const payload = readTargetInputs();
    closeTargetSaveMessage(true);
    let saved = false;
    try {
        const response = await fetch("/internal/api/target-versions/update", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error(`Failed to save target versions: HTTP ${response.status}`);
        }
        saved = true;

        targetScanGeneration++;
        targetSecurityScan = null;
        targetState = {
            plugins: payload.plugins,
            dependencies: payload.dependencies,
            platformBoms: payload.platformBoms,
            gradleVersion: payload.gradleVersion,
            transientDependencies: new Set(payload.transientDependencies),
            drafted: new Set()
        };

        renderTargetTables();
        await loadData();
        showTargetSaveMessage("Target versions updated", true);
    } catch (error) {
        console.error("Could not save target versions", error);
        showTargetSaveMessage(saved
            ? `Target versions saved, but repository data could not be refreshed: ${error.message}`
            : error.message, false);
    }
}
