# IdentityIQ Account Correlation Assistant Plugin

A SailPoint IdentityIQ plugin that helps administrators review uncorrelated (orphan) accounts per application, identify the closest matching identities using configurable matching logic, and either directly correlate the account or route it to the line manager for review and approval.

## Problem it solves

When IdentityIQ aggregates accounts from connected systems (Active Directory, ServiceNow, Azure AD, JDBC, LDAP, SCIM, etc.), most accounts are automatically correlated to the right identity. Some are not - the system cannot determine who they belong to. These **uncorrelated accounts** are a governance risk: they escape access reviews, nobody owns them, and auditors flag them.

This plugin turns that clean-up from a slow, manual, error-prone task into a guided, auditable workflow.

## Key features

- **Application-level orphan counts** - see which applications have uncorrelated accounts and how many, at a glance
- **Weighted fuzzy matching** - automatically suggests the most likely identity owner using configurable signals (email, username, employee ID, display name, first/last name, department, manager, company)
- **Confidence scoring** - each suggestion gets a 0-100 score with a High/Medium/Low label so reviewers know how strong the evidence is
- **Direct correlation** - for confident matches, an admin can link the account immediately
- **3-way approval routing** - route approvals to the line manager, the matched identity, or both (identity confirms first, then manager approves)
- **Manager chain walk** - if the manager is inactive/leaver, the approval escalates up to 2 levels to the grandmanager; falls back to a configurable workgroup, then spadmin
- **Configurable fallback workgroup** - when no valid manager is found, approval routes to a configurable workgroup (default: ServiceAccount-Approvers, auto-created on install)
- **Full audit trail** - every correlation, approval request, and rejection is recorded as a native IIQ AuditEvent with the server-verified score and justification
- **Service / admin / test account detection** - flags non-human, privileged, and test accounts so they are not accidentally matched to people
- **Batched candidate queries** - scan uses bulk `Filter.in()` queries (chunks of 50) to pre-load candidates for all orphans at once, reducing per-orphan query overhead at scale
- **Inactive identity badge** - matched identities flagged as inactive/leaver show a visual warning in the results table
- **Chunked scanning with progress** - handles large applications (thousands of orphans) without freezing the browser or timing out
- **HTML email templates** - styled table-based emails with coloured confidence badges and deep-link Review buttons (Approve/Decline go directly to the work item)
- **Paged results table** - items per page selector, numbered page navigation, filters, CSV export
- **Clickable identity links** - matched identity names link directly to the identity page in IIQ
- **Role-based access** - two rights control who can view (scan) and who can correlate/approve
- **Environment-portable** - no hardcoded hosts, no custom DB tables, no external libraries; works on any IIQ 8.1+ deployment

## Screenshots

*(Add screenshots to `/docs/screenshots/` showing: landing page, scan results, correlation confirmation, manager approval email, audit search)*

## Installation

1. Download `identityiq-account-correlation-assistant-plugin-2.0.0.zip` from the `/dist/` folder
2. Log in to IdentityIQ as a System Administrator
3. Navigate to **Gear icon > Plugins > New**
4. Upload the zip file
5. Assign the **OrphanCorrelatorAdmin** capability to operators who will use the tool
6. Hard refresh the browser (Ctrl+F5)
7. Click the correlation icon in the top navigation bar

For detailed installation steps, see [Deployment Guide](docs/01_Deployment_Guide.docx).

## Configuration

All settings are on the plugin **Configure** page (Gear > Plugins > Orphan Account Correlator > Configure). No code changes are needed per environment.

| Setting | Purpose |
|---|---|
| Account attribute candidate lists | Map account fields (email, login, name, etc.) to logical signals - covers AD, LDAP, JDBC, SCIM out of the box |
| Identity employee-ID / department / company | Optional strong-match attributes (if searchable in the environment) |
| Match threshold | Minimum score (default 70) for a confident match |
| Ambiguity gap | Score proximity that flags a near-tie between two candidates |
| Max accounts per scan | Per-request safety cap (default 1000) |
| Service / admin / test patterns | Whole-token patterns that flag non-human accounts |

## Matching logic and scoring

The engine compares each orphan account's attributes against correlated identities using a weighted scoring model:

| Signal | Weight | Description |
|---|---|---|
| Employee ID | 30 | Exact match - strongest signal |
| Email / UPN | 25 | Email or sign-in name match |
| Username | 20 | Login / sAMAccountName match |
| Display name | 15 | Full name match (token-order independent) |
| First + last name | 5 | Individual name components |
| Organisation | 5 | Manager, department, company alignment |

Penalties reduce the score for risky signals: employee-ID conflict (-40), service account (-25), admin account (-20), test account (-15), no strong identifier (-10). Inactive identity status is surfaced as a UI warning badge (not a score penalty) so reviewers see it without distorting the match quality signal.

Safeguards: thin-evidence floor caps weak matches at matchThreshold-5; single-signal matches are capped at 94; username-only matches are capped below High; near-ties are flagged Ambiguous for human review. Employee ID comparison supports padding tolerance (leading-zero normalisation).

Fuzzy similarity uses Jaro-Winkler, Levenshtein ratio, and token-set matching - all implemented in-house with no external dependencies. Nickname canonicalisation (~70 common names) and diacritics stripping (ł, ø, ß, æ, etc.) improve cross-system name matching.

## Security

| Control | Detail |
|---|---|
| **Access rights** | `ViewOrphanCorrelator` (read-only scan) and `CorrelateOrphanAccount` (correlate/approve). System Administrators pass automatically. |
| **Audit trail** | Every correlation, approval request, and rejection writes a native IIQ `AuditEvent` (searchable in Audit Search). |
| **Server-verified score** | The score recorded in the audit trail is recomputed server-side at correlation time - never trusted from the browser. |
| **CSRF protection** | AngularJS is configured to IIQ's CSRF token names. |
| **Error handling** | Unexpected exceptions return a generic message to the client; full detail is logged server-side only. |
| **Injection protection** | All queries use parameterised `Filter.*`; no string-concatenation SQL/HQL. |
| **CSV formula guard** | Exported CSV values starting with `= + - @` are prefixed to prevent formula injection. |
| **No provisioning** | Correlation re-points a Link; it does NOT trigger lifecycle events, provision access, or assign roles. |

## Tested on

| Component | Version |
|---|---|
| IdentityIQ | 8.5 (minimum supported: 8.1) |
| Java | 17 (compiled with `--release 8` for backward compatibility) |
| Database | SQL Server 2019 (no custom tables - works on any IIQ-supported database) |
| Application types tested | Active Directory, ServiceNow, Azure AD, JDBC (MockAPI), flat file |
| Browsers | Chrome, Edge, Firefox |

## Requirements

- SailPoint IdentityIQ 8.1 or later
- Plugin framework enabled (Gear > Global Settings > Plugins)
- A scheduled **Identity Refresh** task (for entitlement roll-up after correlation)
- A scheduled **Prune Identity Cubes** task (to clean up empty dummy identities)

## Repository structure

```
/manifest.xml              Plugin manifest
/lib/OrphanCorrelator.jar  Compiled plugin classes
/src/                      Java source (REST resource, scoring engine, string matching)
/import/install/           Bundled IIQ objects (workflow, email templates, rights/capability)
/ui/                       AngularJS UI (page, controller, stylesheet, nav snippet)
/ui/js/lib/angular.min.js  Bundled AngularJS 1.8.0 (self-contained - no host dependency)
/dist/                     Installable plugin zip
/docs/                     Deployment, Functional, and Technical guides (Word)
/LICENSE                   MIT License
/README.md                 This file
/CHANGELOG.md              Version history
```

## Support

This is a community-developed IdentityIQ plugin. It is **not** covered under SailPoint standard support. Issues and enhancement requests should be raised through the GitHub repository or CoLab discussion page.

## License

[MIT](LICENSE)
