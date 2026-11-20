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
        const node = {
            tagName, children: [], parentNode: null, attributes: {}, listeners: {}, value: "", open: false,
            get textContent() { return text + this.children.map(child => child.textContent).join(""); },
            set textContent(value) { text = String(value); this.children = []; },
            set innerHTML(value) {
                this.replaceChildren();
                for (const [, tag, attributes] of value.matchAll(/<(input|button)\b([^>]*)>/g)) {
                    const child = element(tag);
                    child.className = attributes.match(/class="([^"]+)"/)?.[1] || "";
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
            addEventListener(name, listener) { this.listeners[name] = listener; }
        };
        return node;
    }
    function get(id) {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    }
    const context = vm.createContext({
        document: { getElementById: get, createElement: element },
        console: { log() {}, error() {} },
        fetch
    });
    for (const file of ["state", "shared", "api", "ignored"]) {
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
    assert.equal(repo.summary.textContent, "Note");
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
