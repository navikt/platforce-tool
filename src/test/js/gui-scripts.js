const { readFileSync } = require("node:fs");
const { resolve } = require("node:path");
const vm = require("node:vm");

const guiRoot = resolve(__dirname, "../../main/resources/gui");
const html = readFileSync(resolve(guiRoot, "index.html"), "utf8");
const scriptTags = [...html.matchAll(/<script\b([^>]*)>\s*<\/script>/g)];
const scripts = scriptTags.map(([, attributes]) => {
    if (/\b(?:async|defer|type)\b/.test(attributes)) {
        throw new Error("GUI scripts must load synchronously as ordered classic scripts");
    }
    const source = attributes.match(/\bsrc="\/internal\/gui\/([^"]+)"/)?.[1];
    if (!source || source.split("/").includes("..")) {
        throw new Error(`Unexpected GUI script: ${attributes}`);
    }
    return {
        source,
        code: readFileSync(resolve(guiRoot, source), "utf8")
    };
});

function loadGuiScripts(context) {
    for (const { source, code } of scripts) {
        vm.runInContext(code, context, { filename: source });
    }
}

module.exports = { html, scripts, loadGuiScripts };
