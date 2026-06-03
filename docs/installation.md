# Installation Guide

## Prerequisites

| Requirement | Detail |
|---|---|
| IdentityIQ version | 8.1 or later (built and tested on 8.5) |
| Plugin framework | Must be enabled: Gear > Global Settings > Plugins |
| Installer account | System Administrator or equivalent with plugin-install rights |
| Database | Any IIQ-supported database - no custom tables or DDL required |
| Browser | Any modern browser (Chrome, Edge, Firefox) |

## Pre-flight checklist

- [ ] Confirm IIQ version is 8.1+
- [ ] Confirm the plugin framework is enabled
- [ ] Confirm you can log in as an administrator who can install plugins
- [ ] (Optional) Confirm no object-name collisions exist (see below)

## Step-by-step installation

1. Download `identityiq-account-correlation-assistant-plugin-1.0.0.zip` from the `/dist/` folder
2. Log in to IdentityIQ as an administrator
3. Navigate to **Gear icon (top right) > Plugins**
4. Click **New** to add a new plugin
5. Upload the zip file and confirm
6. IIQ imports the bundled objects automatically (rights, capability, workflow, email templates, supporting rule)
7. Assign the **OrphanCorrelatorAdmin** capability to operators (see Access below)
8. **Hard refresh** the browser (Ctrl+F5) so the new page assets load
9. Click the correlation icon in the top navigation bar to verify

## Access - rights and capabilities

| Object | Type | Grants |
|---|---|---|
| ViewOrphanCorrelator | Right | Open the page, list applications, run scans (read-only) |
| CorrelateOrphanAccount | Right | Correlate, bulk-correlate, send for manager approval |
| OrphanCorrelatorAdmin | Capability | Bundles both rights - assign to operators who may correlate |

For review-only staff, grant only `ViewOrphanCorrelator`. System Administrators pass automatically.

## Post-install verification

- [ ] The correlation nav icon appears in the top bar for users with access
- [ ] Opening the plugin shows the header, the Scan Application card, and an empty-state panel
- [ ] The application dropdown lists applications that have orphan accounts, with counts
- [ ] Running a scan returns results
- [ ] Sending one item for approval creates a manager work item
- [ ] Audit Search (Search > Advanced Analytics > Audit Search) shows the action `OrphanCorrelate` after a correlation

## Object-name collision check (optional)

The plugin imports only namespaced objects, so collisions are extremely unlikely. To be certain, confirm these names are free before importing into production:

- Rights: `ViewOrphanCorrelator`, `CorrelateOrphanAccount`
- Capability: `OrphanCorrelatorAdmin`
- Workflow: `Workflow-OrphanCorrelationApproval`
- Rule: `Rule-OrphanAccountSummary`
- Email templates: `Orphan Correlation Approval Request`, `Orphan Correlation Result`

## Operational prerequisites

After installation, ensure these scheduled tasks are configured in the environment:

| Task | Why |
|---|---|
| **Identity Refresh** (scheduled, e.g. nightly) | Rolls up the correlated account's entitlements into IIQ's governance views (certifications, risk, entitlement search). The account attaches instantly on correlation; the roll-up happens at the next refresh. |
| **Prune Identity Cubes** (scheduled, e.g. daily) | Cleans up empty placeholder identities left behind after correlation when a foreign-key reference prevented immediate deletion. |

## Upgrade

1. Upload the newer zip over the existing plugin: Gear > Plugins > select plugin > Update
2. Hard refresh the browser (Ctrl+F5)
3. Re-verify per the checklist above
4. Saved configuration settings are preserved across upgrades

## Uninstall

Uninstalling removes the plugin page, REST service, and nav icon. Imported objects (rights, capability, workflow, templates, rule) remain in IIQ and can be deleted manually if desired. No data is harmed - correlations already performed are permanent IIQ identity links, independent of the plugin.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Failed to load applications | User lacks ViewOrphanCorrelator, or stale session | Assign the right; hard refresh (Ctrl+F5); re-login |
| Unable to process manifest file | Zip rebuilt with a tool that added a BOM or backslash paths | Use the supplied zip from `/dist/` |
| Plugin page shows System Exception | Browser served stale cached assets after upgrade | Hard refresh (Ctrl+F5) |
| Dropdown empty | No application has orphans, or plugin framework disabled | Confirm orphans exist (run the OOTB Uncorrelated Accounts report); confirm plugin framework enabled |
| No approval email received | Requester identity has no email, or mail transport not configured | Set an email on the requesting identity; verify IIQ notification settings |
| Raw {{ }} bindings visible | AngularJS not bootstrapped (stale cache) | Hard refresh (Ctrl+F5); clear site cache if needed |
