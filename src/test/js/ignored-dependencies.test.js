const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function app() {
    const rows = new Map();
    const messages = [];
    const rendered = [];
    const context = vm.createContext({
        console: { log() {}, error() {} },
        document: {
            getElementById: () => ({}),
            querySelectorAll: selector => rows.get(selector) || [],
            querySelector: () => ({ querySelector: () => ({ value: "8.0" }) })
        },
        setTimeout() {}, clearTimeout() {}
    });
    for (const file of ["state", "targets", "ignored"]) {
        vm.runInContext(readFileSync(resolve(__dirname, `../../main/resources/gui/js/${file}.js`), "utf8"), context);
    }
    context.renderTable = (container, entries) => rendered.push([container, JSON.parse(JSON.stringify(entries))]);
    context.renderGradleVersion = () => {};
    context.showTargetSaveMessage = (...args) => messages.push(args);
    const row = (key, version) => ({
        querySelector: selector => ({ value: selector === ".key" ? key : version })
    });
    rows.set("#depsTable .target-row", [row("example:visible", "3.0")]);
    return { context, messages, rendered };
}

test("ignored targets are hidden while draft capture and saving retain their version and transient flag", async () => {
    const { context, rendered } = app();
    await context.renderTargets({
        dependencies: { "example:ignored": "2.0", "example:visible": "1.0" },
        ignoredDependencies: ["example:ignored"],
        transientDependencies: ["example:ignored"],
        gradleVersion: "8.0"
    });
    context.renderTargetTables();
    assert.deepEqual(rendered.find(([id]) => id === "depsTable")[1], [["example:visible", "1.0"]]);
    context.captureTargetDrafts();
    const payload = JSON.parse(JSON.stringify(context.readTargetInputs()));
    assert.deepEqual(payload.dependencies, { "example:ignored": "2.0", "example:visible": "3.0" });
    assert.deepEqual(payload.transientDependencies, ["example:ignored"]);
    context.addDraftTarget("DEPENDENCY", "example:ignored", "99.0");
    assert.equal(context.readTargetInputs().dependencies["example:ignored"], "2.0");
    vm.runInContext("ignoredDependencies = []", context);
    context.renderTargetTables();
    assert.deepEqual(rendered.at(-2)[1], [["example:ignored", "2.0"], ["example:visible", "3.0"]]);
});

test("ignore changes refresh backend data and failed saves report errors without changing local state", async () => {
    const { context, messages } = app();
    let refreshed = 0;
    let request;
    context.loadData = async () => refreshed++;
    context.fetch = async (url, options) => {
        request = [url, JSON.parse(options.body)];
        return { ok: true };
    };
    const button = { disabled: false };
    await context.updateIgnoredDependencies(["example:ignored"], button);
    assert.deepEqual(request, ["/internal/api/ignored-dependencies/update", { dependencies: ["example:ignored"] }]);
    assert.equal(refreshed, 1);
    assert.equal(button.disabled, false);
    context.fetch = async () => ({ ok: false, text: async () => "Database unavailable" });
    await context.updateIgnoredDependencies([], button);
    assert.equal(refreshed, 1);
    assert.match(messages[0][0], /Database unavailable/);
    assert.equal(messages[0][1], false);
    assert.equal(button.disabled, false);
});
