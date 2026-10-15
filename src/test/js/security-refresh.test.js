const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");
const { loadGuiScripts } = require("./gui-scripts");

function gui() {
    const elements = new Map();
    const alerts = [];
    const rendered = [];
    const steps = [];
    const calls = [];
    const rows = new Map();
    const timers = new Map();
    let timerId = 0;
    let targetRenders = 0;

    function element(id) {
        if (!elements.has(id)) {
            elements.set(id, {
                listeners: {},
                style: { setProperty() {} },
                classList: { add() {}, remove() {}, toggle() {} },
                addEventListener(event, listener) { this.listeners[event] = listener; },
                insertAdjacentHTML() {}
            });
        }
        return elements.get(id);
    }

    const context = vm.createContext({
        document: {
            getElementById: element,
            querySelectorAll: selector => rows.get(selector) || [],
            querySelector: selector => rows.get(selector)?.[0] || null,
            addEventListener() {}
        },
        console: { log() {}, warn() {}, error() {} },
        alert: message => alerts.push(message),
        setTimeout: (callback, delay) => {
            if (delay === 6000 || delay === 300) {
                const id = ++timerId;
                timers.set(id, () => {
                    timers.delete(id);
                    callback();
                });
                return id;
            }
            callback();
        },
        clearTimeout: id => timers.delete(id),
        setInterval: () => 1,
        clearInterval() {},
        // Leave automatic page initialization pending; each test drives the real flow explicitly.
        fetch: () => new Promise(() => {})
    });
    loadGuiScripts(context);
    context.renderTargetTables = () => targetRenders++;
    context.render = data => rendered.push(data);
    context.renderIgnoredRepositories = () => {};
    context.showScanDetails = () => {};
    context.updateScanStep = (id, status) => steps.push([id, status]);

    function responses(handler) {
        context.fetch = async (url, options = {}) => {
            calls.push([url, options.method || "GET"]);
            const body = await handler(url, options);
            return {
                ok: true,
                status: 200,
                json: async () => body,
                text: async () => JSON.stringify(body)
            };
        };
    }

    return {
        context, calls, alerts, rendered, steps, rows, element, responses, timers,
        targetRenders: () => targetRenders,
        value: expression => vm.runInContext(expression, context)
    };
}

function repositoryResponse(url) {
    if (url === "/internal/repos/view") {
        return [{ name: "example/repository", team: "team", state: "SCANNED" }];
    }
    if (url === "/internal/api/dependency-scan") {
        return [{ repository: "example/repository", findings: [{ status: "ADD", key: "example:override" }] }];
    }
    if (url === "/internal/api/repository-notes") return {};
    if (url === "/internal/api/ignored-repositories") return { repositories: [] };
    throw new Error(`Unexpected API call: ${url}`);
}

test("completed security polling reloads and renders enriched repository data without Refresh Scan", async () => {
    const app = gui();
    app.responses(url => {
        if (url === "/internal/api/target-resolution/start") {
            return { status: "READY", targetFingerprint: "targets" };
        }
        if (url === "/internal/api/target-resolution/security/start") {
            return { status: "RUNNING", targetFingerprint: "targets" };
        }
        if (url === "/internal/api/target-resolution/security") {
            return { status: "READY", targetFingerprint: "targets", result: { targets: [] } };
        }
        return repositoryResponse(url);
    });

    // Drive the same handler as the SCAN button, including its polling and subsequent GUI reload.
    await app.context.scanTargetSecurity();
    assert.equal(app.rendered.length, 1);
    assert.equal(app.rendered[0].scans[0].findings[0].status, "ADD");
    assert.equal(app.targetRenders(), 1);
    assert.ok(app.calls.every(([, method]) => method === "GET"));
    assert.ok(app.calls.every(([url]) => !url.includes("dependency-scan/refresh")));
    assert.equal(app.element("scanTargets").disabled, false);
    assert.ok(app.steps.some(([id, status]) => id === "scanStepDone" && status === "DONE"));
});

test("immediately ready security result also reloads repository data", async () => {
    const app = gui();
    app.responses(url => {
        if (url.endsWith("/start")) {
            return { status: "READY", targetFingerprint: "targets", result: { targets: [] } };
        }
        return repositoryResponse(url);
    });

    await app.context.scanTargetSecurity();
    assert.equal(app.rendered[0].scans[0].findings[0].status, "ADD");
    assert.ok(app.value("targetSecurityScan !== null"));
});

test("BOM scan explicitly opts in for resolution and security while ordinary scan preserves completed BOM results", async () => {
    const app = gui();
    const bom = { key: "example:bom", targetVersion: "1.0", status: "OK" };
    app.responses(url => {
        if (url.includes("/target-resolution")) {
            return {
                status: "READY", targetFingerprint: "targets",
                result: { targets: [], platformBoms: url.includes("includeBoms=true") ? [bom] : [] }
            };
        }
        return repositoryResponse(url);
    });
    await app.context.scanTargetSecurity(true);
    assert.ok(app.calls.some(([url]) => url === "/internal/api/target-resolution/start?includeBoms=true"));
    assert.ok(app.calls.some(([url]) => url === "/internal/api/target-resolution/security/start?includeBoms=true"));
    assert.equal(app.value("targetSecurityScan.platformBoms[0].key"), "example:bom");
    app.calls.length = 0;
    // Browser click listeners pass an event object; it must not accidentally opt in to BOM work.
    await app.context.scanTargetSecurity({ type: "click" });
    assert.ok(app.calls.every(([url]) => !url.includes("includeBoms")));
    assert.equal(app.value("targetSecurityScan.platformBoms[0].key"), "example:bom");
    assert.equal(app.element("scanBoms").disabled, false);
    assert.equal(app.element("scanTargets").disabled, false);
});

test("targets saved during a security scan prevent its old result from being applied", async () => {
    const app = gui();
    app.responses(url => {
        if (url === "/internal/api/target-resolution/start") {
            return { status: "READY", targetFingerprint: "old" };
        }
        if (url === "/internal/api/target-resolution/security/start") {
            return { status: "RUNNING", targetFingerprint: "old" };
        }
        if (url === "/internal/api/target-resolution/security") {
            app.value("targetScanGeneration++; targetSecurityScan = null");
            return { status: "READY", targetFingerprint: "old", result: { targets: [] } };
        }
        throw new Error(`Unexpected API call: ${url}`);
    });

    await app.context.scanTargetSecurity();
    assert.equal(app.value("targetSecurityScan"), null);
    assert.equal(app.rendered.length, 0);
    assert.ok(app.steps.some(([id, status]) => id === "scanStepDone" && status === "FAILED"));
});

test("polling rejects a security result for different target inputs", async () => {
    const app = gui();
    app.responses(url => ({
        status: url.endsWith("/start") ? "RUNNING" : "READY",
        targetFingerprint: url.endsWith("/start") ? "original" : "changed",
        result: { targets: [] }
    }));

    await assert.rejects(app.context.startOrWaitForSecurity(), /Target versions changed/);
    assert.equal(app.value("targetSecurityScan"), null);
});

test("non-ready cached security snapshot clears old target security state", async () => {
    const app = gui();
    app.value("targetSecurityScan = { targets: [{ key: 'old' }] }");
    await app.context.applyTargetSecurityResult({ status: "IDLE", result: null });
    assert.equal(app.value("targetSecurityScan"), null);
});

test("saving targets clears old security and reloads comparisons", async () => {
    const app = gui();
    app.value("targetSecurityScan = { targets: [{ key: 'old' }] }");
    app.responses(url => url === "/internal/api/target-versions/update" ? {} : repositoryResponse(url));

    await app.element("saveTargets").listeners.click();
    assert.equal(app.value("targetSecurityScan"), null);
    assert.equal(app.value("targetScanGeneration"), 1);
    assert.equal(app.rendered.length, 1);
    assert.deepEqual(app.alerts, []);
    assert.equal(app.element("targetSaveMessageText").textContent, "Target versions updated");
    assert.equal(app.element("targetSaveMessage").className, "save-banner save-banner-success");
    assert.equal(app.element("targetSaveMessage").hidden, false);
});

test("failed target save retains existing security and does not report success", async () => {
    const app = gui();
    app.value("targetSecurityScan = { targets: [{ key: 'old' }] }");
    app.context.fetch = async () => ({ ok: false, status: 500 });

    await app.element("saveTargets").listeners.click();
    assert.equal(app.value("targetSecurityScan.targets[0].key"), "old");
    assert.equal(app.value("targetScanGeneration"), 0);
    assert.equal(app.rendered.length, 0);
    assert.deepEqual(app.alerts, []);
    assert.equal(app.element("targetSaveMessageText").textContent, "Failed to save target versions: HTTP 500");
    assert.equal(app.element("targetSaveMessage").className, "save-banner save-banner-error");
    assert.equal(app.element("targetSaveMessage").hidden, false);
});

test("successful save followed by failed repository reload reports partial success in the banner", async () => {
    const app = gui();
    app.context.fetch = async url => ({ ok: url.endsWith("/update"), status: 503 });
    await app.context.saveTargets();
    assert.equal(app.value("targetScanGeneration"), 1);
    assert.equal(app.element("targetSaveMessage").className, "save-banner save-banner-error");
    assert.match(app.element("targetSaveMessageText").textContent, /^Target versions saved, but repository data could not be refreshed:/);
    assert.deepEqual(app.alerts, []);
});

test("new save clears stale feedback until the response arrives", async () => {
    const app = gui();
    app.element("targetSaveMessageText").textContent = "Previous success";
    let finish;
    app.context.fetch = () => new Promise(resolve => { finish = resolve; });
    const pending = app.context.saveTargets();
    assert.equal(app.element("targetSaveMessage").hidden, true);
    assert.equal(app.element("targetSaveMessageText").textContent, "");
    finish({ ok: false, status: 400 });
    await pending;
    assert.equal(app.element("targetSaveMessage").hidden, false);
    assert.match(app.element("targetSaveMessageText").textContent, /HTTP 400/);
});

test("success banner expires after six seconds and errors stay until dismissed", () => {
    const app = gui();
    app.context.showTargetSaveMessage("Saved", true);
    assert.equal(app.timers.size, 1);
    const expire = [...app.timers.values()][0];
    expire();
    assert.equal(app.element("targetSaveMessage").hidden, false);
    [...app.timers.values()][0]();
    assert.equal(app.element("targetSaveMessage").hidden, true);
    app.context.showTargetSaveMessage("Failed", false);
    assert.equal(app.timers.size, 0);
    assert.equal(app.element("targetSaveMessage").hidden, false);
    app.element("closeTargetSaveMessage").listeners.click();
    assert.equal(app.element("targetSaveMessage").hidden, false);
    [...app.timers.values()][0]();
    assert.equal(app.element("targetSaveMessage").hidden, true);
});

test("new feedback cancels a pending exit animation timeout", () => {
    const app = gui();
    app.context.showTargetSaveMessage("Saved", true);
    app.context.closeTargetSaveMessage();
    assert.equal(app.timers.size, 1);
    app.context.showTargetSaveMessage("New error", false);
    assert.equal(app.timers.size, 0);
    assert.equal(app.element("targetSaveMessage").hidden, false);
    assert.equal(app.element("targetSaveMessageText").textContent, "New error");
});

test("replacing a success banner cancels its timeout so a new error remains visible", () => {
    const app = gui();
    app.context.showTargetSaveMessage("Saved", true);
    app.context.showTargetSaveMessage("Failed", false);
    assert.equal(app.timers.size, 0);
    assert.equal(app.element("targetSaveMessageText").textContent, "Failed");
    assert.equal(app.element("targetSaveMessage").hidden, false);
});

test("capturing drafts preserves unsaved target inputs before a scan rerenders the table", () => {
    const app = gui();
    function row(key, version) {
        return { querySelector: selector => ({ value: selector === ".key" ? key : version }) };
    }
    app.rows.set("#pluginsTable .target-row", [row("example.plugin", "2.0.0")]);
    app.rows.set("#depsTable .target-row", [row("example:edited", "3.0.0")]);
    app.rows.set("#gradleTable .target-row", [row("gradle-wrapper", "8.1")]);
    app.rows.set("#platformBomsTable .target-row", [row("io.netty:netty-bom", "4.2.17.Final")]);

    app.context.captureTargetDrafts();
    assert.equal(app.value("targetState.dependencies['example:edited']"), "3.0.0");
    assert.equal(app.value("targetState.plugins['example.plugin']"), "2.0.0");
    assert.equal(app.value("targetState.gradleVersion"), "8.1");
    assert.equal(app.value("targetState.platformBoms['io.netty:netty-bom']"), "4.2.17.Final");
});

test("platform BOM draft uses its own targets map and is included in saves", async () => {
    const app = gui();
    app.context.addDraftTarget("PLATFORM_BOM", "io.netty:netty-bom", "4.2.17.Final");
    assert.equal(app.value("targetState.platformBoms['io.netty:netty-bom']"), "4.2.17.Final");
    assert.equal(app.value("targetState.dependencies['io.netty:netty-bom']"), undefined);
    assert.equal(app.value("targetState.drafted.has('PLATFORM_BOM:io.netty:netty-bom')"), true);
    app.rows.set("#platformBomsTable .target-row", [{
        querySelector: selector => ({ value: selector === ".key" ? "io.netty:netty-bom" : "4.2.17.Final" })
    }]);
    let saved;
    app.responses((url, options) => {
        if (url.endsWith("/update")) {
            saved = JSON.parse(options.body);
            return {};
        }
        return repositoryResponse(url);
    });
    await app.context.saveTargets();
    assert.deepEqual(saved.platformBoms, { "io.netty:netty-bom": "4.2.17.Final" });
    assert.equal(app.value("targetState.platformBoms['io.netty:netty-bom']"), "4.2.17.Final");
});

test("repository renders BOM-only untracked entries and tracked BOMs as a distinct type", () => {
    const app = gui();
    const card = {
        querySelector: selector => selector === ".repo-header" ? { addEventListener() {} } : null
    };
    app.context.document.createElement = () => card;
    const scan = {
        findings: [],
        untrackedPlatformBoms: [{ key: "io.netty:netty-bom", version: "4.2.17.Final" }]
    };
    const render = () => app.context.renderRepo(
        { name: "example/repo", state: "SCANNED" },
        { "example/repo": scan },
        { appendChild() {} }
    );
    render();
    assert.match(card.innerHTML, /1 UNTRKD/);
    assert.match(card.innerHTML, /platform BOMs \(1\)/);
    assert.match(card.innerHTML, /data-kind="PLATFORM_BOM"/);
    assert.match(card.innerHTML, /data-key="io.netty:netty-bom"/);
    scan.untrackedPlatformBoms = [];
    scan.findings = [{
        kind: "PLATFORM_BOM", key: "io.netty:netty-bom",
        currentVersion: "4.2.17.Final", targetVersion: "4.2.18.Final", status: "UPDATE"
    }];
    render();
    assert.match(card.innerHTML, /class="pill">PLATFORM BOM/);
    assert.match(card.innerHTML, /row platform_bom-row/);
    assert.match(card.innerHTML, /1 UPDATE/);
    assert.doesNotMatch(card.innerHTML, /data-toggle-details="true"/);
    scan.findings[0].platformBomSecurity = {
        key: "io.netty:netty-bom", targetVersion: "4.2.18.Final", status: "VULNERABLE",
        managedDependencyCount: 3, vulnerableDependencies: [], coverage: []
    };
    render();
    assert.match(card.innerHTML, /TARGET VULNERABLE/);
    assert.match(card.innerHTML, /target BOM version, not the current repository version/);
    assert.match(card.innerHTML, /data-toggle-details="true"/);
});

test("BOM health and coverage render independently without treating module fixes as BOM upgrade versions", () => {
    const app = gui();
    const bom = {
        key: "example:platform", targetVersion: "1.0", status: "VULNERABLE",
        managedDependencyCount: 2,
        vulnerableDependencies: [{
            dependency: { group: "example", name: "unused<module>", version: "1.0" },
            vulnerabilities: [{ id: "TEST-1", summary: "Example <advisory>", fixedVersions: ["2.0"] }]
        }],
        coverage: [{
            dependency: "example:library", targetVersion: "1.0", enforcedPlatform: false,
            overrides: [{ dependency: "example:child", vulnerableVersion: "1.0", resolvedVersion: "3.0" }]
        }]
    };
    app.context.bom = bom;
    app.value("targetSecurityScan = { targets: [{ key: 'example:platform', targetVersion: '1.0', status: 'OK' }], platformBoms: [bom] }");
    assert.equal(app.context.findTargetSecurity("platform_bom", "example:platform", "1.0").status, "VULNERABLE");
    assert.equal(app.context.findTargetSecurity("dependency", "example:platform", "1.0").status, "OK");
    assert.equal(app.context.findTargetSecurity("platform_bom", "example:platform", "2.0"), null);
    const html = app.context.platformBomSecurityDetailsHtml(bom);
    assert.match(html, /unused modules/);
    assert.match(html, /unused&lt;module&gt;/);
    assert.match(html, /Example &lt;advisory&gt;/);
    assert.match(html, /module fixes: 2.0/);
    assert.match(html, /example:child/);
    assert.match(html, /3.0/);
    assert.doesNotMatch(html, /applySecurityUpgrade|transient-btn/);
    assert.equal(app.context.securityStatusHtml(null), "<div></div>");
});

test("failed repository refresh stops progress polling and permits another attempt", async () => {
    const app = gui();
    app.context.fetch = async () => ({ ok: false, status: 500 });
    await app.context.refresh();
    assert.equal(app.value("isRefreshing"), false);
    assert.equal(app.value("hasActiveScan"), false);
    assert.equal(app.value("progressInterval"), null);
    assert.match(app.element("progressText").innerText, /HTTP 500/);
});

test("empty completed repository scan finishes progress instead of waiting indefinitely", async () => {
    const app = gui();
    app.value("hasActiveScan = true; isStartingScan = true");
    app.responses(() => ({ total: 0, done: 0, running: false }));
    await app.context.pollProgress();
    assert.equal(app.element("progressText").innerText, "Done");
    assert.equal(app.value("hasActiveScan"), false);
});

test("failed repository data lookup is not treated as a successful enriched refresh", async () => {
    const app = gui();
    app.context.fetch = async url => {
        if (url.endsWith("/start")) {
            return {
                ok: true,
                json: async () => ({ status: "READY", targetFingerprint: "targets", result: { targets: [] } })
            };
        }
        return { ok: false, status: 500 };
    };

    await app.context.scanTargetSecurity();
    assert.equal(app.rendered.length, 0);
    assert.ok(app.steps.some(([id, status]) => id === "scanStepDone" && status === "FAILED"));
    assert.equal(app.element("scanTargets").disabled, false);
});
