let deploymentData = null;
let deploymentsLoading = false;
let deploymentRefreshMarker = null;
let deploymentMarkerLoaded = false;
let deploymentMarkerSaving = false;

function formatDeploymentTimestamp(timestamp) {
    const date = new Date(timestamp);
    if (!Number.isFinite(date.getTime())) return "Timestamp unknown";
    const pad = value => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

async function readDeploymentRefreshMarker(response) {
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
    if (!Object.hasOwn(data, "after") ||
        (data.after !== null && (typeof data.after !== "string" || !Number.isFinite(Date.parse(data.after))))) {
        throw new Error("Invalid deployment refresh marker response");
    }
    return data.after;
}

function renderDeploymentRefreshMarker() {
    document.getElementById("deploymentRefreshMarker").textContent = !deploymentMarkerLoaded
        ? "Rebuild marker not loaded."
        : deploymentRefreshMarker
            ? `Newer than: ${formatDeploymentTimestamp(deploymentRefreshMarker)}`
            : "No rebuild marker set.";
    document.getElementById("deploymentRefreshLegend").hidden = !deploymentRefreshMarker;
    document.getElementById("markDeploymentsForRefresh").disabled = deploymentsLoading || deploymentMarkerSaving;
    document.getElementById("clearDeploymentRefreshMarker").disabled =
        deploymentsLoading || deploymentMarkerSaving || !deploymentRefreshMarker;
    document.getElementById("refreshDeployments").disabled = deploymentsLoading || deploymentMarkerSaving;
}

async function saveDeploymentRefreshMarker(clear) {
    if (deploymentsLoading || deploymentMarkerSaving) return;
    if (deploymentRefreshMarker && !confirm(clear
        ? "Clear your rebuild marker for all teams?"
        : "Replace your rebuild marker with the current time for all teams?")) return;
    deploymentMarkerSaving = true;
    const error = document.getElementById("deploymentRefreshError");
    error.textContent = "";
    renderDeploymentRefreshMarker();
    try {
        const response = await fetch("/internal/api/deployments/refresh-marker", {
            method: clear ? "DELETE" : "POST", cache: "no-store"
        });
        const marker = await readDeploymentRefreshMarker(response);
        if (clear ? marker !== null : marker === null) throw new Error("Unexpected deployment refresh marker response");
        deploymentRefreshMarker = marker;
        deploymentMarkerLoaded = true;
        if (deploymentData) renderDeployments();
    } catch (failure) {
        console.error("Could not save rebuild marker", failure);
        error.textContent = `Could not ${clear ? "clear" : "save"} rebuild marker: ${failure.message}. Reload deployments to verify the saved marker.`;
    } finally {
        deploymentMarkerSaving = false;
        renderDeploymentRefreshMarker();
    }
}

function deploymentFreshness(application) {
    if (!deploymentRefreshMarker) return null;
    const timestamp = Date.parse(application.lastDeploymentActivityTime);
    if (!Number.isFinite(timestamp)) return {
        state: "unknown", explanation: "Deployment/activity timestamp unknown."
    };
    return timestamp > Date.parse(deploymentRefreshMarker)
        ? { state: "newer", explanation: "Deployment/activity after marker; image build age and rollout completion are not verified." }
        : { state: "older", explanation: "No deployment/activity after marker; needs redeployment." };
}

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

function deploymentRebuildTargets(repository) {
    const targets = repository.teams.flatMap(team =>
        team.applications.flatMap(app => app.environments.map(env => {
            if (!/^[a-z0-9][a-z0-9-]*$/.test(app.name) || !/^[a-z0-9][a-z0-9-]*$/.test(env.environment)) {
                throw new Error("Cannot create rebuild targets: unsupported application or environment name.");
            }
            return `${app.name}:${env.environment}`;
        })));
    return [...new Set(targets)].sort().join(",");
}

function deploymentShellQuote(value) {
    return `'${value.replaceAll("'", "'\\''")}'`;
}

function deploymentCopyButton(label, text, feedback, repository) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "deployment-copy";
    button.textContent = label;
    button.title = text;
    button.setAttribute("aria-label", `Copy ${label === "gh" ? "rebuild command" : "rebuild targets"} for ${repository}`);
    const icon = document.createElement("img");
    icon.src = "/internal/gui/icons/files.svg";
    icon.alt = "";
    icon.width = 18;
    icon.height = 18;
    icon.setAttribute("aria-hidden", "true");
    button.appendChild(icon);
    button.onclick = async () => {
        feedback.textContent = "";
        try {
            await navigator.clipboard.writeText(text);
            feedback.textContent = `${text} copied into clipboard`;
        } catch (failure) {
            console.error("Could not copy rebuild text", failure);
            feedback.textContent = `Could not copy to clipboard. Copy manually: ${text}`;
        }
    };
    return button;
}

function appendDeploymentRebuildActions(heading, repository) {
    const actions = document.createElement("span");
    actions.className = "deployment-rebuild-actions";
    const link = document.createElement("a");
    link.textContent = "Rebuild";
    link.href = `https://github.com/${repository.repository.split("/").map(encodeURIComponent).join("/")}/actions/workflows/rebuild.yml`;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    actions.appendChild(link);
    const feedback = document.createElement("span");
    feedback.className = "deployment-copy-feedback";
    feedback.setAttribute("role", "status");
    feedback.setAttribute("aria-live", "polite");
    const workflow = deploymentData.rebuildWorkflows?.[repository.repository];
    if (!workflow || workflow.error || typeof workflow.monorepo !== "boolean" || !workflow.branch) {
        feedback.textContent = workflow?.error || "Workflow type unknown. Refresh deployments to check deploy.yml.";
    } else {
        try {
            const targets = workflow.monorepo ? deploymentRebuildTargets(repository) : null;
            const command = `gh workflow run rebuild.yml --repo ${deploymentShellQuote(repository.repository)} --ref ${deploymentShellQuote(workflow.branch)}` +
                (targets ? ` -f targets=${deploymentShellQuote(targets)}` : "");
            actions.appendChild(deploymentCopyButton("gh", command, feedback, repository.repository));
            if (targets) actions.appendChild(deploymentCopyButton("targets", targets, feedback, repository.repository));
        } catch (failure) {
            console.error("Could not create rebuild command", failure);
            feedback.textContent = failure.message;
        }
    }
    heading.append(actions, feedback);
}

async function loadDeployments(refresh = false) {
    if (deploymentsLoading || deploymentMarkerSaving || (deploymentData && !refresh)) return;
    deploymentsLoading = true;
    const button = document.getElementById("refreshDeployments");
    const status = document.getElementById("deploymentsStatus");
    button.disabled = true;
    renderDeploymentRefreshMarker();
    status.textContent = "Loading running applications...";
    try {
        const [response, marker] = await Promise.all([
            fetch("/internal/api/deployments", { cache: "no-store" }),
            fetch("/internal/api/deployments/refresh-marker", { cache: "no-store" }).then(readDeploymentRefreshMarker)
        ]);
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
        if (!Array.isArray(data.teams) || !Array.isArray(data.applications)) {
            throw new Error("Invalid deployments response");
        }
        deploymentData = data;
        deploymentRefreshMarker = marker;
        deploymentMarkerLoaded = true;
        document.getElementById("deploymentRefreshError").textContent = "";
        renderDeployments();
        status.textContent = data.applications.length ? "" : "No running applications found.";
    } catch (error) {
        console.error("Could not load deployments", error);
        status.textContent = `Could not load deployments: ${error.message}${deploymentData ? ". Showing previous results." : ""}`;
    } finally {
        deploymentsLoading = false;
        renderDeploymentRefreshMarker();
    }
}

function renderDeployments() {
    const container = document.getElementById("deploymentsContainer");
    container.replaceChildren();
    const applications = deploymentData.applications;
    if (!applications.length) {
        const empty = document.createElement("p");
        empty.textContent = "No running applications found.";
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
        heading.title = "Repository from successful deployment history or application activity; not verified as the image build source.";
        if (repository.repository) appendDeploymentRebuildActions(heading, repository);
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
                    const freshness = deploymentFreshness(environment);
                    if (freshness) {
                        pill.className += ` deployment-freshness-${freshness.state}`;
                        pill.textContent += ` (${freshness.state})`;
                    }
                    pill.title = `${environment.readyInstances}/${environment.instances} running instances ready` +
                        (freshness ? `\n${freshness.explanation}` : "") +
                        (environment.lastDeploymentActivityTime ? `\nLast deployment/activity: ${formatDeploymentTimestamp(environment.lastDeploymentActivityTime)}` : "") +
                        (environment.repositorySource ? `\nRepository association: ${environment.repositorySource === "ACTIVITY_LOG" ? "application activity (not rollout confirmation)" : "successful deployment"}${environment.repositoryAssociationTime ? ` at ${formatDeploymentTimestamp(environment.repositoryAssociationTime)}` : ""}` : "") +
                        environment.images.map(image => `\n${image.name}${image.tag ? `:${image.tag}` : ""}${image.digest ? `@${image.digest}` : ""}`).join("");
                    pills.appendChild(pill);
                });
                environments.appendChild(pills);
                application.environments.filter(env => env.repositoryDiagnostic).forEach(environment => {
                    const diagnostic = environment.repositoryDiagnostic;
                    const details = document.createElement("details");
                    const summary = document.createElement("summary");
                    summary.textContent = `Why unknown? ${environment.environment}`;
                    details.appendChild(summary);
                    const reason = document.createElement("p");
                    reason.textContent = `${diagnostic.reason} Deployments checked: ${diagnostic.deploymentsChecked}.`;
                    details.appendChild(reason);
                    const list = document.createElement("ul");
                    diagnostic.recentDeployments.forEach(deployment => {
                        const item = document.createElement("li");
                        item.textContent = `${deployment.createdAt ? formatDeploymentTimestamp(deployment.createdAt) : "Date missing"} | Latest status: ${deployment.status || "Missing"} | Repository: ${deployment.repository || "Missing"}`;
                        list.appendChild(item);
                    });
                    if (diagnostic.latestActivity) {
                        const item = document.createElement("li");
                        item.textContent = `${diagnostic.latestActivity.createdAt ? formatDeploymentTimestamp(diagnostic.latestActivity.createdAt) : "Date missing"} | Latest application activity | Repository claim: ${diagnostic.latestActivity.repository || "Missing"}`;
                        list.appendChild(item);
                    }
                    details.appendChild(list);
                    environments.appendChild(details);
                });
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
