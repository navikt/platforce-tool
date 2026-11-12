---
name: platforce-gui
description: Build or debug the platforce-tool browser GUI, its tabs, API calls, rendering, copy actions, and client-side state. Use when changing HTML, CSS, or the classic JavaScript files under src/main/resources/gui.
---

# platforce-tool browser GUI

## Frontend structure

- The GUI is plain classic JavaScript; it does not use a frontend framework or ES modules.
- `src/main/resources/gui/index.html` defines the tabs and page structure.
- Feature scripts live in `src/main/resources/gui/js/`: shared state and helpers, API calls, scanner, targets, ignored repositories, deployments, and bootstrap.
- `src/test/js/gui-scripts.js` loads the real HTML and scripts in a VM-backed browser stub. `gui-structure.test.js` checks script order, startup effects, and inline handlers.

## Change workflow

- Check `index.html` script order before adding a script. Shared definitions must load before feature consumers, and `bootstrap.js` must remain last.
- Keep feature files free of startup side effects; initialize behavior through the existing bootstrap path.
- Follow existing DOM and API patterns. For authenticated endpoints, preserve response shapes and explicit fetch cache behavior used by neighboring calls.
- Test observable behavior through the existing Node test harness. Add or update assertions for rendered controls, event wiring, endpoint calls, and error/status feedback as appropriate.
- Keep related frontend code in its feature file rather than growing `bootstrap.js` with feature-specific behavior.

## Tests

- Run all GUI tests with `node --test src/test/js/*.test.js`.
- For deployment changes, use `node --test src/test/js/deployments.test.js`; for target security and upgrade changes, use `node --test src/test/js/security-refresh.test.js src/test/js/target-upgrades.test.js`.
- For endpoint wiring or script-order changes, run `node --test src/test/js/gui-structure.test.js`.

