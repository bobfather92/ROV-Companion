package uk.co.rovcompanion;

/** One automatic connection per discovery attempt. Names alone never identify a slider. */
final class AutoConnectGate {
    private String remembered;
    private boolean claimed;
    AutoConnectGate(String remembered) { this.remembered=remembered==null?"":remembered; }
    void reset() { claimed=false; }
    void forget() { remembered=""; claimed=false; }
    boolean candidate(String address,boolean carriageServiceAdvertised) {
        if (claimed || address==null) return false;
        if (remembered.isEmpty() ? !carriageServiceAdvertised : !remembered.equalsIgnoreCase(address)) return false;
        claimed=true; return true;
    }
    void manualChoice() { claimed=true; }
    void verified(String address) { if(address!=null) remembered=address; }
}
