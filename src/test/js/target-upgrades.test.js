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
            toggle: (value, force) => force ? classes.add(value) : classes.delete(value),
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
        const wrapper = ui.row.children[0];
        assert.match(wrapper.className, /\btarget-security-details\b/);
        const panel = wrapper.children[0];
        assert.equal(panel.className, "finding-details target-security-detail");
        assert.equal(panel.children.length, 2, "Only the heading and version list are shown");
        assert.equal(panel.children[0].tag, "h4");
        assert.equal(panel.children[0].textContent, "Available upgrades for example:library");
        const list = panel.children.at(-1);
        assert.deepEqual(list.children.map(item => item.children[0].textContent), ["2.0", "1.1"]);
        assert.equal(list.children[0].children[1].textContent, "Major upgrade");
        assert.equal(list.children[0].children[1].className, "target-version-pill");
        assert.equal(list.children[1].children.length, 1, "Non-major upgrades have no badge");
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
        assert.equal(ui.row.children[0].children[0].children.length, 0);
        assert.deepEqual(ui.drafts, []);
    }
});

test("lookup failures and empty results are explicit and a failed lookup can be retried", async () => {
    const ui = app();
    ui.context.bindTargetUpgrades(ui.row, "dependency");
    ui.context.fetch = async () => ({ ok: false, json: async () => ({ error: "Metadata unavailable" }) });
    await ui.button.onclick();
    assert.match(ui.row.children[0].children[0].children[0].textContent, /Could not load versions: Metadata unavailable/);
    await ui.button.onclick();
    ui.context.fetch = async () => ({ ok: true, json: async () => ({ ...result, versions: [] }) });
    await ui.button.onclick();
    assert.match(ui.row.children[0].children[0].children[2].textContent, /No newer stable versions/);
    assert.deepEqual(ui.drafts, []);
});

test("prerelease-only results show the empty state rather than offering unstable upgrades", () => {
    const ui = app();
    const panel = element();
    ui.context.renderAvailableUpgrades(panel, { ...result, versions: [result.versions[0]] }, "dependency", () => {});
    assert.equal(panel.children[1].children.length, 0);
    assert.match(panel.children[2].textContent, /No newer stable versions/);
});

test("repository usage reuses the security details box and shows GitHub icons instead of bullets", () => {
    for (const type of ["dependency", "platform_bom"]) {
        const ui = app();
        vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/targets.js"), "utf8"), ui.context);
        ui.context.dependencyUsageRepositories = (key, usageType) => {
            assert.equal(key, "example:library");
            assert.equal(usageType, type);
            return ["example/repository"];
        };
        ui.row.querySelector = selector => selector === ".dependency-usage-btn" ? ui.button : ui.key;
        ui.context.bindDependencyUsage(ui.row, type);
        ui.button.onclick();
        const wrapper = ui.row.children[0];
        assert.match(wrapper.className, /\btarget-security-details\b/);
        const content = wrapper.children[0];
        assert.equal(content.className, "finding-details target-security-detail");
        assert.equal(content.children[0].tag, "h4");
        const list = content.children[1];
        assert.equal(list.className, "dependency-usage-list");
        const [icon, link] = list.children[0].children;
        assert.equal(icon.getAttribute("aria-hidden"), "true");
        assert.match(icon.innerHTML, /<svg/);
        assert.equal(link.textContent, "example/repository");
        assert.equal(link.href, "https://github.com/example/repository");
        assert.equal(link.rel, "noopener noreferrer");
        ui.button.onclick();
        assert.equal(wrapper.classList.contains("hidden"), true);
    }
});

function bulkApp(dependencies) {
    const ui = app();
    const button = element("button");
    const rows = Object.entries(dependencies).map(([key, version]) => {
        const row = element();
        const keyInput = { value: key };
        const versionInput = { value: version };
        row.querySelector = selector => selector === ".key" ? keyInput : versionInput;
        return row;
    });
    const messages = [];
    let renders = 0;
    ui.context.document.getElementById = () => button;
    ui.context.document.querySelectorAll = () => rows;
    ui.context.targetScanGeneration = 0;
    ui.context.targetState = { dependencies: { ...dependencies }, drafted: new Set(), plugins: { plugin: "1.0" } };
    ui.context.captureTargetDrafts = () => {};
    ui.context.closeTargetSaveMessage = () => {};
    ui.context.showTargetSaveMessage = (...args) => messages.push(args);
    ui.context.renderTargetTables = () => renders++;
    return { ...ui, button, rows, messages, renders: () => renders };
}

test("Upgrade all drafts only the latest stable same-major dependency upgrades and reports every change", async () => {
    const ui = bulkApp({ "example:first": "1.0", "example:second": "2.0", "example:latest": "3.0" });
    ui.context.fetchAvailableUpgrades = async (key) => ({
        versions: key === "example:latest" ? [] : [
            { version: "9.0", majorUpgrade: true },
            { version: "2.9-RC1", prerelease: true },
            { version: key === "example:first" ? "1.9" : "2.9", majorUpgrade: false },
            { version: key === "example:first" ? "1.1" : "2.1", majorUpgrade: false }
        ]
    });
    await ui.context.upgradeAllDependencies();
    assert.deepEqual(ui.context.targetState.dependencies, {
        "example:first": "1.9", "example:second": "2.9", "example:latest": "3.0"
    });
    assert.deepEqual([...ui.context.targetState.drafted], ["DEPENDENCY:example:first", "DEPENDENCY:example:second"]);
    assert.equal(ui.context.targetState.plugins.plugin, "1.0");
    assert.equal(ui.renders(), 1);
    assert.match(ui.messages[0][0], /example:first 1.0 -> 1.9\nexample:second 2.0 -> 2.9/);
    assert.deepEqual(ui.messages[0].slice(1), [true, false]);
    assert.equal(ui.button.disabled, false);
});

test("Upgrade all reports no eligible updates and does not draft major or prerelease-only results", async () => {
    const ui = bulkApp({ "example:library": "1.0" });
    ui.context.fetchAvailableUpgrades = async () => ({
        versions: [{ version: "2.0", majorUpgrade: true }, { version: "1.9-RC1", prerelease: true }]
    });
    await ui.context.upgradeAllDependencies();
    assert.equal(ui.context.targetState.drafted.size, 0);
    assert.equal(ui.renders(), 0);
    assert.equal(ui.messages[0][0], "No non-major updates available.");
});

test("Upgrade all keeps successful drafts while explicitly reporting lookup failures", async () => {
    const ui = bulkApp({ "example:good": "1.0", "example:bad": "1.0" });
    ui.context.fetchAvailableUpgrades = async key => {
        if (key === "example:bad") throw new Error("Metadata unavailable");
        return { versions: [{ version: "1.1", majorUpgrade: false }] };
    };
    await ui.context.upgradeAllDependencies();
    assert.equal(ui.context.targetState.dependencies["example:good"], "1.1");
    assert.equal(ui.context.targetState.dependencies["example:bad"], "1.0");
    assert.match(ui.messages[0][0], /example:bad: Metadata unavailable/);
    assert.equal(ui.messages[0][1], false);
});

test("Upgrade all does not overwrite edits or saved targets while lookups are running", async () => {
    for (const change of ["edit", "save", "remove"]) {
        const ui = bulkApp({ "example:library": "1.0" });
        ui.context.fetchAvailableUpgrades = async () => {
            if (change === "save") ui.context.targetScanGeneration++;
            if (change === "edit") ui.rows[0].querySelector(".version").value = "1.5";
            if (change === "remove") ui.rows[0].isConnected = false;
            return { versions: [{ version: "1.1", majorUpgrade: false }] };
        };
        await ui.context.upgradeAllDependencies();
        assert.equal(ui.context.targetState.drafted.size, 0);
        assert.equal(ui.renders(), 0);
        assert.equal(ui.messages[0][1], false);
    }
});

test("Upgrade all shows the shared spinner while waiting and restores its icon after completion or failure", async () => {
    for (const fail of [false, true]) {
        const ui = bulkApp({ "example:library": "1.0" });
        ui.button.innerHTML = "<svg>upgrade icon</svg>";
        let finish;
        ui.context.fetchAvailableUpgrades = () => new Promise((resolve, reject) => {
            finish = () => fail ? reject(new Error("Lookup failed")) : resolve({ versions: [] });
        });
        const pending = ui.context.upgradeAllDependencies();
        assert.match(ui.button.innerHTML, /class="spinner"/);
        assert.equal(ui.button.disabled, true);
        assert.equal(ui.button.getAttribute("aria-busy"), "true");
        finish();
        await pending;
        assert.equal(ui.button.innerHTML, "<svg>upgrade icon</svg>");
        assert.equal(ui.button.disabled, false);
        assert.equal(ui.button.getAttribute("aria-busy"), "false");
    }
});
