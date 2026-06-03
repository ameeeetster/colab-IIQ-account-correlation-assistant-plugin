/**
 * Orphan Correlator - Header Snippet
 * Injects a nav icon into the IIQ top navigation bar.
 * Only rendered if the user has the ViewOrphanCorrelator SPRight
 * (enforced by the regexPattern/rightRequired in manifest.xml).
 */
/*
 * Runs on EVERY IIQ page (for users with ViewOrphanCorrelator), so it is fully
 * defensive: never throws if a global is missing, never breaks a page with no
 * navbar, never injects a duplicate icon. Worst case it does nothing - it can
 * never interfere with the host page.
 */
(function () {
    'use strict';
    try {
        if (typeof jQuery === 'undefined' || typeof SailPoint === 'undefined'
                || !SailPoint.CONTEXT_PATH) { return; }

        var pluginPageUrl = SailPoint.CONTEXT_PATH + '/plugins/pluginPage.jsf?pn=OrphanCorrelator';

        jQuery(document).ready(function () {
            try {
                if (jQuery('.orc-nav-icon').length > 0) { return; }      // already injected
                var anchor = jQuery('ul.navbar-right li:first');
                if (anchor.length === 0) { return; }                    // no top navbar - skip
                anchor.before(
                    '<li class="dropdown">' +
                      '<a href="' + pluginPageUrl + '"' +
                        ' tabindex="0" role="menuitem"' +
                        ' title="Orphan Account Correlator" class="orc-nav-icon">' +
                        '<i role="presentation" class="fa fa-user-times fa-lg"></i>' +
                      '</a>' +
                    '</li>'
                );
            } catch (inner) { /* never let the nav icon break the host page */ }
        });
    } catch (outer) { /* swallow - snippet must be invisible on failure */ }
}());
