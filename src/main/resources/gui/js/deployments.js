let deploymentData = null;
let deploymentsLoading = false;
let deploymentRefreshMarker = null;
let deploymentMarkerLoaded = false;
let deploymentMarkerSaving = false;
let deploymentLookupGeneration = 0;
let deploymentWorkflowAbort = null;
const deploymentHeadings = new Map();

function deploymentLoadingStatus(text) {
    const status = document.getElementById("deploymentsStatus");
    status.replaceChildren();
    status.textContent = text;
    const spinner = document.createElement("span");
    spinner.className = "spinner";
    spinner.setAttribute("aria-hidden", "true");
    status.appendChild(spinner);
}

async function loadDeploymentWorkflows(data, generation) {
    data.rebuildWorkflows ||= {};
    const pending = [...new Set(data.applications.map(app => app.repository).filter(Boolean))]
        .filter(repository => !Object.hasOwn(data.rebuildWorkflows, repository));
    if (!pending.length) return;
    const controller = new AbortController();
    deploymentWorkflowAbort = controller;
    let remaining = pending.length;
    deploymentLoadingStatus(`Loading rebuild workflows (${remaining} remaining)...`);
    const worker = async () => {
        while (pending.length && generation === deploymentLookupGeneration && deploymentData === data) {
            const repository = pending.shift();
            let workflow;
            try {
                const response = await fetch(`/internal/api/deployments/workflow/${repository.split("/").map(encodeURIComponent).join("/")}`, {
                    cache: "no-store", signal: controller.signal
                });
                workflow = await response.json();
                if (!response.ok) throw new Error(workflow.error || `HTTP ${response.status}`);
                if (!workflow.error && (typeof workflow.monorepo !== "boolean" ||
                    typeof workflow.exists !== "boolean" || typeof workflow.branch !== "string" || !workflow.branch)) {
                    throw new Error("Invalid workflow response");
                }
            } catch (failure) {
                if (controller.signal.aborted) return;
                console.error("Could not load rebuild workflow", failure);
                workflow = { error: `Could not load rebuild workflow: ${failure.message}. Refresh deployments to retry.` };
            }
            if (generation !== deploymentLookupGeneration || deploymentData !== data) return;
            data.rebuildWorkflows[repository] = workflow;
            const rendered = deploymentHeadings.get(repository);
            if (rendered) {
                rendered.heading.replaceChildren(rendered.link);
                appendDeploymentRebuildActions(rendered.heading, rendered.repository);
            }
            remaining--;
            if (remaining) deploymentLoadingStatus(`Loading rebuild workflows (${remaining} remaining)...`);
        }
    };
    await Promise.all(Array.from({ length: Math.min(4, pending.length) }, worker));
    if (generation === deploymentLookupGeneration && deploymentData === data) {
        const failed = Object.values(data.rebuildWorkflows).filter(workflow => workflow.error).length;
        const status = document.getElementById("deploymentsStatus");
        status.replaceChildren();
        status.textContent = failed ? `${failed} rebuild workflow lookup(s) failed. See repository messages.` : "";
    }
}

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
    const marker = document.getElementById("deploymentRefreshMarker");
    marker.replaceChildren();
    if (deploymentRefreshMarker) {
        [["Older", "older"], [formatDeploymentTimestamp(deploymentRefreshMarker), "unknown"], ["Newer", "newer"]]
            .forEach(([text, state]) => {
                const pill = document.createElement("span");
                pill.className = `target-version-pill deployment-freshness-${state}`;
                pill.textContent = text;
                marker.appendChild(pill);
            });
        marker.setAttribute("aria-label", `Deployment/activity cutoff: ${formatDeploymentTimestamp(deploymentRefreshMarker)}`);
    } else {
        marker.textContent = deploymentMarkerLoaded ? "No rebuild marker set." : "Rebuild marker not loaded.";
        marker.setAttribute("aria-label", marker.textContent);
    }
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

function deploymentRebuildTargets(repository, namespace = false) {
    const targets = repository.teams.flatMap(team =>
        team.applications.flatMap(app => app.environments.map(env => {
            if (!/^[a-z0-9][a-z0-9-]*$/.test(app.name) || !/^[a-z0-9][a-z0-9-]*$/.test(env.environment)) {
                throw new Error("Cannot create rebuild targets: unsupported application or environment name.");
            }
            if (namespace && !/^[a-z0-9][a-z0-9-]*$/.test(team.team)) {
                throw new Error("Cannot create rebuild targets: unsupported namespace name.");
            }
            return `${app.name}:${env.environment}${namespace ? `:${team.team}` : ""}`;
        })));
    return [...new Set(targets)].sort().join(",");
}

function deploymentShellQuote(value) {
    return `'${value.replaceAll("'", "'\\''")}'`;
}

const deploymentBannerTimers = new WeakMap();

function showDeploymentFeedback(banner, text, success = true, url = null) {
    clearTimeout(deploymentBannerTimers.get(banner));
    banner.replaceChildren();
    banner.hidden = false;
    banner.className = `deployment-copy-feedback save-banner ${success ? "save-banner-success" : "save-banner-error"}`;
    const message = document.createElement("span");
    message.textContent = text;
    if (url) {
        const link = document.createElement("a");
        link.textContent = " Open pull request";
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        message.appendChild(link);
    }
    const close = document.createElement("button");
    close.type = "button";
    close.className = "save-banner-close";
    close.textContent = "\u00d7";
    close.setAttribute("aria-label", "Dismiss message");
    close.onclick = () => {
        clearTimeout(deploymentBannerTimers.get(banner));
        banner.hidden = true;
    };
    banner.append(message, close);
    if (success) deploymentBannerTimers.set(banner, setTimeout(() => { banner.hidden = true; }, 6000));
}

function deploymentCopyButton(label, text, feedback, repository) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "pr-button deployment-copy";
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
        feedback.hidden = true;
        try {
            await navigator.clipboard.writeText(text);
            showDeploymentFeedback(feedback, `${text} copied into clipboard`);
        } catch (failure) {
            console.error("Could not copy rebuild text", failure);
            showDeploymentFeedback(feedback, `Could not copy to clipboard. Copy manually: ${text}`, false);
        }
    };
    return button;
}

function appendDeploymentRebuildActions(heading, repository) {
    const actions = document.createElement("span");
    actions.className = "deployment-rebuild-actions";
    const feedback = document.createElement("span");
    feedback.className = "deployment-copy-feedback";
    feedback.setAttribute("role", "status");
    feedback.setAttribute("aria-live", "polite");
    feedback.hidden = true;
    const workflow = deploymentData.rebuildWorkflows?.[repository.repository];
    if (!workflow) {
        actions.textContent = "Loading rebuild workflow...";
        const spinner = document.createElement("span");
        spinner.className = "spinner";
        spinner.setAttribute("aria-hidden", "true");
        actions.appendChild(spinner);
    } else if (workflow.error || typeof workflow.monorepo !== "boolean" || !workflow.branch) {
        showDeploymentFeedback(feedback, workflow?.error || "Workflow type unknown. Refresh deployments to check deploy.yml.", false);
    } else if (workflow.exists === false) {
        const generate = document.createElement("button");
        generate.type = "button";
        generate.className = "pr-button deployment-generate-button";
        const label = `Generate rebuild.yml${workflow.monorepo ? " monorepo" : ""}`;
        generate.textContent = label;
        generate.onclick = async () => {
            if (generate.disabled) return;
            generate.disabled = true;
            generate.textContent = "Creating...";
            const spinner = document.createElement("span");
            spinner.className = "spinner";
            spinner.setAttribute("aria-hidden", "true");
            generate.appendChild(spinner);
            generate.setAttribute("aria-busy", "true");
            try {
                const response = await fetch(`/internal/api/deployments/rebuild-pr/${repository.repository.split("/").map(encodeURIComponent).join("/")}`, {
                    method: "POST", cache: "no-store"
                });
                if (!response.ok) {
                    const message = await response.text();
                    let error = message;
                    if (response.headers.get("Content-Type")?.includes("application/json")) {
                        error = JSON.parse(message).error || message;
                    }
                    throw new Error(error);
                }
                const result = await response.json();
                if (typeof result.url !== "string" || !result.url.startsWith(`https://github.com/${repository.repository}/pull/`)) {
                    throw new Error("Invalid PR response. Check GitHub before retrying.");
                }
                showDeploymentFeedback(feedback, "Rebuild workflow PR created.", true, result.url);
                generate.replaceChildren();
                generate.textContent = "PR created";
                generate.disabled = false;
                generate.setAttribute("aria-label", "Open created pull request in a new tab");
                generate.onclick = () => window.open(result.url, "_blank", "noopener,noreferrer");
            } catch (failure) {
                console.error("Could not generate rebuild workflow", failure);
                showDeploymentFeedback(feedback, `Could not generate rebuild.yml: ${failure.message}`, false);
                generate.disabled = false;
                generate.replaceChildren();
                generate.textContent = label;
            } finally {
                generate.setAttribute("aria-busy", "false");
            }
        };
        actions.appendChild(generate);
    } else if (workflow.exists !== true) {
        showDeploymentFeedback(feedback, "rebuild.yml availability unknown. Refresh deployments.", false);
    } else {
        const link = document.createElement("a");
        link.className = "pr-button deployment-rebuild-link";
        link.textContent = "Rebuild";
        link.href = `https://github.com/${repository.repository.split("/").map(encodeURIComponent).join("/")}/actions/workflows/rebuild.yml`;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.setAttribute("aria-label", `Rebuild ${repository.repository} (opens in a new tab)`);
        const icon = document.createElement("img");
        icon.src = "/internal/gui/icons/external-link.svg";
        icon.alt = "";
        icon.width = 18;
        icon.height = 18;
        icon.setAttribute("aria-hidden", "true");
        link.appendChild(icon);
        actions.appendChild(link);
        try {
            const targets = workflow.monorepo ? deploymentRebuildTargets(repository, workflow.namespace === true) : null;
            const command = `gh workflow run rebuild.yml --repo ${deploymentShellQuote(repository.repository)} --ref ${deploymentShellQuote(workflow.branch)}` +
                (targets ? ` -f targets=${deploymentShellQuote(targets)}` : "");
            actions.appendChild(deploymentCopyButton("gh", command, feedback, repository.repository));
            if (targets) actions.appendChild(deploymentCopyButton("targets", targets, feedback, repository.repository));
        } catch (failure) {
            console.error("Could not create rebuild command", failure);
            showDeploymentFeedback(feedback, failure.message, false);
        }
    }
    heading.append(actions, feedback);
}

async function loadDeployments(refresh = false) {
    if (deploymentsLoading || deploymentMarkerSaving || (deploymentData && !refresh)) return;
    deploymentsLoading = true;
    const generation = ++deploymentLookupGeneration;
    deploymentWorkflowAbort?.abort();
    const button = document.getElementById("refreshDeployments");
    const status = document.getElementById("deploymentsStatus");
    button.disabled = true;
    renderDeploymentRefreshMarker();
    deploymentLoadingStatus("Loading running applications...");
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
        status.replaceChildren();
        status.textContent = data.applications.length ? "" : "No running applications found.";
        void loadDeploymentWorkflows(data, generation);
    } catch (error) {
        console.error("Could not load deployments", error);
        status.replaceChildren();
        status.textContent = `Could not load deployments: ${error.message}${deploymentData ? ". Showing previous results." : ""}`;
    } finally {
        deploymentsLoading = false;
        renderDeploymentRefreshMarker();
    }
}

function renderDeployments() {
    const container = document.getElementById("deploymentsContainer");
    container.replaceChildren();
    deploymentHeadings.clear();
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
            deploymentHeadings.set(repository.repository, { heading, link, repository });
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
