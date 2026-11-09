let deploymentData = null;
let deploymentTeam = null;
let deploymentsLoading = false;

function groupDeploymentApplications(applications) {
    const repositories = new Map();
    applications.forEach(application => {
        const repository = application.repository || "";
        if (!repositories.has(repository)) repositories.set(repository, new Map());
        const teams = repositories.get(repository);
        if (!teams.has(application.team)) teams.set(application.team, new Map());
        const apps = teams.get(application.team);
        if (!apps.has(application.name)) apps.set(application.name, []);
        apps.get(application.name).push(application);
    });
    const sorted = map => [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
    return sorted(repositories).map(([repository, teams]) => ({
        repository,
        teams: sorted(teams).map(([team, apps]) => ({
            team,
            applications: sorted(apps).map(([name, environments]) => ({
                name,
                environments: [...environments].sort((a, b) => a.environment.localeCompare(b.environment))
            }))
        }))
    }));
}

async function loadDeployments(refresh = false) {
    if (deploymentsLoading || (deploymentData && !refresh)) return;
    deploymentsLoading = true;
    const button = document.getElementById("refreshDeployments");
    const status = document.getElementById("deploymentsStatus");
    button.disabled = true;
    status.textContent = "Loading running applications...";
    try {
        const response = await fetch("/internal/api/deployments", { cache: "no-store" });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
        if (!Array.isArray(data.teams) || !Array.isArray(data.applications)) {
            throw new Error("Invalid deployments response");
        }
        deploymentData = data;
        if (deploymentTeam === null) deploymentTeam = data.teams.includes(selectedTeam) ? selectedTeam : "";
        renderDeployments();
        status.textContent = data.applications.length ? "" : "No running applications found.";
    } catch (error) {
        console.error("Could not load deployments", error);
        status.textContent = `Could not load deployments: ${error.message}${deploymentData ? ". Showing previous results." : ""}`;
    } finally {
        deploymentsLoading = false;
        button.disabled = false;
    }
}

function renderDeployments() {
    const tabs = document.getElementById("deploymentTeamTabs");
    tabs.replaceChildren();
    ["", ...deploymentData.teams].forEach(team => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = `team-pill${deploymentTeam === team ? " active" : ""}`;
        button.textContent = team || "All teams";
        button.setAttribute("aria-pressed", String(deploymentTeam === team));
        button.onclick = () => {
            deploymentTeam = team;
            renderDeployments();
        };
        tabs.appendChild(button);
    });
    const container = document.getElementById("deploymentsContainer");
    container.replaceChildren();
    const applications = deploymentData.applications.filter(app => !deploymentTeam || app.team === deploymentTeam);
    if (!applications.length) {
        const empty = document.createElement("p");
        empty.textContent = "No running applications for this selection.";
        container.appendChild(empty);
        return;
    }
    groupDeploymentApplications(applications).forEach(repository => {
        const card = document.createElement("section");
        card.className = "repo deployment-repository";
        const heading = document.createElement("h2");
        if (repository.repository) {
            const link = document.createElement("a");
            link.className = "repo-name-link";
            link.textContent = repository.repository;
            link.href = `https://github.com/${repository.repository.split("/").map(encodeURIComponent).join("/")}`;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            heading.appendChild(link);
        } else {
            heading.textContent = "Repository unknown";
        }
        heading.title = "Repository from the latest successful deployment; not verified as the image build source.";
        card.appendChild(heading);
        repository.teams.forEach(team => {
            const section = document.createElement("section");
            section.className = "deployment-team";
            const label = document.createElement("h3");
            label.textContent = team.team;
            section.appendChild(label);
            const table = document.createElement("table");
            table.className = "deployment-apps";
            table.setAttribute("aria-label", `${team.team} running applications`);
            const body = document.createElement("tbody");
            team.applications.forEach(application => {
                const row = document.createElement("tr");
                const name = document.createElement("th");
                name.scope = "row";
                name.textContent = application.name;
                const environments = document.createElement("td");
                const pills = document.createElement("div");
                pills.className = "deployment-environments";
                application.environments.forEach(environment => {
                    const pill = document.createElement("span");
                    pill.className = "target-version-pill deployment-environment";
                    pill.textContent = environment.environment;
                    pill.title = `${environment.readyInstances}/${environment.instances} running instances ready` +
                        (environment.deploymentTime ? `\nLatest successful deployment: ${environment.deploymentTime}` : "") +
                        environment.images.map(image => `\n${image.name}${image.tag ? `:${image.tag}` : ""}${image.digest ? `@${image.digest}` : ""}`).join("");
                    pills.appendChild(pill);
                });
                environments.appendChild(pills);
                row.append(name, environments);
                body.appendChild(row);
            });
            table.appendChild(body);
            section.appendChild(table);
            card.appendChild(section);
        });
        container.appendChild(card);
    });
}
