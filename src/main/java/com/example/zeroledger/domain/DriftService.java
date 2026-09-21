package com.example.zeroledger.domain;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DriftService {
    public DriftModel fit(List<DriftPoint> points, boolean piecewise) {
        List<DriftPoint> accepted = points.stream()
                .filter(DriftPoint::accepted)
                .sorted(Comparator.comparingDouble(DriftPoint::time))
                .toList();
        if (accepted.isEmpty()) {
            return new DriftModel(new double[6], new double[6], null, null, 0, piecewise, List.of());
        }

        double[] slope = new double[6];
        double[] intercept = new double[6];
        if (!piecewise || accepted.size() == 1) {
            for (int channel = 0; channel < 6; channel++) {
                int currentChannel = channel;
                if (accepted.size() == 1) {
                    intercept[currentChannel] = accepted.get(0).values()[currentChannel];
                } else {
                    double meanT = accepted.stream().mapToDouble(DriftPoint::time).average().orElse(0);
                    double meanV = accepted.stream()
                            .mapToDouble(point -> point.values()[currentChannel]).average().orElse(0);
                    double numerator = 0;
                    double denominator = 0;
                    for (DriftPoint point : accepted) {
                        numerator += (point.time() - meanT) * (point.values()[channel] - meanV);
                        denominator += (point.time() - meanT) * (point.time() - meanT);
                    }
                    slope[currentChannel] = denominator == 0 ? 0 : numerator / denominator;
                    intercept[currentChannel] = meanV - slope[currentChannel] * meanT;
                }
            }
        }

        List<DriftModel.DriftKnot> knots = accepted.stream()
                .map(point -> new DriftModel.DriftKnot(point.time(), point.values().clone()))
                .toList();
        return new DriftModel(slope, intercept, accepted.get(0).time(), accepted.get(accepted.size() - 1).time(),
                accepted.size(), piecewise, knots);
    }

    public double[] estimate(DriftModel model, double time) {
        if (model.acceptedTareCount() == 0) {
            return new double[6];
        }
        if (!model.piecewise() || model.acceptedTareCount() == 1) {
            double[] result = new double[6];
            for (int i = 0; i < 6; i++) {
                result[i] = model.slope()[i] * time + model.intercept()[i];
            }
            return result;
        }
        if (time <= model.firstTareTime()) {
            return model.knots().get(0).values().clone();
        }
        if (time >= model.lastTareTime()) {
            return model.knots().get(model.knots().size() - 1).values().clone();
        }
        for (int i = 0; i < model.knots().size() - 1; i++) {
            DriftModel.DriftKnot left = model.knots().get(i);
            DriftModel.DriftKnot right = model.knots().get(i + 1);
            if (time >= left.time() && time <= right.time()) {
                double fraction = (time - left.time()) / (right.time() - left.time());
                double[] result = new double[6];
                for (int channel = 0; channel < 6; channel++) {
                    result[channel] = left.values()[channel]
                            + fraction * (right.values()[channel] - left.values()[channel]);
                }
                return result;
            }
        }
        return model.knots().get(model.knots().size() - 1).values().clone();
    }

    public record DriftPoint(double time, double[] values, boolean accepted) {
    }
}
