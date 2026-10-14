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

            row.innerHTML = `
                <input class="key" value="${repo}" aria-label="Ignored repository ${escapeHtml(repo)}"/>

                <button class="icon-btn remove" aria-label="Stop ignoring ${escapeHtml(repo)}">
                    ${TRASH_SVG}
                </button>
            `;

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
        aria-label="Repository to ignore"
    />

    <button class="icon-btn add-btn" aria-label="Ignore repository">
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
}
