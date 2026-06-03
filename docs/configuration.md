# Configuration Guide

All settings are on the plugin **Configure** page:

**Gear > Plugins > Orphan Account Correlator > Configure**

No code changes are needed per environment. Adjust the settings below to match your application types, identity attributes, and governance requirements.

---

## Account attribute mapping

These comma-separated lists tell the matching engine which **account attributes** to check for each logical signal. The engine tries each attribute name in order and uses the first non-empty value found. The defaults cover AD, LDAP, JDBC, SCIM, and ServiceNow out of the box.

| Setting | Default | Maps to |
|---|---|---|
| Account Email Attributes | `email, mail, emailAddress, email_address, workEmail, userPrincipalName, upn` | Identity email |
| Account Login Attributes | `accountName, sAMAccountName, uid, userName, user_name, userid, login, user, cn` | Identity name (login) |
| Account First-Name Attributes | `firstname, firstName, first_name, givenName, givenname, fname` | Identity firstname |
| Account Last-Name Attributes | `lastname, lastName, last_name, sn, surname, familyName, lname` | Identity lastname |
| Account Display-Name Attributes | `displayName, displayname, fullName, fullname, full_name, cn, name` | Identity displayName |
| Account UPN Attributes | `userPrincipalName, upn, onPremisesUserPrincipalName` | Identity email (exact + local-part) |
| Account Employee-ID Attributes | `employeeID, employeeId, employeeNumber, employee_number, emp_no, staff_number, personnelNumber` | Identity employee-ID attribute (if configured below) |
| Account Department Attributes | `department, dept, departmentName` | Identity department attribute (if configured) |
| Account Company Attributes | `company, companyName, organisation, organization, org` | Identity company attribute (if configured) |
| Account Manager Attributes | `manager, managerName, manager_email, managerEmail` | Identity manager |

If your application uses non-standard attribute names, add them to the relevant list. Order matters: earlier names take priority.

---

## Identity-side strong attributes (optional)

These are the **names of searchable Identity attributes** in your environment. Leave blank to skip - the scoring engine absorbs their absence (the available-field denominator adjusts, so the plugin stays generic).

| Setting | Default | Notes |
|---|---|---|
| Identity Employee-ID Attribute | *(blank)* | The attribute name holding the employee/staff number (e.g. `employeeId`). **Must be a searchable extended attribute** in ObjectConfig:Identity to be used in candidate retrieval. Strongest signal (weight 30). |
| Identity Department Attribute | *(blank)* | Supporting signal. |
| Identity Company Attribute | *(blank)* | Supporting signal. |

---

## Scoring thresholds

| Setting | Default | Purpose |
|---|---|---|
| Match Threshold | 70 | Minimum final score (0-100) below which a match is considered unreliable for direct correlation. |
| Ambiguity Gap | 8 | If the best candidate beats the runner-up by fewer than this many points (and clears the threshold), the result is flagged **Ambiguous** for human review. |

---

## Scan limits

| Setting | Default | Purpose |
|---|---|---|
| Max Accounts Per Scan | 1000 | Per-request safety cap. The UI fetches in batches behind a progress bar, so this bounds each individual server request, not the total scan. Lower it if individual requests are too slow; raise it (cautiously) if you want fewer round-trips. |

---

## Account-type detection patterns

Comma-separated **whole-token** patterns that flag non-human, privileged, or test accounts. The account login is split on non-alphanumeric boundaries (`.`, `_`, `-`, `@`, etc.) and a pattern matches only a **complete token** - so `sa` matches the `sa` account but not `sam`.

| Setting | Default | Penalty | Purpose |
|---|---|---|---|
| Service-Account Name Patterns | `svc, sa, bot, service, system, slack, app, batch, robot, integration, api, daemon, noreply` | -25 | Flags non-human (service/bot) accounts |
| Admin/Privileged Account Patterns | `admin, adm, priv, pua, pa, elevated, root, superuser, sudo` | -20 | Flags privileged accounts |
| Test-Account Name Patterns | `test, tst, qa, uat, demo, sample, dummy, training, sandbox` | -15 | Flags test/non-production accounts (separate from service) |

To disable any category, clear its field. To add patterns, append them (comma-separated). The patterns are case-insensitive.

---

## Default application

| Setting | Default | Purpose |
|---|---|---|
| Default Application | *(blank)* | Pre-selects this application in the dropdown when the plugin opens. The user can always pick a different one at scan time. |

---

## Scoring weights reference

For context when tuning thresholds - the engine uses these fixed weights:

| Signal | Weight | Notes |
|---|---|---|
| Employee ID (exact match) | 30 | Strongest. Only used when both account and identity have the attribute. |
| Email / UPN | 25 | Exact email, UPN-to-email, or local-part match. |
| Username / login | 20 | Normalized login comparison. |
| Display name | 15 | Token-order-independent (handles "Last, First"). |
| First + last name | 5 | Individual name components. |
| Organisation (manager/dept/company) | 5 | Supporting signals. |

| Penalty | Value |
|---|---|
| Employee-ID conflict (different ID) | -40 |
| Inactive / terminated identity | -30 |
| Service / bot pattern | -25 |
| Admin / privileged pattern | -20 |
| Test account pattern | -15 |
| No strong identifier available | -10 |

Safeguards: thin-evidence floor caps weak matches at 79; username-only matches capped at 94; final score clamped to 0-100.

---

## Confidence levels shown in the UI

| Level | Score range | Meaning |
|---|---|---|
| High | 80 - 100 | Strong evidence - multiple signals agree |
| Medium | 50 - 79 | Reasonable evidence - review before acting |
| Low | Below 50 | Weak evidence - prefer manager approval |
