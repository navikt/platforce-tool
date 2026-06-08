async function loadData() {
    const response =
        await fetch(
            "/internal/api/dependency-scan"
        );

    const scans =
        await response.json();

    const tbody =
        document.querySelector(
            "#results tbody"
        );

    tbody.innerHTML = "";

    scans.forEach(scan => {
        scan.findings.forEach(finding => {
            const row =
                document.createElement("tr");

            row.innerHTML = `
                <td>${scan.repository}</td>
                <td>${finding.kind}</td>
                <td>${finding.key}</td>
                <td>${finding.currentVersion}</td>
                <td>${finding.targetVersion}</td>
                <td class="status-${finding.status}">
                    ${finding.status}
                </td>
            `;

            tbody.appendChild(row);
        });
    });
}

document
    .getElementById("refreshButton")
    .addEventListener(
        "click",
        async () => {
            await fetch(
                "/internal/api/dependency-scan/refresh",
                {
                    method: "POST"
                }
            );

            await loadData();
        }
    );

loadData();