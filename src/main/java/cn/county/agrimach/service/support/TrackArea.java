package cn.county.agrimach.service.support;

import cn.county.agrimach.domain.entity.TrackPoint;

import java.util.List;

/**
 * 轨迹核算：有效作业点构成多边形，Shoelace 求面积（m²）后折算亩（1 亩 ≈ 666.67 m²）。
 * 点不足 3 个无法闭合时退化为 0，由实测面积兜底。
 */
public final class TrackArea {

    public static final double MU_IN_SQM = 666.6667;

    private TrackArea() {}

    public static double polygonMu(List<TrackPoint> points) {
        List<TrackPoint> work = points.stream().filter(TrackPoint::isWorking).toList();
        if (work.size() < 3) return 0.0;
        double areaSqm = 0;
        for (int i = 0; i < work.size(); i++) {
            TrackPoint a = work.get(i);
            TrackPoint b = work.get((i + 1) % work.size());
            double[] xyA = meters(a.getLongitude(), a.getLatitude());
            double[] xyB = meters(b.getLongitude(), b.getLatitude());
            areaSqm += xyA[0] * xyB[1] - xyB[0] * xyA[1];
        }
        return Math.round(Math.abs(areaSqm) / 2.0 / MU_IN_SQM * 100) / 100.0;
    }

    /** 经纬度 → 局部近似平面米（以首点为原点） */
    private static double[] meters(double lon, double lat) {
        double x = Math.toRadians(lon) * 6371000.0 * Math.cos(Math.toRadians(lat));
        double y = Math.toRadians(lat) * 6371000.0;
        return new double[]{x, y};
    }
}
