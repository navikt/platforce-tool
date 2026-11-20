const assert = require("node:assert/strict");
const { readdirSync, readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { html, scripts, loadGuiScripts } = require("./gui-scripts");

function browser(fetch) {
    const elements = new Map();
    const documentListeners = [];
    const tabs = ["scanner", "targets", "ignored", "auto", "deployments"].map(tab => ({
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
                replaceChildren() {},
                classList: { toggle() {} },
                setAttribute() {},
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
        clearInterval() {},
        setTimeout: () => 1,
        clearTimeout() {}
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
        "js/auto.js",
        "js/scanner.js",
        "js/targets.js",
        "js/target-upgrades.js",
        "js/target-security.js",
        "js/scan-status.js",
        "js/ignored.js",
        "js/deployments.js",
        "js/bootstrap.js"
    ]);
    const files = readdirSync(resolve(__dirname, "../../main/resources/gui/js"))
        .filter(file => file.endsWith(".js")).map(file => `js/${file}`).sort();
    assert.deepEqual(scripts.map(({ source }) => source).sort(), files);
    assert.ok(html.lastIndexOf("<script") > html.indexOf('id="saveIgnored"'));
});

test("NODE targets follow Platform BOMs with scan controls and no explanatory block", () => {
    assert.ok(html.indexOf('id="nodeTable"') > html.indexOf('id="platformBomsTable"'));
    assert.ok(html.indexOf('id="nodeTable"') < html.indexOf('class="target-section dependencies"'));
    assert.match(html, /class="target-section node"/);
    assert.doesNotMatch(html, /Each saved target is scanned independently|Caret and tilde prefixes|Node PR\/Auto updates are not available yet/);
    assert.match(html, /id="scanNode" class="btn-danger"/);
    assert.match(html, /id="upgradeAllNodeDependencies" class="icon-btn target-upgrade-all"/);
});

test("Platform BOMs have their own initially hidden scan status before their target rows", () => {
    const section = html.slice(html.indexOf('class="target-section platform-boms"'),
        html.indexOf('class="target-section node"'));
    assert.match(section, /id="bomScanStatus" class="target-scan-details" role="status" aria-live="polite" hidden/);
    assert.ok(section.indexOf('id="bomScanStatus"') < section.indexOf('id="platformBomsTable"'));
    assert.doesNotMatch(section, /id="targetScanDetails"/);
    assert.equal(html.split('id="bomScanStatus"').length - 1, 1);
});

test("Node target rows share the Plugins and Platform BOMs grid and button columns", () => {
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
    for (const [selectors, declaration] of [
        [[".target-section.dependencies", ".target-section.plugins", ".target-section.platform-boms", ".target-section.node"], /--target-columns:\s*300px 110px 90px 28px 28px 28px 28px;/],
        [["#pluginsTable .target-row", "#platformBomsTable .target-row", "#nodeTable .target-row"], /grid-template-columns:\s*var\(--target-columns\);/],
        [["#pluginsTable .target-upgrade-btn", "#platformBomsTable .target-upgrade-btn", "#nodeTable .target-upgrade-btn"], /grid-column:\s*5;/],
        [["#pluginsTable .dependency-usage-btn", "#platformBomsTable .dependency-usage-btn", "#nodeTable .dependency-usage-btn"], /grid-column:\s*6;/],
        [["#pluginsTable .remove-btn", "#platformBomsTable .remove-btn", "#nodeTable .remove-btn"], /grid-column:\s*7;/]
    ]) {
        assert.ok(rules.some(([, selector, body]) =>
            selectors.every(value => selector.split(",").map(value => value.trim()).includes(value)) &&
            declaration.test(body)), `Shared layout rule missing for ${selectors.join(", ")}`);
    }
    assert.match(css, /#depsTable \.transient-btn,\s*#nodeTable \.transient-btn\s*\{\s*grid-column:\s*4;/);
    assert.match(css, /grid-template-columns:\s*minmax\(700px, 705px\) minmax\(700px, 705px\) auto;/);
    assert.match(css, /\.target-upgrade-all\s*\{\s*grid-column:\s*5;\s*grid-row:\s*1;/);
    assert.match(css, /\.target-upgrade-all \.spinner\s*\{\s*margin-right:\s*0;/);
});

test("Node repository rows use the shared dependency layout with only a background tint override", () => {
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
    const sharedRow = rules.find(([, selector]) => selector.trim() === ".row")[2];
    assert.match(sharedRow, /grid-template-columns:\s*120px 1fr 160px 120px;/);
    assert.match(sharedRow, /padding:\s*6px 10px;/);
    const nodeRows = rules.filter(([, selector]) => selector.includes(".node-row"));
    assert.ok(nodeRows.length > 0);
    for (const [, , body] of nodeRows) {
        assert.match(body, /^\s*background:\s*#[a-f\d]+;\s*$/i);
    }
});

test("scan resolution progress paints behind existing messages without adding layout space", () => {
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    const rule = css.match(/\.scan-resolution-progress\s*\{([^}]*)\}/)?.[1];
    assert.ok(rule);
    assert.match(rule, /background:\s*linear-gradient\(/);
    assert.match(rule, /var\(--scan-resolution-progress\)/);
    assert.doesNotMatch(rule, /(?:height|width|padding|margin|display)\s*:/);
});

test("scan dismissal controls stay in the top right without adding a status row", () => {
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    const details = css.match(/\.target-scan-details\s*\{([^}]*)\}/)?.[1];
    const close = css.match(/\.scan-status-close\s*\{([^}]*)\}/)?.[1];
    assert.match(details, /position:\s*relative/);
    assert.match(close, /position:\s*absolute/);
    assert.match(close, /top:\s*4px/);
    assert.match(close, /right:\s*4px/);
});

test("Node status pills expand and collapse the same repository details as Gradle", () => {
    const app = browser(() => new Promise(() => {}));
    loadGuiScripts(app.context);
    let toggles = 0;
    const details = { classList: { toggle: name => {
        assert.equal(name, "hidden");
        toggles++;
    } } };
    const status = { closest: selector => {
        assert.equal(selector, ".finding-container");
        return { querySelector: selector => {
            assert.equal(selector, ".finding-details");
            return details;
        } };
    } };
    const event = {
        target: { closest: selector => selector === "[data-toggle-details='true']" ? status : null },
        stopPropagation() {},
        preventDefault() {}
    };
    for (let click = 0; click < 2; click++) {
        app.documentListeners.filter(([name]) => name === "click").forEach(([, listener]) => listener(event));
    }
    assert.equal(toggles, 2);
});

test("repository draft clicks pass the Node override role through to target drafts", () => {
    const app = browser(() => new Promise(() => {}));
    loadGuiScripts(app.context);
    const drafts = [];
    app.context.addDraftTarget = (...args) => drafts.push(args);
    for (const transient of ["true", "false"]) {
        const pill = {
            dataset: { kind: "NODE", key: "leaf", version: "^2.0.0", nodeTransient: transient },
            classList: { add() {}, remove() {} }
        };
        app.documentListeners[0][1]({ target: { closest: () => pill } });
        assert.deepEqual(drafts.at(-1), ["NODE", "leaf", "^2.0.0", transient === "true"]);
        assert.equal(pill.textContent, "DRAFTED");
    }
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
    assert.equal(app.element("scanNode").listeners.click, app.context.scanNodeSecurity);
    assert.equal(app.element("upgradeAllNodeDependencies").listeners.click, app.context.upgradeAllNodeDependencies);
    assert.equal(app.element("startAuto").listeners.click, app.context.startAuto);
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
        "applySecurityUpgrade", "createPr", "dismissScanStatus", "ignoreRepository", "refreshRepository"
    ]);

    let deploymentLoads = 0;
    let autoLoads = 0;
    let ignoredNoteLoads = 0;
    app.context.loadDeployments = () => deploymentLoads++;
    app.context.loadAutoRepositories = () => autoLoads++;
    app.context.loadIgnoredNotes = () => ignoredNoteLoads++;
    for (const tab of app.tabs) {
        tab.listeners.click();
        for (const name of ["scanner", "targets", "ignored", "auto", "deployments"]) {
            assert.equal(app.element(`${name}Tab`).style.display,
                name === tab.dataset.tab ? "block" : "none");
        }
    }
    assert.equal(deploymentLoads, 1);
    assert.equal(autoLoads, 1);
    assert.equal(ignoredNoteLoads, 1);
    app.element("refreshAutoRepositories").listeners.click();
    assert.equal(autoLoads, 2);
    app.element("refreshDeployments").listeners.click();
    assert.equal(deploymentLoads, 2);
});

test("bootstrap preserves startup order, selected team, cached security and uncached repository reads", async () => {
    const calls = [];
    const security = { targets: [{ key: "example:library", targetVersion: "1.0" }] };
    const data = {
        "/internal/selectedTeam": { selectedTeam: "saved-team" },
        "/internal/api/auto/status": { run: null, runs: [], logs: [] },
        "/internal/api/target-versions": { dependencies: { "example:library": "1.0" } },
        "/internal/api/auto-repositories": { repositories: [] },
        "/internal/repos/view": [],
        "/internal/api/dependency-scan": [],
        "/internal/api/repository-notes": { "example/repo": "saved note" },
        "/internal/api/ignored-repositories": { repositories: ["ignored/repo"] },
        "/internal/api/ignored-notes": [],
        "/internal/api/target-resolution/security": {
            status: "READY", normalReady: true, bomReady: true, result: security
        },
        "/internal/api/plugin-security": { status: "IDLE" },
        "/internal/api/node-security": { status: "IDLE" }
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
    for (const [url, options] of calls.slice(1).filter(([url]) => url !== "/internal/api/target-resolution/security")) {
        assert.equal(options.cache, "no-store", `Cache policy for ${url}`);
    }
    assert.deepEqual(renders, ["scanner", "ignored", "targets", "scanner"]);
    assert.equal(vm.runInContext("selectedTeam", app.context), "saved-team");
    assert.equal(vm.runInContext("targetState.dependencies['example:library']", app.context), "1.0");
    assert.equal(vm.runInContext("notes['example/repo']", app.context), "saved note");
    assert.equal(vm.runInContext("ignoredRepositories[0]", app.context), "ignored/repo");
    assert.equal(vm.runInContext("targetSecurityScan", app.context), security);
    assert.equal(vm.runInContext("normalSecurityReady", app.context), true);
    assert.equal(vm.runInContext("bomSecurityReady", app.context), true);
    assert.equal(app.element("scanBoms").disabled, false);
    assert.equal(vm.runInContext("progressInterval", app.context), 1);
});
