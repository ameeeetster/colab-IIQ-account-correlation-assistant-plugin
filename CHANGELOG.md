# Changelog

All notable changes to the IdentityIQ Account Correlation Assistant Plugin.

## [Planned - 2.1.0]

### One-click approve/decline from email (parked 2026-07-03)
Email buttons currently deep-link to the work item; the decision is made there.
Planned: encode the decision in the button, land on a small plugin confirmation
page (behind IIQ login/SSO), and complete the work item via a new authenticated
REST endpoint. Design constraints agreed:
- Decision applies on POST after an explicit Confirm click — never on page load
  (mail scanners / Outlook link-preview follow links with the user's session).
- Endpoint verifies authenticated user = work item owner, including workgroup
  membership, server-side.
- Real error messages for already-actioned / expired / not-yours.
- Before building: verify the supported 8.5 API for programmatically completing
  a work item with an approval decision (Workflower) against local JavaDocs.

## [2.0.0] - 2026-07-03

### 3-way approval routing
- Approval target selector: route to **manager**, **identity** (matched person confirms ownership), or **both** (identity confirms first, then manager approves)
- Self-approval blocked: admin cannot send identity-path approval to themselves
- Sequential two-step approval for "both" mode with comment carry-forward

### Manager chain walk (inactive/leaver handling)
- If the resolved manager is inactive (`identity.isInactive()`), the workflow walks up to 2 levels to the grandmanager
- Requester-exclusion at each level prevents self-approval loops
- Falls back to configurable workgroup (`orcFallbackApproverGroup`, default `ServiceAccount-Approvers`), then `spadmin`
- `Workgroup-ServiceAccountApprovers.xml` auto-imported on plugin install

### Scoring engine improvements
- **Inactive identity penalty removed** (-30 was masking valid matches) — inactive status now surfaced as a UI warning badge (`fa-user-times`) without distorting score
- **Employee ID padding tolerance** — leading-zero normalised comparison scores +25 instead of -40 conflict penalty
- **Nickname canonicalisation** — ~70 common English nicknames (Bob↔Robert, Bill↔William, etc.) via `StringMatch.canonicalFirstName()`
- **Diacritics stripping** — post-NFD replacements for ł, ø, ß, æ, đ, þ, œ and their uppercase variants
- **Email-is-login detection** — when the email local part equals the login, the username signal isn't double-counted (jsmith fix)
- **Single-signal cap** — any match based on a single signal type capped at 94 (prevents a lone email match from scoring 100)
- **Thin-evidence floor** — weak matches capped at `matchThreshold - 5` instead of hardcoded 79

### Batched candidate queries (performance)
- 3-phase scan: extract all keys → bulk `Filter.in()` queries (chunks of 50) into shared candidate pool → per-orphan scoring against pool
- Pool cap: `Math.max(200, orphanCount * 2)` to bound memory
- `Filter.ignoreCase(Filter.in())` with try/catch fallback (unverified on all IIQ versions)
- Cached `filteredResults()` in UI — single computation with cache key, replaces 6+ redundant `.filter()` calls
- Single-pass `recomputeCounts()` replaces 4 separate filter passes

### Email templates
- Both templates rewritten as HTML table-based layout with inline styles (email-client safe)
- Approval email: coloured confidence badge (green ≥80, amber 50-79, red <50), "Review & Approve" / "Review & Decline" deep-link buttons using `$spTools.formatURL`
- Result email: green/red outcome banner, summary table
- Bodies wrapped in `<html><body>` — IIQ determines HTML vs plain text by checking if body starts with `<html>`

### Bug fixes
- **Hibernate session conflict on workflow launch** — removed `context.decache()` calls on objects the Workflower re-loads as approval owners
- **Manager variable not surviving step hand-off** — owner script now resolves manager directly from matched identity instead of relying on a variable set in a prior step
- **Email rendering as raw HTML** — wrapped template bodies in `<html><body>` tags (IIQ only renders HTML when body starts with `<html>`)

---

## [1.0.0] - 2026-06-03

### Initial public release

#### Features
- Scan any non-authoritative application for uncorrelated (orphan) accounts
- Weighted fuzzy matching engine (employee ID, email/UPN, username, display name, first/last name, organisation signals)
- Confidence scoring (0-100) with High / Medium / Low labels
- Direct correlation for any matched account
- Manager approval workflow for controlled correlation
- Bulk correlation (up to 100 accounts) and bulk approval (up to 50)
- Chunked scanning with progress bar and stop button for large applications
- Paged results table with items-per-page selector and numbered navigation
- KPI summary cards (Total / Matched / Unresolved / Service-Bot)
- Outcome bar and strategy breakdown
- Filter chips (All / Matched / Ambiguous / Unresolved / Service-Bot)
- Advanced filters (account, suggested identity, strategy, confidence, match reason)
- CSV export with formula-injection protection
- Clickable identity links (opens identity page in new tab)
- Service / admin / test account detection with configurable whole-token patterns
- Server-side score re-verification at correlation time (audit integrity)
- Native IIQ AuditEvent trail (no custom database tables)
- Structured approval and result email templates
- Defensive nav-icon snippet (try/catch, duplicate-injection guard)
- Bundled AngularJS 1.8.0 (self-contained - works on all IIQ builds)
- Manual Angular bootstrap with readiness poller (no ng-app race)
- Pre-scan landing page with empty state
- Compact post-scan toolbar with scan-again and refresh
- Best-match-first result sorting
- Application dropdown auto-refresh after each scan

#### Security
- @RequiredRight on every REST endpoint (ViewOrphanCorrelator / CorrelateOrphanAccount)
- Parameterised Filter.* queries (no SQL/HQL injection)
- CSRF token configuration for IIQ's CsrfService
- Generic error messages to client; full detail server-side only
- CSV formula-injection guard
- No provisioning, no lifecycle events, no role assignment triggered

#### Portability
- No hardcoded hosts, ports, URLs, or accounts
- No custom database tables or DDL
- All URLs relative via SailPoint.CONTEXT_PATH
- Approval fallback routes to the requester, not a hardcoded admin
- Configurable attribute mapping for any connector type (AD, LDAP, JDBC, SCIM, etc.)
- Works on any IIQ-supported database
- Bundled AngularJS removes dependency on host IIQ providing it
- ASCII-only UI text (no encoding/charset issues)
- UTF-8 no-BOM text files
- Forward-slash zip entries (compatible with IIQ plugin loader)

#### Performance
- Application list via single grouped aggregate query (flat regardless of app count)
- Scan paged with configurable per-request cap (default 1000)
- Projection-IDs-first scan pattern (no decache against open cursors)
- Per-row ctx.decache() during scan
- Bulk correlate cap (100) with per-item decache
- StringMatch input capped at 256 chars before O(n*m) algorithms
- Whole-token pattern matching (no false positives from substring fragments)
