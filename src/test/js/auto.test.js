const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { html } = require("./gui-scripts");

function app(fetch, confirmedIdle = true) {
    const elements = new Map();
    const created = [];
    const timers = new Map();
    let timerId = 0;
    function element(tagName = "div") {
        const classes = new Set();
        let text = "";
        const node = {
            tagName, children: [], parentNode: null, dataset: {}, attributes: {}, listeners: {}, style: { display: "none" },
            get textContent() { return text + this.children.map(child => child.textContent).join(""); },
            set textContent(value) {
                text = String(value);
                this.children.forEach(child => { child.parentNode = null; });
                this.children = [];
            },
            classList: {
                toggle(name, active) { if (active) classes.add(name); else classes.delete(name); },
                contains: name => classes.has(name)
            },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            appendChild(child) { child.remove(); this.children.push(child); child.parentNode = this; return child; },
            prepend(child) { this.insertBefore(child, this.children[0] || null); },
            insertBefore(child, reference) {
                if (child === reference) return;
                child.remove();
                const index = reference === null ? this.children.length : this.children.indexOf(reference);
                this.children.splice(index, 0, child);
                child.parentNode = this;
            },
            remove() {
                if (this.parentNode) {
                    const siblings = this.parentNode.children;
                    siblings.splice(siblings.indexOf(this), 1);
                    this.parentNode = null;
                }
            },
            replaceChildren(...children) { this.textContent = ""; this.append(...children); },
            setAttribute(key, value) { this.attributes[key] = value; },
            addEventListener(event, listener) { this.listeners[event] = listener; }
        };
        created.push(node);
        return node;
    }
    function get(id) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    }
    const context = vm.createContext({
        document: {
            getElementById: get,
            createElement: element,
            querySelectorAll: selector => selector === ".auto-toggle"
                ? created.filter(node => node.className === "auto-toggle") : []
        },
        console: { error() {} },
        URL,
        setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
        clearTimeout: id => timers.delete(id),
        fetch
    });
    for (const file of ["shared", "api", "deployments", "auto"]) {
        vm.runInContext(readFileSync(resolve(__dirname, `../../main/resources/gui/js/${file}.js`), "utf8"), context);
    }
    if (confirmedIdle) vm.runInContext("autoProcessStatusLoaded = true", context);
    return { context, get, created, timers, value: expression => vm.runInContext(expression, context) };
}

function server(initial = []) {
    const repositories = new Set(initial);
    const calls = [];
    return {
        calls, repositories,
        fetch: async (url, options) => {
            calls.push([url, options]);
            assert.equal(url, "/internal/api/auto-repositories");
            assert.equal(options.cache, "no-store");
            if (options.method === "POST") {
                const setting = JSON.parse(options.body);
                if (setting.enabled) repositories.add(setting.repository);
                else repositories.delete(setting.repository);
                return { ok: true, json: async () => setting };
            }
            return { ok: true, json: async () => ({ repositories: [...repositories] }) };
        }
    };
}

test("Auto tab precedes Deployments and renders a sorted compact global list independently of scanner teams", async () => {
    assert.ok(html.indexOf('data-tab="auto"') < html.indexOf('data-tab="deployments"'));
    const backend = server(["other/z-repo", "example/a-repo"]);
    const first = app(backend.fetch);
    await first.context.loadAutoRepositories();
    const rows = first.get("autoRepositories").children;
    assert.deepEqual(rows.map(row => row.children[0].textContent), ["example/a-repo", "other/z-repo"]);
    assert.equal(rows[0].children[0].href, "https://github.com/example/a-repo");
    assert.equal(rows[0].children[1].attributes["aria-checked"], "true");
    assert.match(first.get("autoStatus").textContent, /2 repositories/);
    const second = app(backend.fetch);
    await second.context.loadAutoRepositories();
    assert.equal(second.get("autoRepositories").children.length, 2);
});

test("switches are accessible, default off and disabled until loaded, and save immediately across views and sessions", async () => {
    const backend = server();
    const first = app(backend.fetch);
    const scannerToggle = first.context.createAutoToggle("Example/Repo");
    assert.equal(scannerToggle.attributes.role, "switch");
    assert.equal(scannerToggle.attributes["aria-checked"], "false");
    assert.match(scannerToggle.attributes["aria-label"], /Example\/Repo/);
    assert.equal(scannerToggle.children[1].textContent, "auto");
    assert.equal(scannerToggle.disabled, true);
    await first.context.loadAutoRepositories();
    assert.equal(scannerToggle.disabled, false);
    let stopped = false;
    await scannerToggle.listeners.click({ stopPropagation() { stopped = true; } });
    assert.equal(stopped, true);
    assert.equal(scannerToggle.attributes["aria-checked"], "true");
    assert.deepEqual(JSON.parse(backend.calls.at(-1)[1].body), { repository: "example/repo", enabled: true });
    const rowToggle = first.get("autoRepositories").children[0].children[1];
    assert.equal(rowToggle.attributes["aria-checked"], "true");
    const second = app(backend.fetch);
    await second.context.loadAutoRepositories();
    assert.equal(second.get("autoRepositories").children.length, 1);
    await rowToggle.listeners.click({ stopPropagation() {} });
    assert.equal(scannerToggle.attributes["aria-checked"], "false");
    assert.equal(first.get("autoRepositories").children.length, 0);
    assert.match(first.get("autoStatus").textContent, /No repositories/);
    await second.context.loadAutoRepositories();
    assert.equal(second.get("autoRepositories").children.length, 0);
});

test("failed writes retain confirmed state and show actionable errors in both tabs", async () => {
    const backend = server(["example/repo"]);
    const ui = app(backend.fetch);
    await ui.context.loadAutoRepositories();
    const toggle = ui.context.createAutoToggle("example/repo");
    ui.context.fetch = async () => ({ ok: false, json: async () => ({ error: "Database unavailable" }) });
    await ui.context.saveAutoRepository("example/repo");
    assert.equal(toggle.attributes["aria-checked"], "true");
    assert.equal(toggle.disabled, false);
    assert.equal(ui.get("autoRepositories").children.length, 1);
    assert.match(ui.get("autoError").textContent, /Database unavailable/);
    assert.equal(ui.get("autoError").textContent, ui.get("scannerAutoError").textContent);
});

test("failed reads do not present unknown settings as off and can be retried", async () => {
    const ui = app(async () => { throw new Error("Offline"); });
    const toggle = ui.context.createAutoToggle("example/repo");
    await ui.context.loadAutoRepositories();
    assert.equal(toggle.disabled, true);
    assert.match(ui.get("autoError").textContent, /Offline/);
    assert.match(ui.get("autoStatus").textContent, /not loaded/);
    ui.context.fetch = server(["example/repo"]).fetch;
    await ui.context.loadAutoRepositories();
    assert.equal(toggle.disabled, false);
    assert.equal(toggle.attributes["aria-checked"], "true");
    assert.equal(ui.get("autoError").textContent, "");
});

test("pending writes disable all switches for that repository, reject duplicates and discard stale reads", async () => {
    const ui = app(server().fetch);
    await ui.context.loadAutoRepositories();
    const toggle = ui.context.createAutoToggle("example/repo");
    let finishRead;
    let finishWrite;
    let writes = 0;
    ui.context.fetch = (url, options) => new Promise(resolve => {
        if (options.method === "POST") {
            writes++;
            finishWrite = () => resolve({ ok: true, json: async () => JSON.parse(options.body) });
        } else {
            finishRead = () => resolve({ ok: true, json: async () => ({ repositories: [] }) });
        }
    });
    const read = ui.context.loadAutoRepositories();
    const write = ui.context.saveAutoRepository("example/repo");
    assert.equal(toggle.disabled, true);
    assert.equal(toggle.attributes["aria-checked"], "false");
    await ui.context.saveAutoRepository("example/repo");
    assert.equal(writes, 1);
    finishWrite();
    await write;
    finishRead();
    await read;
    assert.equal(toggle.disabled, false);
    assert.equal(toggle.attributes["aria-checked"], "true");
    assert.equal(ui.get("autoRepositories").children.length, 1);
});

test("scanner only attaches Auto switches to repositories the GitHub App can access", () => {
    const ui = app(server().fetch);
    vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/scanner.js"), "utf8"), ui.context);
    Object.assign(ui.context, {
        notes: {}, normalSecurityReady: false, bomSecurityReady: false,
        EDIT_NOTE_SVG: "", CREATE_NOTE_SVG: ""
    });
    const realCreateElement = ui.context.document.createElement;
    for (const state of ["SCANNED", "NOT_SCANNED", "NOT_IN_GITHUB"]) {
        const actions = realCreateElement();
        const header = { addEventListener() {} };
        const card = { querySelector: selector => selector === ".repo-actions" ? actions
            : selector === ".repo-header" ? header : null };
        let first = true;
        ui.context.document.createElement = (...args) => {
            if (first) { first = false; return card; }
            return realCreateElement(...args);
        };
        ui.context.renderRepo({ name: "example/repo", state }, {}, { appendChild() {} });
        if (state === "NOT_IN_GITHUB") {
            assert.equal(actions.children.length, 0);
            assert.match(card.innerHTML, /Grant App Access/);
            assert.doesNotMatch(card.innerHTML, /pr-create-action/);
        } else {
            assert.equal(actions.children[0].className, "auto-toggle");
            assert.equal(actions.children[0].dataset.repository, "example/repo");
        }
    }
});

test("Auto locks every switch until status is confirmed and throughout a run, then unlocks for every terminal outcome", async () => {
    const backend = server(["example/repo"]);
    let snapshot = processSnapshot("RUNNING");
    let offline = false;
    const ui = app(async (url, options) => {
        if (url !== "/internal/api/auto/status") return backend.fetch(url, options);
        if (offline) throw new Error("Offline");
        return { ok: true, json: async () => snapshot };
    }, false);
    await ui.context.loadAutoRepositories();
    const scanner = ui.context.createAutoToggle("example/repo");
    assert.equal(scanner.disabled, true);
    assert.equal(ui.get("autoRepositories").children[0].children[1].disabled, true);
    await ui.context.saveAutoRepository("example/repo");
    assert.equal(backend.calls.length, 1);
    await ui.context.loadAutoProcess();
    assert.equal(scanner.disabled, true);
    assert.match(scanner.title, /locked/);
    await scanner.listeners.click({ stopPropagation() {} });
    assert.equal(backend.calls.length, 1);
    const newlyRendered = ui.context.createAutoToggle("example/other");
    assert.equal(newlyRendered.disabled, true);
    for (const status of ["COMPLETED", "STOPPED", "FAILED", "INTERRUPTED"]) {
        snapshot = processSnapshot(status);
        await ui.context.loadAutoProcess();
        assert.equal(scanner.disabled, false);
        assert.equal(newlyRendered.disabled, false);
        assert.equal(ui.get("autoRepositories").children[0].children[1].disabled, false);
    }
    offline = true;
    await ui.context.loadAutoProcess();
    assert.equal(scanner.disabled, true);
    assert.equal(newlyRendered.disabled, true);
    offline = false;
    await ui.context.loadAutoProcess();
    assert.equal(scanner.disabled, false);
});

test("a run started in another session rejects a stale toggle and immediately locks switches without changing opt-ins", async () => {
    const backend = server(["example/repo"]);
    const ui = app(async (url, options) => {
        if (url === "/internal/api/auto/status") return { ok: true, json: async () => processSnapshot("RUNNING") };
        if (options.method === "POST") return {
            ok: false, status: 409, json: async () => ({ error: "Auto settings cannot be changed while an Auto process is running" })
        };
        return backend.fetch(url, options);
    });
    await ui.context.loadAutoRepositories();
    const toggle = ui.context.createAutoToggle("example/repo");
    await ui.context.saveAutoRepository("example/repo");
    assert.equal(toggle.attributes["aria-checked"], "true");
    assert.equal(toggle.disabled, true);
    assert.match(ui.get("autoError").textContent, /cannot be changed/);
    assert.equal(ui.get("autoRepositories").children[0].children[1].disabled, true);
});

test("Auto process started exposes a preserved expandable snapshot of the opted-in repositories", async () => {
    const snapshot = processSnapshot("RUNNING", [
        logEntry(1, {
            step: null, message: "Auto process started",
            details: "Repositories opted in at start (2):\nexample/first\nexample/second"
        })
    ]);
    const ui = app(async () => ({ ok: true, json: async () => snapshot }));
    await ui.context.loadAutoProcess();
    const entry = logEntries(ui)[0];
    assert.equal(entry.children[1].textContent, "Auto process started Triggered by service-user");
    assert.equal(entry.children[1].children[0].className, "auto-log-triggered");
    assert.equal(entry.children[0].children.length, 1);
    const details = logEntries(ui)[0].children.find(node => node.tagName === "details");
    assert.equal(details.children[0].textContent, "Opted-in repositories");
    assert.match(details.children[1].textContent, /example\/first.*example\/second/);
    details.open = true;
    snapshot.run.status = "COMPLETED";
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("autoProcessStatus").textContent, "COMPLETED");
    assert.equal(ui.get("autoProcessStatus").children.length, 0);
    assert.equal(logEntries(ui)[0].children.find(node => node.tagName === "details"), details);
    assert.equal(details.open, true);
});

function logEntry(id, overrides = {}) {
    return {
        id, timestamp: "2026-11-12T13:00:00Z", runId: "run-1", username: "service-user",
        level: "INFO", step: "Scan Dependencies", repository: null, message: `Finished step ${id}`,
        ...overrides
    };
}

function logEntries(ui) {
    return ui.get("autoProcessLogs").children
        .flatMap(group => group.children[0].children[1].children.filter(node => node.tagName === "article"))
        .sort((a, b) => Number(b.dataset.logId) - Number(a.dataset.logId));
}

function processSnapshot(status = "RUNNING", logs = []) {
    const run = { id: "run-1", username: "service-user", status, startedAt: "2026-11-12T13:00:00Z",
        finishedAt: status === "RUNNING" ? null : "2026-11-12T13:05:00Z",
        step: "Scan Plugins", repository: null, stepStartedAt: "2026-11-12T13:01:00Z" };
    return {
        run, runs: [run], logs, nextBefore: null
    };
}

test("Start Auto is a red button at the bottom of the Auto tab and waits for confirmed idle status", async () => {
    const autoTab = html.slice(html.indexOf('<div id="autoTab"'), html.indexOf('<div id="deploymentsTab"'));
    assert.match(autoTab, /<button id="startAuto" class="btn-danger" type="button" disabled>Start Auto<\/button>/);
    assert.ok(autoTab.indexOf('id="startAuto"') > autoTab.indexOf('id="loadOlderAutoLogs"'));
    let snapshot = { run: null, runs: [], logs: [], nextBefore: null };
    let calls = 0;
    const ui = app(async () => {
        calls++;
        return { ok: true, json: async () => snapshot };
    }, false);
    ui.context.updateStartAutoButton();
    assert.equal(ui.get("startAuto").disabled, true);
    await ui.context.startAuto();
    assert.equal(calls, 0);
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("startAuto").disabled, false);
    snapshot = processSnapshot();
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("startAuto").disabled, true);
    await ui.context.startAuto();
    assert.equal(calls, 2);
    for (const status of ["COMPLETED", "STOPPED", "FAILED", "INTERRUPTED"]) {
        snapshot = processSnapshot(status);
        await ui.context.loadAutoProcess();
        assert.equal(ui.get("startAuto").disabled, false);
    }
});

test("Start Auto calls the existing trigger once, locks controls while pending and refreshes the running process", async () => {
    let finishTrigger;
    const calls = [];
    const ui = app(async (url, options) => {
        calls.push([url, options]);
        if (url === "/internal/triggerAuto") return new Promise(resolve => { finishTrigger = resolve; });
        assert.equal(url, "/internal/api/auto/status");
        return { ok: true, json: async () => processSnapshot() };
    });
    ui.value('autoRepositoriesLoaded = true');
    const toggle = ui.context.createAutoToggle("example/repo");
    const pending = ui.context.startAuto();
    assert.equal(ui.get("startAuto").disabled, true);
    assert.equal(ui.get("startAuto").textContent, "Starting Auto...");
    assert.equal(toggle.disabled, true);
    await ui.context.startAuto();
    await ui.context.loadAutoProcess();
    await ui.context.saveAutoRepository("example/repo");
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], "/internal/triggerAuto");
    assert.equal(calls[0][1].method, "GET");
    assert.equal(calls[0][1].cache, "no-store");
    finishTrigger({ ok: true, text: async () => "process started" });
    await pending;
    assert.equal(calls.length, 2);
    assert.equal(ui.get("autoStartStatus").textContent, "Auto process started.");
    assert.equal(ui.get("autoStartError").textContent, "");
    assert.equal(ui.get("autoProcessStatus").textContent, "RUNNING");
    assert.equal(ui.get("startAuto").textContent, "Start Auto");
    assert.equal(ui.get("startAuto").disabled, true);
    assert.equal(toggle.disabled, true);
});

test("Start Auto reports conflicts, server and network failures without claiming success and reconciles status", async () => {
    for (const failure of [
        { ok: false, status: 409, text: async () => "Auto process is already ongoing" },
        { ok: false, status: 503, text: async () => '{"error":"Could not access Auto process"}' },
        { ok: true, text: async () => "<html>Sign in</html>" },
        new Error("Network unavailable")
    ]) {
        const ui = app(async url => {
            if (url === "/internal/triggerAuto") {
                if (failure instanceof Error) throw failure;
                return failure;
            }
            return { ok: true, json: async () => processSnapshot() };
        });
        await ui.context.startAuto();
        assert.equal(ui.get("autoStartStatus").textContent, "");
        assert.match(ui.get("autoStartError").textContent, /Could not start Auto process:/);
        assert.equal(ui.get("autoProcessStatus").textContent, "RUNNING");
        assert.equal(ui.get("startAuto").disabled, true);
    }
});

test("Start Auto remains disabled when status refresh fails after a successful trigger", async () => {
    const ui = app(async url => {
        if (url === "/internal/triggerAuto") return { ok: true, text: async () => "process started" };
        throw new Error("Status unavailable");
    });
    await ui.context.startAuto();
    assert.equal(ui.get("autoStartStatus").textContent, "Auto process started.");
    assert.match(ui.get("autoProcessError").textContent, /Status unavailable/);
    assert.equal(ui.get("startAuto").disabled, true);
});

const olderRunId = "11111111-1111-4111-8111-111111111111";
const latestRunId = "22222222-2222-4222-8222-222222222222";

function historySnapshot() {
    const snapshot = processSnapshot();
    snapshot.run.id = latestRunId;
    snapshot.runs.push({
        id: olderRunId, username: "other-user", status: "COMPLETED",
        startedAt: "2026-11-11T12:00:00Z", finishedAt: "2026-11-11T12:05:00Z"
    });
    snapshot.logs = [
        logEntry(1, { runId: olderRunId, message: "Auto process started" }),
        logEntry(2, { runId: olderRunId, message: "Auto process finished normally" }),
        logEntry(3, { runId: latestRunId, message: "Auto process started", details: "Repositories opted in at start:\nexample/repo" }),
        logEntry(4, { runId: latestRunId, message: "Dependency scan finished: 4 targets checked" })
    ];
    return snapshot;
}

function runGroup(ui, id) {
    return ui.get("autoProcessLogs").children.find(group => group.dataset.runId === id);
}

test("Auto groups chronological logs in newest-first run sections with start/end headers and only older-run trash buttons", async () => {
    const snapshot = historySnapshot();
    const ui = app(async () => ({ ok: true, json: async () => snapshot }));
    await ui.context.loadAutoProcess();
    const groups = ui.get("autoProcessLogs").children;
    assert.deepEqual(groups.map(group => group.dataset.runId), [latestRunId, olderRunId]);
    const latest = groups[0].children[0];
    const older = groups[1].children[0];
    assert.equal(latest.open, true);
    assert.equal(older.open, false);
    assert.equal(groups[0].children[1].hidden, true);
    assert.equal(groups[1].children[1].hidden, false);
    assert.match(groups[1].children[1].attributes["aria-label"], /Delete Auto process started/);
    assert.match(groups[1].children[1].innerHTML, /<svg/);
    assert.match(latest.children[0].textContent, /RUNNING.*Started.*In progress/);
    assert.ok(older.children[0].textContent.includes(ui.context.formatDeploymentTimestamp(snapshot.runs[1].startedAt)));
    assert.ok(older.children[0].textContent.includes(ui.context.formatDeploymentTimestamp(snapshot.runs[1].finishedAt)));
    assert.deepEqual(latest.children[1].children.filter(node => node.tagName === "article").map(node => Number(node.dataset.logId)), [3, 4]);
    assert.equal(ui.get("autoCurrentStep").parentNode, latest.children[1]);
    assert.equal(latest.children[1].children.at(-1), ui.get("autoCurrentStep"));
    const report = latest.children[1].children[0].children.find(node => node.tagName === "details");
    report.open = true;
    latest.open = false;
    older.open = true;
    snapshot.run.status = "COMPLETED";
    snapshot.run.finishedAt = "2026-11-12T13:05:00Z";
    await ui.context.loadAutoProcess();
    assert.equal(runGroup(ui, latestRunId), groups[0]);
    assert.equal(latest.open, false);
    assert.equal(older.open, true);
    assert.equal(report.open, true);
    assert.ok(latest.children[0].textContent.includes(ui.context.formatDeploymentTimestamp(snapshot.run.finishedAt)));
    assert.equal(ui.get("autoCurrentStep").hidden, true);
    const next = { ...snapshot.run, id: "33333333-3333-4333-8333-333333333333", status: "RUNNING", finishedAt: null };
    snapshot.run = next;
    snapshot.runs.push(next);
    snapshot.logs.push(logEntry(5, { runId: next.id, message: "Auto process started" }));
    await ui.context.loadAutoProcess();
    assert.equal(runGroup(ui, next.id).children[0].open, true);
    assert.equal(latest.open, false);
    assert.equal(older.open, true);
    assert.equal(ui.get("autoCurrentStep").parentNode, runGroup(ui, next.id).children[0].children[1]);
});

test("Auto run deletion is single-flight, removes only that run after DB confirmation and does not resurrect it on polling", async () => {
    const snapshot = historySnapshot();
    const requests = [];
    let finish;
    const ui = app(async (url, options) => {
        requests.push([url, options]);
        if (options.method === "DELETE") return new Promise(resolve => { finish = resolve; });
        return { ok: true, json: async () => snapshot };
    });
    await ui.context.loadAutoProcess();
    const latest = runGroup(ui, latestRunId);
    const remove = runGroup(ui, olderRunId).children[1];
    let stopped = false;
    const deletion = remove.listeners.click({ stopPropagation() { stopped = true; } });
    assert.equal(stopped, true);
    assert.equal(remove.disabled, true);
    await ui.context.deleteAutoRun(olderRunId);
    await ui.context.deleteAutoRun(latestRunId);
    await ui.context.loadAutoProcess();
    assert.equal(requests.length, 2);
    assert.equal(requests[1][0], `/internal/api/auto/runs/${olderRunId}`);
    assert.equal(requests[1][1].cache, "no-store");
    assert.equal(logEntries(ui).length, 4);
    snapshot.logs = snapshot.logs.filter(entry => entry.runId !== olderRunId);
    snapshot.runs = snapshot.runs.filter(run => run.id !== olderRunId);
    finish({ status: 204 });
    await deletion;
    assert.equal(runGroup(ui, olderRunId), undefined);
    assert.equal(runGroup(ui, latestRunId), latest);
    assert.equal(logEntries(ui).length, 2);
    assert.equal(ui.value(`autoRuns.has("${olderRunId}")`), false);
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("autoProcessLogs").children.length, 1);
    assert.equal(runGroup(ui, latestRunId), latest);
    assert.equal(latest.children[0].open, true);
});

test("Auto preserves history on deletion failures and explicit refresh reconciles history deleted elsewhere", async () => {
    const snapshot = historySnapshot();
    const ui = app(async (url, options) => options.method === "DELETE"
        ? { status: 503, json: async () => ({ error: "Database unavailable" }) }
        : { ok: true, json: async () => snapshot });
    await ui.context.loadAutoProcess();
    const older = runGroup(ui, olderRunId);
    await ui.context.deleteAutoRun(olderRunId);
    assert.equal(runGroup(ui, olderRunId), older);
    assert.equal(older.children[1].disabled, false);
    assert.equal(logEntries(ui).length, 4);
    assert.match(ui.get("autoProcessError").textContent, /Database unavailable/);
    snapshot.logs = snapshot.logs.filter(entry => entry.runId !== olderRunId);
    snapshot.runs = snapshot.runs.filter(run => run.id !== olderRunId);
    await ui.context.loadAutoProcess(null, true);
    assert.equal(runGroup(ui, olderRunId), undefined);
    assert.equal(logEntries(ui).length, 2);
    assert.equal(ui.get("autoProcessError").textContent, "");
});

test("Auto merges split run log pages without duplicate groups or losing report expansion", async () => {
    const snapshot = historySnapshot();
    const allLogs = snapshot.logs;
    snapshot.logs = [allLogs[3]];
    snapshot.nextBefore = 4;
    const ui = app(async url => ({
        ok: true, json: async () => url.includes("before=")
            ? { ...snapshot, logs: allLogs.slice(0, 3), nextBefore: null } : snapshot
    }));
    await ui.context.loadAutoProcess();
    const latest = runGroup(ui, latestRunId);
    assert.equal(latest.children[0].children[2].hidden, false);
    latest.children[0].open = false;
    const original = logEntries(ui)[0];
    await ui.context.loadAutoProcess(4);
    assert.equal(ui.get("autoProcessLogs").children.length, 2);
    assert.equal(runGroup(ui, latestRunId), latest);
    assert.equal(latest.children[0].open, false);
    assert.equal(latest.children[0].children[2].hidden, true);
    assert.equal(logEntries(ui)[0], original);
    assert.deepEqual(latest.children[0].children[1].children.filter(node => node.tagName === "article").map(node => Number(node.dataset.logId)), [3, 4]);
    assert.ok(runGroup(ui, olderRunId).children[0].children[0].textContent.includes(ui.context.formatDeploymentTimestamp(snapshot.runs[1].startedAt)));
});

test("Auto process renders running step and red tab, timestamped expandable reports and safe PR links", async () => {
    const snapshot = processSnapshot("RUNNING", [
        logEntry(1, { details: "example:library 1.0 -> 1.2\nexample:other 2.0 -> 2.1" }),
        logEntry(2, { repository: "example/repo", message: "Pull request created", url: "https://github.com/example/repo/pull/42" })
    ]);
    const ui = app(async (url, options) => {
        assert.equal(url, "/internal/api/auto/status");
        assert.equal(options.cache, "no-store");
        return { ok: true, json: async () => snapshot };
    });
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("autoTabButton").classList.contains("auto-running"), true);
    assert.equal(ui.get("autoCurrentStep").hidden, false);
    assert.equal(ui.get("autoCurrentStep").children[0].className, "spinner");
    assert.equal(ui.get("autoCurrentStep").children[1].textContent, "Scan Plugins");
    assert.equal(ui.get("autoCurrentStep").children[2].dateTime, snapshot.run.stepStartedAt);
    const entries = logEntries(ui);
    assert.equal(entries.length, 2);
    const link = entries[0].children.find(node => node.tagName === "a");
    assert.equal(link.href, "https://github.com/example/repo/pull/42");
    assert.equal(link.textContent, "repo #42");
    assert.equal(link.className, "pr-button auto-pr-link");
    assert.equal(link.children[0].src, "/internal/gui/icons/external-link.svg");
    assert.equal(link.rel, "noopener noreferrer");
    const details = entries[1].children.find(node => node.tagName === "details");
    assert.match(details.children[1].textContent, /example:library 1.0 -> 1.2/);
    details.open = true;
    await ui.context.loadAutoProcess();
    assert.equal(logEntries(ui)[1], entries[1]);
    assert.equal(details.open, true);
    snapshot.run.status = "COMPLETED";
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("autoTabButton").classList.contains("auto-running"), false);
    assert.equal(ui.get("autoCurrentStep").hidden, true);
    assert.match(ui.get("autoProcessStatus").textContent, /COMPLETED/);
    assert.equal(logEntries(ui).length, 2);
});

test("Auto hides repository entries without created PRs in persisted history but keeps errors and run reports", async () => {
    const snapshot = processSnapshot("COMPLETED", [
        logEntry(1, { message: "Auto process started" }),
        logEntry(2, { repository: "example/current", message: "No changes required; Create PR is disabled" }),
        logEntry(3, { repository: "example/ignored", message: "Skipped: repository is ignored by service-user" }),
        logEntry(4, { repository: "example/removed", message: "Skipped: Auto opt-in or GitHub App access was removed" }),
        logEntry(5, { repository: "example/updated", message: "Pull request created", url: "https://github.com/example/updated/pull/42" }),
        logEntry(6, { repository: "example/failed", level: "WARN", message: "Repository scan failed" }),
        logEntry(7, {
            repository: "example/updated", level: "WARN",
            message: "Pull request created, but superseded PR cleanup is unfinished",
            url: "https://github.com/example/updated/pull/43"
        }),
        logEntry(8, { message: "Auto process finished normally" })
    ]);
    const ui = app(async () => ({ ok: true, json: async () => snapshot }));
    await ui.context.loadAutoProcess();
    assert.deepEqual(logEntries(ui).map(node => Number(node.dataset.logId)), [8, 7, 6, 5, 1]);
    await ui.context.loadAutoProcess();
    assert.deepEqual(logEntries(ui).map(node => Number(node.dataset.logId)), [8, 7, 6, 5, 1]);
});

test("Auto scan pills and compact successful reports also apply to persisted legacy logs", async () => {
    const snapshot = processSnapshot("COMPLETED", [
        logEntry(1, {
            message: "Dependency scan finished: no unresolved target vulnerabilities",
            details: "12 targets checked, including transitive dependencies and configured overrides."
        }),
        logEntry(2, {
            message: "BOM scan finished",
            details: "2 saved BOMs checked for coverage. This is coverage analysis, not a full managed-catalog vulnerability audit."
        }),
        logEntry(3, {
            message: "Plugin scan finished: 4 plugins checked",
            details: "Published plugin metadata and transitive build-time dependencies checked without applying plugins."
        })
    ]);
    const ui = app(async () => ({ ok: true, json: async () => snapshot }));
    await ui.context.loadAutoProcess();
    const entries = logEntries(ui);
    for (const [index, kind] of ["plugin", "platform-bom", "dependency"].entries()) {
        assert.equal(entries[index].children.some(node => node.tagName === "details"), false);
        assert.match(entries[index].children[1].children[1].className, new RegExp(`${kind}-pill`));
    }
    assert.equal(entries[0].children[1].textContent, "Plugin scan finished: 4 plugins checked");
    assert.equal(entries[1].children[1].textContent, "BOM scan finished: 2 saved BOMs checked for coverage");
    assert.equal(entries[2].children[1].textContent, "Dependency scan finished: 12 targets checked; no unresolved target vulnerabilities");
    assert.match(entries[0].children[0].children[0].textContent, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    assert.equal(entries[0].children[0].children[0].textContent,
        ui.context.formatDeploymentTimestamp(snapshot.logs[0].timestamp));
});

test("Auto omits repeated user pills while retaining the user inventory and styled actionable reports", async () => {
    const snapshot = processSnapshot("RUNNING", [
        logEntry(1, { message: "Saved Target Versions as service-user", details: "Users in database:\nservice-user\nother-user" }),
        logEntry(2, {
            message: "Pull request created",
            details: "UPDATE example:library: 1.0 -> 1.2\nAHEAD example:other: 3.0 -> 2.0\nADD example:new: null -> 1.0\nREMOVE example:unused: 1.0 -> 1.0"
        }),
        logEntry(3, {
            message: "Dependency scan finished: 12 targets checked; no unresolved target vulnerabilities",
            details: "REMOVE example:unused:1.0: redundant transient override."
        }),
        logEntry(4, { level: "WARN", message: "Auto stopped: plugin vulnerabilities or incomplete checks; work is unfinished", details: "VULNERABLE <img src=x>: TEST-123" })
    ]);
    const ui = app(async () => ({ ok: true, json: async () => snapshot }));
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("autoProcessStatus").textContent, "RUNNING");
    assert.equal(ui.get("autoProcessStatus").children.length, 0);
    assert.match(ui.get("autoCurrentStep").children[1].children[1].className, /plugin-pill/);
    const entries = logEntries(ui);
    entries.forEach(entry => {
        assert.equal(entry.children[0].children.some(node => node.className?.includes("auto-log-user")), false);
        assert.doesNotMatch(entry.textContent, /service-user|other-user/);
    });
    const rows = entries[2].children.find(node => node.tagName === "details").children[1].children;
    for (const [index, action] of ["UPDATE", "AHEAD", "ADD", "REMOVE"].entries()) {
        assert.equal(rows[index].children[0].textContent, action);
        assert.equal(rows[index].children[0].className, `status-pill status-${action.toLowerCase()}`);
    }
    const users = entries[3].children.find(node => node.tagName === "details").children[1].children;
    assert.equal(users[1].children[0].title, "service-user");
    assert.equal(users[2].children[0].title, "other-user");
    assert.equal(entries[1].children.find(node => node.tagName === "details").children[1].children[0].children[0].textContent, "REMOVE");
    assert.match(entries[0].textContent, /VULNERABLE <img src=x>: TEST-123/);
    assert.equal(ui.created.some(node => node.tagName === "img"), false);
});

test("Auto trigger attribution renders the username as plain text without interpreting HTML", async () => {
    const username = "<img src=x onerror=alert(1)>";
    const ui = app(async () => ({
        ok: true, json: async () => processSnapshot("COMPLETED", [logEntry(1, { username, message: "Auto process started" })])
    }));
    await ui.context.loadAutoProcess();
    assert.equal(logEntries(ui)[0].children[1].textContent, `Auto process started Triggered by ${username}`);
    assert.equal(ui.created.some(node => node.tagName === "img"), false);
});

test("Auto cleanup reports keep replacement and superseded PRs as matching safe link buttons", async () => {
    const ui = app(async () => ({
        ok: true, json: async () => processSnapshot("COMPLETED", [logEntry(1, {
            message: "Pull request created",
            url: "https://github.com/example/repo/pull/42",
            details: "UPDATE example:library: 1.0 -> 1.2\nClosed superseded PRs:\nhttps://github.com/example/repo/pull/40\nhttps://github.com/example/repo/pull/41"
        })])
    }));
    await ui.context.loadAutoProcess();
    const entry = logEntries(ui)[0];
    const links = [
        entry.children.find(node => node.tagName === "a"),
        ...entry.children.find(node => node.tagName === "details").children[1].children
            .flatMap(row => row.children.filter(node => node.tagName === "a"))
    ];
    assert.deepEqual(links.map(link => link.textContent), ["repo #42", "repo #40", "repo #41"]);
    assert.equal(links.every(link => link.className === "pr-button auto-pr-link" && link.rel === "noopener noreferrer"), true);
});

test("Auto polling speeds up while running and visible, slows when hidden or finished, and does not overlap reads", async () => {
    let snapshot = processSnapshot();
    let calls = 0;
    const ui = app(async () => { calls++; return { ok: true, json: async () => snapshot }; });
    ui.get("autoTab").style.display = "block";
    ui.context.startAutoProcessPolling();
    await new Promise(resolve => setImmediate(resolve));
    const runTimer = async () => {
        const [id, timer] = ui.timers.entries().next().value;
        ui.timers.delete(id);
        await timer.callback();
    };
    assert.equal(ui.timers.values().next().value.delay, 2000);
    ui.get("autoTab").style.display = "none";
    await runTimer();
    assert.equal(ui.timers.values().next().value.delay, 10000);
    snapshot = processSnapshot("STOPPED");
    ui.get("autoTab").style.display = "block";
    await runTimer();
    assert.equal(ui.timers.values().next().value.delay, 10000);
    assert.equal(ui.get("autoCurrentStep").hidden, true);
    let finish;
    ui.context.fetch = () => { calls++; return new Promise(resolve => { finish = resolve; }); };
    const before = calls;
    const pending = ui.context.loadAutoProcess();
    await ui.context.loadAutoProcess();
    assert.equal(calls, before + 1);
    finish({ ok: true, json: async () => snapshot });
    await pending;
});

test("Auto logs paginate without duplicates and fill a gap when more than one page arrives between polls", async () => {
    let phase = 0;
    const requests = [];
    const ui = app(async url => {
        requests.push(url);
        const snapshot = processSnapshot();
        if (phase === 0) {
            snapshot.logs = [logEntry(1), logEntry(2)];
        } else if (url.endsWith("before=7")) {
            snapshot.logs = [logEntry(3), logEntry(4), logEntry(5), logEntry(6)];
            snapshot.nextBefore = 3;
        } else if (url.endsWith("before=3")) {
            snapshot.logs = [logEntry(1), logEntry(2)];
        } else {
            snapshot.logs = [logEntry(7), logEntry(8)];
            snapshot.nextBefore = 7;
        }
        return { ok: true, json: async () => snapshot };
    });
    await ui.context.loadAutoProcess();
    phase = 1;
    await ui.context.loadAutoProcess();
    assert.deepEqual(requests.slice(1), ["/internal/api/auto/status", "/internal/api/auto/status?before=7", "/internal/api/auto/status?before=3"]);
    assert.equal(logEntries(ui).length, 8);
    assert.equal(logEntries(ui)[0].children[1].textContent, "Finished step 8");
    assert.equal(logEntries(ui)[7].children[1].textContent, "Finished step 1");
    const other = app(async url => ({
        ok: true, json: async () => ({ ...processSnapshot("COMPLETED"), logs: url.includes("before") ? [logEntry(1)] : [logEntry(2)], nextBefore: url.includes("before") ? null : 2 })
    }));
    await other.context.loadAutoProcess();
    assert.equal(other.get("loadOlderAutoLogs").hidden, false);
    await other.context.loadAutoProcess(2);
    assert.equal(logEntries(other).length, 2);
    assert.equal(other.get("loadOlderAutoLogs").hidden, true);
});

test("Auto process failures retain confirmed logs and running status with an explicit stale-data warning", async () => {
    const ui = app(async () => ({ ok: true, json: async () => processSnapshot("RUNNING", [logEntry(1)]) }));
    await ui.context.loadAutoProcess();
    ui.context.fetch = async () => ({ ok: false, json: async () => ({ error: "Database unavailable" }) });
    await ui.context.loadAutoProcess();
    assert.match(ui.get("autoProcessError").textContent, /Database unavailable.*stale/);
    assert.equal(ui.get("autoTabButton").classList.contains("auto-running"), true);
    assert.equal(logEntries(ui).length, 1);
    ui.context.fetch = async () => ({ ok: true, json: async () => processSnapshot("INTERRUPTED", [logEntry(2, { level: "WARN", message: "<script>unsafe</script>" })]) });
    await ui.context.loadAutoProcess();
    assert.equal(ui.get("autoProcessError").textContent, "");
    assert.equal(ui.get("autoTabButton").classList.contains("auto-running"), false);
    assert.match(logEntries(ui)[0].className, /auto-log-warning/);
    assert.equal(logEntries(ui)[0].children[1].textContent, "<script>unsafe</script>");
    ui.context.fetch = async () => ({ ok: true, json: async () => processSnapshot("COMPLETED", [logEntry(3, { url: "javascript:alert(1)" })]) });
    await ui.context.loadAutoProcess();
    assert.match(ui.get("autoProcessError").textContent, /Invalid pull request link/);
    assert.equal(logEntries(ui).length, 2);
});
