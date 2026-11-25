package in.aitradingsystem.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class WalkForwardWindowPlanner {
    record Window(int foldNumber, LocalDate trainingFrom, LocalDate trainingTo,
                  LocalDate validationFrom, LocalDate validationTo) {}

    private WalkForwardWindowPlanner() {}

    static List<Window> plan(LocalDate from, LocalDate to, int trainingDays, int validationDays, int stepDays) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("valid overall date range is required");
        }
        if (trainingDays < 1 || validationDays < 1 || stepDays < 1) {
            throw new IllegalArgumentException("walk-forward window sizes must be positive");
        }
        List<Window> windows = new ArrayList<>();
        LocalDate trainingFrom = from;
        int fold = 1;
        while (true) {
            LocalDate trainingTo = trainingFrom.plusDays(trainingDays - 1L);
            LocalDate validationFrom = trainingTo.plusDays(1);
            LocalDate validationTo = validationFrom.plusDays(validationDays - 1L);
            if (validationTo.isAfter(to)) break;
            windows.add(new Window(fold++, trainingFrom, trainingTo, validationFrom, validationTo));
            trainingFrom = trainingFrom.plusDays(stepDays);
        }
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("overall range does not contain one complete training/validation fold");
        }
        return List.copyOf(windows);
    }
}
