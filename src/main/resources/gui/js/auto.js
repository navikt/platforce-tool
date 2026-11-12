let autoRepositories = new Set();
let autoRepositoriesLoaded = false;
let autoReadGeneration = 0;
const autoRepositoriesSaving = new Set();

function showAutoError(message) {
    document.getElementById("autoError").textContent = message;
    document.getElementById("scannerAutoError").textContent = message;
}

function createAutoToggle(repository) {
    const name = repository.toLowerCase();
    const button = document.createElement("button");
    button.type = "button";
    button.className = "auto-toggle";
    button.dataset.repository = name;
    button.setAttribute("role", "switch");
    button.setAttribute("aria-label", `Auto dependency management for ${repository}`);
    button.title = "Global opt-in shared by all users; automation is not active yet";
    const track = document.createElement("span");
    track.className = "auto-toggle-track";
    track.setAttribute("aria-hidden", "true");
    const label = document.createElement("span");
    label.textContent = "auto";
    button.append(track, label);
    button.addEventListener("click", event => {
        event.stopPropagation();
        return saveAutoRepository(name);
    });
    updateAutoToggle(button);
    return button;
}

function updateAutoToggle(button) {
    const repository = button.dataset.repository;
    button.setAttribute("aria-checked", String(autoRepositories.has(repository)));
    button.disabled = !autoRepositoriesLoaded || autoRepositoriesSaving.has(repository);
}

function updateAutoToggles() {
    document.querySelectorAll(".auto-toggle").forEach(updateAutoToggle);
}

function renderAutoRepositories() {
    const list = document.getElementById("autoRepositories");
    list.replaceChildren();
    document.getElementById("autoStatus").textContent = !autoRepositoriesLoaded
        ? "Auto settings not loaded."
        : autoRepositories.size ? `${autoRepositories.size} repositories opted in.` : "No repositories opted in.";
    if (!autoRepositoriesLoaded) return;
    [...autoRepositories].sort().forEach(repository => {
        const row = document.createElement("div");
        row.className = "auto-repository-row";
        const link = document.createElement("a");
        link.className = "repo-name-link";
        link.href = `https://github.com/${repository}`;
        link.textContent = repository;
        row.append(link, createAutoToggle(repository));
        list.appendChild(row);
    });
}

async function loadAutoRepositories() {
    const generation = ++autoReadGeneration;
    document.getElementById("autoStatus").textContent = "Loading Auto settings...";
    try {
        const data = await fetchJson("/internal/api/auto-repositories");
        if (!Array.isArray(data.repositories) || data.repositories.some(repository => typeof repository !== "string")) {
            throw new Error("Invalid Auto settings response");
        }
        if (generation !== autoReadGeneration) return;
        autoRepositories = new Set(data.repositories.map(repository => repository.toLowerCase()));
        autoRepositoriesLoaded = true;
        showAutoError("");
    } catch (failure) {
        if (generation !== autoReadGeneration) return;
        console.error("Could not load Auto settings", failure);
        autoRepositoriesLoaded = false;
        showAutoError(`Could not load Auto settings: ${failure.message}. Refresh the Auto tab to retry.`);
    }
    if (generation !== autoReadGeneration) return;
    renderAutoRepositories();
    updateAutoToggles();
}

async function saveAutoRepository(repository) {
    if (!autoRepositoriesLoaded || autoRepositoriesSaving.has(repository)) return;
    const enabled = !autoRepositories.has(repository);
    autoRepositoriesSaving.add(repository);
    autoReadGeneration++;
    updateAutoToggles();
    showAutoError("");
    try {
        const response = await fetch("/internal/api/auto-repositories", {
            method: "POST",
            cache: "no-store",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ repository, enabled })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
        if (data.repository !== repository || data.enabled !== enabled) throw new Error("Invalid Auto setting response");
        autoReadGeneration++;
        if (enabled) autoRepositories.add(repository);
        else autoRepositories.delete(repository);
    } catch (failure) {
        console.error("Could not save Auto setting", failure);
        showAutoError(`Could not save Auto for ${repository}: ${failure.message}. Refresh the Auto tab to verify the saved setting.`);
    } finally {
        autoRepositoriesSaving.delete(repository);
        renderAutoRepositories();
        updateAutoToggles();
    }
}
