package cn.county.agrimach.service.support;

/** 地理距离：Haversine 直线距离 × 农村道路绕行系数 ≈ 道路距离 */
public final class Geo {
    /** 县域乡村道路绕行系数（直线→实际道路里程） */
    public static final double DETOUR_FACTOR = 1.35;

    private Geo() {}

    public static double straightKm(double lon1, double lat1, double lon2, double lat2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double roadKm(double lon1, double lat1, double lon2, double lat2) {
        return straightKm(lon1, lat1, lon2, lat2) * DETOUR_FACTOR;
    }
}
