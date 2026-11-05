function targetUpgradeButtonHtml() {
    return `<button type="button" class="icon-btn target-upgrade-btn" aria-label="Show available upgrades"
        title="Show available upgrades" aria-expanded="false">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12.5303 4.46967C12.3897 4.32902 12.1989 4.25 12 4.25C11.8011 4.25 11.6103 4.32902 11.4697 4.46967L6.96967 8.96971C6.67678 9.2626 6.67678 9.73747 6.96967 10.0304C7.26257 10.3233 7.73744 10.3233 8.03033 10.0304L11.25 6.81067V19C11.25 19.4142 11.5858 19.75 12 19.75C12.4142 19.75 12.75 19.4142 12.75 19V6.81067L15.9697 10.0304C16.2626 10.3233 16.7374 10.3233 17.0303 10.0304C17.3232 9.73747 17.3232 9.2626 17.0303 8.9697L12.5303 4.46967Z" fill="currentColor"/>
        </svg>
    </button>`;
}

async function fetchAvailableUpgrades(key, currentVersion, type = "dependency") {
    const response = await fetch(
        `/internal/api/target-versions/available?key=${encodeURIComponent(key)}&currentVersion=${encodeURIComponent(currentVersion)}${type === "plugin" ? "&kind=plugin" : ""}`,
        { cache: "no-store" }
    );
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `Version lookup failed: HTTP ${response.status}`);
    return result;
}

async function upgradeAllDependencies() {
    const button = document.getElementById("upgradeAllDependencies");
    if (button.disabled) return;
    const original = button.innerHTML;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    button.innerHTML = '<span class="spinner" aria-hidden="true"></span>';
    const generation = targetScanGeneration;
    const rows = Array.from(document.querySelectorAll("#depsTable .target-row"))
        .map(row => ({
            row,
            key: row.querySelector(".key")?.value.trim(),
            version: row.querySelector(".version")?.value.trim()
        }))
        .filter(target => target.key && target.version && !ignoredDependencies.includes(target.key));
    const upgrades = [];
    const errors = [];
    closeTargetSaveMessage(true);
    try {
        for (const target of rows) {
            if (generation !== targetScanGeneration) {
                showTargetSaveMessage("Target versions changed. Run Upgrade all again.", false);
                return;
            }
            try {
                const result = await fetchAvailableUpgrades(target.key, target.version);
                const major = target.version.match(/^\d+/)?.[0].replace(/^0+(?=\d)/, "");
                const candidate = result.versions.find(version =>
                    !version.prerelease && !version.majorUpgrade && major !== undefined &&
                    version.version.match(/^\d+/)?.[0].replace(/^0+(?=\d)/, "") === major);
                if (candidate) upgrades.push({ ...target, next: candidate.version });
            } catch (error) {
                console.error("Bulk dependency upgrade lookup failed", error);
                errors.push(`${target.key}: ${error.message}`);
            }
        }
        if (generation !== targetScanGeneration) {
            showTargetSaveMessage("Target versions changed. Run Upgrade all again.", false);
            return;
        }
        captureTargetDrafts();
        const changes = [];
        for (const target of upgrades) {
            if (!target.row.isConnected ||
                target.row.querySelector(".key").value.trim() !== target.key ||
                target.row.querySelector(".version").value.trim() !== target.version) {
                errors.push(`${target.key}: changed during lookup; skipped.`);
                continue;
            }
            targetState.dependencies[target.key] = target.next;
            targetState.drafted.add(`DEPENDENCY:${target.key}`);
            changes.push(`${target.key} ${target.version} -> ${target.next}`);
        }
        if (changes.length) renderTargetTables();
        const summary = changes.length
            ? `Drafted upgrades:\n${changes.join("\n")}`
            : errors.length ? "No upgrades drafted." : "No non-major updates available.";
        showTargetSaveMessage(
            summary + (errors.length ? `\nLookup issues:\n${errors.join("\n")}` : ""),
            errors.length === 0,
            false
        );
    } finally {
        button.innerHTML = original;
        button.disabled = false;
        button.setAttribute("aria-busy", "false");
    }
}

function renderAvailableUpgrades(panel, result, type, selectVersion, currentVersion = result.currentVersion) {
    panel.replaceChildren();
    const heading = document.createElement("h4");
    heading.textContent = `Available upgrades for ${result.key}`;
    const list = document.createElement("ul");
    list.className = "target-upgrade-list";
    const versions = result.versions.filter(candidate => !candidate.prerelease);
    versions.forEach(candidate => {
        const item = document.createElement("li");
        const button = document.createElement("button");
        button.type = "button";
        button.className = "target-upgrade-version";
        button.textContent = candidate.version;
        button.setAttribute("aria-label", `Draft ${result.key} version ${candidate.version}`);
        button.onclick = () => selectVersion(type.toUpperCase(), result.key, candidate.version);
        item.appendChild(button);
        const security = document.createElement("span");
        security.className = "upgrade-security-result";
        security.dataset.version = candidate.version;
        item.appendChild(security);
        if (candidate.majorUpgrade) {
            const badge = document.createElement("span");
            badge.className = "target-version-pill";
            badge.textContent = "Major upgrade";
            item.appendChild(badge);
        }
        list.appendChild(item);
    });
    panel.append(heading, list);
    if (versions.length && type !== "platform_bom") {
        const scan = document.createElement("button");
        scan.type = "button";
        scan.className = "btn-secondary upgrade-scan-button";
        scan.textContent = "SCAN";
        scan.title = "Resolve all listed stable versions independently and check their transitive dependencies with OSV";
        panel.appendChild(scan);
        const message = document.createElement("p");
        message.setAttribute("role", "status");
        panel.appendChild(message);
        const generation = targetScanGeneration;
        const active = () => panel.isConnected && list.isConnected && generation === targetScanGeneration;
        const apply = snapshot => {
            for (const item of list.children) {
                const badge = item.querySelector(".upgrade-security-result");
                const finding = snapshot.result?.find(entry => entry.version === badge.dataset.version);
                if (!finding) continue;
                badge.className = `upgrade-security-result target-version-pill ${finding.status === "VULNERABLE" ? "target-status-vulnerable" : finding.status === "OK" ? "target-status-ok" : "target-status-unknown"}`;
                badge.textContent = finding.status === "VULNERABLE"
                    ? `${finding.vulnerabilityCount} vulnerabilities` : finding.status;
                badge.title = finding.error || "Standalone resolved dependency tree, including transitives. No application overrides.";
            }
        };
        const fetchSnapshot = async start => {
            const response = await fetch(`/internal/api/target-versions/upgrade-security${start ? "/start" : ""}?kind=${type}&key=${encodeURIComponent(result.key)}&currentVersion=${encodeURIComponent(currentVersion)}`, { cache: "no-store" });
            const snapshot = await response.json();
            if (!response.ok) throw new Error(snapshot.error || `Upgrade scan failed: HTTP ${response.status}`);
            return snapshot;
        };
        const follow = async snapshot => {
            scan.disabled = true;
            scan.innerHTML = '<span class="spinner" aria-hidden="true"></span>';
            try {
                const fingerprint = snapshot.targetFingerprint;
                while (snapshot.status === "RUNNING") {
                    message.textContent = "Resolving candidate versions and checking OSV...";
                    await new Promise(resolve => setTimeout(resolve, 1000));
                    if (!active()) return;
                    snapshot = await fetchSnapshot(false);
                    if (snapshot.targetFingerprint !== fingerprint) throw new Error("Targets changed. Reopen available upgrades.");
                }
                if (!active()) return;
                if (snapshot.status !== "READY") throw new Error(snapshot.error || "Upgrade scan did not complete");
                apply(snapshot);
                message.textContent = "";
            } finally {
                scan.disabled = false;
                scan.textContent = "SCAN";
            }
        };
        const report = error => {
            console.error("Upgrade security scan failed", error);
            if (active()) message.textContent = `FAILED: ${error.message}`;
        };
        scan.onclick = async () => {
            if (scan.disabled) return;
            scan.disabled = true;
            scan.innerHTML = '<span class="spinner" aria-hidden="true"></span>';
            try { await follow(await fetchSnapshot(true)); } catch (error) { report(error); }
            finally { scan.disabled = false; scan.textContent = "SCAN"; }
        };
        fetchSnapshot(false).then(snapshot => {
            if (!active()) return;
            if (snapshot.status === "READY") apply(snapshot);
            else if (snapshot.status === "RUNNING") return follow(snapshot);
            else if (snapshot.status === "FAILED") throw new Error(snapshot.error);
        }).catch(report);
    }
    if (!versions.length) {
        const empty = document.createElement("p");
        empty.setAttribute("role", "status");
        empty.textContent = "No newer stable versions found.";
        panel.appendChild(empty);
    }
}

function bindTargetUpgrades(row, type) {
    const button = row.querySelector(".target-upgrade-btn");
    const keyInput = row.querySelector(".key");
    const versionInput = row.querySelector(".version");
    const panel = document.createElement("div");
    panel.className = "target-security-details hidden";
    const details = document.createElement("div");
    details.className = "finding-details target-security-detail";
    panel.appendChild(details);
    row.appendChild(panel);
    let requestId = 0;
    function close() {
        requestId++;
        button.setAttribute("aria-expanded", "false");
        panel.classList.add("hidden");
    }
    keyInput.addEventListener("input", close);
    versionInput.addEventListener("input", close);
    button.onclick = async () => {
        if (button.getAttribute("aria-expanded") === "true") {
            close();
            return;
        }
        const id = ++requestId;
        const key = keyInput.value.trim();
        const currentVersion = versionInput.value.trim();
        button.setAttribute("aria-expanded", "true");
        panel.classList.remove("hidden");
        details.innerHTML = '<p role="status">Loading available versions...</p>';
        try {
            const result = await fetchAvailableUpgrades(key, currentVersion, type);
            if (id !== requestId || !row.isConnected) return;
            renderAvailableUpgrades(details, result, type, (kind, coordinate, version) => {
                if (keyInput.value.trim() !== key || versionInput.value.trim() !== currentVersion) return;
                addDraftTarget(kind, coordinate, version);
                const containerId = type === "platform_bom" ? "platformBomsTable" : type === "plugin" ? "pluginsTable" : "depsTable";
                Array.from(document.querySelectorAll(`#${containerId} .target-row`))
                    .find(target => target.querySelector(".key")?.value === coordinate)
                    ?.querySelector(".version")?.focus();
            });
        } catch (error) {
            console.error("Available version lookup failed", error);
            if (id !== requestId || !row.isConnected) return;
            const message = document.createElement("p");
            message.setAttribute("role", "alert");
            message.textContent = `Could not load versions: ${error.message}. Close and reopen to retry.`;
            details.replaceChildren(message);
        }
    };
}
