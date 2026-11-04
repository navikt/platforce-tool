function targetUpgradeButtonHtml() {
    return `<button type="button" class="icon-btn target-upgrade-btn" aria-label="Show available upgrades"
        title="Show available upgrades" aria-expanded="false">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12.5303 4.46967C12.3897 4.32902 12.1989 4.25 12 4.25C11.8011 4.25 11.6103 4.32902 11.4697 4.46967L6.96967 8.96971C6.67678 9.2626 6.67678 9.73747 6.96967 10.0304C7.26257 10.3233 7.73744 10.3233 8.03033 10.0304L11.25 6.81067V19C11.25 19.4142 11.5858 19.75 12 19.75C12.4142 19.75 12.75 19.4142 12.75 19V6.81067L15.9697 10.0304C16.2626 10.3233 16.7374 10.3233 17.0303 10.0304C17.3232 9.73747 17.3232 9.2626 17.0303 8.9697L12.5303 4.46967Z" fill="currentColor"/>
        </svg>
    </button>`;
}

async function fetchAvailableUpgrades(key, currentVersion) {
    const response = await fetch(
        `/internal/api/target-versions/available?key=${encodeURIComponent(key)}&currentVersion=${encodeURIComponent(currentVersion)}`,
        { cache: "no-store" }
    );
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `Version lookup failed: HTTP ${response.status}`);
    return result;
}

function renderAvailableUpgrades(panel, result, type, selectVersion) {
    panel.replaceChildren();
    const heading = document.createElement("strong");
    heading.textContent = `Available upgrades for ${result.key}`;
    const list = document.createElement("ul");
    list.className = "target-upgrade-list";
    const versions = result.versions.filter(candidate => !candidate.prerelease);
    versions.forEach(candidate => {
        const item = document.createElement("li");
        const button = document.createElement("button");
        button.type = "button";
        button.className = "target-upgrade-version";
        button.textContent = candidate.version;
        button.setAttribute("aria-label", `Draft ${result.key} version ${candidate.version}`);
        button.onclick = () => selectVersion(type.toUpperCase(), result.key, candidate.version);
        item.appendChild(button);
        list.appendChild(item);
    });
    panel.append(heading, list);
    if (!versions.length) {
        const empty = document.createElement("p");
        empty.setAttribute("role", "status");
        empty.textContent = "No newer stable versions found.";
        panel.appendChild(empty);
    }
}

function bindTargetUpgrades(row, type) {
    const button = row.querySelector(".target-upgrade-btn");
    const keyInput = row.querySelector(".key");
    const versionInput = row.querySelector(".version");
    const panel = document.createElement("div");
    panel.className = "target-upgrade-details finding-details hidden";
    row.appendChild(panel);
    let requestId = 0;
    function close() {
        requestId++;
        button.setAttribute("aria-expanded", "false");
        panel.classList.add("hidden");
    }
    keyInput.addEventListener("input", close);
    versionInput.addEventListener("input", close);
    button.onclick = async () => {
        if (button.getAttribute("aria-expanded") === "true") {
            close();
            return;
        }
        const id = ++requestId;
        const key = keyInput.value.trim();
        const currentVersion = versionInput.value.trim();
        button.setAttribute("aria-expanded", "true");
        panel.classList.remove("hidden");
        panel.innerHTML = '<p role="status">Loading available versions...</p>';
        try {
            const result = await fetchAvailableUpgrades(key, currentVersion);
            if (id !== requestId || !row.isConnected) return;
            renderAvailableUpgrades(panel, result, type, (kind, coordinate, version) => {
                if (keyInput.value.trim() !== key || versionInput.value.trim() !== currentVersion) return;
                addDraftTarget(kind, coordinate, version);
                const containerId = type === "platform_bom" ? "platformBomsTable" : "depsTable";
                Array.from(document.querySelectorAll(`#${containerId} .target-row`))
                    .find(target => target.querySelector(".key")?.value === coordinate)
                    ?.querySelector(".version")?.focus();
            });
        } catch (error) {
            console.error("Available version lookup failed", error);
            if (id !== requestId || !row.isConnected) return;
            const message = document.createElement("p");
            message.setAttribute("role", "alert");
            message.textContent = `Could not load versions: ${error.message}. Close and reopen to retry.`;
            panel.replaceChildren(message);
        }
    };
}
