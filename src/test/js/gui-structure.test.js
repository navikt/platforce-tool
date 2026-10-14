const assert = require("node:assert/strict");
const { readdirSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { html, scripts, loadGuiScripts } = require("./gui-scripts");

function browser(fetch) {
    const elements = new Map();
    const documentListeners = [];
    const tabs = ["scanner", "targets", "ignored"].map(tab => ({
        dataset: { tab },
        classList: { add() {}, remove() {} },
        listeners: {},
        addEventListener(event, listener) { this.listeners[event] = listener; }
    }));
    function element(id) {
        if (!elements.has(id)) {
            elements.set(id, {
                style: {},
                listeners: {},
                addEventListener(event, listener) {
                    assert.equal(this.listeners[event], undefined, `Duplicate listener on ${id}`);
                    this.listeners[event] = listener;
                }
            });
        }
        return elements.get(id);
    }
    const context = vm.createContext({
        document: {
            getElementById: element,
            querySelectorAll: selector => selector === ".tab" ? tabs : [],
            addEventListener: (event, listener) => documentListeners.push([event, listener])
        },
        console: { log() {}, warn() {}, error() {} },
        fetch,
        setInterval: () => 1,
        clearInterval() {}
    });
    // Classic script globals and inline handlers share the browser's window object.
    context.window = context;
    return { context, element, tabs, documentListeners };
}

test("HTML loads every frontend feature file once, with shared definitions first and bootstrap last", () => {
    assert.deepEqual(scripts.map(({ source }) => source), [
        "js/state.js",
        "js/shared.js",
        "js/api.js",
        "js/scanner.js",
        "js/targets.js",
        "js/target-security.js",
        "js/scan-status.js",
        "js/ignored.js",
        "js/bootstrap.js"
    ]);
    const files = readdirSync(resolve(__dirname, "../../main/resources/gui/js"))
        .filter(file => file.endsWith(".js")).map(file => `js/${file}`).sort();
    assert.deepEqual(scripts.map(({ source }) => source).sort(), files);
    assert.ok(html.lastIndexOf("<script") > html.indexOf('id="saveIgnored"'));
});

test("feature files have no startup side effects and bootstrap exposes all inline handlers", () => {
    const calls = [];
    const app = browser(url => {
        calls.push(url);
        return new Promise(() => {});
    });
    for (const { source, code } of scripts.slice(0, -1)) {
        vm.runInContext(code, app.context, { filename: source });
    }
    assert.deepEqual(calls, []);
    assert.equal(app.documentListeners.length, 0);
    assert.ok([...["saveTargets", "scanTargets", "refreshBtn", "saveIgnored"]]
        .every(id => Object.keys(app.element(id).listeners).length === 0));

    const bootstrap = scripts.at(-1);
    vm.runInContext(bootstrap.code, app.context, { filename: bootstrap.source });
    assert.deepEqual(calls, ["/internal/selectedTeam"]);
    assert.equal(app.documentListeners.length, 3);
    assert.equal(app.element("saveTargets").listeners.click, app.context.saveTargets);
    assert.equal(app.element("scanTargets").listeners.click, app.context.scanTargetSecurity);
    assert.equal(app.element("refreshBtn").listeners.click, app.context.refresh);
    assert.equal(typeof app.element("saveIgnored").listeners.click, "function");

    const handlers = new Set();
    for (const code of [html, ...scripts.map(script => script.code)]) {
        for (const [, handler] of code.matchAll(/\bonclick="([\s\S]*?)"/g)) {
            const inlineCode = handler.replace(/\$\{[^}]*\}/g, "");
            for (const [, name] of inlineCode.matchAll(/(?<![\w.])([A-Za-z_$][\w$]*)\s*\(/g)) {
                handlers.add(name);
                assert.equal(typeof app.context.window[name], "function", `Inline handler ${name}`);
            }
        }
    }
    assert.deepEqual([...handlers].sort(), [
        "applySecurityUpgrade", "createPr", "ignoreRepository", "refreshRepository"
    ]);

    for (const tab of app.tabs) {
        tab.listeners.click();
        for (const name of ["scanner", "targets", "ignored"]) {
            assert.equal(app.element(`${name}Tab`).style.display,
                name === tab.dataset.tab ? "block" : "none");
        }
    }
});

test("bootstrap preserves startup order, selected team, cached security and uncached repository reads", async () => {
    const calls = [];
    const security = { targets: [{ key: "example:library", targetVersion: "1.0" }] };
    const data = {
        "/internal/selectedTeam": { selectedTeam: "saved-team" },
        "/internal/api/target-versions": { dependencies: { "example:library": "1.0" } },
        "/internal/repos/view": [],
        "/internal/api/dependency-scan": [],
        "/internal/api/repository-notes": { "example/repo": "saved note" },
        "/internal/api/ignored-repositories": { repositories: ["ignored/repo"] },
        "/internal/api/target-resolution/security": { status: "READY", result: security }
    };
    const app = browser(async (url, options) => {
        assert.ok(Object.hasOwn(data, url), `Unexpected startup request ${url}`);
        calls.push([url, options]);
        return { ok: true, json: async () => data[url] };
    });
    loadGuiScripts(app.context);
    const renders = [];
    app.context.render = () => renders.push("scanner");
    app.context.renderIgnoredRepositories = () => renders.push("ignored");
    app.context.renderTargetTables = () => renders.push("targets");

    // Let the actual bootstrap initialization finish, without invoking init a second time.
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(calls.map(([url]) => url), Object.keys(data));
    for (const [url, options] of calls.slice(1, -1)) {
        assert.equal(options.cache, "no-store", `Cache policy for ${url}`);
    }
    assert.deepEqual(renders, ["scanner", "ignored", "targets"]);
    assert.equal(vm.runInContext("selectedTeam", app.context), "saved-team");
    assert.equal(vm.runInContext("targetState.dependencies['example:library']", app.context), "1.0");
    assert.equal(vm.runInContext("notes['example/repo']", app.context), "saved note");
    assert.equal(vm.runInContext("ignoredRepositories[0]", app.context), "ignored/repo");
    assert.equal(vm.runInContext("targetSecurityScan", app.context), security);
    assert.equal(vm.runInContext("progressInterval", app.context), 1);
});
