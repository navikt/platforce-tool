const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function element(tag = "div") {
    const classes = new Set();
    return {
        tag, children: [], attributes: {}, value: "", isConnected: true,
        classList: {
            add: value => classes.add(value),
            remove: value => classes.delete(value),
            contains: value => classes.has(value)
        },
        append(...children) { this.children.push(...children); },
        appendChild(child) { this.append(child); },
        replaceChildren(...children) { this.children = children; },
        setAttribute(name, value) { this.attributes[name] = value; },
        getAttribute(name) { return this.attributes[name]; },
        addEventListener(event, callback) { this[event] = callback; }
    };
}

function app() {
    const drafts = [];
    const context = vm.createContext({
        document: { createElement: element, querySelectorAll: () => [] },
        console: { error() {} },
        fetch: () => { throw new Error("Unexpected request"); },
        addDraftTarget: (...args) => drafts.push(args)
    });
    vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/target-upgrades.js"), "utf8"), context);
    const row = element();
    const button = element("button");
    const key = element("input");
    key.value = "example:library";
    const version = element("input");
    version.value = "1.0";
    row.querySelector = selector => ({ ".target-upgrade-btn": button, ".key": key, ".version": version })[selector];
    return { context, drafts, row, button, key, version };
}

const result = {
    key: "example:library", currentVersion: "1.0",
    versions: [
        { version: "2.1-RC1", prerelease: true, majorUpgrade: true },
        { version: "2.0", prerelease: false, majorUpgrade: true },
        { version: "1.1", prerelease: false, majorUpgrade: false }
    ]
};

test("upgrade button works without scan data, fetches only on open and drafts dependencies or BOMs without saving", async () => {
    for (const type of ["dependency", "platform_bom"]) {
        const ui = app();
        let calls = 0;
        ui.context.fetch = async (url, options) => {
            calls++;
            assert.equal(url, "/internal/api/target-versions/available?key=example%3Alibrary&currentVersion=1.0");
            assert.equal(options.cache, "no-store");
            return { ok: true, json: async () => result };
        };
        assert.doesNotMatch(ui.context.targetUpgradeButtonHtml(), /disabled/);
        ui.context.bindTargetUpgrades(ui.row, type);
        assert.equal(calls, 0);
        await ui.button.onclick();
        assert.equal(calls, 1);
        assert.equal(ui.button.getAttribute("aria-expanded"), "true");
        const panel = ui.row.children[0];
        assert.match(panel.className, /\bfinding-details\b/);
        assert.equal(panel.children.length, 2, "Only the heading and version list are shown");
        assert.equal(panel.children[0].textContent, "Available upgrades for example:library");
        const list = panel.children.at(-1);
        assert.deepEqual(list.children.map(item => item.children[0].textContent), ["2.0", "1.1"]);
        assert.ok(list.children.every(item => item.children.length === 1), "Versions have no extra labels");
        list.children[0].children[0].onclick();
        assert.deepEqual(ui.drafts, [[type.toUpperCase(), "example:library", "2.0"]]);
        assert.equal(calls, 1, "Selecting a draft does not save or scan");
        await ui.button.onclick();
        assert.equal(calls, 1, "Closing does not start another lookup");
        assert.equal(ui.button.getAttribute("aria-expanded"), "false");
    }
});

test("editing a target or removing its row while loading discards stale upgrade results", async () => {
    for (const change of ["key", "version", "remove", "close"]) {
        const ui = app();
        let reply;
        ui.context.fetch = () => new Promise(resolve => { reply = resolve; });
        ui.context.bindTargetUpgrades(ui.row, "dependency");
        const pending = ui.button.onclick();
        if (change === "remove") ui.row.isConnected = false;
        else if (change === "close") await ui.button.onclick();
        else {
            ui[change].value = "changed";
            ui[change].input();
        }
        reply({ ok: true, json: async () => result });
        await pending;
        assert.equal(ui.row.children[0].children.length, 0);
        assert.deepEqual(ui.drafts, []);
    }
});

test("lookup failures and empty results are explicit and a failed lookup can be retried", async () => {
    const ui = app();
    ui.context.bindTargetUpgrades(ui.row, "dependency");
    ui.context.fetch = async () => ({ ok: false, json: async () => ({ error: "Metadata unavailable" }) });
    await ui.button.onclick();
    assert.match(ui.row.children[0].children[0].textContent, /Could not load versions: Metadata unavailable/);
    await ui.button.onclick();
    ui.context.fetch = async () => ({ ok: true, json: async () => ({ ...result, versions: [] }) });
    await ui.button.onclick();
    assert.match(ui.row.children[0].children[2].textContent, /No newer stable versions/);
    assert.deepEqual(ui.drafts, []);
});

test("prerelease-only results show the empty state rather than offering unstable upgrades", () => {
    const ui = app();
    const panel = element();
    ui.context.renderAvailableUpgrades(panel, { ...result, versions: [result.versions[0]] }, "dependency", () => {});
    assert.equal(panel.children[1].children.length, 0);
    assert.match(panel.children[2].textContent, /No newer stable versions/);
});
