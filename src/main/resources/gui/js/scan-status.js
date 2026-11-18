async function refresh() {
    if (isRefreshing) return;

    isRefreshing = true;

    hasActiveScan = true;
    isStartingScan = true;

    text.innerText = "Starting scan...";
    bar.style.width = "0%";

    startProgressPolling()

    try {
        const response = await fetch("/internal/api/dependency-scan/refresh", {
            method: "POST"
        });
        if (!response.ok) {
            throw new Error(`Repository refresh failed: HTTP ${response.status}`);
        }
        await loadData();
        await pollProgress();
    } catch (error) {
        console.error("Repository refresh failed", error);
        text.innerText = error.message;
        hasActiveScan = false;
        clearInterval(progressInterval);
        progressInterval = null;
    } finally {
        isRefreshing = false;
        isStartingScan = false;
    }
}

async function pollProgress() {
    try {
        if (!hasActiveScan) return;

        const p = await fetchProgress();

        if (!p) return;

        if (p.error || (!p.running && p.done >= p.total)) {
            text.innerText = p.error ? `Failed: ${p.error}` : "Done";
            if (!p.error) bar.style.width = p.total > 0 ? "100%" : "0%";
            hasActiveScan = false;
            isStartingScan = false;
            clearInterval(progressInterval);
            progressInterval = null;
            return;
        }

        if (!p.total || p.total === 0) {
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

        text.innerText = `Scanning ${p.done}/${p.total}`;

    } catch (e) {
        console.warn("progress error", e);
    }
}

function startProgressPolling() {
    if (progressInterval) return;

    progressInterval = setInterval(pollProgress, 500);
}

function scanStatusCloseButtonHtml(id, scanButtonId) {
    return `<button id="${id}Close" class="save-banner-close scan-status-close" type="button"
        aria-label="Dismiss scan status" title="Dismiss scan status"
        onclick="dismissScanStatus('${id}', '${scanButtonId}')" disabled>&times;</button>`;
}

function setScanStatusDismissible(id, dismissible) {
    document.getElementById(`${id}Close`).disabled = !dismissible;
}

function dismissScanStatus(id, scanButtonId) {
    if (document.getElementById(`${id}Close`).disabled) return;
    const container = document.getElementById(id);
    container.hidden = true;
    container.style.display = "none";
    container.innerHTML = "";
    document.getElementById(scanButtonId).focus();
}

function showScanStatusError(id, scanButtonId, message) {
    const container = document.getElementById(id);
    container.textContent = message;
    container.insertAdjacentHTML("afterbegin", scanStatusCloseButtonHtml(id, scanButtonId));
    container.hidden = false;
    container.style.display = "";
    setScanStatusDismissible(id, true);
}

function targetScanIds(includeBoms) {
    return includeBoms
        ? { container: "bomScanStatus", message: "bomScanMessage", step: "bomStep" }
        : { container: "targetScanDetails", message: "targetScanMessage", step: "scanStep" };
}

function showScanDetails(includeBoms = false) {
    const ids = targetScanIds(includeBoms);
    const container =
        document.getElementById(ids.container);

    container.hidden = false;
    container.style.display = includeBoms ? "" : "block";

    container.innerHTML = `
        ${scanStatusCloseButtonHtml(ids.container, includeBoms ? "scanBoms" : "scanTargets")}
        <div id="${includeBoms ? "bomScanSteps" : "targetScanSteps"}">
            <div
                id="${ids.step}Resolution"
                class="target-scan-step running"
            >
                <span class="step-status">RUNNING</span>
                <span>${includeBoms ? "Gradle BOM catalogs and dependency coverage" : "Gradle target resolution (dependencies only)"}</span>
            </div>

            <div
                id="${ids.step}Osv"
                class="target-scan-step"
            >
                <span class="step-status">WAITING</span>
                <span>Query OSV for vulnerabilities</span>
            </div>

            <div
                id="${ids.step}Done"
                class="target-scan-step"
            >
                <span class="step-status">WAITING</span>
                <span>Update security status</span>
            </div>
        </div>
        <div id="${ids.message}" style="margin-top:8px"></div>
    `;
    setScanStatusDismissible(ids.container, false);
    showResolutionProgress(ids.message, 0,
        includeBoms ? 0 : Object.keys(targetState?.dependencies || {}).length + 1,
        includeBoms ? "BOM coverage" : "Gradle");
}

function updateScanStep(
    id,
    status,
) {
    const element =
        document.getElementById(id);

    if (!element) {
        return;
    }

    element.classList.remove(
        "running",
        "done",
        "failed",
    );

    element.classList.add(
        status.toLowerCase()
    );

    const statusElement =
        element.querySelector(".step-status");

    statusElement.textContent =
        status;
}

async function loadCachedTargetSecurity() {
    const generation = targetScanGeneration;
    try {
        const response =
            await fetch(
                "/internal/api/target-resolution/security"
            );

        if (!response.ok) {
            console.warn(
                "Could not load target security:",
                response.status
            );
            return;
        }

        const snapshot = await response.json();

        console.log(
            "TARGET SECURITY HTTP RESPONSE (loadCachedTargetSecurity):",
            {
                status: snapshot.status,
                fingerprint: snapshot.targetFingerprint,
                targetCount: snapshot.result?.targets?.length,
                snapshot
            }
        );

        if (generation === targetScanGeneration) {
            await applyTargetSecurityResult(snapshot);
        }
    } catch (error) {
        console.warn(
            "Could not load cached target security result",
            error
        );
    }
}

async function applyTargetSecurityResult(snapshot) {
    normalSecurityReady = snapshot.normalReady === true;
    bomSecurityReady = snapshot.bomReady === true;
    updateBomScanButton();
    console.log(
        "APPLY TARGET SECURITY:",
        {
            status: snapshot.status,
            hasResult: !!snapshot.result,
            targetCount: snapshot.result?.targets?.length
        }
    );
    targetSecurityScan = snapshot.status === "READY" ? snapshot.result : null;

    console.log(
        "TARGET SECURITY STATE SET:",
        targetSecurityScan?.targets?.length
    );
}

function updateBomScanButton() {
    const button = document.getElementById("scanBoms");
    button.disabled = !normalSecurityReady;
    button.title = normalSecurityReady
        ? "Check BOM coverage using the completed normal security scan"
        : "Run normal SCAN first";
}

async function scanTargetSecurity(includeBoms = false) {
    includeBoms = includeBoms === true;
    if (includeBoms && !normalSecurityReady) return;
    const ids = targetScanIds(includeBoms);
    const generation = targetScanGeneration;
    const button =
        document.getElementById(includeBoms ? "scanBoms" : "scanTargets");

    button.disabled = true;
    document.getElementById(includeBoms ? "scanTargets" : "scanBoms").disabled = true;
    button.textContent = "SCANNING...";
    captureTargetDrafts();
    if (!includeBoms) {
        normalSecurityReady = false;
    }
    bomSecurityReady = false;
    renderTargetTables();
    if (lastLoadedData?.repos) render(lastLoadedData);

    showScanDetails(includeBoms);

    try {
        updateScanStep(
            `${ids.step}Resolution`,
            "RUNNING",
        );

        const resolution =
            await startOrWaitForResolution(includeBoms);

        if (!resolution) {
            throw new Error(
                "Target resolution did not complete"
            );
        }

        updateScanStep(
            `${ids.step}Resolution`,
            "DONE",
        );

        updateScanStep(
            `${ids.step}Osv`,
            "RUNNING",
        );

        const security =
            await startOrWaitForSecurity(includeBoms);

        if (!security) {
            throw new Error(
                "Security scan did not complete"
            );
        }

        if (generation !== targetScanGeneration) {
            throw new Error("Target versions changed during the scan. Run SCAN again.");
        }

        await applyTargetSecurityResult(security);
        captureTargetDrafts();
        renderTargetTables();
        await loadData();

        console.log("TARGET SECURITY SCAN:", targetSecurityScan);
        console.log(
            "TARGET SECURITY TARGETS:",
            targetSecurityScan?.targets
        );

        updateScanStep(
            `${ids.step}Osv`,
            "DONE",
        );

        updateScanStep(
            `${ids.step}Done`,
            "DONE",
        );

        showScanMessage(ids.message, "DONE");
    } catch (error) {
        console.error(error);

        updateScanStep(
            `${ids.step}Resolution`,
            "FAILED",
        );

        updateScanStep(
            `${ids.step}Osv`,
            "FAILED",
        );

        updateScanStep(
            `${ids.step}Done`,
            "FAILED",
        );

        showScanMessage(ids.message, `FAILED: ${error.message}`);
    } finally {
        setScanStatusDismissible(ids.container, true);
        button.disabled = false;
        document.getElementById(includeBoms ? "scanTargets" : "scanBoms").disabled = false;
        button.textContent = "SCAN";
        updateBomScanButton();
    }
}

async function startOrWaitForResolution(includeBoms = false) {
    const ids = targetScanIds(includeBoms);
    const query = includeBoms ? "?includeBoms=true" : "";
    const startResponse =
        await fetch(
            "/internal/api/target-resolution/start" + query
        );

    if (!startResponse.ok && startResponse.status !== 202) {
        const text = await startResponse.text();
        throw new Error(
            `Failed to start target resolution: ${text}`
        );
    }

    let snapshot = await startResponse.json();
    const expectedFingerprint = snapshot.targetFingerprint;

    showResolutionProgress(ids.message, snapshot.completedResolutions, snapshot.totalResolutions,
        includeBoms ? "BOM coverage" : "Gradle");
    if (snapshot.status === "READY") {
        return snapshot;
    }

    if (snapshot.status === "IDLE") {
        throw new Error(
            "Target resolution is idle"
        );
    }

    while (snapshot.status === "RUNNING") {
        await new Promise(
            resolve => setTimeout(resolve, 1000)
        );

        const response =
            await fetch(
                "/internal/api/target-resolution" + query
            );

        if (!response.ok && response.status !== 202) {
            const text = await response.text();

            throw new Error(
                `Failed to get target resolution: ${text}`
            );
        }

        snapshot = await response.json();
        if (snapshot.targetFingerprint !== expectedFingerprint) {
            throw new Error("Target versions changed during resolution. Run SCAN again.");
        }
        showResolutionProgress(ids.message, snapshot.completedResolutions, snapshot.totalResolutions,
            includeBoms ? "BOM coverage" : "Gradle");
    }

    if (snapshot.status === "READY") {
        return snapshot;
    }

    if (snapshot.status === "FAILED") {
        throw new Error(
            snapshot.error ||
            "Target resolution failed"
        );
    }

    throw new Error(
        `Unexpected target resolution status: ${snapshot.status}`
    );
}

async function startOrWaitForSecurity(includeBoms = false) {
    const query = includeBoms ? "?includeBoms=true" : "";
    const startResponse =
        await fetch(
            "/internal/api/target-resolution/security/start" + query
        );

    if (!startResponse.ok && startResponse.status !== 202) {
        const text = await startResponse.text();

        throw new Error(
            `Failed to start security scan: ${text}`
        );
    }

    let snapshot = await startResponse.json();
    const expectedFingerprint = snapshot.targetFingerprint;

    if (snapshot.status === "READY") {
        return snapshot;
    }

    if (snapshot.status === "IDLE") {
        throw new Error(
            "Security scan is idle"
        );
    }

    while (snapshot.status === "RUNNING") {
        await new Promise(
            resolve => setTimeout(resolve, 1000)
        );

        const response =
            await fetch(
                "/internal/api/target-resolution/security" + query
            );

        if (!response.ok && response.status !== 202) {
            const text = await response.text();

            throw new Error(
                `Failed to get security scan: ${text}`
            );
        }

        snapshot = await response.json();
        if (snapshot.targetFingerprint !== expectedFingerprint) {
            throw new Error("Target versions changed during security scan. Run SCAN again.");
        }
    }

    if (snapshot.status === "READY") {
        return snapshot;
    }

    if (snapshot.status === "FAILED") {
        throw new Error(
            snapshot.error ||
            "Security scan failed"
        );
    }

    throw new Error(
        `Unexpected security scan status: ${snapshot.status}`
    );
}

async function fetchPluginSecurity(start = false) {
    const response = await fetch("/internal/api/plugin-security" + (start ? "/start" : ""), { cache: "no-store" });
    const snapshot = await response.json();
    if (!response.ok || snapshot.status === "FAILED") {
        throw new Error(snapshot.error || `Plugin scan failed: HTTP ${response.status}`);
    }
    return snapshot;
}

function showPluginScanDetails(message = "") {
    const container = document.getElementById("pluginScanStatus");
    container.hidden = false;
    container.style.display = "";
    container.innerHTML = `
        ${scanStatusCloseButtonHtml("pluginScanStatus", "scanPlugins")}
        <div id="pluginScanSteps">
            <div id="pluginStepResolution" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>Resolve published plugin metadata (not applied or executed)</span>
            </div>
            <div id="pluginStepOsv" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>Query OSV for vulnerabilities</span>
            </div>
            <div id="pluginStepDone" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>Update security status</span>
            </div>
        </div>
        <div id="pluginScanMessage" style="margin-top:8px"></div>
    `;
    setScanStatusDismissible("pluginScanStatus", false);
    showPluginScanMessage(message);
}

function showPluginScanMessage(message) {
    showScanMessage("pluginScanMessage", message);
}

function updatePluginScanSteps(phase, completedResolutions, totalResolutions) {
    if (phase === "OSV") {
        updateScanStep("pluginStepResolution", "DONE");
        updateScanStep("pluginStepOsv", "RUNNING");
    } else {
        updateScanStep("pluginStepResolution", "RUNNING");
    }
    showResolutionProgress("pluginScanMessage", completedResolutions, totalResolutions, "plugin");
}

async function loadCachedPluginSecurity() {
    const generation = targetScanGeneration;
    try {
        const snapshot = await fetchPluginSecurity();
        if (generation === targetScanGeneration) {
            pluginSecurityScan = snapshot.status === "READY" ? snapshot.result : null;
            if (snapshot.status === "RUNNING") {
                showPluginScanDetails();
                updatePluginScanSteps(snapshot.phase, snapshot.completedResolutions, snapshot.totalResolutions);
            }
        }
    } catch (error) {
        console.error("Could not load plugin security", error);
        if (generation !== targetScanGeneration) return;
        showScanStatusError("pluginScanStatus", "scanPlugins", error.message);
    }
}

async function scanPluginSecurity() {
    const button = document.getElementById("scanPlugins");
    if (button.disabled) return;
    const generation = targetScanGeneration;
    button.disabled = true;
    button.textContent = "SCANNING...";
    showPluginScanDetails();
    updateScanStep("pluginStepResolution", "RUNNING");
    pluginSecurityScan = null;
    captureTargetDrafts();
    renderTargetTables();
    try {
        let snapshot = await fetchPluginSecurity(true);
        const fingerprint = snapshot.targetFingerprint;
        while (snapshot.status === "RUNNING") {
            updatePluginScanSteps(snapshot.phase, snapshot.completedResolutions, snapshot.totalResolutions);
            await new Promise(resolve => setTimeout(resolve, 1000));
            if (generation !== targetScanGeneration) return;
            snapshot = await fetchPluginSecurity();
            if (snapshot.targetFingerprint !== fingerprint) {
                throw new Error("Target versions changed during plugin scanning. Run SCAN in Plugins again.");
            }
        }
        if (generation !== targetScanGeneration) return;
        if (snapshot.status !== "READY" || !snapshot.result) {
            throw new Error(`Plugin scan did not complete: ${snapshot.status}`);
        }
        updateScanStep("pluginStepResolution", "DONE");
        updateScanStep("pluginStepOsv", "DONE");
        pluginSecurityScan = snapshot.result;
        captureTargetDrafts();
        renderTargetTables();
        updateScanStep("pluginStepDone", "DONE");
        const incomplete = snapshot.result.plugins.filter(plugin =>
            plugin.status === "UNRESOLVED" || plugin.status === "UNSUPPORTED").length;
        showPluginScanMessage(incomplete
            ? `DONE with ${incomplete} unresolved or unsupported plugins. See their details.`
            : "DONE");
    } catch (error) {
        console.error("Plugin security scan failed", error);
        if (generation === targetScanGeneration) {
            updateScanStep("pluginStepResolution", "FAILED");
            updateScanStep("pluginStepOsv", "FAILED");
            updateScanStep("pluginStepDone", "FAILED");
            showPluginScanMessage(`FAILED: ${error.message}`);
        }
    } finally {
        if (generation === targetScanGeneration) setScanStatusDismissible("pluginScanStatus", true);
        button.disabled = false;
        button.textContent = "SCAN";
    }
}

async function fetchNodeSecurity(start = false) {
    const response = await fetch("/internal/api/node-security" + (start ? "/start" : ""), { cache: "no-store" });
    const snapshot = await response.json();
    if (!response.ok || snapshot.status === "FAILED") {
        throw new Error(snapshot.error || `Node scan failed: HTTP ${response.status}`);
    }
    return snapshot;
}

function showNodeScanDetails(message = "") {
    const container = document.getElementById("nodeScanStatus");
    container.hidden = false;
    container.style.display = "";
    container.innerHTML = `
        ${scanStatusCloseButtonHtml("nodeScanStatus", "scanNode")}
        <div id="nodeScanSteps">
            <div id="nodeStepResolution" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>npm target resolution</span>
            </div>
            <div id="nodeStepOsv" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>Query OSV for vulnerabilities</span>
            </div>
            <div id="nodeStepCoverage" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>Check override coverage</span>
            </div>
            <div id="nodeStepDone" class="target-scan-step">
                <span class="step-status">WAITING</span>
                <span>Update security status</span>
            </div>
        </div>
        <div id="nodeScanMessage" style="margin-top:8px"></div>
    `;
    setScanStatusDismissible("nodeScanStatus", false);
    showNodeScanMessage(message);
}

function showNodeScanMessage(message, completedResolutions = 0, totalResolutions = 0) {
    showScanMessage("nodeScanMessage", message, completedResolutions, totalResolutions, "npm resolutions");
}

function showScanMessage(id, message, completedResolutions = 0, totalResolutions = 0, label = "Gradle resolutions") {
    const element = document.getElementById(id);
    if (!element) return;
    element.textContent = message;
    element.hidden = !message;
    const progress = totalResolutions > 0;
    element.classList.toggle("scan-resolution-progress", progress);
    element.style.setProperty("--scan-resolution-progress", progress ? `${completedResolutions / totalResolutions * 100}%` : "0%");
    if (progress) {
        element.setAttribute("role", "progressbar");
        element.setAttribute("aria-label", label);
        element.setAttribute("aria-valuemin", "0");
        element.setAttribute("aria-valuemax", String(totalResolutions));
        element.setAttribute("aria-valuenow", String(completedResolutions));
        element.setAttribute("aria-valuetext", message);
    } else {
        for (const attribute of ["role", "aria-label", "aria-valuemin", "aria-valuemax", "aria-valuenow", "aria-valuetext"]) {
            element.removeAttribute(attribute);
        }
    }
}

function showResolutionProgress(id, completedResolutions = 0, totalResolutions = 0, resolutionType = "Gradle") {
    const completed = Math.min(totalResolutions, Math.max(0, completedResolutions));
    showScanMessage(id, totalResolutions
        ? `${completed} / ${totalResolutions} ${resolutionType} resolutions completed`
        : "Scan in progress...", completed, totalResolutions, `${resolutionType} resolutions`);
}

function updateNodeScanSteps(phase, completedResolutions = 0, totalResolutions) {
    const coverage = phase === "COVERAGE";
    updateScanStep("nodeStepResolution", phase === "OSV" || coverage ? "DONE" : "RUNNING");
    updateScanStep("nodeStepOsv", coverage ? "DONE" : phase === "OSV" ? "RUNNING" : "WAITING");
    updateScanStep("nodeStepCoverage", coverage ? "RUNNING" : "WAITING");
    const targetCount = Object.keys(targetState?.nodeDependencies || {}).length;
    const total = totalResolutions ?? (targetCount ? targetCount + 1 : 0);
    showResolutionProgress("nodeScanMessage", completedResolutions, total, "npm");
}

async function loadCachedNodeSecurity() {
    const generation = targetScanGeneration;
    try {
        const snapshot = await fetchNodeSecurity();
        if (generation !== targetScanGeneration) return;
        nodeSecurityScan = snapshot.status === "READY" ? snapshot.result : null;
        const status = document.getElementById("nodeScanStatus");
        status.hidden = snapshot.status !== "RUNNING";
        if (!status.hidden) {
            showNodeScanDetails();
            updateNodeScanSteps(snapshot.phase, snapshot.completedResolutions, snapshot.totalResolutions);
        }
    } catch (error) {
        console.error("Could not load Node security", error);
        if (generation !== targetScanGeneration) return;
        nodeSecurityScan = null;
        showScanStatusError("nodeScanStatus", "scanNode", error.message);
    }
}

async function scanNodeSecurity() {
    const button = document.getElementById("scanNode");
    if (button.disabled) return;
    const generation = targetScanGeneration;
    button.disabled = true;
    button.textContent = "SCANNING...";
    showNodeScanDetails();
    updateNodeScanSteps();
    nodeSecurityScan = null;
    captureTargetDrafts();
    renderTargetTables();
    if (lastLoadedData?.repos) render(lastLoadedData);
    try {
        let snapshot = await fetchNodeSecurity(true);
        const fingerprint = snapshot.targetFingerprint;
        while (snapshot.status === "RUNNING") {
            updateNodeScanSteps(snapshot.phase, snapshot.completedResolutions, snapshot.totalResolutions);
            await new Promise(resolve => setTimeout(resolve, 1000));
            if (generation !== targetScanGeneration) return;
            snapshot = await fetchNodeSecurity();
            if (snapshot.targetFingerprint !== fingerprint) {
                throw new Error("Target versions changed during Node scanning. Run SCAN in Node again.");
            }
        }
        if (generation !== targetScanGeneration) return;
        if (snapshot.status !== "READY" || !snapshot.result) {
            throw new Error(`Node scan did not complete: ${snapshot.status}`);
        }
        nodeSecurityScan = snapshot.result;
        updateScanStep("nodeStepResolution", "DONE");
        updateScanStep("nodeStepOsv", "DONE");
        updateScanStep("nodeStepCoverage", "DONE");
        captureTargetDrafts();
        renderTargetTables();
        await loadData();
        updateScanStep("nodeStepDone", "DONE");
        const incomplete = snapshot.result.targets.filter(target =>
            target.status === "UNRESOLVED" || target.status === "UNSUPPORTED").length;
        showNodeScanMessage(incomplete
            ? `DONE with ${incomplete} unresolved or unsupported targets. See their details.`
            : "DONE");
    } catch (error) {
        console.error("Node security scan failed", error);
        if (generation === targetScanGeneration) {
            updateScanStep("nodeStepResolution", "FAILED");
            updateScanStep("nodeStepOsv", "FAILED");
            updateScanStep("nodeStepCoverage", "FAILED");
            updateScanStep("nodeStepDone", "FAILED");
            showNodeScanMessage(`FAILED: ${error.message}`);
        }
    } finally {
        if (generation === targetScanGeneration) setScanStatusDismissible("nodeScanStatus", true);
        button.disabled = false;
        button.textContent = "SCAN";
    }
}
