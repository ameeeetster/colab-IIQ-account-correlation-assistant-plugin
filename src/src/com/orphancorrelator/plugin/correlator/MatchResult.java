package com.orphancorrelator.plugin.correlator;

import java.util.ArrayList;
import java.util.List;

/**
 * POJO representing a single orphan account and its best-match identity as
 * produced by the weighted scoring engine.
 *
 * Serialised to JSON by the JAX-RS layer and consumed by the UI.
 */
public class MatchResult {

    /** Strategy constants - retained for back-compat / audit labelling. */
    public static final String STRATEGY_EMAIL       = "Email";
    public static final String STRATEGY_LOGIN       = "Login Name";
    public static final String STRATEGY_NAME        = "Full Name";
    public static final String STRATEGY_DISPLAY     = "Display Name";
    public static final String STRATEGY_NORMALIZED  = "Normalized Login";
    public static final String STRATEGY_SERVICE     = "Service/Bot";
    public static final String STRATEGY_SCORED      = "Weighted Score";

    // --- Orphan account fields ---
    private String nativeIdentity;
    private String applicationName;
    private String displayName;
    private String distinguishedName;
    private String dummyIdentityName;

    // --- Match result fields ---
    private String matchedIdentityId;       // IIQ Identity id (for deep-linking to the identity page)
    private String matchedIdentityName;
    private String matchedIdentityDisplayName;
    private String matchedIdentityEmail;
    private String strategy;
    private String matchDetail;

    // --- Scoring fields ---
    private int     score;                 // final 0-100 score for the best match
    private int     secondScore;           // best runner-up score (for gap/ambiguity)
    private boolean ambiguous;             // best vs runner-up gap below threshold
    private List<String> reasons  = new ArrayList<String>();
    private List<String> warnings = new ArrayList<String>();

    // --- Action fields ---
    private String  errorMessage;
    private boolean approvalPending;       // an open manager-approval workflow exists for this account
    private boolean testAccount;           // account name matches a test/non-production pattern

    public MatchResult() {}

    public boolean isApprovalPending()               { return approvalPending; }
    public void    setApprovalPending(boolean v)     { this.approvalPending = v; }

    public boolean isTestAccount()                   { return testAccount; }
    public void    setTestAccount(boolean v)         { this.testAccount = v; }

    public String getNativeIdentity()                { return nativeIdentity; }
    public void   setNativeIdentity(String v)        { this.nativeIdentity = v; }

    public String getApplicationName()               { return applicationName; }
    public void   setApplicationName(String v)       { this.applicationName = v; }

    public String getDisplayName()                   { return displayName; }
    public void   setDisplayName(String v)           { this.displayName = v; }

    public String getDistinguishedName()             { return distinguishedName; }
    public void   setDistinguishedName(String v)     { this.distinguishedName = v; }

    public String getDummyIdentityName()             { return dummyIdentityName; }
    public void   setDummyIdentityName(String v)     { this.dummyIdentityName = v; }

    public String getMatchedIdentityId()             { return matchedIdentityId; }
    public void   setMatchedIdentityId(String v)     { this.matchedIdentityId = v; }

    public String getMatchedIdentityName()           { return matchedIdentityName; }
    public void   setMatchedIdentityName(String v)   { this.matchedIdentityName = v; }

    public String getMatchedIdentityDisplayName()    { return matchedIdentityDisplayName; }
    public void   setMatchedIdentityDisplayName(String v) { this.matchedIdentityDisplayName = v; }

    public String getMatchedIdentityEmail()          { return matchedIdentityEmail; }
    public void   setMatchedIdentityEmail(String v)  { this.matchedIdentityEmail = v; }

    public String getStrategy()                      { return strategy; }
    public void   setStrategy(String v)              { this.strategy = v; }

    public String getMatchDetail()                   { return matchDetail; }
    public void   setMatchDetail(String v)           { this.matchDetail = v; }

    public int    getScore()                         { return score; }
    public void   setScore(int v)                    { this.score = v; }

    public int    getSecondScore()                   { return secondScore; }
    public void   setSecondScore(int v)              { this.secondScore = v; }

    public boolean isAmbiguous()                     { return ambiguous; }
    public void    setAmbiguous(boolean v)           { this.ambiguous = v; }

    public List<String> getReasons()                 { return reasons; }
    public void   setReasons(List<String> v)         { this.reasons = (v != null) ? v : new ArrayList<String>(); }
    public void   addReason(String r)                { if (r != null) { this.reasons.add(r); } }

    public List<String> getWarnings()                { return warnings; }
    public void   setWarnings(List<String> v)        { this.warnings = (v != null) ? v : new ArrayList<String>(); }
    public void   addWarning(String w)               { if (w != null) { this.warnings.add(w); } }

    public String getErrorMessage()                  { return errorMessage; }
    public void   setErrorMessage(String v)          { this.errorMessage = v; }
}
