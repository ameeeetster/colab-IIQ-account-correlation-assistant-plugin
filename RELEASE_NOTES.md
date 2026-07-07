# Release Notes - v2.0.0

**IdentityIQ Account Correlation Assistant Plugin**
Approval routing, scoring, and performance update | July 2026

---

## What is this plugin?

A SailPoint IdentityIQ plugin that helps administrators find and resolve uncorrelated (orphan) accounts. It scans any non-authoritative application, suggests the most likely identity owner using weighted fuzzy matching, and lets the operator either correlate directly or route the decision for approval - with a full audit trail.

---

## What's new in v2.0.0

### 3-way approval routing
Approvals can now be sent to the matched identity's **manager** (as before), to the **matched identity themselves** (self-confirmation), or to **both** in sequence - the identity confirms ownership first, then the manager approves. The admin sending the request cannot route an identity-path approval to themselves.

### Manager chain walk for inactive/leaver managers
If the resolved manager is inactive (a leaver whose account hasn't been deprovisioned), the workflow automatically escalates up to the grandmanager (2 levels). If no active manager is found anywhere in the chain, the request falls back to a configurable approver workgroup (`ServiceAccount-Approvers` by default, created automatically on install).

### Scoring engine refinements
- Inactive identity status no longer reduces the match score - it's shown as a warning badge instead, so a correct match to a leaver isn't unfairly buried
- Employee ID comparison now tolerates leading-zero padding differences (e.g. `00012345` vs `12345`)
- Nickname recognition (~70 common English name pairs - Bob/Robert, Bill/William, etc.)
- Diacritics stripping for international names (ł, ø, ß, æ, and others normalise to their ASCII equivalents)
- A single matching signal alone can no longer produce a perfect 100 score - "Very High" confidence requires corroboration from at least two signals

### Performance at scale
Scanning now pre-loads match candidates in bulk batched queries instead of running several queries per individual orphan account, meaningfully reducing scan time on applications with large numbers of uncorrelated accounts. The results UI also caches computed filter results instead of recalculating them repeatedly.

### Improved approval emails
Approval and result emails are now styled HTML with a colour-coded confidence badge and direct "Review & Approve" / "Review & Decline" buttons that deep-link straight to the work item (login still required - this is not an unauthenticated action link).

---

## Fixes since v1.0.0
- Fixed a workflow launch failure ("Hibernate session conflict") that could occur when sending certain accounts for approval
- Fixed manager approval occasionally routing to the fallback workgroup instead of the correct manager
- Fixed approval emails rendering as raw HTML code instead of a formatted message

---

## Features carried over from v1.0.0

### Scanning and matching
- Scan any non-authoritative application for uncorrelated accounts
- Weighted fuzzy matching engine using six signal categories: employee ID, email/UPN, username, display name, first/last name, and organisation (manager, department, company)
- Confidence scoring (0-100) with High, Medium, and Low labels
- Configurable attribute mapping - works with AD, LDAP, JDBC, SCIM, ServiceNow, Azure AD, and any connector type out of the box
- Service, admin, and test account detection with configurable whole-token patterns
- Best-match-first sorting so the most actionable items appear on page 1

### Correlation workflow
- Direct correlation for any matched account (one-click with confirmation modal)
- Bulk correlation (up to 100 accounts) and bulk approval (up to 50 accounts)
- Duplicate-approval guard prevents the same account being sent twice

### User interface
- Pre-scan landing page with application selector and orphan count badges
- Compact post-scan toolbar with scan-again and application switching
- KPI summary cards (Total Orphans, Matched, Unresolved, Service/Bot)
- Horizontal outcome bar with strategy breakdown
- Filter chips: All, Matched, Ambiguous, Unresolved, Service/Bot
- Advanced filters: account name, suggested identity, strategy, confidence level, match reason
- Paged results table with items-per-page selector (10, 25, 50, 100) and numbered page navigation
- Clickable identity links (opens the identity page in a new tab)
- Chunked scanning with progress bar and stop button for large applications
- CSV export with formula-injection protection

### Security and audit
- Role-based access: ViewOrphanCorrelator (read-only) and CorrelateOrphanAccount (correlate/approve)
- Server-side score re-verification at correlation time - the audit trail records the engine's score, not a value asserted by the browser
- Native IIQ AuditEvent trail for every correlation, approval request, and rejection
- No custom database tables or DDL required
- CSRF protection configured for IIQ's token names
- Generic error messages to client; full exception detail logged server-side only
- Parameterised queries throughout (no SQL/HQL injection surface)
- No provisioning triggered - correlation re-points a Link only; no lifecycle events, no role assignment

### Portability
- Works on IdentityIQ 8.1 and later (tested on 8.5)
- No hardcoded hosts, ports, URLs, or accounts
- No external Java libraries required
- Bundled AngularJS 1.8.0 (self-contained - works on all IIQ builds regardless of whether the host provides Angular)
- Works on any IIQ-supported database (SQL Server, Oracle, MySQL)
- All URLs relative via SailPoint.CONTEXT_PATH
- ASCII-only UI text (no encoding/charset issues across environments)
- Plugin install is passive - changes no existing data and runs nothing automatically

---

## Tested on

| Component | Version |
|---|---|
| IdentityIQ | 8.5 (minimum supported: 8.1) |
| Java | 17 (compiled with --release 8 for backward compatibility) |
| Database | SQL Server 2019 |
| Application types | Active Directory, ServiceNow, Azure AD, JDBC (MockAPI), Flat File |
| Browsers | Chrome, Edge, Firefox |

---

## Installation

1. Download `identityiq-account-correlation-assistant-plugin-2.0.0.zip` from the `dist/` folder
2. In IdentityIQ: Gear > Plugins > New (or Update, if upgrading from v1.0.0) > upload the zip
3. Assign `OrphanCorrelatorAdmin` capability to operators
4. Add at least one member to the `ServiceAccount-Approvers` workgroup (created automatically on install) - this is the fallback approver when no manager can be resolved
5. Hard refresh the browser (Ctrl+F5)

See [Installation Guide](docs/installation.md) for detailed steps.

## Configuration

All settings are on the plugin Configure page (Gear > Plugins > Orphan Account Correlator > Configure). No code changes needed per environment.

See [Configuration Guide](docs/configuration.md) for the full settings reference.

---

## Prerequisites

- SailPoint IdentityIQ 8.1 or later with the plugin framework enabled
- A scheduled **Identity Refresh** task (for entitlement roll-up after correlation)
- A scheduled **Prune Identity Cubes** task (for cleaning up empty placeholder identities)

---

## Known limitations

- Scanning runs synchronously in the browser session - the user must keep the tab open until the scan completes (a progress bar and stop button are provided). A background-scan task is designed but deferred to a future release.
- The bundled AngularJS 1.8.0 is end-of-life (no security patches from Google). This is the same version IdentityIQ itself ships, so there is no incremental risk, but it should be tracked over time.
- The items-per-page dropdown, application selector, and other controls inside AngularJS ng-if blocks use explicit ng-change handlers to work around AngularJS child-scope limitations. This is a standard AngularJS pattern, not a defect.
- Approval emails link to the work item for the decision; a fully one-click approve/decline directly from the email is planned for a future release (see CHANGELOG "Planned - 2.1.0").
- Work items currently have no automatic expiration - an unactioned approval remains open until manually resolved.

---

## Support

This is a community-developed IdentityIQ plugin. It is **not** covered under SailPoint standard support. Issues and enhancement requests should be raised through the GitHub repository.

---

## License

[MIT](LICENSE)
