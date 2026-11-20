const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { html } = require("./gui-scripts");

function app(fetch) {
    const elements = new Map();
    function element(tagName = "div") {
        let text = "";
        let markup = "";
        const node = {
            tagName, children: [], parentNode: null, attributes: {}, dataset: {}, listeners: {}, value: "", open: false,
            get textContent() { return text + this.children.map(child => child.textContent).join(""); },
            set textContent(value) { text = String(value); this.children = []; },
            get innerHTML() { return markup; },
            set innerHTML(value) {
                this.replaceChildren();
                markup = value;
                for (const [, tag, attributes] of value.matchAll(/<(input|button)\b([^>]*)>/g)) {
                    const child = element(tag);
                    child.className = attributes.match(/class="([^"]+)"/)?.[1] || "";
                    for (const [, name, value] of attributes.matchAll(/([\w-]+)="([^"]*)"/g)) {
                        child.setAttribute(name, value);
                        if (name.startsWith("data-")) {
                            child.dataset[name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
                        }
                    }
                    child.disabled = /\bdisabled\b/.test(attributes);
                    this.appendChild(child);
                }
            },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            appendChild(child) { child.remove(); child.parentNode = this; this.children.push(child); return child; },
            insertBefore(child, reference) {
                child.remove();
                child.parentNode = this;
                this.children.splice(this.children.indexOf(reference), 0, child);
            },
            remove() {
                if (this.parentNode) {
                    const siblings = this.parentNode.children;
                    siblings.splice(siblings.indexOf(this), 1);
                    this.parentNode = null;
                }
            },
            replaceChildren() { this.children.forEach(child => { child.parentNode = null; }); this.children = []; text = ""; },
            querySelector(selector) {
                for (const child of this.children) {
                    if (selector.startsWith(".") ? child.className?.split(" ").includes(selector.slice(1)) : child.tagName === selector) {
                        return child;
                    }
                    const match = child.querySelector(selector);
                    if (match) return match;
                }
                return null;
            },
            setAttribute(key, value) { this.attributes[key] = value; },
            getAttribute(key) { return this.attributes[key]; },
            closest(selector) {
                if (selector.startsWith(".") && this.className?.split(" ").includes(selector.slice(1))) return this;
                return this.parentNode?.closest(selector) || null;
            },
            addEventListener(name, listener) { this.listeners[name] = listener; }
        };
        node.classList = {
            contains: name => (node.className || "").split(" ").includes(name),
            add(name) { if (!this.contains(name)) node.className = `${node.className || ""} ${name}`.trim(); },
            remove(name) { node.className = (node.className || "").split(" ").filter(value => value !== name).join(" "); },
            toggle(name, active) { if (active ?? !this.contains(name)) this.add(name); else this.remove(name); }
        };
        return node;
    }
    function get(id) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    }
    const context = vm.createContext({
        document: {
            getElementById: get, createElement: element,
            querySelectorAll(selector) {
                const matches = [];
                const visit = node => {
                    if (selector.startsWith(".") && node.className?.split(" ").includes(selector.slice(1))) matches.push(node);
                    node.children.forEach(visit);
                };
                elements.forEach(visit);
                return matches;
            }
        },
        console: { log() {}, error() {} },
        fetch
    });
    for (const file of ["state", "shared", "api", "targets", "ignored"]) {
        vm.runInContext(readFileSync(resolve(__dirname, `../../main/resources/gui/js/${file}.js`), "utf8"), context);
    }
    context.render = () => {};
    return { context, get, value: expression => vm.runInContext(expression, context) };
}

function server(initial = []) {
    const notes = new Map(initial.map(note => [JSON.stringify([note.kind, note.key]), note]));
    const calls = [];
    return {
        notes, calls,
        fetch: async (url, options) => {
            calls.push([url, options]);
            assert.equal(url, "/internal/api/ignored-notes");
            assert.equal(options.cache, "no-store");
            if (options.method === "POST") {
                const setting = JSON.parse(options.body);
                const id = JSON.stringify([setting.kind, setting.key]);
                if (setting.note.trim()) notes.set(id, setting);
                else notes.delete(id);
                return { ok: true, json: async () => setting };
            }
            return { ok: true, json: async () => [...notes.values()] };
        }
    };
}

function editor(ui, kind, key) {
    return ui.value(`ignoredNoteEditors.get(ignoredNoteId(${JSON.stringify(kind)}, ${JSON.stringify(key)}))`);
}

function draft(editor, note) {
    editor.input.value = note;
    editor.input.listeners.input();
}

test("each ignored kind has a global editor, safe plain-text notes, and shared persistence across browser sessions", async () => {
    const backend = server();
    const ui = app(backend.fetch);
    ui.value('ignoredRepositories = ["Example/Repo"]; ignoredDependencies = ["example:library"]');
    ui.context.renderIgnoredRepositories();
    const repo = editor(ui, "REPOSITORY", "example/repo");
    const dependency = editor(ui, "DEPENDENCY", "example:library");
    assert.equal(repo.save.disabled, true);
    assert.equal(dependency.save.disabled, true);
    await repo.save.listeners.click();
    assert.equal(backend.calls.length, 0);
    await ui.context.loadIgnoredNotes();
    assert.equal(repo.save.disabled, false);
    const text = "<script>alert(1)</script>\nCompatibility constraint";
    draft(repo, text);
    await repo.save.listeners.click();
    draft(dependency, "Keep pinned");
    await dependency.save.listeners.click();
    assert.equal(repo.preview.textContent, text);
    assert.equal(repo.preview.children.length, 0);
    assert.equal(repo.toggle.innerHTML, ui.value("EDIT_NOTE_SVG"));
    assert.equal(repo.toggle.attributes["data-has-note"], "true");
    assert.equal(repo.input.maxLength, 10000);
    assert.equal(repo.input.attributes["aria-label"], "Global note for repository Example/Repo");
    const other = app(backend.fetch);
    other.value('ignoredRepositories = ["example/repo"]; ignoredDependencies = ["example:library"]');
    other.context.renderIgnoredRepositories();
    await other.context.loadIgnoredNotes();
    assert.equal(editor(other, "REPOSITORY", "example/repo").preview.textContent, text);
    assert.equal(editor(other, "DEPENDENCY", "example:library").preview.textContent, "Keep pinned");
    draft(editor(other, "DEPENDENCY", "example:library"), "Updated globally");
    await editor(other, "DEPENDENCY", "example:library").save.listeners.click();
    await ui.context.loadIgnoredNotes();
    assert.equal(dependency.preview.textContent, "Updated globally");
    assert.match(html, /<details id="parentlessIgnoredNotes"[^>]*hidden>/);
    assert.doesNotMatch(html, /<details id="parentlessIgnoredNotes"[^>]*\bopen\b/);
});

test("ignored rows place trash directly after aligned fields and use the scanner note icon to toggle editing", async () => {
    const ui = app(server().fetch);
    ui.value('ignoredRepositories = ["example/repo"]; ignoredDependencies = ["example:library"]');
    ui.context.renderIgnoredRepositories();
    await ui.context.loadIgnoredNotes();
    assert.doesNotMatch(html, /Notes are shared globally\. Removing an ignored item keeps its note\./);
    for (const [table, kind, key] of [
        ["ignoredReposTable", "REPOSITORY", "example/repo"],
        ["ignoredDepsTable", "DEPENDENCY", "example:library"]
    ]) {
        const current = editor(ui, kind, key);
        const row = ui.get(table).children[0];
        assert.equal(row.querySelector(".key").value, key);
        assert.equal(row.children[1].className, "icon-btn remove");
        assert.equal(row.children[kind === "REPOSITORY" ? 2 : 3], current.container);
        assert.equal(ui.get(table).children[1].children[0].tagName, "input");
        assert.equal(current.container.children[0], current.preview);
        assert.equal(current.container.children[1], current.toggle);
        assert.equal(current.toggle.tagName, "button");
        assert.equal(current.toggle.className, "note-icon");
        assert.equal(current.toggle.innerHTML, ui.value("CREATE_NOTE_SVG"));
        assert.equal(current.toggle.attributes["data-has-note"], "false");
        assert.equal(current.preview.hidden, true);
        const form = current.container.querySelector(".ignored-note-editor");
        assert.equal(form.hidden, true);
        current.toggle.listeners.click();
        assert.equal(form.hidden, false);
        assert.equal(current.toggle.attributes["aria-expanded"], "true");
        draft(current, "Note text");
        await current.save.listeners.click();
        assert.equal(form.hidden, true);
        assert.equal(current.toggle.attributes["aria-expanded"], "false");
        assert.equal(current.toggle.attributes["data-has-note"], "true");
        assert.equal(current.preview.textContent, "Note text");
        current.toggle.listeners.click();
        current.toggle.listeners.click();
        assert.equal(form.hidden, true);
    }
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    assert.match(css, /\.ignore-content\s*\{[^}]*--ignored-key-width:\s*300px;/);
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
    assert.ok(rules.some(([, selectors, body]) =>
        ["#ignoredReposTable .target-row", "#ignoredDepsTable .target-row",
            "#ignoredReposTable .add-row", "#ignoredDepsTable .add-row"]
            .every(selector => selectors.split(",").map(value => value.trim()).includes(selector)) &&
        /grid-template-columns:\s*var\(--ignored-key-width\) 28px minmax\(0, 1fr\)/.test(body)));
    assert.match(css, /\.ignored-note \.note-icon\[data-has-note="true"\]\s*\{\s*opacity:\s*0\.85;/);
    assert.match(css, /\.ignored-note \.note-icon:hover,\s*\.ignored-note \.note-icon:focus-visible\s*\{\s*opacity:\s*1;/);
    assert.match(css, /\.ignored-note\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) 28px;/);
    assert.match(css, /\.ignored-note-preview\s*\{\s*grid-column:\s*1;/);
    assert.match(css, /\.ignored-note \.note-icon\s*\{\s*grid-column:\s*2;/);
});

test("ignored repository fields show all known teams as sorted safe pills without changing the repository identity", () => {
    const ui = app(server().fetch);
    ui.value(`ignoredRepositories = ["EXAMPLE/REPO", "example/unknown"];
        selectedTeam = "team-a";
        lastLoadedData = { repos: [
            { name: "example/repo", team: "team-b" },
            { name: "Example/Repo", team: "team-a" },
            { name: "example/repo", team: "team-a" },
            { name: "example/repo", team: "<script>team</script>" },
            { name: "example/repo", team: null },
            { name: "example/other", team: "unrelated-team" }
        ] }`);
    ui.context.renderIgnoredRepositories();
    const row = ui.get("ignoredReposTable").children[0];
    const field = row.children[0];
    assert.equal(field.className, "ignored-repository-field");
    assert.equal(field.children[0].value, "EXAMPLE/REPO");
    assert.equal(field.children[0].attributes["aria-label"], "Ignored repository EXAMPLE/REPO");
    const pills = field.children.slice(1);
    assert.deepEqual(pills.map(pill => pill.textContent), ["<script>team</script>", "team-a", "team-b"]);
    assert.ok(pills.every(pill => pill.className === "team-pill ignored-team-pill" && pill.children.length === 0));
    assert.equal(ui.get("ignoredReposTable").children[1].children[0].children.length, 1);
    ui.value('lastLoadedData.repos = [{ name: "example/repo", team: "updated-team" }]');
    ui.context.renderIgnoredRepositories();
    assert.deepEqual(ui.get("ignoredReposTable").children[0].children[0].children.slice(1).map(pill => pill.textContent),
        ["updated-team"]);
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    assert.match(css, /\.ignored-repository-field\s*\{[^}]*flex-wrap:\s*nowrap;[^}]*overflow:\s*hidden;/);
    assert.match(css, /\.ignored-repository-field \.key\s*\{[^}]*flex:\s*0 0 auto;[^}]*max-width:\s*100%;/);
    assert.match(css, /\.ignored-repository-field \.ignored-team-pill\s*\{[^}]*min-width:\s*0;[^}]*white-space:\s*nowrap;[^}]*overflow:\s*hidden;[^}]*text-overflow:\s*ellipsis;/);
    assert.match(css, /\.ignored-repository-field \.key \+ \.ignored-team-pill\s*\{[^}]*margin-left:\s*auto;/);
});

test("ignored dependency crosshairs reuse raw inventory usage, include ignored repositories, and refresh without changing targets", () => {
    const ui = app(server().fetch);
    ui.value('ignoredDependencies = ["example:library", "example:unused"]; ignoredRepositories = ["ignored/repo"]');
    ui.context.renderIgnoredDependencies();
    let row = ui.get("ignoredDepsTable").children[0];
    assert.equal(row.querySelector(".dependency-usage-btn").disabled, true);
    assert.equal(row.querySelector(".dependency-usage-btn").title, "Run Dependency Scanner first");
    ui.value(`lastLoadedData = { scans: [
        { repository: "example/current", declaredDependencyKeys: ["example:library"], findings: [], untrackedDependencies: [] },
        { repository: "ignored/repo", declaredDependencyKeys: ["example:library"], findings: [], untrackedDependencies: [] },
        { repository: "example/current", declaredDependencyKeys: ["example:library"] },
        { repository: "example/add-only", declaredDependencyKeys: [], findings: [{ key: "example:library", status: "ADD" }] }
    ] }`);
    ui.context.renderIgnoredDependencies();
    row = ui.get("ignoredDepsTable").children[0];
    const button = row.querySelector(".dependency-usage-btn");
    assert.equal(row.children[1].className, "icon-btn remove");
    assert.equal(row.children[2], button);
    assert.equal(button.disabled, false);
    assert.match(button.innerHTML, /r="2"/);
    button.onclick();
    const details = row.querySelector(".dependency-usage-details");
    assert.equal(details.classList.contains("hidden"), false);
    const content = details.children[0];
    assert.equal(content.children[0].textContent, "2 scanned repositories declare example:library.");
    assert.deepEqual(content.children[1].children.map(item => item.children[1].textContent),
        ["example/current", "ignored/repo"]);
    assert.equal(content.children[1].children[1].children[1].href, "https://github.com/ignored/repo");
    assert.equal(content.children[1].children[1].children[1].rel, "noopener noreferrer");
    button.onclick();
    assert.equal(details.classList.contains("hidden"), true);
    const unusedRow = ui.get("ignoredDepsTable").children[1];
    const unused = unusedRow.querySelector(".dependency-usage-btn");
    assert.equal(unused.disabled, false);
    assert.doesNotMatch(unused.innerHTML, /r="2"/);
    unused.onclick();
    assert.equal(unusedRow.querySelector(".dependency-usage-details").children[0].children[0].textContent,
        "0 scanned repositories declare example:unused.");
    ui.value('lastLoadedData.scans[0].declaredDependencyKeys.push("example:unused")');
    ui.context.refreshDependencyUsageButtons();
    assert.match(unused.innerHTML, /r="2"/);
    assert.equal(unused.getAttribute("aria-expanded"), "false");
    assert.equal(unusedRow.querySelector(".dependency-usage-details").classList.contains("hidden"), true);
    assert.equal(ui.value('Object.keys(targetState.dependencies).length'), 0);
    for (const scans of ['[]', '[{ repository: "unknown/repo" }]']) {
        ui.value(`lastLoadedData.scans = ${scans}`);
        ui.context.refreshDependencyUsageButtons();
        assert.equal(button.disabled, true);
        assert.equal(unused.disabled, true);
    }
});

test("ignored sections share target-version border tints and subtle alternating row backgrounds", () => {
    assert.match(html, /class="target-section ignored ignored-repositories"/);
    assert.match(html, /class="target-section ignored ignored-dependencies"/);
    const css = readFileSync(resolve(__dirname, "../../main/resources/gui/styles.css"), "utf8");
    const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)];
    function rule(selector) {
        return rules.findLast(([, selectors]) => selectors.split(",").map(value => value.trim()).includes(selector))?.[2];
    }
    for (const [section, target] of [["ignored-repositories", "plugins"], ["ignored-dependencies", "dependencies"]]) {
        const panel = rule(`.target-section.${section}`);
        const targetPanel = rule(`.target-section.${target}`);
        assert.equal(panel.match(/background:\s*([^;]+)/)[1], targetPanel.match(/background:\s*([^;]+)/)[1]);
        assert.equal(panel.match(/border:\s*([^;]+)/)[1], targetPanel.match(/border:\s*([^;]+)/)[1]);
        for (const stripe of ["odd", "even"]) {
            const background = rule(`.target-section.${section} .target-row:nth-child(${stripe})`).match(/background:\s*([^;]+)/)[1];
            const targetBackground = rule(`.target-section.${target} .target-row:nth-child(${stripe})`).match(/background:\s*([^;]+)/)[1];
            assert.equal(background, targetBackground);
        }
    }
    assert.match(rule("#ignoredDepsTable .target-row"), /var\(--ignored-key-width\)/);
    assert.match(css, /#ignoredDepsTable \.target-row,\s*#ignoredDepsTable \.add-row\s*\{\s*grid-template-columns:\s*var\(--ignored-key-width\) 28px 28px minmax\(0, 1fr\);/);
});

test("unignoring retains notes in the collapsed parent-less section and re-ignoring reattaches them", async () => {
    const backend = server([
        { kind: "REPOSITORY", key: "example/repo", note: "Repo note" },
        { kind: "DEPENDENCY", key: "example:library", note: "Dependency note" }
    ]);
    const ui = app(backend.fetch);
    ui.value('ignoredRepositories = ["EXAMPLE/REPO"]; ignoredDependencies = ["example:library"]');
    ui.context.renderIgnoredRepositories();
    await ui.context.loadIgnoredNotes();
    const section = ui.get("parentlessIgnoredNotes");
    assert.equal(section.hidden, true);
    const repo = editor(ui, "REPOSITORY", "example/repo");
    draft(repo, "Unsaved draft");
    ui.value("ignoredRepositories = []; ignoredDependencies = []");
    ui.context.renderIgnoredRepositories();
    assert.equal(section.hidden, false);
    assert.equal(section.open, false);
    assert.equal(ui.get("parentlessIgnoredNotesList").children.length, 2);
    assert.equal(repo.input.value, "Unsaved draft");
    assert.equal(backend.notes.size, 2);
    section.open = true;
    await ui.context.loadIgnoredNotes();
    assert.equal(section.open, true);
    assert.equal(repo.input.value, "Unsaved draft");
    draft(repo, "Edited without parent");
    await repo.save.listeners.click();
    assert.equal(backend.notes.get('["REPOSITORY","example/repo"]').note, "Edited without parent");
    ui.value('ignoredRepositories = ["example/repo"]; ignoredDependencies = ["example:library"]');
    ui.context.renderIgnoredRepositories();
    assert.equal(section.hidden, true);
    assert.equal(ui.get("ignoredReposTable").children[0].querySelector(".ignored-note"), repo.container);
    assert.equal(repo.preview.textContent, "Edited without parent");
    ui.value("ignoredRepositories = []; ignoredDependencies = []");
    ui.context.renderIgnoredRepositories();
    for (const [kind, key] of [["REPOSITORY", "example/repo"], ["DEPENDENCY", "example:library"]]) {
        const current = editor(ui, kind, key);
        draft(current, "  ");
        await current.save.listeners.click();
    }
    assert.equal(section.hidden, true);
    assert.equal(backend.notes.size, 0);
});

test("failed writes retain confirmed notes and drafts, prevent duplicates, and reject stale reads", async () => {
    const backend = server([{ kind: "REPOSITORY", key: "example/repo", note: "Confirmed" }]);
    const ui = app(backend.fetch);
    ui.value('ignoredRepositories = ["example/repo"]');
    ui.context.renderIgnoredRepositories();
    await ui.context.loadIgnoredNotes();
    const repo = editor(ui, "REPOSITORY", "example/repo");
    draft(repo, "Draft");
    let finishWrite;
    let finishRead;
    let writes = 0;
    ui.context.fetch = async (url, options) => {
        if (options.method === "POST") {
            writes++;
            return new Promise(resolve => { finishWrite = resolve; });
        }
        return new Promise(resolve => { finishRead = resolve; });
    };
    const read = ui.context.loadIgnoredNotes();
    const save = repo.save.listeners.click();
    assert.equal(repo.save.disabled, true);
    assert.equal(repo.input.disabled, true);
    await repo.save.listeners.click();
    assert.equal(writes, 1);
    finishWrite({ ok: false, status: 503, json: async () => ({ error: "Database unavailable" }) });
    await save;
    finishRead({ ok: true, json: async () => [] });
    await read;
    assert.equal(repo.preview.textContent, "Confirmed");
    assert.equal(repo.input.value, "Draft");
    assert.match(repo.error.textContent, /Database unavailable/);
    assert.equal(repo.error.attributes.role, "alert");
    assert.equal(repo.save.disabled, false);
    ui.context.fetch = backend.fetch;
    await repo.save.listeners.click();
    assert.equal(repo.preview.textContent, "Draft");
    assert.equal(repo.error.textContent, "");
});

test("failed and malformed reads disable editing without presenting unknown notes as empty and recover on retry", async () => {
    const backend = server([{ kind: "DEPENDENCY", key: "example:library", note: "Confirmed" }]);
    const ui = app(backend.fetch);
    ui.value('ignoredDependencies = ["example:library"]');
    ui.context.renderIgnoredDependencies();
    await ui.context.loadIgnoredNotes();
    const dependency = editor(ui, "DEPENDENCY", "example:library");
    for (const response of [
        { ok: false, status: 503 },
        { ok: true, json: async () => ({ notes: [] }) },
        { ok: true, json: async () => [{ kind: "OTHER", key: "bad", note: "bad" }] }
    ]) {
        ui.context.fetch = async () => response;
        await ui.context.loadIgnoredNotes();
        assert.equal(dependency.save.disabled, true);
        assert.equal(dependency.preview.textContent, "Confirmed");
        assert.match(ui.get("ignoredNotesError").textContent, /Could not load ignored notes/);
    }
    ui.context.fetch = backend.fetch;
    await ui.context.loadIgnoredNotes();
    assert.equal(dependency.save.disabled, false);
    assert.equal(ui.get("ignoredNotesError").textContent, "");
});

test("successful saves invalidate stale snapshots and unconfirmed responses never replace confirmed notes", async () => {
    const backend = server([{ kind: "DEPENDENCY", key: "example:library", note: "Confirmed" }]);
    const ui = app(backend.fetch);
    ui.value('ignoredDependencies = ["example:library"]');
    ui.context.renderIgnoredDependencies();
    await ui.context.loadIgnoredNotes();
    const dependency = editor(ui, "DEPENDENCY", "example:library");
    let finishRead;
    ui.context.fetch = async (url, options) => options.method === "POST"
        ? backend.fetch(url, options)
        : new Promise(resolve => { finishRead = resolve; });
    const read = ui.context.loadIgnoredNotes();
    draft(dependency, "Saved");
    await dependency.save.listeners.click();
    finishRead({ ok: true, json: async () => [] });
    await read;
    assert.equal(dependency.preview.textContent, "Saved");
    draft(dependency, "Unconfirmed");
    for (const fetch of [
        async () => { throw new Error("Offline"); },
        async () => ({ ok: true, json: async () => ({ kind: "DEPENDENCY", key: "example:other", note: "Unconfirmed" }) }),
        async () => ({ ok: true, json: async () => null })
    ]) {
        ui.context.fetch = fetch;
        await dependency.save.listeners.click();
        assert.equal(dependency.preview.textContent, "Saved");
        assert.equal(dependency.input.value, "Unconfirmed");
        assert.match(dependency.error.textContent, /Could not save note/);
        assert.equal(dependency.save.disabled, false);
    }
});
