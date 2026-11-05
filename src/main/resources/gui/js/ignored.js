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

            row.innerHTML = `
                <input class="key" value="${repo}"/>

                <button class="icon-btn remove">
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
}
