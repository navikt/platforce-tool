let ignoredNotes = new Map();
let ignoredNotesLoaded = false;
let ignoredNotesGeneration = 0;
const ignoredNoteEditors = new Map();

function ignoredNoteId(kind, key) {
    return JSON.stringify([kind, kind === "REPOSITORY" ? key.toLowerCase() : key]);
}

function validIgnoredNote(note) {
    return note && ["REPOSITORY", "DEPENDENCY"].includes(note.kind) &&
        typeof note.key === "string" && typeof note.note === "string";
}

async function loadIgnoredNotes() {
    const generation = ++ignoredNotesGeneration;
    try {
        const data = await fetchJson("/internal/api/ignored-notes");
        if (!Array.isArray(data) || !data.every(validIgnoredNote)) throw new Error("Invalid ignored notes response");
        if (generation !== ignoredNotesGeneration) return;
        ignoredNotes = new Map(data.map(note => [ignoredNoteId(note.kind, note.key), note]));
        ignoredNotesLoaded = true;
        document.getElementById("ignoredNotesError").textContent = "";
    } catch (failure) {
        if (generation !== ignoredNotesGeneration) return;
        ignoredNotesLoaded = false;
        console.error("Could not load ignored notes", failure);
        document.getElementById("ignoredNotesError").textContent =
            `Could not load ignored notes: ${failure.message}. Reopen the Ignored tab to retry.`;
    }
    if (generation !== ignoredNotesGeneration) return;
    updateIgnoredNoteEditors();
    renderParentlessIgnoredNotes();
}

function updateIgnoredNoteEditors() {
    ignoredNoteEditors.forEach((editor, id) => {
        const note = ignoredNotes.get(id)?.note || "";
        editor.summary.textContent = note.trim() ? "Note" : "Add note";
        editor.preview.textContent = note;
        editor.preview.hidden = !note.trim();
        if (!editor.dirty && !editor.saving) editor.input.value = note;
        editor.save.disabled = !ignoredNotesLoaded || editor.saving;
        editor.input.disabled = !ignoredNotesLoaded || editor.saving;
        editor.save.textContent = editor.saving ? "Saving..." : "Save";
    });
}

function createIgnoredNoteEditor(kind, key) {
    const normalizedKey = kind === "REPOSITORY" ? key.toLowerCase() : key;
    const id = ignoredNoteId(kind, key);
    if (ignoredNoteEditors.has(id)) return ignoredNoteEditors.get(id).container;
    const container = document.createElement("div");
    container.className = "ignored-note";
    const preview = document.createElement("div");
    preview.className = "ignored-note-preview";
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.setAttribute("aria-label", `Edit global note for ${kind.toLowerCase()} ${key}`);
    const input = document.createElement("textarea");
    input.maxLength = 10000;
    input.rows = 3;
    input.setAttribute("aria-label", `Global note for ${kind.toLowerCase()} ${key}`);
    const save = document.createElement("button");
    save.type = "button";
    save.className = "save-note-btn";
    const error = document.createElement("p");
    error.className = "ignored-note-error";
    error.setAttribute("role", "alert");
    details.append(summary, input, save, error);
    container.append(preview, details);
    const editor = { container, preview, summary, input, save, error, dirty: false, saving: false };
    ignoredNoteEditors.set(id, editor);
    input.addEventListener("input", () => { editor.dirty = true; });
    save.addEventListener("click", async () => {
        if (!ignoredNotesLoaded || editor.saving) return;
        editor.saving = true;
        ignoredNotesGeneration++;
        error.textContent = "";
        const setting = { kind, key: normalizedKey, note: input.value };
        updateIgnoredNoteEditors();
        try {
            const response = await fetch("/internal/api/ignored-notes", {
                method: "POST",
                cache: "no-store",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(setting)
            });
            const saved = await response.json();
            if (!response.ok) throw new Error(saved?.error || `HTTP ${response.status}`);
            if (!validIgnoredNote(saved) || saved.kind !== kind || saved.key !== normalizedKey || saved.note !== setting.note) {
                throw new Error("Invalid ignored note response");
            }
            ignoredNotesGeneration++;
            if (saved.note.trim()) ignoredNotes.set(id, saved);
            else ignoredNotes.delete(id);
            editor.dirty = false;
            details.open = false;
        } catch (failure) {
            console.error("Could not save ignored note", failure);
            error.textContent = `Could not save note: ${failure.message}. Your draft is retained; retry Save.`;
        } finally {
            editor.saving = false;
            updateIgnoredNoteEditors();
            renderParentlessIgnoredNotes();
        }
    });
    updateIgnoredNoteEditors();
    return container;
}

function renderParentlessIgnoredNotes() {
    const repositories = new Set(ignoredRepositories.map(repo => repo.toLowerCase()));
    const dependencies = new Set(ignoredDependencies);
    const parentless = [...ignoredNotes.values()].filter(note => note.note.trim() &&
        !(note.kind === "REPOSITORY" ? repositories.has(note.key.toLowerCase()) : dependencies.has(note.key)));
    parentless.sort((a, b) => a.kind.localeCompare(b.kind) || a.key.localeCompare(b.key));
    const section = document.getElementById("parentlessIgnoredNotes");
    section.hidden = parentless.length === 0;
    const list = document.getElementById("parentlessIgnoredNotesList");
    list.replaceChildren();
    parentless.forEach(note => {
        const row = document.createElement("div");
        row.className = "parentless-ignored-note";
        const label = document.createElement("span");
        label.textContent = `${note.kind === "REPOSITORY" ? "Repository" : "Dependency"}: ${note.key}`;
        row.append(label, createIgnoredNoteEditor(note.kind, note.key));
        list.appendChild(row);
    });
}

async function ignoreRepository(repo) {

    const btn = event.currentTarget;

    const original = btn.innerHTML;

    btn.disabled = true;
    btn.innerHTML = `
        <span class="spinner"></span>
        Ignoring...
    `;

    if (
        ignoredRepositories.includes(repo)
    ) {
        return;
    }

    ignoredRepositories.push(repo);

    await saveIgnoredRepositories();

    await loadData();
}

function renderIgnoredRepositories() {
    renderIgnoredDependencies();

    const container =
        document.getElementById("ignoredReposTable");

    container.innerHTML = "";

    ignoredRepositories
        .sort()
        .forEach(repo => {

            const row =
                document.createElement("div");

            row.className =
                "target-row";

            row.innerHTML = `<input class="key" readonly />
                <button type="button" class="icon-btn remove">${TRASH_SVG}</button>`;
            row.querySelector(".key").value = repo;
            row.insertBefore(createIgnoredNoteEditor("REPOSITORY", repo), row.querySelector(".remove"));
            row.querySelector(".remove").setAttribute("aria-label", `Stop ignoring ${repo}`);

            row.querySelector(".remove")
                .addEventListener("click", async () => {

                        ignoredRepositories =
                            ignoredRepositories
                                .filter(
                                    r => r !== repo
                                );

                        await saveIgnoredRepositories();

                        renderIgnoredRepositories();

                        render(lastLoadedData)
                    }
                );

            container.appendChild(row);
        });

    const addRow =
        document.createElement("div");

    addRow.className = "add-row";

    addRow.innerHTML = `
    <input
        class="key new-ignore-input"
        placeholder="owner/repo"
    />

    <button class="icon-btn add-btn">
        ${ADD_SVG}
    </button>
`;

    const input = addRow.querySelector(".new-ignore-input");
    const btn = addRow.querySelector(".add-btn");

    const add = async () => {
        const repo = input.value.trim();
        if (!repo) return;

        if (!ignoredRepositories.includes(repo)) {
            ignoredRepositories.push(repo);
        }

        input.value = "";

        await saveIgnoredRepositories();
        renderIgnoredRepositories();

        // IMPORTANT: update scanner view immediately
        render(lastLoadedData);
    };

    btn.addEventListener("click", add);

    input.addEventListener("keydown", e => {
        if (e.key === "Enter") add();
    });

    container.appendChild(addRow);
    renderParentlessIgnoredNotes();
}

async function updateIgnoredDependencies(dependencies, button) {
    button.disabled = true;
    try {
        const response = await fetch("/internal/api/ignored-dependencies/update", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ dependencies })
        });
        if (!response.ok) throw new Error(await response.text());
        await loadData();
    } catch (error) {
        console.error("Could not update ignored dependencies", error);
        showTargetSaveMessage(`Could not update ignored dependencies: ${error.message}`, false);
    } finally {
        button.disabled = false;
    }
}

function renderIgnoredDependencies() {
    const container = document.getElementById("ignoredDepsTable");
    container.replaceChildren();
    [...ignoredDependencies].sort().forEach(dependency => {
        const row = document.createElement("div");
        row.className = "target-row";
        row.innerHTML = `<input class="key" readonly />
            <button type="button" class="icon-btn remove">${TRASH_SVG}</button>`;
        row.querySelector(".key").value = dependency;
        const button = row.querySelector(".remove");
        row.insertBefore(createIgnoredNoteEditor("DEPENDENCY", dependency), button);
        button.setAttribute("aria-label", `Stop ignoring ${dependency}`);
        button.onclick = () => updateIgnoredDependencies(
            ignoredDependencies.filter(key => key !== dependency), button);
        container.appendChild(row);
    });
    const row = document.createElement("div");
    row.className = "add-row";
    row.innerHTML = `<input class="key" placeholder="group:artifact"
            aria-label="Dependency to ignore" />
        <button type="button" class="icon-btn add-btn" aria-label="Ignore dependency">${ADD_SVG}</button>`;
    const input = row.querySelector(".key");
    const button = row.querySelector(".add-btn");
    const add = () => {
        const dependency = input.value.trim();
        if (!/^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$/.test(dependency) || dependency.length > 500) {
            showTargetSaveMessage("Enter a dependency using group:artifact", false);
            return;
        }
        if (ignoredDependencies.includes(dependency)) return;
        updateIgnoredDependencies([...ignoredDependencies, dependency], button);
    };
    button.onclick = add;
    input.onkeydown = event => { if (event.key === "Enter" && !button.disabled) add(); };
    container.appendChild(row);
    renderParentlessIgnoredNotes();
}
