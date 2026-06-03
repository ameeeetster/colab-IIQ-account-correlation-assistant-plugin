package com.orphancorrelator.plugin.correlator;

import sailpoint.object.*;
import sailpoint.api.SailPointContext;
import sailpoint.api.Workflower;
import sailpoint.rest.plugin.BasePluginResource;
import sailpoint.rest.plugin.RequiredRight;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * Orphan Account Correlator - REST Resource
 *
 * Endpoints:
 *   GET  /applications     - List non-authoritative apps with count of dummy-owned links
 *   GET  /scan             - Scan an app, fuzzy-match dummy-owned links to real identities
 *   POST /correlate        - Re-point a link from dummy to real; delete dummy if empty
 *   POST /request-approval - Launch manager approval workflow
 *
 * Dummy-owned mode: We look for Links whose owning identity has correlated=false.
 * Those are placeholder identities IIQ created during aggregation. We fuzzy-match
 * to correlated=true identities (real HR-correlated). When admin confirms, we
 * re-point the Link to the real identity and delete the dummy if it has no
 * remaining links. Authoritative source apps are never scanned.
 */
@Path("orphan")
public class OrphanCorrelatorResource extends BasePluginResource {

    private static final Logger log = Logger.getLogger("com.orphancorrelator.plugin.correlator.OrphanCorrelatorResource");

    private static final String PLUGIN_NAME    = "OrphanCorrelator";
    private static final String SETTING_APP    = "applicationName";

    // Approval WorkflowCase name prefix - used to launch, to block duplicates,
    // and (in scan) to detect accounts that already have a pending approval.
    private static final String CASE_PREFIX    = "Orphan Correlation Approval: ";

    // ---- Configurable account-attribute candidate lists (Compass-generic) ----
    // The matching engine resolves each "logical field" (email, login, first/last
    // name, display name) from the FIRST non-empty account attribute in these
    // comma-separated candidate lists. Defaults cover AD, LDAP, JDBC, SCIM, etc.
    // Identity side uses standard attributes (email, name, firstname, lastname,
    // displayName) by default, PLUS optional identity-side employeeId/department/
    // company attributes when their names are configured (see SETTING_ID_* below).
    private static final String SETTING_EMAIL_ATTRS   = "emailAccountAttributes";
    private static final String SETTING_UPN_ATTRS     = "upnAccountAttributes";
    private static final String SETTING_LOGIN_ATTRS   = "loginAccountAttributes";
    private static final String SETTING_FIRST_ATTRS   = "firstNameAccountAttributes";
    private static final String SETTING_LAST_ATTRS    = "lastNameAccountAttributes";
    private static final String SETTING_DISPLAY_ATTRS = "displayNameAccountAttributes";
    private static final String SETTING_EMPID_ATTRS   = "employeeIdAccountAttributes";
    private static final String SETTING_DEPT_ATTRS    = "departmentAccountAttributes";
    private static final String SETTING_COMPANY_ATTRS = "companyAccountAttributes";
    private static final String SETTING_MANAGER_ATTRS = "managerAccountAttributes";
    private static final String SETTING_SERVICE_PATTS = "serviceAccountPatterns";
    private static final String SETTING_ADMIN_PATTS   = "adminAccountPatterns";
    private static final String SETTING_TEST_PATTS    = "testAccountPatterns";
    private static final String SETTING_MAX_SCAN      = "maxScanResults";

    // Identity-side STRONG attribute NAMES (optional; used only if configured AND
    // searchable). Blank = signal not used. Keeps the plugin generic per decision.
    private static final String SETTING_ID_EMPID_ATTR   = "identityEmployeeIdAttribute";
    private static final String SETTING_ID_DEPT_ATTR    = "identityDepartmentAttribute";
    private static final String SETTING_ID_COMPANY_ATTR = "identityCompanyAttribute";

    // Scoring knobs
    private static final String SETTING_CAND_LIMIT      = "candidateLimit";
    private static final String SETTING_MATCH_THRESHOLD = "matchThreshold";
    private static final String SETTING_GAP             = "ambiguityGap";

    private static final String DEF_EMAIL_ATTRS   = "email,mail,emailAddress,email_address,workEmail";
    private static final String DEF_UPN_ATTRS      = "userPrincipalName,upn,onPremisesUserPrincipalName";
    private static final String DEF_LOGIN_ATTRS    = "accountName,sAMAccountName,uid,userName,user_name,userid,login,user,cn,onPremisesSamAccountName";
    private static final String DEF_FIRST_ATTRS    = "firstname,firstName,first_name,givenName,givenname,fname";
    private static final String DEF_LAST_ATTRS     = "lastname,lastName,last_name,sn,surname,familyName,lname";
    private static final String DEF_DISPLAY_ATTRS  = "displayName,displayname,fullName,fullname,full_name,name,cn";
    private static final String DEF_EMPID_ATTRS    = "employeeID,employeeId,employeeNumber,employee_number,emp_no,staff_number,personnelNumber";
    private static final String DEF_DEPT_ATTRS     = "department,dept,departmentName";
    private static final String DEF_COMPANY_ATTRS  = "company,companyName,organisation,organization,org";
    private static final String DEF_MANAGER_ATTRS  = "manager,managerName,manager_email,managerEmail";
    private static final String DEF_SERVICE_PATTS  = "svc,sa,bot,service,system,slack,app,batch,robot,integration,api,daemon,noreply";
    private static final String DEF_ADMIN_PATTS    = "admin,adm,priv,pua,pa,elevated,root,superuser,sudo";
    private static final String DEF_TEST_PATTS     = "test,tst,qa,uat,demo,sample,dummy,training,sandbox";

    @Override
    public String getPluginName() {
        return PLUGIN_NAME;
    }

    // =========================================================================
    // GET /orphan/permissions
    // Reports whether the current user may perform correlation actions, so the
    // UI can hide the Correlate / Approval controls from view-only operators.
    // Mirrors how @RequiredRight treats a SystemAdministrator (who passes the
    // right check without the right being explicitly listed).
    // =========================================================================
    @GET
    @Path("permissions")
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("ViewOrphanCorrelator")
    public Response permissions() {
        boolean canCorrelate = false;
        try {
            if (Capability.hasSystemAdministrator(getLoggedInUserCapabilities())) {
                canCorrelate = true;
            } else {
                Collection<String> rights = getLoggedInUserRights();
                canCorrelate = (rights != null) && rights.contains("CorrelateOrphanAccount");
            }
        } catch (Exception ex) {
            log.warn("OrphanCorrelatorResource: permissions() check failed; defaulting canCorrelate=false. "
                    + ex.getMessage());
        }
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("canCorrelate", Boolean.valueOf(canCorrelate));
        return Response.ok(m).build();
    }

    // =========================================================================
    // GET /orphan/applications
    // Returns all NON-AUTHORITATIVE applications with the count of links whose
    // owning identity is a placeholder/dummy (correlated=false).
    // =========================================================================
    @GET
    @Path("applications")
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("ViewOrphanCorrelator")
    public Response listApplications() {

        log.info("OrphanCorrelatorResource: listApplications() called");

        try {
            SailPointContext ctx = getContext();

            // Scale: instead of one COUNT query per application (90 apps = 90
            // round-trips on every page load / refresh), compute every orphan count
            // in a SINGLE grouped aggregate over Link - the same pattern IIQ's OOTB
            // Uncorrelated Accounts report uses (GROUP BY application, count(*)).
            // Dummy-owned link = identity not null AND identity.correlated=false;
            // authoritative source apps are excluded in the query. Only applications
            // that actually have orphans come back (count > 0), which is exactly what
            // the dropdown is for.
            QueryOptions qo = new QueryOptions();
            qo.addFilter(Filter.notnull("identity"));
            qo.addFilter(Filter.eq("identity.correlated", new Boolean(false)));
            qo.addFilter(Filter.eq("application.authoritative", new Boolean(false)));
            qo.addGroupBy("application.name");
            qo.addOrdering("application.name", true);

            List<String> cols = new ArrayList<String>();
            cols.add("application.name");
            cols.add("count(*)");

            List<Map<String, Object>> apps = new ArrayList<Map<String, Object>>();
            String defaultApp = getSettingString(SETTING_APP);

            Iterator<Object[]> it = ctx.search(Link.class, qo, cols);
            try {
                while (it != null && it.hasNext()) {
                    Object[] row = it.next();
                    String name = (String) row[0];
                    int orphanCount = Util.otoi(row[1]);   // count(*) returns Long/BigInteger
                    if (Util.isNullOrEmpty(name) || orphanCount <= 0) { continue; }

                    Map<String, Object> entry = new HashMap<String, Object>();
                    entry.put("name",        name);
                    entry.put("orphanCount", Integer.valueOf(orphanCount));
                    entry.put("isDefault",   name.equals(defaultApp));
                    apps.add(entry);
                }
            } finally {
                Util.flushIterator(it);
            }

            log.info("OrphanCorrelatorResource: listApplications() returning "
                     + apps.size() + " application(s) with orphans (single grouped query).");
            return Response.ok(apps).build();

        } catch (Exception ex) {
            log.error("OrphanCorrelatorResource: listApplications() error.", ex);
            return internalError("listApplications");
        }
    }

    // =========================================================================
    // GET /orphan/scan?application=<appName>
    // Scans for links owned by DUMMY identities and fuzzy-matches against
    // correlated (real) identities.
    // =========================================================================
    @GET
    @Path("scan")
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("ViewOrphanCorrelator")
    public Response scan(@QueryParam("application") String applicationParam,
                         @QueryParam("offset") Integer offsetParam,
                         @QueryParam("limit")  Integer limitParam) {

        log.info("OrphanCorrelatorResource: scan() called for application=["
                 + applicationParam + "] offset=[" + offsetParam + "] limit=[" + limitParam + "]");

        List<MatchResult> results = new ArrayList<MatchResult>();

        try {
            SailPointContext ctx = getContext();

            String appName = applicationParam;
            if (Util.isNullOrEmpty(appName)) {
                appName = getSettingString(SETTING_APP);
            }
            if (Util.isNullOrEmpty(appName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"No application specified.\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            Application app = ctx.getObjectByName(Application.class, appName);
            if (app == null) {
                log.error("OrphanCorrelatorResource: Application [" + appName + "] not found.");
                return jsonError(Response.Status.NOT_FOUND, "Application not found: " + appName);
            }
            if (app.isAuthoritative()) {
                log.warn("OrphanCorrelatorResource: refusing to scan authoritative application [" + appName + "]");
                return jsonError(Response.Status.BAD_REQUEST,
                        "Cannot scan authoritative application [" + appName + "]. "
                        + "Authoritative apps drive identity creation and must not be re-correlated.");
            }

            // C3 + chunked scan: a scan runs in-request and fires several candidate
            // queries PER orphan, so one unbounded synchronous request can time out
            // the browser/load-balancer and pin a Tomcat thread on large apps. The
            // UI therefore scans in PAGES: it asks for a window [offset, offset+limit)
            // at a time and accumulates client-side behind a progress bar. Each
            // request stays short. `maxScanResults` (default 1000) is the per-request
            // SAFETY CAP - a single request can never process more than this many,
            // however large a `limit` the caller asks for.
            int batchCap = settingInt(SETTING_MAX_SCAN, 1000);
            if (batchCap < 1) { batchCap = 1; }

            int offset = (offsetParam != null && offsetParam.intValue() > 0) ? offsetParam.intValue() : 0;
            int limit  = (limitParam  != null && limitParam.intValue()  > 0) ? limitParam.intValue()  : batchCap;
            if (limit > batchCap) { limit = batchCap; }

            // Total orphan count for this app (cheap, indexed) - lets the UI show
            // progress and know when it has fetched everything.
            QueryOptions countQo = new QueryOptions();
            countQo.addFilter(Filter.eq("application.name", appName));
            countQo.addFilter(Filter.notnull("identity"));
            countQo.addFilter(Filter.eq("identity.correlated", new Boolean(false)));
            int total = ctx.countObjects(Link.class, countQo);

            // C2: collect the orphan Link IDs for THIS PAGE via a projection query
            // first, then close that cursor before processing. The per-row
            // ctx.decache() below (needed so candidate identities don't accumulate
            // in the session) therefore never runs against an OPEN search iterator -
            // which could otherwise detach the Hibernate cursor / throw
            // LazyInitializationException. Stable nativeIdentity ordering makes the
            // paging deterministic across requests.
            QueryOptions qo = new QueryOptions();
            qo.addFilter(Filter.eq("application.name", appName));
            qo.addFilter(Filter.notnull("identity"));
            qo.addFilter(Filter.eq("identity.correlated", new Boolean(false)));
            qo.addOrdering("nativeIdentity", true);
            qo.setFirstRow(offset);
            qo.setResultLimit(limit);

            List<String> linkIds = new ArrayList<String>();
            Iterator<Object[]> idIter = ctx.search(Link.class, qo, "id");
            try {
                while (idIter != null && idIter.hasNext()) {
                    Object[] row = idIter.next();
                    if (row != null && row[0] != null) { linkIds.add(row[0].toString()); }
                }
            } finally {
                Util.flushIterator(idIter);
            }

            int count = 0;
            for (String linkId : linkIds) {
                Link orphan = null;
                try {
                    orphan = ctx.getObjectById(Link.class, linkId);
                    if (orphan == null) { continue; } // re-pointed / removed since the id scan
                    count++;
                    log.debug("OrphanCorrelatorResource: processing dummy-owned link ["
                            + orphan.getNativeIdentity() + "]");
                    results.add(runFuzzyMatch(ctx, orphan, appName));
                } catch (Exception ex) {
                    log.error("OrphanCorrelatorResource: error processing dummy-owned link ["
                            + (orphan != null ? orphan.getNativeIdentity() : linkId) + "].", ex);
                    MatchResult errResult = new MatchResult();
                    if (orphan != null) {
                        errResult.setNativeIdentity(orphan.getNativeIdentity());
                        errResult.setApplicationName(appName);
                    }
                    errResult.setErrorMessage("Processing error - see server logs.");
                    results.add(errResult);
                } finally {
                    // Safe here - no open search cursor. Clears the orphan link and
                    // every candidate identity loaded while matching this row.
                    try { ctx.decache(); } catch (Exception ignore) {}
                }
            }

            int returned = linkIds.size();
            boolean hasMore = (offset + returned) < total;
            log.info("OrphanCorrelatorResource: scan page complete - offset=" + offset
                     + " returned=" + returned + " total=" + total + " hasMore=" + hasMore);

            // Flag accounts that already have an OPEN manager-approval workflow so
            // the UI shows "Sent for Approval" (greyed) instead of the button. One
            // bulk query (not per-row): collect pending case names, parse the
            // nativeIdentity out of each, then mark matching results.
            try {
                QueryOptions wcQo = new QueryOptions();
                wcQo.addFilter(Filter.like("name", CASE_PREFIX, Filter.MatchMode.START));
                Iterator<Object[]> wcIter = ctx.search(WorkflowCase.class, wcQo, "name");
                Set<String> pending = new HashSet<String>();
                try {
                    while (wcIter != null && wcIter.hasNext()) {
                        String nm = (String) wcIter.next()[0];
                        if (nm == null || nm.length() <= CASE_PREFIX.length()) { continue; }
                        String rest = nm.substring(CASE_PREFIX.length());
                        int arrow = rest.indexOf(" -> ");
                        pending.add((arrow >= 0 ? rest.substring(0, arrow) : rest).trim());
                    }
                } finally {
                    Util.flushIterator(wcIter);
                }
                if (!pending.isEmpty()) {
                    for (MatchResult mr : results) {
                        if (mr.getNativeIdentity() != null && pending.contains(mr.getNativeIdentity())) {
                            mr.setApprovalPending(true);
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("OrphanCorrelatorResource: pending-approval detection failed (non-fatal). " + ex.getMessage());
            }

            List<Map<String, Object>> mapped = toMapList(results);
            Map<String, Object> envelope = new HashMap<String, Object>();
            envelope.put("results",  mapped);
            envelope.put("total",    Integer.valueOf(total));      // total orphans on this app
            envelope.put("offset",   Integer.valueOf(offset));     // window start this page
            envelope.put("limit",    Integer.valueOf(limit));      // window size this page
            envelope.put("returned", Integer.valueOf(returned));   // rows actually in this page
            envelope.put("hasMore",  Boolean.valueOf(hasMore));    // more pages remain
            envelope.put("matchThreshold", Integer.valueOf(settingInt(SETTING_MATCH_THRESHOLD, 70)));
            return Response.ok(envelope).build();

        } catch (Exception ex) {
            log.error("OrphanCorrelatorResource: scan() error.", ex);
            return internalError("scan");
        }
    }

    // =========================================================================
    // POST /orphan/correlate
    // Re-points dummy-owned link to a real identity. Deletes the dummy if it
    // has no remaining links and is still correlated=false.
    // =========================================================================
    @POST
    @Path("correlate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("CorrelateOrphanAccount")
    public Response correlate(Map<String, String> body) {

        String nativeIdentity = body.get("nativeIdentity");
        String appName        = body.get("applicationName");
        String identityName   = body.get("identityName");
        String strategy       = body.get("strategy");
        String scoreStr       = body.get("score");
        String matchDetail    = body.get("matchDetail");

        log.info("OrphanCorrelatorResource: correlate() called for nativeIdentity=["
                 + nativeIdentity + "] identityName=[" + identityName + "]");

        if (Util.isNullOrEmpty(nativeIdentity) || Util.isNullOrEmpty(appName)
                || Util.isNullOrEmpty(identityName)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"nativeIdentity, applicationName and identityName are required\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            SailPointContext ctx = getContext();
            Map<String, Object> r = correlateOne(ctx, nativeIdentity, appName,
                    identityName, strategy, scoreStr, matchDetail, resolveAdminUser(ctx));
            if ("error".equals(r.get("status"))) {
                return errorResponse(r.get("error"));
            }
            return Response.ok(r).build();
        } catch (Exception ex) {
            log.error("OrphanCorrelatorResource: correlate() error.", ex);
            return internalError("correlate");
        }
    }

    // =========================================================================
    // POST /orphan/correlate-bulk
    // Body: { "items": [ {nativeIdentity, applicationName, identityName, strategy}, ... ] }
    // Correlates every item in one request; returns a per-item result list and
    // a success/failure tally. One bad item never aborts the rest.
    // =========================================================================
    @POST
    @Path("correlate-bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("CorrelateOrphanAccount")
    @SuppressWarnings("unchecked")
    public Response correlateBulk(Map<String, Object> body) {

        List<Map<String, String>> items = null;
        if (body != null && body.get("items") instanceof List) {
            items = (List<Map<String, String>>) body.get("items");
        }
        if (items == null || items.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"No items supplied for bulk correlation.\"}")
                    .type(MediaType.APPLICATION_JSON).build();
        }

        // Hard cap: each item re-points a link, commits, and the affected identities
        // are refreshed (cube recompute) after the batch - so a synchronous bulk
        // request must stay bounded to avoid HTTP timeout / thread-pin. 100 keeps a
        // worst-case batch (100 distinct identities to refresh) within a request.
        int MAX_BULK = 100;
        if (items.size() > MAX_BULK) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Bulk request exceeds the maximum of " + MAX_BULK
                            + " items. Split into smaller batches.\"}")
                    .type(MediaType.APPLICATION_JSON).build();
        }

        log.info("OrphanCorrelatorResource: correlateBulk() called for " + items.size() + " item(s).");

        try {
            SailPointContext ctx = getContext();
            String adminUser = resolveAdminUser(ctx);

            int ok = 0, failed = 0;
            List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
            for (Map<String, String> it : items) {
                Map<String, Object> r = correlateOne(ctx,
                        it.get("nativeIdentity"), it.get("applicationName"),
                        it.get("identityName"), it.get("strategy"),
                        it.get("score"), it.get("matchDetail"), adminUser);
                if ("success".equals(r.get("status"))) { ok++; } else { failed++; }
                results.add(r);
                // Bound session heap across a large batch (safe here - correlateOne
                // has committed and closed its own iterators by now).
                try { ctx.decache(); } catch (Exception ignore) {}
            }

            log.info("OrphanCorrelatorResource: correlateBulk() complete - success=" + ok + " failed=" + failed);

            Map<String, Object> resp = new HashMap<String, Object>();
            resp.put("status",  "done");
            resp.put("success", Integer.valueOf(ok));
            resp.put("failed",  Integer.valueOf(failed));
            resp.put("results", results);
            return Response.ok(resp).build();

        } catch (Exception ex) {
            log.error("OrphanCorrelatorResource: correlateBulk() error.", ex);
            return internalError("correlateBulk");
        }
    }

    /**
     * Core re-point of one orphan link to a correlated identity (deletes the
     * dummy if it has no remaining links). Returns a status map; never throws.
     * Shared by the single and bulk correlate endpoints.
     */
    private Map<String, Object> correlateOne(SailPointContext ctx, String nativeIdentity,
                                             String appName, String identityName,
                                             String strategy, String scoreStr,
                                             String matchDetail, String adminUser) {
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("nativeIdentity", nativeIdentity);
        r.put("identityName",   identityName);

        Identity targetIdentity = null, dummyIdentity = null;
        Link     orphanLink     = null;
        String   dummyName      = null;
        boolean  dummyDeleted   = false;

        try {
            if (Util.isNullOrEmpty(nativeIdentity) || Util.isNullOrEmpty(appName)
                    || Util.isNullOrEmpty(identityName)) {
                r.put("status", "error");
                r.put("error", "nativeIdentity, applicationName and identityName are required");
                return r;
            }

            targetIdentity = ctx.getObjectByName(Identity.class, identityName);
            if (targetIdentity == null) {
                r.put("status", "error"); r.put("error", "Target identity not found: " + identityName); return r;
            }
            if (!Boolean.TRUE.equals(targetIdentity.isCorrelated())) {
                r.put("status", "error"); r.put("error", "Target identity [" + identityName + "] is not correlated."); return r;
            }

            QueryOptions qo = new QueryOptions();
            qo.addFilter(Filter.eq("nativeIdentity", nativeIdentity));
            qo.addFilter(Filter.eq("application.name", appName));
            Iterator<Link> linkIter = ctx.search(Link.class, qo);
            try {
                if (linkIter != null && linkIter.hasNext()) { orphanLink = linkIter.next(); }
            } finally {
                Util.flushIterator(linkIter);
            }

            if (orphanLink == null) {
                r.put("status", "error"); r.put("error", "Link not found: " + nativeIdentity + " on " + appName); return r;
            }

            // Safety guards (mirror scan()/the workflow): the UI only ever offers
            // genuine orphans, but a direct REST call could target anything. Refuse
            // authoritative applications, and refuse links that are not owned by an
            // uncorrelated dummy - so we can never re-point an already-correctly-
            // correlated account or touch an authoritative source app.
            Application app = ctx.getObjectByName(Application.class, appName);
            boolean authoritative = (app != null) && app.isAuthoritative();
            if (app != null) { try { ctx.decache(app); } catch (Exception ignore) {} }
            if (authoritative) {
                r.put("status", "error");
                r.put("error", "Cannot correlate on authoritative application [" + appName + "].");
                return r;
            }

            Identity currentOwner = orphanLink.getIdentity();
            if (currentOwner == null || Boolean.TRUE.equals(currentOwner.isCorrelated())) {
                r.put("status", "error");
                r.put("error", "Account [" + nativeIdentity + "] is not an uncorrelated orphan; refusing to re-point.");
                return r;
            }
            dummyName = currentOwner.getName();

            // C1 (council finding): re-derive the match score SERVER-SIDE for the
            // chosen identity so the audit trail records what the engine computes,
            // not a value asserted by the client. Done BEFORE the re-point - it uses
            // the account attributes and the target identity's attributes, both of
            // which are unaffected by re-pointing the link.
            ScoreCard verified   = recomputeScore(ctx, orphanLink, targetIdentity);
            String    serverScore   = (verified != null) ? String.valueOf(verified.score) : null;
            String    serverReasons = (verified != null) ? joinCsv(verified.reasons)       : null;

            orphanLink.setIdentity(targetIdentity);
            ctx.saveObject(orphanLink);
            ctx.saveObject(targetIdentity);
            ctx.commitTransaction();

            log.info("OrphanCorrelatorResource: re-pointed link [" + nativeIdentity
                     + "] from dummy [" + dummyName + "] to identity [" + identityName + "]");

            if (dummyName != null) {
                // The re-point above is already committed - the account IS correlated.
                // Deleting the now-empty dummy is best-effort: if a foreign-key
                // reference still holds it (e.g. an identity snapshot / history row),
                // the delete throws ConstraintViolationException. That must NOT fail
                // the correlation. Log it, roll back the failed delete so the session
                // is clean for the audit/refresh that follow, and leave the empty
                // dummy for the OOTB Prune Identity Cubes task to reap.
                try {
                    dummyIdentity = ctx.getObjectByName(Identity.class, dummyName);
                    if (dummyIdentity != null && Boolean.FALSE.equals(dummyIdentity.isCorrelated())) {
                        QueryOptions remainingQo = new QueryOptions();
                        remainingQo.addFilter(Filter.eq("identity.name", dummyName));
                        int remaining = ctx.countObjects(Link.class, remainingQo);
                        if (remaining == 0 && isSafeToDeleteDummy(ctx, dummyName)) {
                            log.info("OrphanCorrelatorResource: deleting empty dummy [" + dummyName + "].");
                            ctx.removeObject(dummyIdentity);
                            ctx.commitTransaction();
                            dummyDeleted = true;
                        } else if (remaining == 0) {
                            log.warn("OrphanCorrelatorResource: dummy [" + dummyName
                                    + "] has 0 links but owns other relationships (manager, workitems). Skipping deletion.");
                        }
                    }
                } catch (Exception dummyEx) {
                    log.warn("OrphanCorrelatorResource: dummy cleanup failed for [" + dummyName
                            + "] (correlation already succeeded; leaving the empty dummy for Prune Identity Cubes). "
                            + dummyEx.getMessage());
                    try { ctx.rollbackTransaction(); } catch (Exception ignore) {}
                    dummyDeleted = false;
                }
            }


            // The score persisted to the audit trail is the SERVER-computed value
            // (authoritative). The client-asserted score/justification is retained
            // only as a clearly-labelled "operator-asserted" note for transparency.
            String recordedScore = (serverScore != null) ? serverScore
                                   : (scoreStr != null ? scoreStr : "");

            StringBuilder nb = new StringBuilder();
            if (dummyName != null) {
                nb.append("Re-pointed from dummy [").append(dummyName).append("]")
                  .append(dummyDeleted ? " (dummy deleted)" : " (dummy kept)");
            }
            if (verified != null) {
                if (nb.length() > 0) { nb.append(". "); }
                nb.append("Verified score ").append(serverScore).append("/100");
                if (!Util.isNullOrEmpty(serverReasons)) { nb.append(": ").append(serverReasons); }
            } else {
                if (nb.length() > 0) { nb.append(". "); }
                nb.append("Server-side score verification unavailable - recorded operator-asserted value");
            }
            if (!Util.isNullOrEmpty(matchDetail)) {
                if (nb.length() > 0) { nb.append(". "); }
                nb.append("Operator-asserted: ").append(matchDetail);
            }
            String auditNotes = (nb.length() > 0) ? nb.toString() : null;

            r.put("status",       "success");
            r.put("dummyName",    dummyName);
            r.put("dummyDeleted", Boolean.valueOf(dummyDeleted));
            if (serverScore != null) { r.put("verifiedScore", serverScore); }

            // Flag a material divergence between the asserted and verified score so
            // it surfaces to the operator, not just inside the audit note.
            if (serverScore != null && !Util.isNullOrEmpty(scoreStr)) {
                try {
                    int diff = Math.abs(Integer.parseInt(scoreStr.trim()) - verified.score);
                    if (diff >= 10) {
                        r.put("scoreWarning", "Displayed score (" + scoreStr.trim()
                                + ") differs from server-verified score (" + serverScore
                                + "). The verified score was recorded.");
                    }
                } catch (NumberFormatException ignore) { /* asserted score not numeric */ }
            }

            // Native IIQ AuditEvent - the single audit trail (shows in Audit
            // Search, works on any DB, no custom table or DDL required).
            auditEvent(ctx, "OrphanCorrelate", nativeIdentity, appName,
                    identityName, recordedScore, adminUser, auditNotes);

            // NOTE: the plugin intentionally does NOT recompute the identity cube here.
            // The account (Link) is attached immediately; the rolled-up identity-level
            // entitlement records, detected roles and risk are recomputed by IIQ's
            // scheduled Identity Refresh on its next cycle - the same timing IIQ uses
            // for accounts that correlate during normal aggregation. This avoids the
            // best-effort in-request refresh (and its lazy-init session issues).
            // Ensure a regular Identity Refresh task is scheduled in the environment.
            return r;

        } catch (Exception ex) {
            // M1: log the real cause server-side; return a generic message so raw
            // exception text (Hibernate/JDBC internals, object names) never reaches
            // the client.
            log.error("OrphanCorrelatorResource: correlateOne() error for [" + nativeIdentity
                      + "]. Error: " + ex.getMessage(), ex);
            r.put("status", "error");
            r.put("error", "Correlation failed due to an internal error. Check server logs for details.");
            return r;
        } finally {
            if (orphanLink     != null) { try { ctx.decache(orphanLink);     } catch (Exception ignore) {} }
            if (targetIdentity != null) { try { ctx.decache(targetIdentity); } catch (Exception ignore) {} }
            if (dummyIdentity  != null && !dummyDeleted) {
                try { ctx.decache(dummyIdentity); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * Returns true only if the dummy identity has no other live relationships
     * that would be orphaned by deleting it. Checks: manager-of-others, open
     * WorkItems it owns, and open WorkItems where it is the requester.
     */
    private boolean isSafeToDeleteDummy(SailPointContext ctx, String dummyName)
            throws GeneralException {
        // Is this identity a manager of any other identity?
        QueryOptions mgrQo = new QueryOptions();
        mgrQo.addFilter(Filter.eq("manager.name", dummyName));
        if (ctx.countObjects(Identity.class, mgrQo) > 0) { return false; }

        // Does it own any open WorkItems?
        QueryOptions wiOwnerQo = new QueryOptions();
        wiOwnerQo.addFilter(Filter.eq("owner.name", dummyName));
        if (ctx.countObjects(WorkItem.class, wiOwnerQo) > 0) { return false; }

        // Is it the requester on any open WorkItems?
        QueryOptions wiReqQo = new QueryOptions();
        wiReqQo.addFilter(Filter.eq("requester.name", dummyName));
        if (ctx.countObjects(WorkItem.class, wiReqQo) > 0) { return false; }

        return true;
    }

    /** Resolve the acting admin user for audit/launcher purposes. */
    private String resolveAdminUser(SailPointContext ctx) {
        String adminUser;
        try {
            Identity loggedIn = getLoggedInUser();
            adminUser = (loggedIn != null) ? loggedIn.getName() : ctx.getUserName();
        } catch (Exception ex) {
            adminUser = ctx.getUserName();
        }
        if (Util.isNullOrEmpty(adminUser)) { adminUser = "spadmin"; }
        return adminUser;
    }

    // =========================================================================
    // POST /orphan/request-approval
    // Launches the Workflow-OrphanCorrelationApproval workflow.
    // =========================================================================
    @POST
    @Path("request-approval")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("CorrelateOrphanAccount")
    public Response requestApproval(Map<String, String> body) {

        String nativeIdentity = body.get("nativeIdentity");
        String appName        = body.get("applicationName");
        String identityName   = body.get("identityName");
        String strategy       = body.get("strategy");
        String scoreStr       = body.get("score");
        String matchDetail    = body.get("matchDetail");

        log.info("OrphanCorrelatorResource: requestApproval() called for nativeIdentity=["
                 + nativeIdentity + "] identityName=[" + identityName + "]");

        if (Util.isNullOrEmpty(nativeIdentity) || Util.isNullOrEmpty(appName)
                || Util.isNullOrEmpty(identityName)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"nativeIdentity, applicationName and identityName are required\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        try {
            SailPointContext ctx = getContext();
            Map<String, Object> r = requestApprovalOne(ctx, nativeIdentity, appName,
                    identityName, strategy, scoreStr, matchDetail, resolveAdminUser(ctx));
            String st = (String) r.get("status");
            if ("conflict".equals(st)) {
                return Response.status(Response.Status.CONFLICT).entity(r)
                        .type(MediaType.APPLICATION_JSON).build();
            }
            if ("error".equals(st)) {
                return errorResponse(r.get("error"));
            }
            return Response.ok(r).build();
        } catch (Exception ex) {
            log.error("OrphanCorrelatorResource: requestApproval() error.", ex);
            return internalError("requestApproval");
        }
    }

    // =========================================================================
    // POST /orphan/request-approval-bulk
    // Sends multiple matched accounts for manager approval in one request.
    // Each item launches its own approval workflow (to that identity's manager).
    // =========================================================================
    @POST
    @Path("request-approval-bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequiredRight("CorrelateOrphanAccount")
    @SuppressWarnings("unchecked")
    public Response requestApprovalBulk(Map<String, Object> body) {
        List<Map<String, String>> items = null;
        if (body != null && body.get("items") instanceof List) {
            items = (List<Map<String, String>>) body.get("items");
        }
        if (items == null || items.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "No items supplied for bulk approval.");
        }
        // Lower cap than bulk-correlate: each item launches a workflow (heavier
        // than a link re-point), so a synchronous request must stay bounded.
        int MAX_BULK_APPROVAL = 50;
        if (items.size() > MAX_BULK_APPROVAL) {
            return jsonError(Response.Status.BAD_REQUEST, "Bulk approval exceeds the maximum of "
                    + MAX_BULK_APPROVAL + " items. Split into smaller batches.");
        }

        log.info("OrphanCorrelatorResource: requestApprovalBulk() called for " + items.size() + " item(s).");
        try {
            SailPointContext ctx = getContext();
            String adminUser = resolveAdminUser(ctx);
            int ok = 0, failed = 0;
            List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
            for (Map<String, String> it : items) {
                Map<String, Object> r = requestApprovalOne(ctx,
                        it.get("nativeIdentity"), it.get("applicationName"),
                        it.get("identityName"), it.get("strategy"),
                        it.get("score"), it.get("matchDetail"), adminUser);
                if ("approval_requested".equals(r.get("status"))) { ok++; } else { failed++; }
                results.add(r);
            }
            log.info("OrphanCorrelatorResource: requestApprovalBulk() complete - sent=" + ok + " failed=" + failed);
            Map<String, Object> resp = new HashMap<String, Object>();
            resp.put("status",  "done");
            resp.put("success", Integer.valueOf(ok));
            resp.put("failed",  Integer.valueOf(failed));
            resp.put("results", results);
            return Response.ok(resp).build();
        } catch (Exception ex) {
            log.error("OrphanCorrelatorResource: requestApprovalBulk() error.", ex);
            return internalError("requestApprovalBulk");
        }
    }

    /**
     * Launch one manager-approval workflow. Returns a status map; never throws.
     * status = "approval_requested" | "conflict" (already pending) | "error".
     * Shared by the single and bulk request-approval endpoints.
     */
    private Map<String, Object> requestApprovalOne(SailPointContext ctx, String nativeIdentity,
            String appName, String identityName, String strategy, String scoreStr,
            String matchDetail, String adminUser) {
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("nativeIdentity", nativeIdentity);
        r.put("identityName",   identityName);
        try {
            if (Util.isNullOrEmpty(nativeIdentity) || Util.isNullOrEmpty(appName)
                    || Util.isNullOrEmpty(identityName)) {
                r.put("status", "error");
                r.put("error", "nativeIdentity, applicationName and identityName are required");
                return r;
            }

            // L1: mirror the direct-correlate guards so a hand-crafted REST call to
            // request-approval can't bypass them. Refuse authoritative applications
            // and require the target to be a real (correlated) identity.
            Application reqApp = ctx.getObjectByName(Application.class, appName);
            boolean reqAuthoritative = (reqApp != null) && reqApp.isAuthoritative();
            if (reqApp != null) { try { ctx.decache(reqApp); } catch (Exception ignore) {} }
            if (reqAuthoritative) {
                r.put("status", "error");
                r.put("error", "Cannot request approval on authoritative application [" + appName + "].");
                return r;
            }

            Identity matchedId = ctx.getObjectByName(Identity.class, identityName);
            if (matchedId == null) {
                r.put("status", "error"); r.put("error", "Identity not found: " + identityName); return r;
            }
            if (!Boolean.TRUE.equals(matchedId.isCorrelated())) {
                r.put("status", "error");
                r.put("error", "Target identity [" + identityName + "] is not correlated.");
                return r;
            }
            Identity manager = matchedId.getManager();
            // No manager => the workflow routes the approval to the requester; keep
            // this UI message consistent (don't claim it went to a hardcoded admin).
            String managerName    = (manager != null) ? manager.getName()        : adminUser;
            String managerDisplay = (manager != null) ? manager.getDisplayName()
                                    : (adminUser + " (requester — matched identity has no manager)");
            String matchedDisplay = matchedId.getDisplayName();

            // C1: re-derive the score SERVER-SIDE here too, so the value that flows
            // into the workflow (orcScore) and the audit trail is the engine's, not
            // the client's. Best-effort: if the link is gone or scoring throws, fall
            // back to the asserted value (recorded as such downstream).
            Link orphanLink = findLink(ctx, nativeIdentity, appName);
            ScoreCard verified   = recomputeScore(ctx, orphanLink, matchedId);
            String    serverScore   = (verified != null) ? String.valueOf(verified.score)
                                       : (scoreStr != null ? scoreStr : "");
            String    serverReasons = (verified != null) ? joinCsv(verified.reasons) : null;
            if (orphanLink != null) { try { ctx.decache(orphanLink); } catch (Exception ignore) {} }
            ctx.decache(matchedId);

            // Duplicate guard: skip if an open approval already exists.
            QueryOptions existQo = new QueryOptions();
            existQo.addFilter(Filter.like("name", CASE_PREFIX + nativeIdentity, Filter.MatchMode.START));
            if (ctx.countObjects(WorkflowCase.class, existQo) > 0) {
                r.put("status", "conflict");
                r.put("error", "An approval is already pending for account [" + nativeIdentity
                        + "]. Check the manager's Approvals inbox.");
                return r;
            }

            Workflow wf = ctx.getObjectByName(Workflow.class, "Workflow-OrphanCorrelationApproval");
            if (wf == null) {
                r.put("status", "error"); r.put("error", "Approval workflow not installed. Re-import plugin."); return r;
            }

            Map<String, Object> launchArgs = new HashMap<String, Object>();
            launchArgs.put("orcNativeIdentity",   nativeIdentity);
            launchArgs.put("orcApplicationName",  appName);
            launchArgs.put("orcIdentityName",     identityName);
            launchArgs.put("orcIdentityDisplay",  matchedDisplay);
            launchArgs.put("orcStrategy",         strategy);
            launchArgs.put("orcScore",            serverScore);
            launchArgs.put("orcMatchDetail",      matchDetail != null ? matchDetail : "");
            launchArgs.put("orcRequestedBy",      adminUser);

            WorkflowLaunch wfLaunch = new WorkflowLaunch();
            wfLaunch.setWorkflowName(wf.getName());
            wfLaunch.setWorkflowRef(wf.getName());
            wfLaunch.setCaseName(CASE_PREFIX + nativeIdentity + " -> " + matchedDisplay);
            wfLaunch.setLauncher(adminUser);
            wfLaunch.setVariables(launchArgs);

            Workflower workflower = new Workflower(ctx);
            WorkflowLaunch result = workflower.launch(wfLaunch);
            if (result != null && "Failed".equalsIgnoreCase(result.getStatus())) {
                r.put("status", "error"); r.put("error", "Workflow launch failed - check IIQ logs."); return r;
            }
            String taskResultId = (result != null && result.getTaskResult() != null)
                                  ? result.getTaskResult().getId() : null;

            // Native IIQ AuditEvent for the approval request (verified score).
            String reqNotes = (verified != null)
                    ? ("Approval requested. Verified score " + serverScore + "/100"
                       + (Util.isNullOrEmpty(serverReasons) ? "" : ": " + serverReasons))
                    : "Approval requested. Server-side score verification unavailable - recorded operator-asserted value"
                      + (Util.isNullOrEmpty(matchDetail) ? "" : ". Operator-asserted: " + matchDetail);
            auditEvent(ctx, "OrphanApprovalRequested", nativeIdentity, appName,
                    identityName, serverScore, adminUser, reqNotes);

            log.info("OrphanCorrelatorResource: approval launched for [" + nativeIdentity
                     + "]. Manager=[" + managerName + "] TaskResult=[" + taskResultId + "]");
            r.put("status",        "approval_requested");
            r.put("managerName",   managerName);
            r.put("managerDisplay", managerDisplay);
            r.put("taskResultId",  taskResultId);
            return r;
        } catch (Exception ex) {
            // M1: generic client message; full detail to the server log only.
            log.error("OrphanCorrelatorResource: requestApprovalOne() error for [" + nativeIdentity
                      + "]. " + ex.getMessage(), ex);
            r.put("status", "error");
            r.put("error", "Approval request failed due to an internal error. Check server logs for details.");
            return r;
        }
    }

    // =========================================================================
    // WEIGHTED SCORING MATCH ENGINE (Compass-ready, available-field normalized)
    //
    // For each orphan account we build a logical profile from configurable
    // account attributes, retrieve a BOUNDED candidate set of correlated
    // identities via indexable queries, then score each candidate out of 100
    // using available-field normalization (earned / availableWeight * 100) plus
    // penalties. Fuzzy similarity (Jaro-Winkler / Levenshtein / token-set) is in
    // StringMatch - IIQ ships none of it.
    //
    // Identity-side STRONG attributes (employeeId, department, company) are used
    // ONLY when their attribute name is configured AND present on the identity;
    // absent ones simply drop out of the denominator, so the plugin stays generic
    // across AD, LDAP, JDBC, SCIM, etc. (no required extended attributes).
    //
    //   Signal weights : empID 30 | email/upn 25 | username 20 | displayName 15
    //                    first+last 5 | organisation 5
    //   Penalties      : empID conflict -40 | inactive -30 | service -25
    //                    admin/privileged -20 | no strong identifier -10
    //   Guardrails     : thin-evidence floor (no strong id AND availableWeight<40
    //                    -> score capped at 79/LOW); ambiguity flagged when the
    //                    winner clears the match threshold but the runner-up is
    //                    within `gap`.
    // =========================================================================
    private MatchResult runFuzzyMatch(SailPointContext ctx, Link orphan, String appName)
            throws GeneralException {

        MatchResult result = new MatchResult();
        String nativeId = orphan.getNativeIdentity();
        String dn = firstNonEmpty(attr(orphan, "distinguishedName"), nativeId);
        String dummy = (orphan.getIdentity() != null) ? orphan.getIdentity().getName() : null;

        // ---- account logical profile (configurable attribute candidates) ----
        String acctEmail   = firstAccountValue(orphan, setting(SETTING_EMAIL_ATTRS,   DEF_EMAIL_ATTRS));
        String acctUpn     = firstAccountValue(orphan, setting(SETTING_UPN_ATTRS,     DEF_UPN_ATTRS));
        String acctLogin   = firstAccountValue(orphan, setting(SETTING_LOGIN_ATTRS,   DEF_LOGIN_ATTRS));
        if (Util.isNullOrEmpty(acctLogin)) { acctLogin = cnFromDn(dn); } // AD DN fallback
        String acctFirst   = firstAccountValue(orphan, setting(SETTING_FIRST_ATTRS,   DEF_FIRST_ATTRS));
        String acctLast    = firstAccountValue(orphan, setting(SETTING_LAST_ATTRS,    DEF_LAST_ATTRS));
        String acctDisplay = firstAccountValue(orphan, setting(SETTING_DISPLAY_ATTRS, DEF_DISPLAY_ATTRS));
        String acctEmpId   = firstAccountValue(orphan, setting(SETTING_EMPID_ATTRS,   DEF_EMPID_ATTRS));
        String acctDept    = firstAccountValue(orphan, setting(SETTING_DEPT_ATTRS,    DEF_DEPT_ATTRS));
        String acctCompany = firstAccountValue(orphan, setting(SETTING_COMPANY_ATTRS, DEF_COMPANY_ATTRS));
        String acctManager = firstAccountValue(orphan, setting(SETTING_MANAGER_ATTRS, DEF_MANAGER_ATTRS));

        result.setNativeIdentity(nativeId);
        result.setApplicationName(appName);
        result.setDisplayName(firstNonEmpty(acctDisplay, acctLogin, nativeId));
        result.setDistinguishedName(dn);
        result.setDummyIdentityName(dummy);

        String svcKey = firstNonEmpty(acctLogin, nativeId);
        boolean isService = looksLikeServiceAccount(svcKey);
        boolean isAdmin   = looksLikeAdminAccount(svcKey);
        boolean isTest    = looksLikeTestAccount(svcKey);
        result.setTestAccount(isTest);

        // ---- identity-side extended attribute NAMES (optional, per decision) ----
        String idEmpAttr  = trimToNull(getSettingString(SETTING_ID_EMPID_ATTR));
        String idDeptAttr = trimToNull(getSettingString(SETTING_ID_DEPT_ATTR));
        String idCompAttr = trimToNull(getSettingString(SETTING_ID_COMPANY_ATTR));

        // ---- candidate retrieval (bounded, indexable queries; excludes dummy) ----
        int candLimit = settingInt(SETTING_CAND_LIMIT, 25);
        Map<String, Identity> cands = new LinkedHashMap<String, Identity>();
        collectCandidates(ctx, cands, dummy, candLimit, acctEmail, acctUpn, acctLogin,
                acctFirst, acctLast, acctDisplay, acctEmpId, idEmpAttr);

        if (cands.isEmpty()) {
            result.setStrategy(isService ? MatchResult.STRATEGY_SERVICE : MatchResult.STRATEGY_SCORED);
            result.setScore(0);
            result.setMatchDetail("No candidate identity found for this account.");
            if (isService) { result.addWarning("Account name matches a service-account pattern."); }
            if (isAdmin)   { result.addWarning("Account name matches a privileged-account pattern."); }
            if (isTest)    { result.addWarning("Account name matches a test-account pattern."); }
            return result;
        }

        // ---- score every candidate ----
        int matchThreshold = settingInt(SETTING_MATCH_THRESHOLD, 70);
        int gap            = settingInt(SETTING_GAP, 8);

        List<ScoreCard> cards = new ArrayList<ScoreCard>();
        for (Identity id : cands.values()) {
            cards.add(scoreCandidate(id, acctEmail, acctUpn, acctLogin, acctDisplay,
                    acctFirst, acctLast, acctEmpId, acctDept, acctCompany, acctManager,
                    idEmpAttr, idDeptAttr, idCompAttr, isService, isAdmin, isTest));
        }
        Collections.sort(cards, new Comparator<ScoreCard>() {
            public int compare(ScoreCard a, ScoreCard b) {
                if (b.score != a.score) { return b.score - a.score; }
                return ("" + a.name).compareTo("" + b.name); // deterministic tie-break
            }
        });

        ScoreCard best = cards.get(0);
        int secondScore = (cards.size() > 1) ? cards.get(1).score : 0;

        result.setScore(best.score);
        result.setSecondScore(secondScore);
        result.setStrategy(MatchResult.STRATEGY_SCORED);
        result.setMatchedIdentityId(best.id);
        result.setMatchedIdentityName(best.name);
        result.setMatchedIdentityDisplayName(best.displayName);
        result.setMatchedIdentityEmail(best.email);
        result.setReasons(best.reasons);
        result.setMatchDetail(best.reasons.isEmpty()
                ? ("Best score " + best.score + "/100.")
                : ("Best score " + best.score + "/100: " + joinCsv(best.reasons)));

        if (best.cappedByFloor) {
            result.addWarning("Thin evidence - only weak or sparse signals available; score capped.");
        }
        if (isService) { result.addWarning("Account matches a service-account pattern (penalty applied)."); }
        if (isAdmin)   { result.addWarning("Account matches a privileged-account pattern (penalty applied)."); }
        if (isTest)    { result.addWarning("Account matches a test-account pattern (penalty applied)."); }

        // ---- ambiguity: only meaningful when the winner clears the bar ----
        if (cards.size() > 1 && best.score >= matchThreshold && (best.score - secondScore) < gap) {
            result.setAmbiguous(true);
            result.addWarning("Ambiguous: runner-up scored " + secondScore
                    + " (within " + gap + " of the best). Manual review recommended.");
        }
        return result;
    }

    /**
     * Re-derive the match score for ONE specific identity against an orphan link,
     * server-side, mirroring the profile-building and scoring of runFuzzyMatch().
     * Used at correlate time so the audit record reflects the engine's own score
     * rather than a value asserted by the client (council finding C1). Returns
     * null only if scoring throws - the caller then falls back to the asserted
     * value and records that the verification was unavailable.
     */
    private ScoreCard recomputeScore(SailPointContext ctx, Link orphan, Identity target) {
        try {
            if (orphan == null || target == null) { return null; }
            String nativeId = orphan.getNativeIdentity();
            String dn = firstNonEmpty(attr(orphan, "distinguishedName"), nativeId);

            String acctEmail   = firstAccountValue(orphan, setting(SETTING_EMAIL_ATTRS,   DEF_EMAIL_ATTRS));
            String acctUpn     = firstAccountValue(orphan, setting(SETTING_UPN_ATTRS,     DEF_UPN_ATTRS));
            String acctLogin   = firstAccountValue(orphan, setting(SETTING_LOGIN_ATTRS,   DEF_LOGIN_ATTRS));
            if (Util.isNullOrEmpty(acctLogin)) { acctLogin = cnFromDn(dn); }
            String acctFirst   = firstAccountValue(orphan, setting(SETTING_FIRST_ATTRS,   DEF_FIRST_ATTRS));
            String acctLast    = firstAccountValue(orphan, setting(SETTING_LAST_ATTRS,    DEF_LAST_ATTRS));
            String acctDisplay = firstAccountValue(orphan, setting(SETTING_DISPLAY_ATTRS, DEF_DISPLAY_ATTRS));
            String acctEmpId   = firstAccountValue(orphan, setting(SETTING_EMPID_ATTRS,   DEF_EMPID_ATTRS));
            String acctDept    = firstAccountValue(orphan, setting(SETTING_DEPT_ATTRS,    DEF_DEPT_ATTRS));
            String acctCompany = firstAccountValue(orphan, setting(SETTING_COMPANY_ATTRS, DEF_COMPANY_ATTRS));
            String acctManager = firstAccountValue(orphan, setting(SETTING_MANAGER_ATTRS, DEF_MANAGER_ATTRS));

            String svcKey = firstNonEmpty(acctLogin, nativeId);
            boolean isService = looksLikeServiceAccount(svcKey);
            boolean isAdmin   = looksLikeAdminAccount(svcKey);
            boolean isTest    = looksLikeTestAccount(svcKey);

            String idEmpAttr  = trimToNull(getSettingString(SETTING_ID_EMPID_ATTR));
            String idDeptAttr = trimToNull(getSettingString(SETTING_ID_DEPT_ATTR));
            String idCompAttr = trimToNull(getSettingString(SETTING_ID_COMPANY_ATTR));

            return scoreCandidate(target, acctEmail, acctUpn, acctLogin, acctDisplay,
                    acctFirst, acctLast, acctEmpId, acctDept, acctCompany, acctManager,
                    idEmpAttr, idDeptAttr, idCompAttr, isService, isAdmin, isTest);
        } catch (Exception ex) {
            log.warn("OrphanCorrelatorResource: server-side score recompute failed for ["
                    + (orphan != null ? orphan.getNativeIdentity() : "?") + "]. " + ex.getMessage());
            return null;
        }
    }

    // ----------------------- scoring ----------------------------------------

    /** Per-candidate scoring outcome. */
    private static class ScoreCard {
        String id, name, displayName, email;
        int score;
        boolean cappedByFloor;
        List<String> reasons = new ArrayList<String>();
    }

    private ScoreCard scoreCandidate(Identity id,
            String acctEmail, String acctUpn, String acctLogin, String acctDisplay,
            String acctFirst, String acctLast, String acctEmpId, String acctDept,
            String acctCompany, String acctManager,
            String idEmpAttr, String idDeptAttr, String idCompAttr,
            boolean isService, boolean isAdmin, boolean isTest) {

        ScoreCard sc = new ScoreCard();
        sc.id          = id.getId();
        sc.name        = id.getName();
        sc.displayName = id.getDisplayName();
        sc.email       = id.getEmail();

        double earned = 0, available = 0, penalty = 0, usernameEarned = 0;
        boolean strong = false;

        // Employee ID (30) - exact only. Missing = neutral; wrong = dangerous.
        String idEmp = (idEmpAttr != null) ? idAttr(id, idEmpAttr) : null;
        if (!Util.isNullOrEmpty(acctEmpId) && !Util.isNullOrEmpty(idEmp)) {
            available += 30;
            if (StringMatch.normId(acctEmpId).equals(StringMatch.normId(idEmp))) {
                earned += 30; strong = true; sc.reasons.add("Employee ID exact match");
            } else {
                penalty += 40; sc.reasons.add("Employee ID present but DIFFERENT (heavy penalty)");
            }
        }

        // Email / UPN (25)
        String idEmail = id.getEmail();
        if ((!Util.isNullOrEmpty(acctEmail) || !Util.isNullOrEmpty(acctUpn)) && !Util.isNullOrEmpty(idEmail)) {
            available += 25;
            double e = emailScore(acctEmail, acctUpn, idEmail, sc);
            earned += e;
            if (e >= 20) { strong = true; }
        }

        // Username (20)
        String idName = id.getName();
        if (!Util.isNullOrEmpty(acctLogin) && !Util.isNullOrEmpty(idName)) {
            available += 20;
            double u = usernameScore(acctLogin, idName, sc);
            earned += u;
            usernameEarned = u;
            if (u >= 20) { strong = true; }
        }

        // Display name (15)
        String idDisplay = id.getDisplayName();
        if (!Util.isNullOrEmpty(acctDisplay) && !Util.isNullOrEmpty(idDisplay)) {
            available += 15;
            earned += displayScore(acctDisplay, idDisplay, id.getFirstname(), id.getLastname(), sc);
        }

        // First + last (5)
        boolean acctHasName = !Util.isNullOrEmpty(acctFirst) || !Util.isNullOrEmpty(acctLast);
        boolean idHasName   = !Util.isNullOrEmpty(id.getFirstname()) || !Util.isNullOrEmpty(id.getLastname());
        if (acctHasName && idHasName) {
            available += 5;
            earned += firstLastScore(acctFirst, acctLast, id.getFirstname(), id.getLastname(), sc);
        }

        // Organisation (5) - supporting signal only
        String idDept = (idDeptAttr != null) ? idAttr(id, idDeptAttr) : null;
        String idComp = (idCompAttr != null) ? idAttr(id, idCompAttr) : null;
        String idMgr  = (id.getManager() != null)
                ? firstNonEmpty(id.getManager().getDisplayName(), id.getManager().getName(), id.getManager().getEmail())
                : null;
        // Each org field contributes to availableWeight ONLY when both sides have
        // that specific field - so an account with a department isn't penalised
        // because the identity only records a manager.
        if (!Util.isNullOrEmpty(acctManager) && !Util.isNullOrEmpty(idMgr)) {
            available += 2;
            if (StringMatch.normName(acctManager).equals(StringMatch.normName(idMgr))) {
                earned += 2; sc.reasons.add("Same manager");
            }
        }
        if (!Util.isNullOrEmpty(acctDept) && !Util.isNullOrEmpty(idDept)) {
            available += 2;
            if (StringMatch.normName(acctDept).equals(StringMatch.normName(idDept))) {
                earned += 2; sc.reasons.add("Same department");
            }
        }
        if (!Util.isNullOrEmpty(acctCompany) && !Util.isNullOrEmpty(idComp)) {
            available += 1;
            if (StringMatch.normName(acctCompany).equals(StringMatch.normName(idComp))) {
                earned += 1; sc.reasons.add("Same company");
            }
        }

        // Penalties
        if (id.isInactive()) { penalty += 30; sc.reasons.add("Identity is inactive/terminated (penalty)"); }
        if (isService) { penalty += 25; }
        if (isAdmin)   { penalty += 20; }
        if (isTest)    { penalty += 15; }   // lighter than service - a test account may map to a real person
        if (!strong)   { penalty += 10; }

        // Available-field normalization + clamp
        double base = (available > 0) ? (earned / available * 100.0) : 0.0;
        double finalScore = base - penalty;

        // Thin-evidence floor: no strong identifier AND sparse signals -> cap at LOW.
        if (!strong && available < 40 && finalScore > 79) {
            finalScore = 79; sc.cappedByFloor = true;
        }
        // Username-only match (decision 1c): an exact username is "strong" enough
        // to skip the floor, but a normalized-username match with NOTHING else
        // corroborating is too collision-prone for VERY_HIGH - cap it at HIGH (94).
        boolean corroborated = (earned - usernameEarned) > 0.0;
        if (usernameEarned >= 20 && !corroborated && finalScore > 94) {
            finalScore = 94;
            sc.reasons.add("Username-only match - capped at HIGH (no corroborating signal)");
        }
        if (finalScore > 100) { finalScore = 100; }
        if (finalScore < 0)   { finalScore = 0; }

        sc.score = (int) Math.round(finalScore);
        return sc;
    }

    /** Email/UPN signal (max 25). */
    private double emailScore(String acctEmail, String acctUpn, String idEmail, ScoreCard sc) {
        String ie = idEmail.trim().toLowerCase();
        if (!Util.isNullOrEmpty(acctEmail) && acctEmail.trim().toLowerCase().equals(ie)) {
            sc.reasons.add("Email exact match"); return 25;
        }
        if (!Util.isNullOrEmpty(acctUpn) && acctUpn.trim().toLowerCase().equals(ie)) {
            sc.reasons.add("UPN equals identity email"); return 23;
        }
        String idLocal   = StringMatch.emailLocalPart(idEmail);
        String acctLocal = firstNonEmpty(StringMatch.emailLocalPart(acctEmail), StringMatch.emailLocalPart(acctUpn));
        if (!Util.isNullOrEmpty(acctLocal) && !Util.isNullOrEmpty(idLocal)) {
            if (acctLocal.equals(idLocal)) { sc.reasons.add("Email local-part exact match"); return 20; }
            double jw = StringMatch.jaroWinkler(StringMatch.normLogin(acctLocal), StringMatch.normLogin(idLocal));
            if (jw >= 0.90) { sc.reasons.add("Email local-part strong fuzzy match"); return 10 + (jw - 0.90) / 0.10 * 8; }
        }
        return 0;
    }

    /** Username signal (max 20). */
    private double usernameScore(String acctLogin, String idName, ScoreCard sc) {
        String a = StringMatch.normLogin(acctLogin);
        String b = StringMatch.normLogin(idName);
        if (a.isEmpty() || b.isEmpty()) { return 0; }
        if (a.equals(b)) { sc.reasons.add("Username exact (normalized) match"); return 20; }
        double sim = Math.max(StringMatch.jaroWinkler(a, b), StringMatch.levenshteinRatio(a, b));
        if (sim >= 0.93) { sc.reasons.add("Username very strong fuzzy match"); return 16 + (sim - 0.93) / 0.07 * 3; }
        if (sim >= 0.85) { sc.reasons.add("Username medium fuzzy match");      return 10 + (sim - 0.85) / 0.08 * 5; }
        if (sim >= 0.70) { sc.reasons.add("Username weak fuzzy match");        return  1 + (sim - 0.70) / 0.15 * 8; }
        return 0;
    }

    /** Display-name signal (max 15), token-aware for name-order differences. */
    private double displayScore(String acctDisplay, String idDisplay, String idFirst,
                                String idLast, ScoreCard sc) {
        String ad = StringMatch.normName(acctDisplay);
        List<String> idForms = new ArrayList<String>();
        if (!Util.isNullOrEmpty(idDisplay)) { idForms.add(StringMatch.normName(idDisplay)); }
        if (!Util.isNullOrEmpty(idFirst) && !Util.isNullOrEmpty(idLast)) {
            idForms.add(StringMatch.normName(idFirst + " " + idLast));
        }
        boolean token = false; double bestSim = 0;
        for (String f : idForms) {
            if (ad.equals(f)) { sc.reasons.add("Display name exact match"); return 15; }
            if (StringMatch.tokenSetRatio(acctDisplay, f) >= 1.0) { token = true; }
            bestSim = Math.max(bestSim, StringMatch.jaroWinkler(ad.replace(" ", ""), f.replace(" ", "")));
        }
        if (token)           { sc.reasons.add("Display name token match");        return 14; }
        if (bestSim >= 0.85) { sc.reasons.add("Display name strong fuzzy match"); return 10 + (bestSim - 0.85) / 0.10 * 2; }
        if (bestSim >= 0.70) { sc.reasons.add("Display name partial match");      return  5 + (bestSim - 0.70) / 0.15 * 4; }
        return 0;
    }

    /** First/last-name signal (max 5), supporting evidence only. */
    private double firstLastScore(String af, String al, String idf, String idl, ScoreCard sc) {
        boolean fMatch = !Util.isNullOrEmpty(af) && !Util.isNullOrEmpty(idf)
                && StringMatch.normName(af).equals(StringMatch.normName(idf));
        boolean lMatch = !Util.isNullOrEmpty(al) && !Util.isNullOrEmpty(idl)
                && StringMatch.normName(al).equals(StringMatch.normName(idl));
        if (fMatch && lMatch) { sc.reasons.add("First + last name match"); return 5; }
        if (lMatch)           { sc.reasons.add("Last name match");        return 3; }
        if (fMatch)           { sc.reasons.add("First name match");       return 2; }
        double f = (!Util.isNullOrEmpty(af) && !Util.isNullOrEmpty(idf))
                ? StringMatch.jaroWinkler(StringMatch.normLogin(af), StringMatch.normLogin(idf)) : 0;
        double l = (!Util.isNullOrEmpty(al) && !Util.isNullOrEmpty(idl))
                ? StringMatch.jaroWinkler(StringMatch.normLogin(al), StringMatch.normLogin(idl)) : 0;
        if (f >= 0.9 && l >= 0.9) { sc.reasons.add("First/last fuzzy match"); return 4; }
        if (f >= 0.9 || l >= 0.9) { return 1; }
        return 0;
    }

    // ----------------------- candidate retrieval ----------------------------

    /**
     * Populate `out` with a bounded, deduplicated set of correlated candidate
     * identities via indexable queries. Fuzzy similarity is NOT done at the DB
     * layer (no Filter can express Jaro-Winkler) - we retrieve by exact/prefix
     * keys here, then score in memory. The dummy owner is always excluded.
     */
    private void collectCandidates(SailPointContext ctx, Map<String, Identity> out, String dummy,
            int limit, String acctEmail, String acctUpn, String acctLogin, String acctFirst,
            String acctLast, String acctDisplay, String acctEmpId, String idEmpAttr)
            throws GeneralException {

        if (!Util.isNullOrEmpty(acctEmail)) {
            addCandidates(out, queryIdentities(ctx, Filter.ignoreCase(Filter.eq("email", acctEmail)), dummy, 5), limit);
        }
        if (out.size() < limit && !Util.isNullOrEmpty(acctUpn)) {
            addCandidates(out, queryIdentities(ctx, Filter.ignoreCase(Filter.eq("email", acctUpn)), dummy, 5), limit);
        }
        if (out.size() < limit && !Util.isNullOrEmpty(acctLogin)) {
            addCandidates(out, queryIdentities(ctx, Filter.ignoreCase(Filter.eq("name", acctLogin)), dummy, 5), limit);
        }
        // employeeId requires a SEARCHABLE identity attribute - guard against
        // misconfiguration so a non-searchable attr name can't abort the scan.
        if (out.size() < limit && !Util.isNullOrEmpty(acctEmpId) && !Util.isNullOrEmpty(idEmpAttr)) {
            try {
                addCandidates(out, queryIdentities(ctx,
                        Filter.ignoreCase(Filter.eq(idEmpAttr, acctEmpId)), dummy, 5), limit);
            } catch (Exception ex) {
                log.warn("OrphanCorrelatorResource: employeeId candidate query failed (is ["
                        + idEmpAttr + "] a searchable identity attribute?). Skipping. " + ex.getMessage());
            }
        }
        if (out.size() < limit && !Util.isNullOrEmpty(acctFirst) && !Util.isNullOrEmpty(acctLast)) {
            addCandidates(out, queryIdentities(ctx, Filter.and(
                    Filter.ignoreCase(Filter.eq("firstname", acctFirst)),
                    Filter.ignoreCase(Filter.eq("lastname", acctLast))), dummy, 10), limit);
        }
        if (out.size() < limit && !Util.isNullOrEmpty(acctDisplay)) {
            addCandidates(out, queryIdentities(ctx,
                    Filter.ignoreCase(Filter.eq("displayName", acctDisplay)), dummy, 10), limit);
        }
        if (out.size() < limit && !Util.isNullOrEmpty(acctLogin)) {
            String[] toks = acctLogin.split("[._\\-\\s@\\\\]");
            String token = (toks.length > 0) ? toks[0] : acctLogin;
            if (token.length() >= 3) {
                addCandidates(out, queryIdentities(ctx,
                        Filter.ignoreCase(Filter.like("name", token, Filter.MatchMode.START)), dummy, 10), limit);
            }
        }
    }

    private void addCandidates(Map<String, Identity> out, List<Identity> found, int limit) {
        if (found == null) { return; }
        for (Identity i : found) {
            if (out.size() >= limit) { return; }
            if (i != null && i.getName() != null && !out.containsKey(i.getName())) {
                out.put(i.getName(), i);
            }
        }
    }

    private List<Identity> queryIdentities(SailPointContext ctx, Filter f, String dummy, int limit)
            throws GeneralException {
        QueryOptions qo = new QueryOptions();
        qo.addFilter(f);
        qo.addFilter(Filter.eq("correlated", new Boolean(true)));
        if (dummy != null) { qo.addFilter(Filter.ne("name", dummy)); }
        qo.setResultLimit(limit);
        return ctx.getObjects(Identity.class, qo);
    }

    /** Null-safe read of a Link attribute as a String. */
    private String attr(Link l, String name) {
        Object v = (l != null) ? l.getAttribute(name) : null;
        return (v != null) ? v.toString().trim() : null;
    }

    /** First non-empty account value among a comma-separated candidate attr list. */
    private String firstAccountValue(Link l, String csv) {
        if (Util.isNullOrEmpty(csv)) { return null; }
        for (String name : csv.split(",")) {
            name = name.trim();
            if (name.isEmpty()) { continue; }
            String v = "nativeIdentity".equals(name) ? l.getNativeIdentity() : attr(l, name);
            if (!Util.isNullOrEmpty(v)) { return v.trim(); }
        }
        return null;
    }

    /** Plugin setting value, or the supplied default when unset. */
    private String setting(String key, String def) {
        String v = getSettingString(key);
        return Util.isNullOrEmpty(v) ? def : v;
    }

    /** First non-empty string from the arguments. */
    private String firstNonEmpty(String... vals) {
        if (vals != null) {
            for (String v : vals) { if (!Util.isNullOrEmpty(v)) { return v; } }
        }
        return null;
    }

    /** Extract the leading RDN value of a DN (CN=foo,OU=... -> foo). */
    private String cnFromDn(String dn) {
        if (Util.isNullOrEmpty(dn)) { return null; }
        String first = dn.split(",")[0];
        int eq = first.indexOf('=');
        return (eq >= 0) ? first.substring(eq + 1).trim() : first.trim();
    }

    /** Find the single Link for (nativeIdentity, application); null if none. */
    private Link findLink(SailPointContext ctx, String nativeIdentity, String appName)
            throws GeneralException {
        QueryOptions qo = new QueryOptions();
        qo.addFilter(Filter.eq("nativeIdentity", nativeIdentity));
        qo.addFilter(Filter.eq("application.name", appName));
        Iterator<Link> it = ctx.search(Link.class, qo);
        try {
            return (it != null && it.hasNext()) ? it.next() : null;
        } finally {
            Util.flushIterator(it);
        }
    }

    /** Plugin int setting, or the supplied default when unset/invalid. */
    private int settingInt(String key, int def) {
        try {
            String v = getSettingString(key);
            if (!Util.isNullOrEmpty(v)) { return Integer.parseInt(v.trim()); }
        } catch (NumberFormatException nfe) {
            log.warn("OrphanCorrelatorResource: invalid int setting [" + key + "]; using " + def);
        }
        return def;
    }

    /** Trim, returning null for null/blank. */
    private String trimToNull(String s) {
        if (s == null) { return null; }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** Null-safe read of an Identity attribute (standard or extended) as String. */
    private String idAttr(Identity id, String name) {
        if (id == null || Util.isNullOrEmpty(name)) { return null; }
        Object v = id.getAttribute(name);
        return (v != null) ? v.toString().trim() : null;
    }

    /** Join a list into a comma-separated string (no external Util dependency). */
    private String joinCsv(List<String> items) {
        if (items == null || items.isEmpty()) { return ""; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) { sb.append(", "); }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * Whole-token pattern match. The key is split on any non-alphanumeric run
     * (space, '.', '_', '-', '\\', '@', '/', etc.) into tokens, and a pattern
     * fires ONLY when it equals a complete token. This prevents false positives
     * from fragments inside real names: e.g. "sam.payne" -> tokens [sam, payne],
     * so the service pattern "sa" and the admin pattern "pa" no longer match a
     * human account. Real cases still work: "sa" (the literal account), "svc_x"
     * -> [svc, x], "admin.john" -> [admin, john], "batch-load" -> [batch, load].
     * Trade-off: a pattern concatenated WITHOUT a delimiter (e.g. "svcbackup")
     * is not flagged - add an explicit pattern or rely on delimited naming.
     */
    private boolean nameHasPattern(String key, String patternsCsv) {
        if (Util.isNullOrEmpty(key) || Util.isNullOrEmpty(patternsCsv)) { return false; }
        String[] toks = key.toLowerCase().split("[^a-z0-9]+");
        java.util.Set<String> tokens = new java.util.HashSet<String>(java.util.Arrays.asList(toks));
        for (String p : patternsCsv.split(",")) {
            p = p.trim().toLowerCase();
            if (!p.isEmpty() && tokens.contains(p)) { return true; }
        }
        return false;
    }

    /** Configurable service/non-human account heuristic (now a scoring penalty). */
    private boolean looksLikeServiceAccount(String key) {
        return nameHasPattern(key, setting(SETTING_SERVICE_PATTS, DEF_SERVICE_PATTS));
    }

    /** Configurable admin/privileged account heuristic (scoring penalty). */
    private boolean looksLikeAdminAccount(String key) {
        return nameHasPattern(key, setting(SETTING_ADMIN_PATTS, DEF_ADMIN_PATTS));
    }

    /** Configurable test/non-production account heuristic (own flag + penalty). */
    private boolean looksLikeTestAccount(String key) {
        return nameHasPattern(key, setting(SETTING_TEST_PATTS, DEF_TEST_PATTS));
    }

    /**
     * Write a native IIQ AuditEvent so correlation activity shows up in IIQ's
     * own audit search and standard reports, and works on ANY database (no
     * custom table required). Non-blocking - an audit failure never breaks the
     * correlation.
     */
    private void auditEvent(SailPointContext ctx, String action, String target,
                            String app, String matched, String score,
                            String adminUser, String detail) {
        try {
            AuditEvent ev = new AuditEvent();
            ev.setSource(!Util.isNullOrEmpty(adminUser) ? adminUser : PLUGIN_NAME);
            ev.setAction(action);
            ev.setTarget(target);
            ev.setApplication(app);
            ev.setString1(matched);
            ev.setString2(score);
            ev.setString3(detail);
            ctx.saveObject(ev);
            ctx.commitTransaction();
        } catch (Exception ex) {
            log.warn("OrphanCorrelatorResource: AuditEvent write failed for [" + target + "]. " + ex.getMessage());
        }
    }

    /**
     * Returns a 500 response with a SAFE, generic message (no internal details).
     * Full exception detail is already logged server-side by the caller.
     */
    private Response internalError(String operation) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("error", "An internal error occurred during [" + operation
                + "]. Check server logs for details.");
        return Response.serverError().entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Returns a response with the given status whose message is serialized via
     * the JAX-RS Map serializer — never hand-built JSON. This keeps any quotes,
     * backslashes or braces in user-supplied values (app/identity names) from
     * corrupting the response body.
     */
    private Response jsonError(Response.Status status, String message) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("error", message);
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Returns a 400/500 response whose message is serialized properly via
     * the JAX-RS Map serializer — no string concatenation, no JSON injection.
     */
    private Response errorResponse(Object message) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("error", message != null ? message.toString() : "Unknown error");
        return Response.serverError().entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    private List<Map<String, Object>> toMapList(List<MatchResult> results) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (MatchResult r : results) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("nativeIdentity",            r.getNativeIdentity());
            m.put("applicationName",           r.getApplicationName());
            m.put("displayName",               r.getDisplayName());
            m.put("distinguishedName",         r.getDistinguishedName());
            m.put("dummyIdentityName",         r.getDummyIdentityName());
            m.put("matchedIdentityId",         r.getMatchedIdentityId());
            m.put("matchedIdentityName",       r.getMatchedIdentityName());
            m.put("matchedIdentityDisplayName",r.getMatchedIdentityDisplayName());
            m.put("matchedIdentityEmail",      r.getMatchedIdentityEmail());
            m.put("strategy",                  r.getStrategy());
            m.put("matchDetail",               r.getMatchDetail());
            m.put("score",                     Integer.valueOf(r.getScore()));
            m.put("secondScore",               Integer.valueOf(r.getSecondScore()));
            m.put("ambiguous",                 Boolean.valueOf(r.isAmbiguous()));
            m.put("reasons",                   r.getReasons());
            m.put("warnings",                  r.getWarnings());
            m.put("approvalPending",           Boolean.valueOf(r.isApprovalPending()));
            m.put("testAccount",               Boolean.valueOf(r.isTestAccount()));
            m.put("errorMessage",              r.getErrorMessage());
            list.add(m);
        }
        return list;
    }
}