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
    let targetRenders = 0;

    function element(id) {
        if (!elements.has(id)) {
            elements.set(id, {
                listeners: {},
                style: {},
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
        setTimeout: callback => callback(),
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
        context, calls, alerts, rendered, steps, rows, element, responses,
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
    assert.equal(app.element("targetSaveMessage").textContent, "Target versions updated");
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
    assert.equal(app.element("targetSaveMessage").textContent, "Failed to save target versions: HTTP 500");
    assert.equal(app.element("targetSaveMessage").className, "save-banner save-banner-error");
    assert.equal(app.element("targetSaveMessage").hidden, false);
});

test("successful save followed by failed repository reload reports partial success in the banner", async () => {
    const app = gui();
    app.context.fetch = async url => ({ ok: url.endsWith("/update"), status: 503 });
    await app.context.saveTargets();
    assert.equal(app.value("targetScanGeneration"), 1);
    assert.equal(app.element("targetSaveMessage").className, "save-banner save-banner-error");
    assert.match(app.element("targetSaveMessage").textContent, /^Target versions saved, but repository data could not be refreshed:/);
    assert.deepEqual(app.alerts, []);
});

test("new save clears stale feedback until the response arrives", async () => {
    const app = gui();
    app.element("targetSaveMessage").textContent = "Previous success";
    let finish;
    app.context.fetch = () => new Promise(resolve => { finish = resolve; });
    const pending = app.context.saveTargets();
    assert.equal(app.element("targetSaveMessage").hidden, true);
    assert.equal(app.element("targetSaveMessage").textContent, "");
    finish({ ok: false, status: 400 });
    await pending;
    assert.equal(app.element("targetSaveMessage").hidden, false);
    assert.match(app.element("targetSaveMessage").textContent, /HTTP 400/);
});

test("capturing drafts preserves unsaved target inputs before a scan rerenders the table", () => {
    const app = gui();
    function row(key, version) {
        return { querySelector: selector => ({ value: selector === ".key" ? key : version }) };
    }
    app.rows.set("#pluginsTable .target-row", [row("example.plugin", "2.0.0")]);
    app.rows.set("#depsTable .target-row", [row("example:edited", "3.0.0")]);
    app.rows.set("#gradleTable .target-row", [row("gradle-wrapper", "8.1")]);

    app.context.captureTargetDrafts();
    assert.equal(app.value("targetState.dependencies['example:edited']"), "3.0.0");
    assert.equal(app.value("targetState.plugins['example.plugin']"), "2.0.0");
    assert.equal(app.value("targetState.gradleVersion"), "8.1");
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
