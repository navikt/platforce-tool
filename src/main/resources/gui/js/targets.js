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
            ${securityStatusHtml(securityTarget)}
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
            ${type === "dependency" || type === "platform_bom" ? targetUpgradeButtonHtml() + dependencyUsageButtonHtml(type) : ""}
            <button class="icon-btn remove-btn${securityTarget?.status === "UNUSED" ? " unused-target-remove" : ""}"
                    title="${securityTarget?.status === "UNUSED" ? "Remove unused target: not declared in any currently scanned repository" : "Remove"}">
                ${TRASH_SVG}
            </button>
        
        ${
            securityTarget
                ? `
                <div class="target-security-details hidden">
                    ${type === "platform_bom" ? platformBomSecurityDetailsHtml(securityTarget)
                        : type === "plugin" ? pluginSecurityDetailsHtml(securityTarget) : targetSecurityDetailsHtml(securityTarget)}
                </div>
            `
                : ""
        }
        `;
        if (type === "dependency" || type === "platform_bom") {
            bindDependencyUsage(row, type);
            bindTargetUpgrades(row, type);
        }
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
            ${securityStatusHtml(securityTarget)}
            ${type === "dependency" || type === "platform_bom" ? targetUpgradeButtonHtml() + dependencyUsageButtonHtml(type) : ""}
            <button class="icon-btn remove-btn${securityTarget?.status === "UNUSED" ? " unused-target-remove" : ""}"
                    title="${securityTarget?.status === "UNUSED" ? "Remove unused target: not declared in any currently scanned repository" : "Remove"}">
                ${TRASH_SVG}
            </button>
        
        ${
            securityTarget
                ? `
                <div class="target-security-details hidden">
                    ${type === "platform_bom" ? platformBomSecurityDetailsHtml(securityTarget)
                        : type === "plugin" ? pluginSecurityDetailsHtml(securityTarget) : targetSecurityDetailsHtml(securityTarget)}
                </div>
            `
                : ""
        }
        `;
        if (type === "dependency" || type === "platform_bom") {
            bindDependencyUsage(newRow, type);
            bindTargetUpgrades(newRow, type);
        }
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

function dependencyUsageAvailable(type = "dependency") {
    const scans = lastLoadedData?.scans;
    const field = type === "platform_bom" ? "declaredPlatformBomKeys" : "declaredDependencyKeys";
    return !!scans?.length && scans.every(scan => Array.isArray(scan[field]));
}

function dependencyUsageRepositories(key, type = "dependency") {
    const field = type === "platform_bom" ? "declaredPlatformBomKeys" : "declaredDependencyKeys";
    return [...new Set((lastLoadedData?.scans || [])
        .filter(scan => scan[field]?.includes(key))
        .map(scan => scan.repository))]
        .sort();
}

function dependencyUsageButtonHtml(type = "dependency") {
    const available = dependencyUsageAvailable(type);
    const label = type === "platform_bom" ? "Show repositories using this platform BOM" : "Show repositories using this dependency";
    return `<button type="button" class="icon-btn dependency-usage-btn" data-usage-type="${type}" aria-label="${label}"
        aria-expanded="false" title="${available ? label : "Run Dependency Scanner first"}"
        ${available ? "" : "disabled"}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            stroke-width="1.75" stroke-linecap="round" aria-hidden="true">
            <circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/>
            <path d="M12 2v4m0 12v4M2 12h4m12 0h4"/>
        </svg>
    </button>`;
}

function bindDependencyUsage(row, type = "dependency") {
    const button = row.querySelector(".dependency-usage-btn");
    const details = document.createElement("div");
    details.className = "dependency-usage-details target-security-details hidden";
    const content = document.createElement("div");
    content.className = "finding-details target-security-detail";
    details.appendChild(content);
    row.appendChild(details);
    button.onclick = () => {
        const expanded = button.getAttribute("aria-expanded") !== "true";
        button.setAttribute("aria-expanded", String(expanded));
        details.classList.toggle("hidden", !expanded);
        if (!expanded) return;
        const key = row.querySelector(".key").value.trim();
        const repositories = dependencyUsageRepositories(key, type);
        content.replaceChildren();
        const heading = document.createElement("h4");
        heading.textContent = `${repositories.length} scanned repositories declare ${key}.`;
        content.appendChild(heading);
        const list = document.createElement("ul");
        list.className = "dependency-usage-list";
        repositories.forEach(repository => {
            const item = document.createElement("li");
            const link = document.createElement("a");
            link.textContent = repository;
            link.href = `https://github.com/${repository.split("/").map(encodeURIComponent).join("/")}`;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            const icon = document.createElement("span");
            icon.className = "dependency-usage-icon";
            icon.setAttribute("aria-hidden", "true");
            icon.innerHTML = `<svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor">
                <path d="M6.766 11.328c-2.063-.25-3.516-1.734-3.516-3.656 0-.781.281-1.625.75-2.188-.203-.515-.172-1.609.063-2.062.625-.078 1.468.25 1.968.703.594-.187 1.219-.281 1.985-.281.765 0 1.39.094 1.953.265.484-.437 1.344-.765 1.969-.687.218.422.25 1.515.046 2.047.5.593.766 1.39.766 2.203 0 1.922-1.453 3.375-3.547 3.64.531.344.89 1.094.89 1.954v1.625c0 .468.391.734.86.547C13.781 14.359 16 11.53 16 8.03 16 3.61 12.406 0 7.984 0 3.563 0 0 3.61 0 8.031a7.88 7.88 0 0 0 5.172 7.422c.422.156.828-.125.828-.547v-1.25c-.219.094-.5.156-.75.156-1.031 0-1.64-.562-2.078-1.609-.172-.422-.36-.672-.719-.719-.187-.015-.25-.093-.25-.187 0-.188.313-.328.625-.328.453 0 .844.281 1.25.86.313.452.64.655 1.031.655s.641-.14 1-.5c.266-.265.47-.5.657-.656"/>
            </svg>`;
            item.appendChild(icon);
            item.appendChild(link);
            list.appendChild(item);
        });
        content.appendChild(list);
    };
}

function refreshDependencyUsageButtons() {
    document.querySelectorAll(".dependency-usage-btn").forEach(button => {
        const type = button.dataset.usageType;
        const available = dependencyUsageAvailable(type);
        button.disabled = !available;
        button.title = available ? button.getAttribute("aria-label") : "Run Dependency Scanner first";
        button.setAttribute("aria-expanded", "false");
        button.closest(".target-row").querySelector(".dependency-usage-details").classList.add("hidden");
    });
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

function showTargetSaveMessage(text, success, autoDismiss = true) {
    closeTargetSaveMessage(true);
    const message = document.getElementById("targetSaveMessage");
    message.className = `save-banner ${success ? "save-banner-success" : "save-banner-error"}`;
    document.getElementById("targetSaveMessageText").textContent = text;
    message.hidden = false;
    message.style.setProperty("--save-banner-height", `${message.scrollHeight}px`);
    if (success && autoDismiss) {
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
        pluginSecurityScan = null;
        document.getElementById("pluginScanStatus").hidden = true;
        normalSecurityReady = false;
        bomSecurityReady = false;
        updateBomScanButton();
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
