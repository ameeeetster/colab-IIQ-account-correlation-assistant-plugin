/**
 * Orphan Account Correlator - Angular controller
 *
 * REST endpoints:
 *   GET  /identityiq/plugin/rest/orphan/applications
 *   GET  /identityiq/plugin/rest/orphan/scan
 *   POST /identityiq/plugin/rest/orphan/correlate
 *   POST /identityiq/plugin/rest/orphan/request-approval
 */
(function () {
    'use strict';

    function initOrphanCorrelator() {
    angular.module('OrphanCorrelatorApp', [])

    .controller('OrphanCorrelatorCtrl', ['$scope', '$http', function ($scope, $http) {

        var BASE = SailPoint.CONTEXT_PATH + '/plugin/rest/orphan';

        /* CSRF: IIQ stores its token in a cookie named 'CSRF-TOKEN' and validates
           the 'X-XSRF-TOKEN' header (see IIQ's appPageBegin.js). AngularJS defaults
           to an 'XSRF-TOKEN' cookie, so without these two lines the token is never
           sent and IIQ's CsrfService rejects every plugin REST call
           ("Failed to load applications", scan/correlate/approval all blocked).
           Setting the cookie/header names to match IIQ makes $http attach the
           token automatically on every same-origin request (GET and POST). */
        $http.defaults.xsrfCookieName = 'CSRF-TOKEN';
        $http.defaults.xsrfHeaderName = 'X-XSRF-TOKEN';

        /* Whether the current user may perform correlation actions. Defaults to
           false until the permissions endpoint answers, so buttons never flash
           for a view-only user. */
        $scope.canCorrelate = false;
        $http.get(BASE + '/permissions')
            .then(function (res) { $scope.canCorrelate = !!(res.data && res.data.canCorrelate); })
            .catch(function ()   { $scope.canCorrelate = false; });

        /* ---- State ---- */
        $scope.applications           = [];
        $scope.selectedApp            = null;
        $scope.appsLoading            = false;
        $scope.appsError              = null;
        $scope.lastScannedApp         = null;
        $scope.results                = [];
        $scope.scanning               = false;
        $scope.scanRun                = false;
        $scope.scanError              = null;
        $scope.matchThreshold         = 70;   // overwritten by the scan response

        /* ---- Chunked scan progress + cancel ----
           Large applications are scanned in PAGES (the server caps each request),
           accumulated client-side behind a progress bar so the page never freezes
           and no single request times out. */
        $scope.scanBatch     = 500;                 // accounts requested per page
        $scope.scanProgress  = { done: 0, total: 0 };
        $scope.scanCancel    = false;
        $scope.scanCancelled = false;

        /* ---- Results table paging (render only one page of rows) ----
           Even after fetching thousands of rows, dumping them all into the DOM
           would bog the browser down. We render displayPageSize rows at a time. */
        $scope.displayPage     = 1;
        $scope.displayPageSize = 25;
        $scope.pageSizes       = [10, 25, 50, 100];
        $scope.activeFilter           = 'ALL';
        $scope.pendingCorrelation     = null;
        $scope.correlating            = false;
        $scope.correlateError         = null;
        $scope.pendingApprovalRequest = null;
        $scope.requestingApproval     = false;
        $scope.approvalRequestError   = null;

        /* ---- Advanced (per-column) filters ---- */
        $scope.showFilters = false;
        $scope.filters = { account: '', identity: '', strategy: '', detail: '', confidence: '' };
        $scope.strategyOptions = ['Weighted Score', 'Service/Bot'];

        /* Confidence level by score: High 80-100, Medium 50-79, Low below 50. */
        $scope.scoreClass = function (r) {
            if (!r || !r.matchedIdentityName) { return 'orc-score-none'; }
            var s = r.score || 0;
            if (s >= 80) { return 'orc-score-high'; }
            if (s >= 50) { return 'orc-score-med'; }
            return 'orc-score-low';
        };
        $scope.scoreLabel = function (r) {
            if (!r || !r.matchedIdentityName) { return ''; }
            var s = r.score || 0;
            return (s >= 80) ? 'High' : (s >= 50 ? 'Medium' : 'Low');
        };
        /* Deep-link to the matched identity's IIQ page. Built RELATIVE to the IIQ
           context path via SailPoint.CONTEXT_PATH (e.g. /identityiq) - so the host,
           port and context are inherited from whatever environment the user is on.
           Nothing here is environment-specific, so it works unchanged in any client
           deployment. Returns '' when there's no match so the template renders plain
           text instead of a dead link. */
        $scope.identityUrl = function (r) {
            if (!r || !r.matchedIdentityId) { return ''; }
            return SailPoint.CONTEXT_PATH + '/define/identity/identity.jsf?id=' + encodeURIComponent(r.matchedIdentityId);
        };
        /* Orphan count for the currently-selected application (for the badge
           beside the dropdown). Reads from the already-loaded applications list,
           so no extra request. */
        $scope.selectedAppOrphans = function () {
            var apps = $scope.applications || [];
            for (var i = 0; i < apps.length; i++) {
                if (apps[i] && apps[i].name === $scope.selectedApp) { return apps[i].orphanCount || 0; }
            }
            return 0;
        };
        /* The application <select> lives inside an ng-if block (child scope), so an
           ng-model write to the primitive selectedApp would land on the child scope
           and never reach the controller - runScan() would then keep scanning the
           previously-selected app. This handler writes the choice to the CONTROLLER
           scope (via closure) so the selection actually takes effect. */
        $scope.setSelectedApp = function (name) { $scope.selectedApp = name; };
        /* Category counts - same definitions as recalcSummary(), so the chips,
           KPI cards, summary and table always agree. service = Service/Bot;
           matched = has identity & non-service (includes ambiguous); unresolved =
           non-service with no identity; ambiguous = subset of matched. */
        function isServiceRow(r)  { return r.strategy === 'Service/Bot'; }
        $scope.countMatched    = function () { return $scope.results.filter(function (r) { return !isServiceRow(r) && !!r.matchedIdentityName; }).length; };
        $scope.countUnresolved = function () { return $scope.results.filter(function (r) { return !isServiceRow(r) && !r.matchedIdentityName; }).length; };
        $scope.countService    = function () { return $scope.results.filter(isServiceRow).length; };
        $scope.countAmbiguous  = function () { return $scope.results.filter(function (r) { return r.ambiguous; }).length; };

        $scope.toggleFilters = function () { $scope.showFilters = !$scope.showFilters; };
        $scope.clearFilters = function () {
            $scope.filters = { account: '', identity: '', strategy: '', detail: '', confidence: '' };
            $scope.activeFilter = 'ALL';
        };
        $scope.activeFilterCount = function () {
            var f = $scope.filters, n = 0;
            if (f.account)    { n++; }
            if (f.identity)   { n++; }
            if (f.strategy)   { n++; }
            if (f.detail)     { n++; }
            if (f.confidence) { n++; }
            if ($scope.activeFilter !== 'ALL') { n++; }
            return n;
        };

        /* ---- Applications dropdown ---- */
        $scope.loadApplications = function () {
            $scope.appsLoading = true;
            $scope.appsError   = null;
            $http.get(BASE + '/applications')
                .then(function (res) {
                    $scope.applications = res.data || [];
                    if (!$scope.selectedApp && $scope.applications.length > 0) {
                        var def = $scope.applications.find(function (a) {
                            return a.isDefault && a.orphanCount > 0;
                        });
                        if (!def) {
                            def = $scope.applications.slice().sort(function (a, b) {
                                return b.orphanCount - a.orphanCount;
                            })[0];
                        }
                        $scope.selectedApp = def ? def.name : null;
                    }
                    $scope.appsLoading = false;
                })
                .catch(function (err) {
                    $scope.appsLoading = false;
                    $scope.appsError = (err.data && err.data.error)
                        ? err.data.error : 'Failed to load applications (' + err.status + ')';
                });
        };

        /* ---- Scan (chunked) ---- */
        $scope.runScan = function () {
            if (!$scope.selectedApp) { $scope.scanError = 'Please select an application first.'; return; }
            $scope.scanning = true;
            $scope.scanError = null;
            $scope.results = [];
            $scope.summary = emptySummary();
            $scope.scanRun = false;
            $scope.activeFilter = 'ALL';
            $scope.clearFilters();
            $scope.selected = {};
            $scope.bulkSummary = null;
            $scope.bulkApprovalSummary = null;
            $scope.approvalToast = null;
            $scope.lastScannedApp = $scope.selectedApp;
            $scope.scanCancel = false;
            $scope.scanCancelled = false;
            $scope.scanProgress = { done: 0, total: 0 };
            $scope.displayPage = 1;

            fetchScanPage(0);
        };

        $scope.cancelScan = function () { $scope.scanCancel = true; };

        /* Fetch one page, accumulate, then recurse for the next until the server
           says there are no more (or the user cancels). Recursion is through the
           promise chain, so there's no stack growth. */
        function fetchScanPage(offset) {
            $http.get(BASE + '/scan', {
                params: { application: $scope.lastScannedApp, offset: offset, limit: $scope.scanBatch }
            })
            .then(function (res) {
                var data = res.data || {};
                var page = data.results || (Array.isArray(data) ? data : []);
                page.forEach(function (r) { if (r.approvalPending) { r.approvalStatus = 'pending'; } });
                $scope.results = $scope.results.concat(page);
                if (data.matchThreshold) { $scope.matchThreshold = data.matchThreshold; }
                $scope.scanProgress = {
                    done:  $scope.results.length,
                    total: (typeof data.total === 'number') ? data.total : $scope.results.length
                };

                var next = (data.offset || 0) + (data.returned || page.length);
                if (data.hasMore && !$scope.scanCancel && page.length > 0) {
                    fetchScanPage(next);            // keep going
                } else {
                    finishScan();                  // done or cancelled
                }
            })
            .catch(function (err) {
                $scope.scanning = false;
                $scope.scanRun  = true;
                $scope.scanError = (err.data && err.data.error)
                    ? err.data.error : 'Scan failed (' + err.status + ')';
            });
        }

        function finishScan() {
            sortByBestMatch();
            try { $scope.scannedAt = new Date().toLocaleString(); } catch (e) { $scope.scannedAt = ''; }
            recalcSummary();
            $scope.scanRun = true;
            $scope.scanning = false;
            $scope.scanCancelled = $scope.scanCancel;
            // Keep the application dropdown's orphan counts in sync with the results
            // just produced - otherwise the selector keeps showing the pre-correlation
            // count (e.g. "10 orphans") while the tiles show the new total.
            $scope.loadApplications();
        }

        /* Order the results so the admin sees the actionable ones first: matched
           accounts by descending score, then no-match / service accounts. Stable
           tie-break on account name. Done once per scan (not per digest). */
        function sortKey(r) {
            return (r && r.matchedIdentityName) ? (r.score || 0) : -1;  // unmatched sink below score 0
        }
        function sortByBestMatch() {
            $scope.results.sort(function (a, b) {
                var ka = sortKey(a), kb = sortKey(b);
                if (kb !== ka) { return kb - ka; }
                return ('' + (a.nativeIdentity || '')).localeCompare('' + (b.nativeIdentity || ''));
            });
        }

        $scope.loadApplications();

        /* ---- Quick confidence filter (pills) ---- */
        $scope.setFilter = function (f) { $scope.activeFilter = f; };

        function contains(hay, needle) {
            return ('' + (hay || '')).toLowerCase().indexOf(('' + (needle || '')).toLowerCase()) >= 0;
        }

        /* Single category model shared by the filter chips. Definitions match
           recalcSummary() and the count helpers exactly. */
        function inCategory(r, af) {
            var service = r.strategy === 'Service/Bot';
            if (af === 'ALL')        { return true; }
            if (af === 'SERVICE')    { return service; }
            if (af === 'AMBIGUOUS')  { return !!r.ambiguous; }
            if (af === 'MATCHED')    { return !service && !!r.matchedIdentityName; }
            if (af === 'UNRESOLVED') { return !service && !r.matchedIdentityName; }
            return true;
        }

        $scope.filteredResults = function () {
            var f = $scope.filters, af = $scope.activeFilter;
            return $scope.results.filter(function (r) {
                if (!inCategory(r, af)) { return false; }
                if (f.strategy && (r.strategy || '') !== f.strategy) { return false; }
                if (f.account && !contains(r.nativeIdentity, f.account) && !contains(r.displayName, f.account)) { return false; }
                if (f.identity && !contains(r.matchedIdentityDisplayName, f.identity)
                        && !contains(r.matchedIdentityName, f.identity)
                        && !contains(r.matchedIdentityEmail, f.identity)) { return false; }
                if (f.confidence) {
                    if (!r.matchedIdentityName) { return false; }
                    var s = r.score || 0;
                    var band = (s >= 80) ? 'High' : (s >= 50 ? 'Medium' : 'Low');
                    if (band !== f.confidence) { return false; }
                }
                if (f.detail && !contains(r.matchDetail, f.detail)) { return false; }
                return true;
            });
        };

        /* ---- Results table paging (render one page of filtered rows) ---- */
        $scope.displayPageCount = function () {
            return Math.max(1, Math.ceil($scope.filteredResults().length / $scope.displayPageSize));
        };
        $scope.pagedResults = function () {
            var all = $scope.filteredResults();
            var pg  = Math.min($scope.displayPage, Math.max(1, Math.ceil(all.length / $scope.displayPageSize)));
            var start = (pg - 1) * $scope.displayPageSize;
            return all.slice(start, start + $scope.displayPageSize);
        };
        $scope.gotoPage = function (p) {
            var max = $scope.displayPageCount();
            if (p < 1) { p = 1; }
            if (p > max) { p = max; }
            $scope.displayPage = p;
        };
        /* Page-size change handler. The <select> lives inside an ng-if (child
           scope), so an ng-model write to the primitive displayPageSize would
           land on the child scope and never reach the controller. We pass the
           selected value into this function, whose closure writes it to the
           CONTROLLER scope - so pagedResults() actually picks it up. */
        $scope.setPageSize = function (sz) {
            $scope.displayPageSize = Number(sz) || 25;
            $scope.displayPage = 1;
        };
        /* "1-25 of 72" style range label for the current page. */
        $scope.pageRangeLabel = function () {
            var n = $scope.filteredResults().length;
            if (n === 0) { return '0 of 0'; }
            var size = $scope.displayPageSize;
            var from = ($scope.displayPage - 1) * size + 1;
            var to   = Math.min($scope.displayPage * size, n);
            return from + '-' + to + ' of ' + n;
        };
        /* Windowed list of page numbers to show (max 5 around the current page),
           so a 200-page result set doesn't render 200 buttons. */
        $scope.pageWindow = function () {
            var total = $scope.displayPageCount(), cur = $scope.displayPage;
            var start = Math.max(1, cur - 2), end = Math.min(total, cur + 2);
            while ((end - start) < 4 && (start > 1 || end < total)) {
                if (start > 1) { start--; } else if (end < total) { end++; } else { break; }
            }
            var arr = [];
            for (var i = start; i <= end; i++) { arr.push(i); }
            return arr;
        };
        /* Any filter change resets to page 1 so you're never stranded on an empty
           page. ng-model typing on the per-column filters is caught by the watch. */
        $scope.$watchCollection('filters', function () { $scope.displayPage = 1; });
        $scope.$watch('activeFilter',   function () { $scope.displayPage = 1; });
        $scope.$watch('displayPageSize', function () { $scope.displayPage = 1; }); // page-size change


        /* ---- Summary (computed ONCE per scan; never per-digest) ----
           Binding chart data to functions that build fresh arrays each digest
           causes Angular's collection watchers to never settle (perpetual
           digest + re-running CSS animations). So we precompute stable objects. */
        $scope.summary = emptySummary();

        function emptySummary() {
            return { total: 0, matched: 0, service: 0, ambiguous: 0, nomatch: 0,
                     pctMatched: 0, pctService: 0, pctNoMatch: 0, strat: [] };
        }

        function recalcSummary() {
            var rs = $scope.results, t = rs.length;
            var matched = 0, service = 0, amb = 0, stratCounts = {};
            rs.forEach(function (r) {
                if (r.strategy === 'Service/Bot') { service++; }
                else if (r.matchedIdentityName) {
                    matched++;
                    if (r.ambiguous) { amb++; }
                }
                if (r.strategy) { stratCounts[r.strategy] = (stratCounts[r.strategy] || 0) + 1; }
            });
            var nomatch = t - matched - service;
            var pct = function (n) { return t ? Math.round(n * 100 / t) : 0; };

            // strategy list (used by the outcome-bar legend)
            var strat = [];
            $scope.strategyOptions.forEach(function (n) {
                var c = stratCounts[n] || 0;
                if (c > 0) { strat.push({ name: n, count: c }); }
            });

            $scope.summary = {
                total: t, matched: matched, service: service, ambiguous: amb, nomatch: nomatch,
                pctMatched: pct(matched), pctService: pct(service), pctNoMatch: pct(nomatch),
                strat: strat
            };
        }

        /* ---- CSV export of the currently filtered rows ---- */
        $scope.exportCsv = function () {
            var rows = $scope.filteredResults();
            var cols = [
                ['Orphan Account', 'nativeIdentity'], ['Distinguished Name', 'distinguishedName'],
                ['Matched Identity', 'matchedIdentityDisplayName'], ['Matched Login', 'matchedIdentityName'],
                ['Matched Email', 'matchedIdentityEmail'], ['Score', 'score'],
                ['Match Detail', 'matchDetail']
            ];
            // M2: guard against CSV formula injection - a value beginning with
            // = + - @ (or tab/CR) is treated as a formula by Excel/Sheets. Prefix
            // such values with a single quote so they render as literal text.
            var esc = function (v) {
                var s = ('' + (v == null ? '' : v));
                if (/^[=+\-@\t\r]/.test(s)) { s = "'" + s; }
                return '"' + s.replace(/"/g, '""') + '"';
            };
            var lines = [cols.map(function (c) { return esc(c[0]); }).join(',')];
            rows.forEach(function (r) {
                lines.push(cols.map(function (c) { return esc(r[c[1]]); }).join(','));
            });
            var blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = 'orphan-correlation-' + ($scope.lastScannedApp || 'scan').replace(/[^a-z0-9]+/gi, '_') + '.csv';
            document.body.appendChild(a); a.click(); document.body.removeChild(a);
            URL.revokeObjectURL(url);
        };

        /* =================================================================
           Bulk selection + bulk correlate (Access-Review style)
           ================================================================= */
        $scope.selected = {};                 // keyed by nativeIdentity
        $scope.bulkConfirm  = false;
        $scope.bulkRunning  = false;
        $scope.bulkError    = null;
        $scope.bulkSummary  = null;

        /* Direct-correlate eligibility: confident, non-ambiguous match
           (score >= threshold). Weaker/ambiguous matches can't be one-clicked. */
        $scope.isEligible = function (r) {
            // Direct-correlate is offered on EVERY matched row (per request), not
            // only confident/non-ambiguous ones. The confirmation modal still shows
            // the score, the bulk modal warns on low scores, the server re-verifies
            // the score and enforces its guards, and every correlation is audited.
            return $scope.canCorrelate
                && !r.correlated && !!r.matchedIdentityName
                && r.approvalStatus !== 'pending';
        };
        /* Selectable (checkbox) = ANY matched account not already correlated or
           pending. Broader than isEligible so low/ambiguous matches can still be
           bulk-sent for manager approval. */
        $scope.isSelectable = function (r) {
            return $scope.canCorrelate
                && !r.correlated && !!r.matchedIdentityName
                && r.approvalStatus !== 'pending';
        };
        $scope.toggleRow = function (r) {
            if (!$scope.isSelectable(r)) { return; }
            $scope.selected[r.nativeIdentity] = !$scope.selected[r.nativeIdentity];
        };
        $scope.selectedRows = function () {
            return $scope.filteredResults().filter(function (r) {
                return $scope.isSelectable(r) && $scope.selected[r.nativeIdentity];
            });
        };
        $scope.selectedCount = function () { return $scope.selectedRows().length; };
        /* Of the selected rows, the subset that can be DIRECTLY correlated. */
        $scope.selectedCorrelatable = function () {
            return $scope.selectedRows().filter($scope.isEligible);
        };
        $scope.selectedCorrelatableCount = function () { return $scope.selectedCorrelatable().length; };
        $scope.selectedRowsHasLow = function () {
            return $scope.selectedCorrelatable().some(function (r) { return (r.score || 0) < 80; });
        };
        $scope.eligibleVisible = function () {
            return $scope.filteredResults().filter($scope.isSelectable);
        };
        $scope.allVisibleSelected = function () {
            var elig = $scope.eligibleVisible();
            if (elig.length === 0) { return false; }
            return elig.every(function (r) { return $scope.selected[r.nativeIdentity]; });
        };
        $scope.toggleSelectAll = function () {
            var elig = $scope.eligibleVisible();
            var sel = !$scope.allVisibleSelected();
            elig.forEach(function (r) { $scope.selected[r.nativeIdentity] = sel; });
        };
        $scope.clearSelection = function () { $scope.selected = {}; };

        $scope.openBulkConfirm = function () {
            if ($scope.selectedCount() === 0) { return; }
            $scope.bulkConfirm = true; $scope.bulkError = null; $scope.bulkSummary = null;
        };
        $scope.cancelBulkConfirm = function () {
            if ($scope.bulkRunning) { return; }
            $scope.bulkConfirm = false;
        };
        $scope.doBulkCorrelate = function () {
            var rows = $scope.selectedCorrelatable();
            if (rows.length === 0 || $scope.bulkRunning) { return; }
            $scope.bulkRunning = true; $scope.bulkError = null;

            var items = rows.map(function (r) {
                return {
                    nativeIdentity:  r.nativeIdentity,
                    applicationName: r.applicationName,
                    identityName:    r.matchedIdentityName,
                    strategy:        r.strategy,
                    score:           String(r.score)
                };
            });

            $http.post(BASE + '/correlate-bulk', { items: items })
                .then(function (res) {
                    var byId = {};
                    (res.data.results || []).forEach(function (x) { byId[x.nativeIdentity] = x; });
                    $scope.results.forEach(function (r) {
                        var x = byId[r.nativeIdentity];
                        if (x && x.status === 'success') {
                            r.correlated = true;
                            delete $scope.selected[r.nativeIdentity];
                        }
                    });
                    $scope.bulkSummary = { success: res.data.success || 0, failed: res.data.failed || 0 };
                    $scope.bulkRunning = false;
                    $scope.bulkConfirm = false;
                })
                .catch(function (err) {
                    $scope.bulkRunning = false;
                    $scope.bulkError = (err.data && err.data.error) ? err.data.error : 'Bulk correlation failed (' + err.status + ')';
                });
        };

        /* ---- Bulk: send all selected for manager approval ---- */
        $scope.bulkApprovalConfirm  = false;
        $scope.bulkApprovalRunning  = false;
        $scope.bulkApprovalError    = null;
        $scope.bulkApprovalSummary  = null;
        $scope.openBulkApproval = function () {
            if ($scope.selectedCount() === 0) { return; }
            $scope.bulkApprovalConfirm = true; $scope.bulkApprovalError = null; $scope.bulkApprovalSummary = null;
        };
        $scope.cancelBulkApproval = function () {
            if ($scope.bulkApprovalRunning) { return; }
            $scope.bulkApprovalConfirm = false;
        };
        $scope.doBulkApproval = function () {
            var rows = $scope.selectedRows();
            if (rows.length === 0 || $scope.bulkApprovalRunning) { return; }
            $scope.bulkApprovalRunning = true; $scope.bulkApprovalError = null;

            var items = rows.map(function (r) {
                return {
                    nativeIdentity:  r.nativeIdentity,
                    applicationName: r.applicationName,
                    identityName:    r.matchedIdentityName,
                    strategy:        r.strategy,
                    score:           String(r.score),
                    matchDetail:     r.matchDetail
                };
            });

            $http.post(BASE + '/request-approval-bulk', { items: items })
                .then(function (res) {
                    var byId = {};
                    (res.data.results || []).forEach(function (x) { byId[x.nativeIdentity] = x; });
                    $scope.results.forEach(function (r) {
                        var x = byId[r.nativeIdentity];
                        if (x && x.status === 'approval_requested') {
                            r.approvalStatus = 'pending';
                            r.approvalManager = x.managerDisplay || x.managerName;
                            delete $scope.selected[r.nativeIdentity];
                        }
                    });
                    $scope.bulkApprovalSummary = { success: res.data.success || 0, failed: res.data.failed || 0 };
                    $scope.bulkApprovalRunning = false;
                    $scope.bulkApprovalConfirm = false;
                })
                .catch(function (err) {
                    $scope.bulkApprovalRunning = false;
                    $scope.bulkApprovalError = (err.data && err.data.error) ? err.data.error : 'Bulk approval failed (' + err.status + ')';
                });
        };

        /* ---- DN parsing: leaf CN (account label) + OU path (location) ----
           Renders a clean two-line orphan cell instead of one long mono DN. */
        $scope.dnLeaf = function (dn) {
            if (!dn) { return ''; }
            var first = dn.split(',')[0] || dn;
            return first.replace(/^CN=/i, '').trim();
        };
        $scope.dnPath = function (dn) {
            if (!dn) { return ''; }
            var parts = dn.split(','), ous = [];
            for (var i = 1; i < parts.length; i++) {
                var p = parts[i].trim();
                if (/^OU=/i.test(p)) { ous.push(p.replace(/^OU=/i, '')); }
            }
            return ous.join(' / ');
        };

        /* ---- Row CSS class by confidence ---- */
        $scope.rowClass = function (r) {
            if (r.correlated)           { return 'orc-row-done'; }
            if (r.ambiguous)            { return 'orc-row-amb'; }
            if (!r.matchedIdentityName) { return ''; }
            var s = r.score || 0;
            if (s >= 75) { return 'orc-row-high'; }
            if (s >= 60) { return 'orc-row-medium'; }
            return 'orc-row-low';
        };

        /* ---- Manual correlate flow ---- */
        $scope.openConfirm = function (r) { $scope.pendingCorrelation = r; $scope.correlateError = null; $scope.correlating = false; };
        $scope.cancelConfirm = function () { if ($scope.correlating) { return; } $scope.pendingCorrelation = null; $scope.correlateError = null; };
        $scope.doCorrelate = function () {
            if (!$scope.pendingCorrelation || $scope.correlating) { return; }
            $scope.correlating = true; $scope.correlateError = null;
            var payload = {
                nativeIdentity:  $scope.pendingCorrelation.nativeIdentity,
                applicationName: $scope.pendingCorrelation.applicationName,
                identityName:    $scope.pendingCorrelation.matchedIdentityName,
                strategy:        $scope.pendingCorrelation.strategy,
                score:           String($scope.pendingCorrelation.score)
            };
            $http.post(BASE + '/correlate', payload)
                .then(function () {
                    var row = $scope.results.find(function (r) { return r.nativeIdentity === payload.nativeIdentity; });
                    if (row) { row.correlated = true; }
                    $scope.correlating = false; $scope.pendingCorrelation = null;
                })
                .catch(function (err) {
                    $scope.correlating = false;
                    $scope.correlateError = (err.data && err.data.error) ? err.data.error : 'Correlation failed (' + err.status + ')';
                });
        };

        /* ---- Approval request flow ---- */
        $scope.openApprovalRequest = function (r) { $scope.pendingApprovalRequest = r; $scope.approvalRequestError = null; $scope.requestingApproval = false; $scope.approvalSent = null; };
        $scope.cancelApprovalRequest = function () { if ($scope.requestingApproval) { return; } $scope.pendingApprovalRequest = null; $scope.approvalRequestError = null; $scope.approvalSent = null; };
        $scope.doRequestApproval = function () {
            if (!$scope.pendingApprovalRequest || $scope.requestingApproval) { return; }
            $scope.requestingApproval = true; $scope.approvalRequestError = null;
            var r = $scope.pendingApprovalRequest;
            var payload = {
                nativeIdentity: r.nativeIdentity, applicationName: r.applicationName,
                identityName: r.matchedIdentityName, strategy: r.strategy,
                score: String(r.score), matchDetail: r.matchDetail, displayName: r.displayName
            };
            $http.post(BASE + '/request-approval', payload)
                .then(function (res) {
                    var row = $scope.results.find(function (result) { return result.nativeIdentity === payload.nativeIdentity; });
                    if (row) { row.approvalStatus = 'pending'; row.approvalManager = res.data.managerDisplay || res.data.managerName; }
                    var mgr = res.data.managerDisplay || res.data.managerName || 'the line manager';
                    // Keep the modal OPEN and show a success state (don't null
                    // pendingApprovalRequest) so the user always sees confirmation.
                    $scope.approvalSent = { manager: mgr, account: (r.displayName || r.nativeIdentity) };
                    $scope.approvalToast = { account: (r.displayName || r.nativeIdentity), manager: mgr };
                    $scope.requestingApproval = false;
                })
                .catch(function (err) {
                    $scope.requestingApproval = false;
                    $scope.approvalRequestError = (err.data && err.data.error) ? err.data.error : 'Request failed (' + err.status + ')';
                });
        };

    }]);
    } // initOrphanCorrelator

    /* Defensive bootstrap. Depending on the IIQ version and how the plugin page
       loads its scripts, AngularJS may not yet be defined when this file runs, and
       mixing ng-app auto-bootstrap with a manual bootstrap races (symptom: the page
       shows raw {{ }} bindings because Angular never started). We therefore use
       ONLY manual bootstrap (ng-app removed from page.xhtml), and we wait until both
       AngularJS and the root element exist before bootstrapping exactly once. */
    var orcTries = 0;
    (function orcBoot() {
        var el = document.getElementById('orc-root');
        if (typeof angular !== 'undefined' && angular.bootstrap && el) {
            try {
                initOrphanCorrelator();
                angular.bootstrap(el, ['OrphanCorrelatorApp']);
            } catch (e) {
                if (window.console) { console.error('OrphanCorrelator: bootstrap failed', e); }
            }
        } else if (orcTries++ < 100) {
            setTimeout(orcBoot, 50);   // retry up to ~5s while IIQ finishes loading AngularJS
        } else if (window.console) {
            console.error('OrphanCorrelator: AngularJS was not available on this page.');
        }
    })();

}());
