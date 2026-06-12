package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.ProgressState;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ProgressTracker {
    private final ProgressState state = new ProgressState();
    private final AtomicInteger processed = new AtomicInteger(0);
    private int total = 0;

    public synchronized void startOperation(String operationName, int totalItems) {
        state.setCurrentOperation(operationName);
        this.total = totalItems;
        state.setTotalItems(totalItems);
        this.processed.set(0);
        state.setProcessedItems(0);
        state.setPercentage(0);
        state.setStatusDetail("");
        state.setActive(true);
    }

    public void increment() {
        if (!state.isActive())
            return;
        processed.incrementAndGet();
    }

    public synchronized void setStatusDetail(String detail) {
        state.setStatusDetail(detail);
    }

    public synchronized void stopOperation() {
        state.setActive(false);
        state.setPercentage(100);
        if (total > 0) {
            processed.set(total);
        }
    }

    public synchronized ProgressState getProgress() {
        // Return a copy to avoid concurrency issues during serialization
        ProgressState copy = new ProgressState();
        copy.setCurrentOperation(state.getCurrentOperation());
        copy.setTotalItems(state.getTotalItems());
        int p = processed.get();
        copy.setProcessedItems(p);
        copy.setPercentage(total > 0 ? (int) ((p / (double) total) * 100) : state.getPercentage());
        copy.setStatusDetail(state.getStatusDetail());
        copy.setActive(state.isActive());
        return copy;
    }
}
