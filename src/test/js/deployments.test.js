const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

function element(tag = "div") {
    return {
        tag, children: [], attributes: {}, textContent: "",
        append(...children) { this.children.push(...children); },
        appendChild(child) { this.children.push(child); },
        replaceChildren(...children) { this.children = children; },
        setAttribute(name, value) { this.attributes[name] = value; }
    };
}

function app() {
    const elements = new Map();
    const get = id => {
        if (!elements.has(id)) elements.set(id, element());
        return elements.get(id);
    };
    const context = vm.createContext({
        document: { createElement: element, getElementById: get },
        selectedTeam: "team-a",
        console: { error() {} },
        fetch: () => { throw new Error("Unexpected request"); }
    });
    vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/deployments.js"), "utf8"), context);
    return { context, get };
}

const application = (team, name, environment, repository = "example/mono") => ({
    team, name, environment, repository, instances: 2, readyInstances: 1, images: []
});
const data = {
    teams: ["team-a", "team-b"],
    applications: [
        application("team-a", "orders", "prod-gcp"),
        application("team-a", "orders", "dev-gcp"),
        application("team-a", "worker", "prod-gcp"),
        application("team-b", "orders", "prod-gcp"),
        application("team-b", "unknown", "dev-gcp", null)
    ]
};

test("groups monorepo applications by repository then owning team and merges environments by app name", () => {
    const { context } = app();
    const grouped = JSON.parse(JSON.stringify(context.groupDeploymentApplications(data.applications)));
    const mono = grouped.find(repo => repo.repository === "example/mono");
    assert.equal(mono.teams.length, 2);
    assert.equal(mono.teams[0].applications.length, 2);
    assert.deepEqual(mono.teams[0].applications[0].environments.map(env => env.environment), ["dev-gcp", "prod-gcp"]);
    assert.equal(mono.teams[1].applications[0].environments.length, 1);
    assert.equal(grouped.find(repo => repo.repository === "").teams[0].applications[0].name, "unknown");
});

test("loads lazily once, renders team filters and concise merged rows, refreshes explicitly", async () => {
    const { context, get } = app();
    let calls = 0;
    context.fetch = async (url, options) => {
        calls++;
        assert.equal(url, "/internal/api/deployments");
        assert.equal(options.cache, "no-store");
        return { ok: true, json: async () => data };
    };
    await context.loadDeployments();
    await context.loadDeployments();
    assert.equal(calls, 1);
    const cards = get("deploymentsContainer").children;
    assert.equal(cards.length, 1);
    const rows = cards[0].children[1].children[1].children[0].children;
    assert.equal(rows.length, 2);
    assert.equal(rows[0].children[0].textContent, "orders");
    assert.deepEqual(rows[0].children[1].children[0].children.map(pill => pill.textContent), ["dev-gcp", "prod-gcp"]);
    get("deploymentTeamTabs").children[0].onclick();
    assert.equal(get("deploymentsContainer").children.length, 2);
    await context.loadDeployments(true);
    assert.equal(calls, 2);
});

test("failed refresh retains previous results and reports stale data explicitly", async () => {
    const { context, get } = app();
    context.fetch = async () => ({ ok: true, json: async () => data });
    await context.loadDeployments();
    const card = get("deploymentsContainer").children[0];
    context.fetch = async () => ({ ok: false, json: async () => ({ error: "Nais unavailable" }) });
    await context.loadDeployments(true);
    assert.equal(get("deploymentsContainer").children[0], card);
    assert.match(get("deploymentsStatus").textContent, /Nais unavailable.*Showing previous results/);
    assert.equal(get("refreshDeployments").disabled, false);
});

test("team selection filters repositories but keeps their applications from every discovered namespace", async () => {
    const { context, get } = app();
    context.fetch = async () => ({
        ok: true,
        json: async () => ({
            ...data,
            applications: [
                ...data.applications,
                application("team-b", "unrelated", "prod-gcp", "example/other")
            ]
        })
    });
    await context.loadDeployments();
    const cards = get("deploymentsContainer").children;
    assert.equal(cards.length, 1);
    assert.equal(cards[0].children[0].children[0].textContent, "example/mono");
    assert.deepEqual(cards[0].children.slice(1).map(section => section.children[0].textContent), ["team-a", "team-b"]);
    assert.equal(cards[0].children[2].children[1].children[0].children[0].children[0].textContent, "orders");
    get("deploymentTeamTabs").children[2].onclick();
    assert.equal(get("deploymentsContainer").children.length, 3);
    const mono = get("deploymentsContainer").children.find(card =>
        card.children[0].children[0]?.textContent === "example/mono");
    assert.deepEqual(mono.children.slice(1).map(section => section.children[0].textContent), ["team-a", "team-b"]);
});

test("empty team selection renders an explicit empty state", async () => {
    const { context, get } = app();
    context.fetch = async () => ({ ok: true, json: async () => ({ teams: ["team-a"], applications: [] }) });
    await context.loadDeployments();
    assert.match(get("deploymentsContainer").children[0].textContent, /No running applications/);
});

test("unknown rows explain the per-environment reason and show raw deployment metadata as text", async () => {
    const { context, get } = app();
    const unknown = application("team-a", "sf-arkiv", "dev-gcp", null);
    unknown.repositoryDiagnostic = {
        reason: "No deployment had SUCCESS as its latest status.",
        deploymentsChecked: 3,
        recentDeployments: [{ repository: "<raw-repo>", status: "INACTIVE", createdAt: "2026-11-09" }],
        latestActivity: { repository: "<raw-claim>", createdAt: "2026-11-09" }
    };
    context.fetch = async () => ({ ok: true, json: async () => ({ teams: ["team-a"], applications: [unknown] }) });
    await context.loadDeployments();
    const cell = get("deploymentsContainer").children[0].children[1].children[1].children[0].children[0].children[1];
    const details = cell.children[1];
    assert.equal(details.tag, "details");
    assert.equal(details.children[0].textContent, "Why unknown? dev-gcp");
    assert.match(details.children[1].textContent, /SUCCESS.*Deployments checked: 3/);
    assert.match(details.children[2].children[0].textContent, /INACTIVE.*<raw-repo>/);
    assert.match(details.children[2].children[1].textContent, /Latest application activity.*<raw-claim>/);
});

test("environment tooltips distinguish activity provenance from successful deployments", async () => {
    const { context, get } = app();
    const activityApp = {
        ...application("team-a", "orders", "dev-gcp"),
        repositorySource: "ACTIVITY_LOG",
        repositoryAssociationTime: "2026-11-09T13:00:00Z"
    };
    const deployedApp = {
        ...application("team-a", "orders", "prod-gcp"),
        repositorySource: "SUCCESSFUL_DEPLOYMENT",
        repositoryAssociationTime: "2026-11-09T12:00:00Z"
    };
    context.fetch = async () => ({
        ok: true, json: async () => ({ teams: ["team-a"], applications: [activityApp, deployedApp] })
    });
    await context.loadDeployments();
    const pills = get("deploymentsContainer").children[0].children[1].children[1].children[0].children[0].children[1].children[0].children;
    assert.match(pills[0].title, /application activity \(not rollout confirmation\) at 2026-11-09T13:00:00Z/);
    assert.doesNotMatch(pills[0].title, /successful deployment/i);
    assert.match(pills[1].title, /successful deployment at 2026-11-09T12:00:00Z/);
});
