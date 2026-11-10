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
        confirm: () => true,
        navigator: { clipboard: { writeText: async () => {} } },
        setTimeout: () => 1,
        clearTimeout: () => {},
        window: { open: () => null },
        fetch: () => { throw new Error("Unexpected request"); }
    });
    vm.runInContext(readFileSync(resolve(__dirname, "../../main/resources/gui/js/deployments.js"), "utf8"), context);
    return { context, get };
}

function withMarker(fetchDeployments, after = null) {
    return (url, options) => url === "/internal/api/deployments/refresh-marker"
        ? Promise.resolve({ ok: true, json: async () => ({ after }) })
        : fetchDeployments(url, options);
}

function firstEnvironmentPill(get) {
    const card = get("deploymentsContainer").children.find(card => card.children[0].children[0]?.textContent === "example/mono");
    return card.children[1].children[1].children[0].children[0].children[1].children[0].children[0];
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

test("deployment timestamps use local calendar dates and zero-padded 24-hour time with seconds", () => {
    const { context } = app();
    const localDate = new Date(2026, 0, 2, 15, 4, 9);
    assert.equal(context.formatDeploymentTimestamp(localDate.toISOString()), "2026-01-02 15:04:09");
    const midnight = new Date(2026, 10, 10, 0, 0, 0);
    assert.equal(context.formatDeploymentTimestamp(midnight.toISOString()), "2026-11-10 00:00:00");
    assert.equal(context.formatDeploymentTimestamp("invalid"), "Timestamp unknown");
});

test("deployment view has no team selector or explanatory help", () => {
    const html = readFileSync(resolve(__dirname, "../../main/resources/gui/index.html"), "utf8");
    assert.doesNotMatch(html, /id="deploymentTeamTabs"/);
    assert.doesNotMatch(html, /deployment-refresh-help|deploymentRefreshLegend/);
});

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

test("loads lazily once, renders all teams and concise merged rows, refreshes explicitly", async () => {
    const { context, get } = app();
    let calls = 0;
    context.fetch = withMarker(async (url, options) => {
        calls++;
        assert.equal(url, "/internal/api/deployments");
        assert.equal(options.cache, "no-store");
        return { ok: true, json: async () => data };
    });
    await context.loadDeployments();
    await context.loadDeployments();
    assert.equal(calls, 1);
    const cards = get("deploymentsContainer").children;
    assert.equal(cards.length, 2);
    const mono = cards.find(card => card.children[0].children[0]?.textContent === "example/mono");
    const rows = mono.children[1].children[1].children[0].children;
    assert.equal(rows.length, 2);
    assert.equal(rows[0].children[0].textContent, "orders");
    assert.deepEqual(rows[0].children[1].children[0].children.map(pill => pill.textContent), ["dev-gcp", "prod-gcp"]);
    await context.loadDeployments(true);
    assert.equal(calls, 2);
});

test("failed refresh retains previous results and reports stale data explicitly", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({ ok: true, json: async () => data }));
    await context.loadDeployments();
    const card = get("deploymentsContainer").children[0];
    context.fetch = withMarker(async () => ({ ok: false, json: async () => ({ error: "Nais unavailable" }) }));
    await context.loadDeployments(true);
    assert.equal(get("deploymentsContainer").children[0], card);
    assert.match(get("deploymentsStatus").textContent, /Nais unavailable.*Showing previous results/);
    assert.equal(get("refreshDeployments").disabled, false);
});

test("scanner team selection does not hide deployments or unknown repositories from other teams", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({
        ok: true,
        json: async () => ({
            ...data,
            applications: [
                ...data.applications,
                application("team-b", "unrelated", "prod-gcp", "example/other")
            ]
        })
    }));
    await context.loadDeployments();
    const cards = get("deploymentsContainer").children;
    assert.equal(cards.length, 3);
    assert.equal(cards[0].children[0].textContent, "Repository unknown");
    assert.equal(cards[0].children[1].children[0].textContent, "team-b");
    const mono = get("deploymentsContainer").children.find(card =>
        card.children[0].children[0]?.textContent === "example/mono");
    assert.deepEqual(mono.children.slice(1).map(section => section.children[0].textContent), ["team-a", "team-b"]);
});

test("empty inventory renders an explicit empty state", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({ ok: true, json: async () => ({ teams: ["team-a"], applications: [] }) }));
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
    context.fetch = withMarker(async () => ({ ok: true, json: async () => ({ teams: ["team-a"], applications: [unknown] }) }));
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
    context.fetch = withMarker(async () => ({
        ok: true, json: async () => ({ teams: ["team-a"], applications: [activityApp, deployedApp] })
    }));
    await context.loadDeployments();
    const pills = get("deploymentsContainer").children[0].children[1].children[1].children[0].children[0].children[1].children[0].children;
    assert.ok(pills[0].title.includes(`application activity (not rollout confirmation) at ${context.formatDeploymentTimestamp(activityApp.repositoryAssociationTime)}`));
    assert.doesNotMatch(pills[0].title, /successful deployment/i);
    assert.ok(pills[1].title.includes(`successful deployment at ${context.formatDeploymentTimestamp(deployedApp.repositoryAssociationTime)}`));
});

test("saved marker classifies timestamps strictly and survives refresh and a new GUI session", async () => {
    const marker = "2026-11-09T13:00:00Z";
    const cases = [
        ["2026-11-09T12:59:59Z", "older"],
        [marker, "older"],
        ["2026-11-09T13:00:00.001Z", "newer"],
        ["2026-11-09T15:00:01+02:00", "newer"],
        [null, "unknown"],
        ["not-a-timestamp", "unknown"]
    ];
    for (const [timestamp, state] of cases) {
        const instance = app();
        const payload = {
            teams: ["team-a"],
            applications: [{ ...application("team-a", "orders", "dev-gcp"), lastDeploymentActivityTime: timestamp }]
        };
        instance.context.fetch = withMarker(async () => ({ ok: true, json: async () => payload }), marker);
        await instance.context.loadDeployments();
        const pill = firstEnvironmentPill(instance.get);
        assert.match(pill.className, new RegExp(`deployment-freshness-${state}`));
        assert.equal(pill.textContent, `dev-gcp (${state})`);
        assert.deepEqual(instance.get("deploymentRefreshMarker").children.map(pill => pill.textContent),
            ["Older", instance.context.formatDeploymentTimestamp(marker), "Newer"]);
        await instance.context.loadDeployments(true);
        assert.equal(firstEnvironmentPill(instance.get).className, pill.className);
    }
});

test("mark, advance and clear update the server-backed marker across teams without refreshing deployments", async () => {
    const { context, get } = app();
    let saved = null;
    let now = "2026-11-09T13:00:00Z";
    let deploymentRequests = 0;
    const calls = [];
    context.fetch = async (url, options) => {
        if (url === "/internal/api/deployments") {
            deploymentRequests++;
            return { ok: true, json: async () => data };
        }
        assert.equal(url, "/internal/api/deployments/refresh-marker");
        assert.equal(options.cache, "no-store");
        if (options.method === "POST") saved = now;
        if (options.method === "DELETE") saved = null;
        calls.push(options.method || "GET");
        return { ok: true, json: async () => ({ after: saved }) };
    };
    await context.loadDeployments();
    assert.match(get("deploymentRefreshMarker").textContent, /No rebuild marker/);
    assert.doesNotMatch(firstEnvironmentPill(get).className, /freshness/);
    await context.saveDeploymentRefreshMarker(false);
    assert.equal(saved, now);
    assert.equal(get("clearDeploymentRefreshMarker").disabled, false);
    assert.match(firstEnvironmentPill(get).className, /freshness-unknown/);
    now = "2026-11-10T13:00:00Z";
    await context.saveDeploymentRefreshMarker(false);
    assert.equal(saved, now);
    context.confirm = () => false;
    await context.saveDeploymentRefreshMarker(true);
    assert.equal(saved, now);
    context.confirm = () => true;
    await context.saveDeploymentRefreshMarker(true);
    assert.equal(saved, null);
    assert.equal(get("clearDeploymentRefreshMarker").disabled, true);
    assert.doesNotMatch(firstEnvironmentPill(get).className, /freshness/);
    assert.equal(deploymentRequests, 1);
    assert.deepEqual(calls, ["GET", "POST", "POST", "DELETE"]);
});

test("failed marker writes preserve the previous marker and report uncertainty explicitly", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({ ok: true, json: async () => data }), "2026-11-09T13:00:00Z");
    await context.loadDeployments();
    const label = get("deploymentRefreshMarker").textContent;
    context.fetch = async () => ({ ok: false, json: async () => ({ error: "Database unavailable" }) });
    await context.saveDeploymentRefreshMarker(false);
    assert.equal(get("deploymentRefreshMarker").textContent, label);
    assert.match(get("deploymentRefreshError").textContent, /Database unavailable.*Reload deployments/);
    assert.equal(get("markDeploymentsForRefresh").disabled, false);
    assert.equal(get("refreshDeployments").disabled, false);
    await context.saveDeploymentRefreshMarker(true);
    assert.equal(get("deploymentRefreshMarker").textContent, label);
    assert.match(get("deploymentRefreshError").textContent, /Could not clear/);
});

test("invalid or unavailable persisted markers fail visibly instead of silently removing the marker", async () => {
    for (const payload of [{}, { after: "invalid" }, { after: 123 }]) {
        const { context, get } = app();
        context.fetch = async url => ({
            ok: true, json: async () => url.endsWith("refresh-marker") ? payload : data
        });
        await context.loadDeployments();
        assert.match(get("deploymentsStatus").textContent, /Invalid deployment refresh marker/);
        assert.match(get("deploymentRefreshMarker").textContent, /not loaded/);
        assert.equal(get("deploymentsContainer").children.length, 0);
    }
});

test("marker changes cannot race with a deployment refresh or with another marker write", async () => {
    const { context, get } = app();
    let finish;
    context.fetch = withMarker(() => new Promise(resolve => { finish = resolve; }));
    const loading = context.loadDeployments();
    await context.saveDeploymentRefreshMarker(false);
    assert.equal(get("markDeploymentsForRefresh").disabled, true);
    finish({ ok: true, json: async () => data });
    await loading;
    let writes = 0;
    context.fetch = () => {
        writes++;
        return new Promise(resolve => { finish = resolve; });
    };
    const saving = context.saveDeploymentRefreshMarker(false);
    await context.saveDeploymentRefreshMarker(false);
    await context.loadDeployments(true);
    assert.equal(writes, 1);
    assert.equal(get("refreshDeployments").disabled, true);
    finish({ ok: true, json: async () => ({ after: "2026-11-09T13:00:00Z" }) });
    await saving;
    assert.equal(get("refreshDeployments").disabled, false);
});

test("normal repository has a rebuild link and copies a shell-safe command with Aksel copy icon and feedback", async () => {
    const { context, get } = app();
    const branch = "release/it's-ready";
    context.fetch = withMarker(async () => ({
        ok: true,
        json: async () => ({ ...data, rebuildWorkflows: { "example/mono": { monorepo: false, branch, exists: true } } })
    }));
    let copied;
    context.navigator.clipboard.writeText = async text => { copied = text; };
    await context.loadDeployments();
    const cards = get("deploymentsContainer").children;
    const heading = cards.find(card => card.children[0].children[0]?.textContent === "example/mono").children[0];
    const actions = heading.children[1];
    assert.equal(actions.className, "deployment-rebuild-actions");
    assert.equal(actions.children.length, 2);
    assert.equal(actions.children[0].textContent, "Rebuild");
    assert.equal(actions.children[0].href, "https://github.com/example/mono/actions/workflows/rebuild.yml");
    assert.equal(actions.children[0].rel, "noopener noreferrer");
    const button = actions.children[1];
    assert.equal(button.textContent, "gh");
    assert.equal(button.children[0].src, "/internal/gui/icons/files.svg");
    assert.equal(button.children[0].attributes["aria-hidden"], "true");
    assert.equal(button.attributes["aria-label"], "Copy rebuild command for example/mono");
    await button.onclick();
    assert.equal(copied, "gh workflow run rebuild.yml --repo 'example/mono' --ref 'release/it'\\''s-ready'");
    assert.equal(heading.children[2].children[0].textContent, `${copied} copied into clipboard`);
    assert.equal(heading.children[2].attributes.role, "status");
    assert.equal(cards[0].children[0].children.length, 0);
});

test("monorepo copies deduplicated targets for every running app and environment across teams", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({
        ok: true,
        json: async () => ({ ...data, rebuildWorkflows: { "example/mono": { monorepo: true, branch: "main", exists: true } } })
    }));
    const copied = [];
    context.navigator.clipboard.writeText = async text => { copied.push(text); };
    await context.loadDeployments();
    const heading = get("deploymentsContainer").children[1].children[0];
    const actions = heading.children[1];
    assert.deepEqual(actions.children.map(child => child.textContent), ["Rebuild", "gh", "targets"]);
    await actions.children[1].onclick();
    await actions.children[2].onclick();
    const targets = "orders:dev-gcp,orders:prod-gcp,worker:prod-gcp";
    assert.deepEqual(copied, [
        `gh workflow run rebuild.yml --repo 'example/mono' --ref 'main' -f targets='${targets}'`,
        targets
    ]);
    assert.equal(heading.children[2].children[0].textContent, `${targets} copied into clipboard`);
});

test("workflow check failure does not guess rebuild availability, command or targets", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({
        ok: true,
        json: async () => ({ ...data, rebuildWorkflows: { "example/mono": { error: "GitHub unavailable" } } })
    }));
    await context.loadDeployments();
    const heading = get("deploymentsContainer").children[1].children[0];
    assert.equal(heading.children[1].children.length, 0);
    assert.equal(heading.children[2].children[0].textContent, "GitHub unavailable");
});

test("clipboard failures show manual copy text and never claim success", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({
        ok: true,
        json: async () => ({ ...data, rebuildWorkflows: { "example/mono": { monorepo: false, branch: "main", exists: true } } })
    }));
    context.navigator.clipboard.writeText = async () => { throw new Error("Permission denied"); };
    await context.loadDeployments();
    const heading = get("deploymentsContainer").children[1].children[0];
    await heading.children[1].children[1].onclick();
    assert.match(heading.children[2].children[0].textContent, /Could not copy to clipboard.*Copy manually: gh workflow run/);
    assert.doesNotMatch(heading.children[2].children[0].textContent, /copied into clipboard/);
});

test("unsupported app names cannot be injected into rebuild target commands", () => {
    const { context } = app();
    const grouped = context.groupDeploymentApplications([application("team", "app'; echo bad", "dev-gcp")]);
    assert.throws(() => context.deploymentRebuildTargets(grouped[0]), /unsupported application/);
});

test("copy banners can be dismissed, success expires and errors remain", () => {
    const { context } = app();
    const banner = element();
    let expire;
    context.setTimeout = (callback, delay) => { assert.equal(delay, 6000); expire = callback; return 1; };
    context.showDeploymentFeedback(banner, "copied");
    assert.match(banner.className, /save-banner-success/);
    assert.equal(banner.hidden, false);
    banner.children[1].onclick();
    assert.equal(banner.hidden, true);
    context.showDeploymentFeedback(banner, "copied again");
    expire();
    assert.equal(banner.hidden, true);
    context.showDeploymentFeedback(banner, "failed", false);
    assert.match(banner.className, /save-banner-error/);
    assert.equal(banner.hidden, false);
});

test("missing rebuild workflow offers PR generation instead of dispatch or copy controls", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({
        ok: true, json: async () => ({ ...data, rebuildWorkflows: {
            "example/mono": { monorepo: true, branch: "main", exists: false }
        } })
    }));
    await context.loadDeployments();
    const heading = get("deploymentsContainer").children[1].children[0];
    const actions = heading.children[1];
    assert.equal(actions.children.length, 1);
    const generate = actions.children[0];
    assert.equal(generate.textContent, "Generate rebuild.yml monorepo");
    assert.match(generate.className, /pr-button/);
    const opened = { location: {}, close() {} };
    context.window.open = () => opened;
    context.confirm = () => { throw new Error("Generation must not request confirmation"); };
    context.fetch = async (url, options) => {
        assert.equal(url, "/internal/api/deployments/rebuild-pr/example/mono");
        assert.equal(options.method, "POST");
        return { ok: true, json: async () => ({ url: "https://github.com/example/mono/pull/42" }) };
    };
    await generate.onclick();
    assert.equal(generate.disabled, true);
    assert.equal(generate.textContent, "PR created");
    assert.equal(opened.location.href, "https://github.com/example/mono/pull/42");
    assert.equal(generate.attributes["aria-busy"], "false");
    const message = heading.children[2].children[0];
    assert.equal(message.textContent, "Rebuild workflow PR created.");
    assert.equal(message.children[0].href, "https://github.com/example/mono/pull/42");
});

test("generation shows spinner while waiting, avoids duplicate requests and restores the label on failure", async () => {
    const { context, get } = app();
    context.fetch = withMarker(async () => ({
        ok: true, json: async () => ({ ...data, rebuildWorkflows: {
            "example/mono": { monorepo: false, branch: "main", exists: false }
        } })
    }));
    await context.loadDeployments();
    const button = get("deploymentsContainer").children[1].children[0].children[1].children[0];
    assert.equal(button.textContent, "Generate rebuild.yml");
    let finish;
    let calls = 0;
    let closed = false;
    context.window.open = () => ({ close() { closed = true; } });
    context.fetch = () => { calls++; return new Promise(resolve => { finish = resolve; }); };
    const pending = button.onclick();
    assert.equal(button.disabled, true);
    assert.equal(button.attributes["aria-busy"], "true");
    assert.equal(button.children[0].className, "spinner");
    await button.onclick();
    assert.equal(calls, 1);
    finish({ ok: false, text: async () => "Synthetic failure", headers: { get: () => "text/plain" } });
    await pending;
    assert.equal(button.disabled, false);
    assert.equal(button.attributes["aria-busy"], "false");
    assert.equal(button.textContent, "Generate rebuild.yml");
    assert.equal(closed, true);
});
