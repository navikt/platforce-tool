document.addEventListener("click", e => {
    const pill = e.target.closest(".target-draft-pill");

    if (!pill) {
        return;
    }

    const kind = pill.dataset.kind;
    const key = pill.dataset.key;
    const version = pill.dataset.version;

    addDraftTarget(kind, key, version);

    pill.textContent = "DRAFTED";
    pill.classList.remove("status-unknown", "status-ahead");
    pill.classList.add("status-drafted");
    pill.classList.add("disabled");
});

document.getElementById("saveTargets")
    .addEventListener("click", saveTargets);

document.getElementById("closeTargetSaveMessage")
    .addEventListener("click", closeTargetSaveMessage);

document.getElementById("refreshBtn")
    .addEventListener("click", refresh);

document
    .querySelectorAll(".tab")
    .forEach(tab => {

        tab.addEventListener("click", () => {

            document
                .querySelectorAll(".tab")
                .forEach(t => t.classList.remove("active"));

            tab.classList.add("active");

            const selected =
                tab.dataset.tab;

            document
                .getElementById("scannerTab")
                .style.display =
                selected === "scanner"
                    ? "block"
                    : "none";

            document
                .getElementById("targetsTab")
                .style.display =
                selected === "targets"
                    ? "block"
                    : "none";

            document
                .getElementById("ignoredTab")
                .style.display =
                selected === "ignored"
                    ? "block"
                    : "none";
        });
    });

document
    .getElementById("scanTargets")
    .addEventListener("click", scanTargetSecurity);

document.getElementById("scanBoms")
    .addEventListener("click", () => scanTargetSecurity(true));

document
    .getElementById("saveIgnored")
    .addEventListener("click", async () => {

        await saveIgnoredRepositories();

        await loadData(); // ensures scanner refresh + backend truth
    });

document.addEventListener("click", (e) => {
    const toggle = e.target.closest(".untracked-toggle");
    if (!toggle) return;

    e.stopPropagation();

    const section = toggle.closest(".untracked-section");
    if (!section) return;

    section.classList.toggle("open");
});

document.addEventListener("click", event => {
    const status = event.target.closest("[data-toggle-details='true']");

    if (!status) return;

    const container = status.closest(".finding-container");
    const details = container?.querySelector(".finding-details");

    if (!details) return;

    details.classList.toggle("hidden");
});

async function init() {
    selectedTeam = await loadSelectedTeam();

    await initTargets();
    await loadData();

    await loadCachedTargetSecurity();
    renderTargetTables();
    if (lastLoadedData?.repos) render(lastLoadedData);

    startProgressPolling();
}

init();
