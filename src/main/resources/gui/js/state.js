const container = document.getElementById("container");
const bar = document.getElementById("progressBar");
const text = document.getElementById("progressText");

let progressInterval = null;
let isRefreshing = false;
let hasActiveScan = false;
let isStartingScan = false;

let lastLoadedData = [];

let ignoredRepositories = [];

let targetSecurityScan = null;
let targetScanGeneration = 0;
let normalSecurityReady = false;
let bomSecurityReady = false;

let selectedTeam = null;

let notes = {};

let targetState = {
    plugins: {},
    dependencies: {},
    platformBoms: {},
    gradleVersion: "",
    transientDependencies: new Set(),
    drafted: new Set()
};
