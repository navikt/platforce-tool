function findTargetSecurity(type, key, version) {
    const results = type === "platform_bom"
        ? targetSecurityScan?.platformBoms
        : type === "dependency" ? targetSecurityScan?.targets : null;
    return results?.find(target => target.key === key && target.targetVersion === version) ?? null;
}

function platformBomSecurityDetailsHtml(security) {
    const vulnerable = security.vulnerableDependencies || [];
    const coverage = (security.coverage || []).filter(item => item.overrides?.length);
    return `
        <div class="finding-details target-security-detail">
            <h4>${escapeHtml(security.key)} ${escapeHtml(security.targetVersion)}</h4>
            <p>${escapeHtml(security.managedDependencyCount)} managed modules checked independently.
                Managed versions are constraints, not necessarily installed dependencies.</p>
            ${vulnerable.length
                ? `<strong>Vulnerable managed versions</strong>
                   <p>This includes unused modules. Upgrade the BOM target and rescan;
                   fixed module versions below are not suggested BOM versions.</p>
                   ${vulnerable.map(item => `
                       <div class="target-security-vulnerability">
                           <strong>${escapeHtml(item.dependency.group)}:${escapeHtml(item.dependency.name)}</strong>
                           ${escapeHtml(item.dependency.version)}
                           <ul>${(item.vulnerabilities || []).map(v => `
                               <li>${escapeHtml(v.id)}: ${escapeHtml(v.summary || "")}
                                   ${v.fixedVersions?.length ? ` (module fixes: ${v.fixedVersions.map(escapeHtml).join(", ")})` : ""}
                               </li>`).join("")}</ul>
                       </div>`).join("")}`
                : "<p>No vulnerabilities found in the effective managed versions.</p>"}
            <h4>Verified dependency coverage</h4>
            ${coverage.length
                ? `<ul>${coverage.map(item => `
                    <li>${escapeHtml(item.dependency)} ${escapeHtml(item.targetVersion)}
                        (${item.enforcedPlatform ? "enforcedPlatform" : "platform"})
                        <ul>${item.overrides.map(reason => `
                            <li>${escapeHtml(reason.dependency)}:
                                ${escapeHtml(reason.vulnerableVersion)} &rarr; ${escapeHtml(reason.resolvedVersion)}</li>
                        `).join("")}</ul>
                    </li>`).join("")}</ul>`
                : "<p>No verified fixes for the scanned dependency targets.</p>"}
            <p>Coverage applies only to matching scanned versions and applicable declarations.
                Managed-version warnings above remain independent of coverage.</p>
        </div>`;
}

function toggleTargetSecurityDetails(button) {

    const row = button.closest(".target-row");

    if (!row) return;

    const details = row.querySelector(".target-security-details");

    if (!details) return;

    details.classList.toggle("hidden");

    button.classList.toggle(
        "expanded",
        !details.classList.contains("hidden")
    );
}

function securityStatusHtml(security) {
    if (!security) {
        return `<div></div>`;
    }

    return `
        <button
            class="target-status-pill ${securityStatusClass(security.status)}"
            title="Show security details"
        >
            ${securityStatusLabel(security.status)}
        </button>
    `;
}

function securityStatusClass(status) {
    switch (status) {
        case "OK":
            return "target-status-ok";

        case "OK_TRANSIENT":
            return "target-status-transient";

        case "OK_OVERRIDDEN":
            return "target-status-overridden";

        case "VULNERABLE":
            return "target-status-vulnerable";

        case "TRANSIENT_UNUSED":
            return "target-status-remove";

        default:
            return "target-status-unknown";
    }
}

function targetSecurityDetailsHtml(security) {
    if (!security) {
        return `
            <div class="finding-details">
                No security result available.
            </div>
        `;
    }

    let html = `
        <div class="finding-details target-security-detail">
            <h4>
                ${escapeHtml(security.key)}
                ${escapeHtml(security.targetVersion)}
            </h4>
    `;

    if (security.status === "OK") {
        html += `
            <div>
                No vulnerabilities found in the resolved dependency tree.
            </div>
        `;
    }

    if (security.status === "OK_TRANSIENT") {
        html += `
            <div>
                No vulnerabilities found in the resolved dependency tree. Marked as transient 
            </div>
        `;
        if (security.transientUsage?.length) {
            html += `
            <div>
                Covers vulnerabilities for:
                <ul>
                    ${security.transientUsage.map(usage => `
                        <li>
                            <strong>
                                ${escapeHtml(usage.dependency)}
                            </strong>
                            ${escapeHtml(usage.targetVersion)}
                        </li>
                    `).join("")}
                </ul>
            </div>
        `;
        }
    }

    if (security.status === "OK_OVERRIDDEN") {
        html += `
            <div>
                <strong>
                    Transitive vulnerability overridden by dependency resolution
                </strong>
            </div>
        `;

        if (security.relatedTo?.length) {
            html += `<ul>`;

            security.relatedTo.forEach(reason => {
                html += `
                    <li>
                        <strong>
                            ${escapeHtml(reason.dependency)}
                        </strong>
                        ${escapeHtml(reason.vulnerableVersion || "")}
                        →
                        ${escapeHtml(reason.resolvedVersion)}
                `;

                if (reason.causedBy?.length) {
                    html += `
                        <div>
                            Resolved by:
                            ${reason.causedBy
                        .map(cause => escapeHtml(cause))
                        .join(", ")}
                        </div>
                    `;
                }

                // suggestedVersion belongs to vulnerableDependencies,
                // not relatedTo.
                const vulnerableDependency =
                    security.vulnerableDependencies?.find(
                        vulnerable =>
                            `${vulnerable.dependency.group}:${vulnerable.dependency.name}` ===
                            reason.dependency
                    );

                /*
                const suggestion =
                    vulnerableDependency?.suggestedVersion;

                if (suggestion) {
                    html += `
                                <div>
                                    <strong>
                                        Upgrade ${escapeHtml(suggestion.dependency)}
                                        to ${escapeHtml(suggestion.version)}
                                        to avoid the vulnerability.
                                    </strong>
            
                                    <div class="security-suggested-version">
                                        <button
                                            class="target-version-pill"
                                            title="Upgrade ${escapeHtml(suggestion.dependency)} to ${escapeHtml(suggestion.version)}"
                                            onclick="applySecurityUpgrade(
                                                this,
                                                '${escapeHtml(suggestion.dependency)}',
                                                '${escapeHtml(suggestion.version)}'
                                            )"
                                        >
                                            → ${escapeHtml(suggestion.version)}
                                        </button>
                                    </div>
                                </div>
                            `;
                }*/

                html += `
                    </li>
                `;
            });

            html += `</ul>`;
        }
    }

    if (security.status === "VULNERABLE") {
        html += `
            <div>
                <strong>
                    Vulnerabilities found in the resolved dependency tree.
                </strong>
            </div>
        `;

        if (security.vulnerableDependencies?.length) {
            security.vulnerableDependencies.forEach(vulnerable => {
                const dependency = vulnerable.dependency;
                const suggestion = vulnerable.suggestedVersion;

                html += `
                    <div class="target-security-vulnerability">
                        <div>
                            <strong>
                                ${escapeHtml(dependency.group)}:${escapeHtml(dependency.name)}
                            </strong>
                            <span>
                                ${escapeHtml(dependency.version)}
                            </span>

                            ${
                    suggestion
                        ? `
                                        <button
                                            class="target-version-pill"
                                            title="Upgrade ${escapeHtml(suggestion.dependency)} to ${escapeHtml(suggestion.version)}"
                                            onclick="applySecurityUpgrade(
                                                this,
                                                '${escapeHtml(suggestion.dependency)}',
                                                '${escapeHtml(suggestion.version)}'
                                            )"
                                        >
                                            → ${escapeHtml(suggestion.version)}
                                        </button>
                                    `
                        : ""
                }
                        </div>

                        ${
                    vulnerable.vulnerabilities?.length
                        ? `
                                    <div>
                                        Fixed in:
                                        ${escapeHtml(
                            [...new Set(
                                vulnerable.vulnerabilities
                                    .flatMap(v => v.fixedVersions || [])
                            )].sort((a, b) =>
                                a.localeCompare(
                                    b,
                                    undefined,
                                    {
                                        numeric: true,
                                        sensitivity: "base"
                                    }
                                )
                            ).join(", ")
                        )}
                                    </div>
                                `
                        : ""
                }

                        <details>
                            <summary>Vulnerability details</summary>

                            ${
                    vulnerable.vulnerabilities
                        .map(vulnerability => `
                                        <div>
                                            <strong>
                                                ${escapeHtml(vulnerability.id)}
                                            </strong>

                                            <div>
                                                ${escapeHtml(
                            vulnerability.summary || ""
                        )}
                                            </div>
                                        </div>
                                    `)
                        .join("")
                }
                        </details>
                    </div>
                `;
            });
        }
    }

    if (security.status === "TRANSIENT_UNUSED") {
        html += `
            <div>
                This transient override is no longer needed and is marked for removal from repositories
            </div>
        `;
    }

    html += `
        </div>
    `;

    return html;
}

function securityStatusLabel(status) {
    switch (status) {
        case "OK":
            return "OK";

        case "OK_TRANSIENT":
            return `OK ${BANDAGE_SVG}`;

        case "OK_OVERRIDDEN":
            return "OK *";

        case "VULNERABLE":
            return "VULNERABLE";

        case "TRANSIENT_UNUSED":
            return "REMOVE";

        default:
            return "?";
    }
}
