const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { html } = require("./gui-scripts");

function app(fetch) {
    const elements = new Map();
    const created = [];
    function element() {
        const node = {
            children: [], dataset: {}, attributes: {}, listeners: {}, textContent: "",
            append(...children) { this.children.push(...children); },
            appendChild(child) { this.children.push(child); },
            prepend(child) { this.children.unshift(child); },
            replaceChildren(...children) { this.children = children; },
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
        fetch
    });
    for (const file of ["api", "auto"]) {
        vm.runInContext(readFileSync(resolve(__dirname, `../../main/resources/gui/js/${file}.js`), "utf8"), context);
    }
    return { context, get, created, value: expression => vm.runInContext(expression, context) };
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

test("scanner attaches an Auto switch to scanned, unscanned and inaccessible repository cards", () => {
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
        assert.equal(actions.children[0].className, "auto-toggle");
        assert.equal(actions.children[0].dataset.repository, "example/repo");
    }
});
