const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function element(tag = "div") {
    const classes = new Set();
    return {
        tag, children: [], attributes: {}, dataset: {}, value: "", isConnected: true,
        querySelector(selector) { return this.children.find(child => child.className?.split(" ").includes(selector.slice(1))); },
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
        targetScanGeneration: 0,
        ignoredDependencies: [],
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
        { version: "2.0", prerelease: false, majorUpgrade: true, releaseDate: "2026-11-15T12:00:00Z", young: true },
        { version: "1.1", prerelease: false, majorUpgrade: false, releaseDate: "2024-01-01T00:00:00Z", young: false }
    ]
};

test("upgrade button works without scan data, fetches only on open and drafts dependencies or BOMs without saving", async () => {
    for (const type of ["dependency", "platform_bom", "plugin", "node"]) {
        const ui = app();
        let calls = 0;
        ui.context.fetch = async (url, options) => {
            if (url.includes("upgrade-security")) return { ok: true, json: async () => ({ status: "IDLE" }) };
            calls++;
            assert.equal(url, "/internal/api/target-versions/available?key=example%3Alibrary&currentVersion=1.0" + (type === "plugin" || type === "node" ? `&kind=${type}` : ""));
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
        assert.equal(panel.children.length, type === "platform_bom" ? 2 : 4);
        assert.equal(panel.children[0].tag, "h4");
        assert.equal(panel.children[0].textContent, "Available upgrades for example:library");
        const list = panel.children[1];
        assert.deepEqual(list.children.map(item => item.children[0].textContent), ["2.0", "1.1"]);
        assert.equal(list.children[0].children[2].textContent, "Major upgrade");
        assert.equal(list.children[0].children[2].className, "target-version-pill");
        assert.equal(list.children[0].children[3].textContent, "young");
        assert.match(list.children[0].children[3].title, /Released 2026-11-15; less than one week old/);
        assert.equal(list.children[0].children.length, 4, "Young major upgrades show only their version and badges");
        assert.equal(list.children[1].children.length, 2, "Mature non-major upgrades have no badges or publication date");
        list.children[0].children[0].onclick();
        assert.deepEqual(ui.drafts, [[type.toUpperCase(), "example:library", "2.0"]]);
        assert.equal(calls, 1, "Selecting a draft does not save or scan");
        await ui.button.onclick();
        assert.equal(calls, 1, "Closing does not start another lookup");
        assert.equal(ui.button.getAttribute("aria-expanded"), "false");
    }
});

test("scoped Node upgrades encode the package, draft only NODE and focus the Node row", async () => {
    const ui = app();
    ui.key.value = "@slack/bolt";
    ui.version.value = "3.22.0";
    let focused = false;
    ui.version.focus = () => { focused = true; };
    ui.context.document.querySelectorAll = selector => {
        assert.equal(selector, "#nodeTable .target-row");
        return [ui.row];
    };
    ui.context.fetch = async (url, options) => {
        assert.equal(options.cache, "no-store");
        if (url.includes("upgrade-security")) {
            assert.match(url, /kind=node&key=%40slack%2Fbolt&currentVersion=3.22.0$/);
            return { ok: true, json: async () => ({ status: "IDLE" }) };
        }
        assert.equal(url, "/internal/api/target-versions/available?key=%40slack%2Fbolt&currentVersion=3.22.0&kind=node");
        return { ok: true, json: async () => ({ key: "@slack/bolt", currentVersion: "3.22.0", versions: [{ version: "4.0.0", majorUpgrade: true }] }) };
    };
    ui.context.bindTargetUpgrades(ui.row, "node");
    await ui.button.onclick();
    ui.row.children[0].children[0].children[1].children[0].children[0].onclick();
    assert.deepEqual(ui.drafts, [["NODE", "@slack/bolt", "4.0.0"]]);
    assert.equal(focused, true);
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

test("unknown release dates are explicit and young or undated versions remain manually selectable", () => {
    const ui = app();
    const panel = element();
    const selected = [];
    ui.context.renderAvailableUpgrades(panel, {
        ...result,
        versions: [
            result.versions[1],
            { version: "1.2", releaseDateError: "Release date unavailable: HTTP 503" }
        ]
    }, "platform_bom", (...args) => selected.push(args));
    const items = panel.children[1].children;
    assert.equal(items[1].children.length, 2, "Unknown dates are not shown inline");
    assert.equal(items[1].children[0].title, "Release date unavailable: HTTP 503");
    items.forEach(item => item.children[0].onclick());
    assert.deepEqual(selected, [
        ["PLATFORM_BOM", "example:library", "2.0"],
        ["PLATFORM_BOM", "example:library", "1.2"]
    ]);
});

test("repository usage reuses the security details box and shows GitHub icons instead of bullets", () => {
    for (const type of ["dependency", "platform_bom", "plugin", "salesforce"]) {
        const ui = app();
        vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/targets.js"), "utf8"), ui.context);
        ui.context.lastLoadedData = null;
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

test("usage crosshair loses only its center circle for undeclared targets after a scan and refreshes on edits", () => {
    for (const type of ["dependency", "platform_bom", "plugin", "node", "salesforce"]) {
        const ui = app();
        vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/targets.js"), "utf8"), ui.context);
        ui.context.lastLoadedData = null;
        ui.button.setAttribute("aria-label", "Show repositories using this target");
        ui.row.querySelector = selector => selector === ".dependency-usage-btn" ? ui.button
            : selector === ".key" ? ui.key : ui.row.children[0];
        ui.button.closest = () => ui.row;
        ui.button.dataset.usageType = type;
        ui.context.document.querySelectorAll = () => [ui.button];
        ui.context.bindDependencyUsage(ui.row, type);
        assert.equal(ui.button.disabled, true);
        assert.match(ui.button.innerHTML, /r="2"/);

        const field = ui.context.declaredTargetKeysField(type);
        ui.context.lastLoadedData = { scans: [{ repository: "example/repo", [field]: ["example:library"] }] };
        ui.context.refreshDependencyUsageButtons();
        assert.equal(ui.button.disabled, false);
        assert.match(ui.button.innerHTML, /r="2"/);

        ui.key.value = "example:absent";
        ui.key.input();
        assert.doesNotMatch(ui.button.innerHTML, /r="2"/);
        assert.match(ui.button.innerHTML, /r="7"/);
        assert.match(ui.button.innerHTML, /M12 2v4m0 12v4M2 12h4m12 0h4/);
        assert.equal(ui.button.title, "No scanned repositories declare this target");
        ui.button.onclick();
        assert.equal(ui.row.children[0].children[0].children[0].textContent,
            "0 scanned repositories declare example:absent.");

        ui.context.lastLoadedData.scans[0][field].push("example:absent");
        ui.context.refreshDependencyUsageButtons();
        assert.match(ui.button.innerHTML, /r="2"/);
        assert.equal(ui.button.title, "Show repositories using this target");

        for (const scans of [[], [{ repository: "unknown/repo" }]]) {
            ui.context.lastLoadedData = { scans };
            ui.context.refreshDependencyUsageButtons();
            assert.equal(ui.button.disabled, true);
            assert.match(ui.button.innerHTML, /r="2"/);
        }
    }
});

function bulkApp(dependencies, type = "dependency") {
    const ui = app();
    if (type === "node") {
        vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/targets.js"), "utf8"), ui.context);
    }
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
    ui.context.document.getElementById = id => {
        assert.equal(id, type === "node" ? "upgradeAllNodeDependencies" : "upgradeAllDependencies");
        return button;
    };
    ui.context.document.querySelectorAll = selector => {
        assert.equal(selector, type === "node" ? "#nodeTable .target-row" : "#depsTable .target-row");
        return rows;
    };
    ui.context.targetScanGeneration = 0;
    ui.context.targetState = {
        dependencies: type === "node" ? { "example:untouched": "1.0" } : { ...dependencies },
        nodeDependencies: type === "node" ? { ...dependencies } : {},
        nodeTransientDependencies: new Set(),
        drafted: new Set(),
        plugins: { plugin: "1.0" }
    };
    ui.context.captureTargetDrafts = () => {};
    ui.context.closeTargetSaveMessage = () => {};
    ui.context.showTargetSaveMessage = (...args) => messages.push(args);
    ui.context.renderTargetTables = () => renders++;
    return { ...ui, button, rows, messages, renders: () => renders };
}

test("Upgrade all drafts only the latest mature stable same-major dependency upgrades and reports every change", async () => {
    const ui = bulkApp({ "example:first": "1.0", "example:second": "2.0", "example:latest": "3.0" });
    ui.context.fetchAvailableUpgrades = async (key) => ({
        versions: key === "example:latest" ? [] : [
            { version: "9.0", majorUpgrade: true },
            { version: "2.9-RC1", prerelease: true },
            { version: key === "example:first" ? "1.10" : "2.10", majorUpgrade: false, releaseDate: "2026-11-15T00:00:00Z", young: true },
            { version: key === "example:first" ? "1.9" : "2.9", majorUpgrade: false, releaseDate: "2024-01-01T00:00:00Z", young: false },
            { version: key === "example:first" ? "1.1" : "2.1", majorUpgrade: false, releaseDate: "2024-01-01T00:00:00Z", young: false }
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

test("Node Upgrade all uses npm metadata and drafts scoped packages and overrides without changing their roles or saving", async () => {
    const ui = bulkApp({ "@slack/bolt": "^3.22.0", "ws": "~7.5.11", "small": "0.1.0" }, "node");
    ui.context.targetState.nodeTransientDependencies.add("ws");
    const calls = [];
    ui.context.fetch = async (url, options) => {
        calls.push(url);
        assert.equal(options.cache, "no-store");
        const query = new URL(url, "http://localhost").searchParams;
        assert.equal(query.get("kind"), "node");
        const key = query.get("key");
        const current = { "@slack/bolt": "^3.22.0", ws: "~7.5.11", small: "0.1.0" };
        assert.equal(query.get("currentVersion"), current[key]);
        const mature = { "@slack/bolt": "3.23.0", ws: "7.6.0", small: "0.2.0" }[key];
        const major = mature.split(".")[0];
        return { ok: true, json: async () => ({
            versions: [
                { version: `${Number(major) + 1}.0.0`, majorUpgrade: true, releaseDate: "2024-01-01T00:00:00Z" },
                { version: `${major}.99.0`, young: true, releaseDate: "2026-11-17T00:00:00Z" },
                { version: `${major}.98.0-beta.1`, prerelease: true, releaseDate: "2024-01-01T00:00:00Z" },
                { version: mature, young: false, releaseDate: "2024-01-01T00:00:00Z" }
            ]
        }) };
    };
    await ui.context.upgradeAllNodeDependencies();
    assert.equal(calls.length, 3);
    assert.deepEqual(ui.context.targetState.nodeDependencies, { "@slack/bolt": "3.23.0", ws: "7.6.0", small: "0.2.0" });
    assert.deepEqual([...ui.context.targetState.nodeTransientDependencies], ["ws"]);
    assert.deepEqual([...ui.context.targetState.drafted], ["NODE:@slack/bolt", "NODE:ws", "NODE:small"]);
    assert.deepEqual(ui.context.targetState.dependencies, { "example:untouched": "1.0" });
    assert.equal(ui.context.targetState.plugins.plugin, "1.0");
    assert.equal(ui.renders(), 1);
    assert.match(ui.messages[0][0], /@slack\/bolt \^3.22.0 -> 3.23.0/);
    assert.equal(ui.messages[0][1], true);
});

test("Node Upgrade all skips ignored packages, reports unknown dates and failures, and retains successful drafts", async () => {
    const ui = bulkApp({ ignored: "1.0.0", unknown: "1.0.0", failed: "1.0.0", good: "1.0.0" }, "node");
    ui.context.ignoredDependencies = ["ignored"];
    const calls = [];
    ui.context.fetchAvailableUpgrades = async (key, version, type) => {
        calls.push(key);
        assert.equal(type, "node");
        if (key === "failed") throw new Error("npm metadata unavailable");
        return { versions: [
            ...(key === "unknown" ? [{ version: "1.9.0" }] : []),
            { version: "1.8.0", releaseDate: "2024-01-01T00:00:00Z", young: false }
        ] };
    };
    await ui.context.upgradeAllNodeDependencies();
    assert.deepEqual(calls, ["unknown", "failed", "good"]);
    assert.deepEqual(ui.context.targetState.nodeDependencies, {
        ignored: "1.0.0", unknown: "1.8.0", failed: "1.0.0", good: "1.8.0"
    });
    assert.match(ui.messages[0][0], /unknown 1.9.0: skipped because its release date is unknown/);
    assert.match(ui.messages[0][0], /failed: npm metadata unavailable/);
    assert.equal(ui.messages[0][1], false);
});

test("Node Upgrade all protects concurrent edits, removals and saves and prevents duplicate starts", async () => {
    for (const change of ["edit", "save", "remove"]) {
        const ui = bulkApp({ package: "1.0.0" }, "node");
        ui.button.innerHTML = "<svg>upgrade icon</svg>";
        let finish;
        let calls = 0;
        ui.context.fetchAvailableUpgrades = () => {
            calls++;
            return new Promise(resolve => {
                finish = () => resolve({ versions: [{ version: "1.1.0", releaseDate: "2024-01-01T00:00:00Z", young: false }] });
            });
        };
        const pending = ui.context.upgradeAllNodeDependencies();
        assert.equal(ui.button.disabled, true);
        assert.equal(ui.button.getAttribute("aria-busy"), "true");
        assert.match(ui.button.innerHTML, /class="spinner"/);
        await ui.context.upgradeAllNodeDependencies();
        assert.equal(calls, 1);
        if (change === "save") ui.context.targetScanGeneration++;
        if (change === "edit") ui.rows[0].querySelector(".version").value = "1.5.0";
        if (change === "remove") ui.rows[0].isConnected = false;
        finish();
        await pending;
        assert.equal(ui.context.targetState.drafted.size, 0);
        assert.equal(ui.context.targetState.nodeDependencies.package, "1.0.0");
        assert.equal(ui.renders(), 0);
        assert.equal(ui.messages[0][1], false);
        assert.equal(ui.button.innerHTML, "<svg>upgrade icon</svg>");
        assert.equal(ui.button.disabled, false);
        assert.equal(ui.button.getAttribute("aria-busy"), "false");
    }
});

test("Upgrade all reports no eligible updates and does not draft major, prerelease or young results", async () => {
    const ui = bulkApp({ "example:library": "1.0" });
    ui.context.fetchAvailableUpgrades = async () => ({
        versions: [
            { version: "2.0", majorUpgrade: true, releaseDate: "2024-01-01T00:00:00Z" },
            { version: "1.9-RC1", prerelease: true, releaseDate: "2024-01-01T00:00:00Z" },
            { version: "1.9", young: true, releaseDate: "2026-11-15T00:00:00Z" }
        ]
    });
    await ui.context.upgradeAllDependencies();
    assert.equal(ui.context.targetState.drafted.size, 0);
    assert.equal(ui.renders(), 0);
    assert.equal(ui.messages[0][0], "No stable non-major updates at least one week old available.");
});

test("Upgrade all skips unknown dates and warns only when they would change the selected version", async () => {
    for (const mature of [true, false]) {
        const ui = bulkApp({ "example:library": "1.0" });
        ui.context.fetchAvailableUpgrades = async () => ({
            versions: [
                { version: "1.9", majorUpgrade: false },
                ...(mature ? [{ version: "1.8", releaseDate: "2024-01-01T00:00:00Z", young: false }] : [])
            ]
        });
        await ui.context.upgradeAllDependencies();
        assert.equal(ui.context.targetState.dependencies["example:library"], mature ? "1.8" : "1.0");
        assert.match(ui.messages[0][0], /example:library 1.9: skipped because its release date is unknown/);
        assert.equal(ui.messages[0][1], false);
    }
    const ui = bulkApp({ "example:library": "1.0" });
    ui.context.fetchAvailableUpgrades = async () => ({
        versions: [
            { version: "1.9", releaseDate: "2024-01-01T00:00:00Z", young: false },
            { version: "1.8" }
        ]
    });
    await ui.context.upgradeAllDependencies();
    assert.equal(ui.messages[0][1], true);
    assert.doesNotMatch(ui.messages[0][0], /unknown/);
});

test("Upgrade all skips ignored dependency coordinates even when typed into a visible row", async () => {
    const ui = bulkApp({ "example:ignored": "1.0", "example:active": "1.0" });
    ui.context.ignoredDependencies = ["example:ignored"];
    const lookedUp = [];
    ui.context.fetchAvailableUpgrades = async key => {
        lookedUp.push(key);
        return { versions: [{ version: "1.1", majorUpgrade: false, releaseDate: "2024-01-01T00:00:00Z", young: false }] };
    };
    await ui.context.upgradeAllDependencies();
    assert.deepEqual(lookedUp, ["example:active"]);
    assert.equal(ui.context.targetState.dependencies["example:ignored"], "1.0");
    assert.deepEqual([...ui.context.targetState.drafted], ["DEPENDENCY:example:active"]);
});

test("Upgrade all keeps successful drafts while explicitly reporting lookup failures", async () => {
    const ui = bulkApp({ "example:good": "1.0", "example:bad": "1.0" });
    ui.context.fetchAvailableUpgrades = async key => {
        if (key === "example:bad") throw new Error("Metadata unavailable");
        return { versions: [{ version: "1.1", majorUpgrade: false, releaseDate: "2024-01-01T00:00:00Z", young: false }] };
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
            return { versions: [{ version: "1.1", majorUpgrade: false, releaseDate: "2024-01-01T00:00:00Z", young: false }] };
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

test("dropdown security scans are opt-in, poll results and reuse cached vulnerability badges on reopen", async () => {
    for (const type of ["dependency", "node"]) {
            const ui = app();
            const calls = [];
            let cached = false;
            ui.context.setTimeout = callback => callback();
            ui.context.fetch = async url => {
                calls.push(url);
                assert.match(url, new RegExp(`kind=${type}&`));
                const snapshot = url.includes("/start")
                    ? { status: "RUNNING", targetFingerprint: "saved" }
                    : cached || calls.length > 2
                        ? { status: "READY", targetFingerprint: "saved", result: [
                            { version: "2.0", status: "VULNERABLE", vulnerabilityCount: 2 },
                            { version: "1.1", status: "UNRESOLVED", error: "Missing metadata" }
                        ] }
                        : { status: "IDLE", targetFingerprint: "saved" };
                return { ok: true, json: async () => snapshot };
            };
            const panel = element();
            ui.context.renderAvailableUpgrades(panel, result, type, () => {});
            await new Promise(resolve => setImmediate(resolve));
            assert.equal(calls.length, 1);
            assert.ok(!calls[0].includes("/start"));
            const scan = panel.children[2];
            await scan.onclick();
            const badges = panel.children[1].children.map(item => item.children[1]);
            assert.equal(badges[0].textContent, "2 vulnerabilities");
            assert.equal(badges[1].textContent, "UNRESOLVED");
            assert.equal(badges[1].title, "Missing metadata");
            assert.equal(scan.disabled, false);
            cached = true;
            const reopened = element();
            ui.context.renderAvailableUpgrades(reopened, result, type, () => {});
            await new Promise(resolve => setImmediate(resolve));
            assert.equal(reopened.children[1].children[0].children[1].textContent, "2 vulnerabilities");
            assert.equal(calls.filter(url => url.includes("/start")).length, 1);
    }
});
