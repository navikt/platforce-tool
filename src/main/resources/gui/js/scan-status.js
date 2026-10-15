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

function showScanDetails(includeBoms = false) {
    const container =
        document.getElementById("targetScanDetails");

    container.style.display = "block";

    container.innerHTML = `
        <div id="targetScanSteps">
            <div
                id="scanStepResolution"
                class="target-scan-step running"
            >
                <span class="step-status">RUNNING</span>
                <span>${includeBoms ? "Gradle BOM catalogs and dependency coverage" : "Gradle target resolution (dependencies only)"}</span>
            </div>

            <div
                id="scanStepOsv"
                class="target-scan-step"
            >
                <span class="step-status">WAITING</span>
                <span>Query OSV for vulnerabilities</span>
            </div>

            <div
                id="scanStepDone"
                class="target-scan-step"
            >
                <span class="step-status">WAITING</span>
                <span>Update security status</span>
            </div>
        </div>
    `;
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

async function scanTargetSecurity(includeBoms = false) {
    includeBoms = includeBoms === true;
    const generation = targetScanGeneration;
    const button =
        document.getElementById(includeBoms ? "scanBoms" : "scanTargets");

    button.disabled = true;
    document.getElementById(includeBoms ? "scanTargets" : "scanBoms").disabled = true;
    button.textContent = "SCANNING...";

    showScanDetails(includeBoms);

    try {
        updateScanStep(
            "scanStepResolution",
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
            "scanStepResolution",
            "DONE",
        );

        updateScanStep(
            "scanStepOsv",
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

        const previousBoms = targetSecurityScan?.platformBoms;
        await applyTargetSecurityResult(security);
        if (!includeBoms && targetSecurityScan && previousBoms) {
            targetSecurityScan.platformBoms = previousBoms;
        }
        captureTargetDrafts();
        renderTargetTables();
        await loadData();

        console.log("TARGET SECURITY SCAN:", targetSecurityScan);
        console.log(
            "TARGET SECURITY TARGETS:",
            targetSecurityScan?.targets
        );

        updateScanStep(
            "scanStepOsv",
            "DONE",
        );

        updateScanStep(
            "scanStepDone",
            "DONE",
        );

        document.getElementById(
            "targetScanDetails"
        ).insertAdjacentHTML(
            "beforeend",
            `
                <div style="margin-top:8px">
                    DONE
                </div>
            `
        );
    } catch (error) {
        console.error(error);

        updateScanStep(
            "scanStepResolution",
            "FAILED",
        );

        updateScanStep(
            "scanStepOsv",
            "FAILED",
        );

        updateScanStep(
            "scanStepDone",
            "FAILED",
        );

        document.getElementById(
            "targetScanDetails"
        ).insertAdjacentHTML(
            "beforeend",
            `
                <div style="margin-top:8px">
                    FAILED:
                    ${escapeHtml(error.message)}
                </div>
            `
        );
    } finally {
        button.disabled = false;
        document.getElementById(includeBoms ? "scanTargets" : "scanBoms").disabled = false;
        button.textContent = includeBoms ? "Scan BOMs" : "SCAN";
    }
}

async function startOrWaitForResolution(includeBoms = false) {
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
