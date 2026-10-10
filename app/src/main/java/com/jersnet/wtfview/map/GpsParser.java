package com.jersnet.wtfview.map;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GpsParser {

    private static final Pattern LABELED_LAT_PATTERN = Pattern.compile(
            "(?:LAT|LATITUDE)[:\\s]*([-+]?\\d{1,2}\\.\\d{4,8})\\s*([NS])?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LABELED_LON_PATTERN = Pattern.compile(
            "(?:LON|LONG|LONGITUDE|LNG)[:\\s]*([-+]?\\d{1,3}\\.\\d{4,8})\\s*([EW])?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CARDINAL_LAT_PATTERN = Pattern.compile(
            "([NS])\\s*([-+]?\\d{1,2}\\.\\d{4,8})|([-+]?\\d{1,2}\\.\\d{4,8})\\s*([NS])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CARDINAL_LON_PATTERN = Pattern.compile(
            "([EW])\\s*([-+]?\\d{1,3}\\.\\d{4,8})|([-+]?\\d{1,3}\\.\\d{4,8})\\s*([EW])",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern GENERIC_COORD_PATTERN = Pattern.compile(
            "[-+]?\\d{1,3}\\.\\d{4,8}"
    );

    public static GpsCoordinate parseFromOsdText(String osdText) {
        if (osdText == null || osdText.isEmpty()) {
            return null;
        }

        Matcher mLat = LABELED_LAT_PATTERN.matcher(osdText);
        Matcher mLon = LABELED_LON_PATTERN.matcher(osdText);
        if (mLat.find() && mLon.find()) {
            Double lat = parseDouble(mLat.group(1));
            String latCard = mLat.group(2);
            if (lat != null && latCard != null && latCard.equalsIgnoreCase("S")) {
                lat = -Math.abs(lat);
            }

            Double lon = parseDouble(mLon.group(1));
            String lonCard = mLon.group(2);
            if (lon != null && lonCard != null && lonCard.equalsIgnoreCase("W")) {
                lon = -Math.abs(lon);
            }

            if (isValidPair(lat, lon)) {
                return new GpsCoordinate(lat, lon);
            }
        }

        Matcher cLat = CARDINAL_LAT_PATTERN.matcher(osdText);
        Matcher cLon = CARDINAL_LON_PATTERN.matcher(osdText);
        if (cLat.find() && cLon.find()) {
            String latNum = cLat.group(2) != null ? cLat.group(2) : cLat.group(3);
            String latDir = cLat.group(1) != null ? cLat.group(1) : cLat.group(4);
            Double lat = parseDouble(latNum);
            if (lat != null && latDir != null && latDir.equalsIgnoreCase("S")) {
                lat = -Math.abs(lat);
            }

            String lonNum = cLon.group(2) != null ? cLon.group(2) : cLon.group(3);
            String lonDir = cLon.group(1) != null ? cLon.group(1) : cLon.group(4);
            Double lon = parseDouble(lonNum);
            if (lon != null && lonDir != null && lonDir.equalsIgnoreCase("W")) {
                lon = -Math.abs(lon);
            }

            if (isValidPair(lat, lon)) {
                return new GpsCoordinate(lat, lon);
            }
        }

        List<Double> numbers = new ArrayList<>();
        Matcher gMatcher = GENERIC_COORD_PATTERN.matcher(osdText);
        while (gMatcher.find()) {
            Double val = parseDouble(gMatcher.group(0));
            if (val != null) {
                numbers.add(val);
            }
        }

        for (int i = 0; i < numbers.size(); i++) {
            for (int j = i + 1; j < numbers.size(); j++) {
                double n1 = numbers.get(i);
                double n2 = numbers.get(j);

                if (isValidPair(n1, n2)) {
                    return new GpsCoordinate(n1, n2);
                }
            }
        }

        return null;
    }

    private static boolean isValidPair(Double lat, Double lon) {
        if (lat == null || lon == null) return false;
        if (Math.abs(lat) > 90.0) return false;
        if (Math.abs(lon) > 180.0) return false;
        if (Math.abs(lat) < 0.0001 && Math.abs(lon) < 0.0001) return false;
        return Math.abs(lat - lon) > 0.0001;
    }

    private static Double parseDouble(String str) {
        if (str == null) return null;
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
