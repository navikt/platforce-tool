async function fetchJson(url) {
    const response = await fetch(url, { cache: "no-store" });
    if (!response.ok) {
        throw new Error(`Request failed: ${url} (HTTP ${response.status})`);
    }
    return await response.json();
}

async function fetchRepoView() {
    return await fetchJson("/internal/repos/view");
}

async function fetchProgress() {
    return await fetchJson("/internal/api/dependency-scan/progress");
}

async function fetchData() {
    return await fetchJson("/internal/api/dependency-scan");
}

async function fetchIgnoredRepositories() {
    return await fetchJson("/internal/api/ignored-repositories");
}

async function fetchTargetVersions() {
    return await fetchJson("/internal/api/target-versions");
}

async function fetchNotes() {
    return await fetchJson("/internal/api/repository-notes");
}

async function saveNote(repository, note) {
    await fetch("/internal/api/repository-notes", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            repository,
            note
        })
    });
}

async function saveIgnoredRepositories() {

    console.log("Ignored repositories list persisted to db")

    await fetch(
        "/internal/api/ignored-repositories/update",
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                repositories:
                ignoredRepositories
            })
        }
    );
}

async function loadSelectedTeam() {
    const response = await fetch("/internal/selectedTeam");

    if (!response.ok) {
        console.warn("Failed to load selected team");
        return null;
    }

    const data = await response.json();

    return data.selectedTeam;
}

async function saveSelectedTeam(team) {
    const response = await fetch("/internal/selectedTeam", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            selectedTeam: team,
        }),
    });

    if (!response.ok) {
        console.warn("Failed to save selected team");
    }
}
