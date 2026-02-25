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
        state.setActive(true);
    }

    public void increment() {
        if (!state.isActive())
            return;

        int current = processed.incrementAndGet();
        synchronized (this) {
            state.setProcessedItems(current);
            if (total > 0) {
                state.setPercentage((int) ((current / (double) total) * 100));
            }
        }
    }

    public synchronized void stopOperation() {
        state.setActive(false);
        state.setPercentage(100);
        if (total > 0) {
            state.setProcessedItems(total);
        }
    }

    public synchronized ProgressState getProgress() {
        // Return a copy to avoid concurrency issues during serialization
        ProgressState copy = new ProgressState();
        copy.setCurrentOperation(state.getCurrentOperation());
        copy.setTotalItems(state.getTotalItems());
        copy.setProcessedItems(state.getProcessedItems());
        copy.setPercentage(state.getPercentage());
        copy.setActive(state.isActive());
        return copy;
    }
}
