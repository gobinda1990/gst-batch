package gov.com.ai.webapp.util;



public final class GstinUtils {

    private GstinUtils() {
    }

    public static boolean isValid(String gstin) {

        if (gstin == null) {
            return false;
        }

        return gstin.matches(
                "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$"
        );
    }

    public static String mask(String gstin) {

        if (gstin == null || gstin.length() < 7) {
            return "***";
        }

        return gstin.substring(0, 2)
                + "*****"
                + gstin.substring(gstin.length() - 4);
    }
}
