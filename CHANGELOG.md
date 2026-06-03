# Changelog

All notable changes to the IdentityIQ Account Correlation Assistant Plugin.

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
