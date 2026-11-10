package report;

/** How to rank services in a report. */
public enum ReportOrder {
    UPTIME,          // best uptime first
    RESPONSE_TIME,   // fastest average response first
    NAME             // alphabetical
}
