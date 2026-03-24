package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.ProgressState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ProgressTrackerTest {

    private ProgressTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ProgressTracker();
    }

    @Test
    void initialState_isInactive() {
        ProgressState state = tracker.getProgress();
        assertFalse(state.isActive());
        assertEquals("", state.getCurrentOperation());
        assertEquals(0, state.getTotalItems());
        assertEquals(0, state.getProcessedItems());
        assertEquals(0, state.getPercentage());
    }

    @Test
    void startOperation_setsActiveState() {
        tracker.startOperation("PST feldolgozás", 100);

        ProgressState state = tracker.getProgress();
        assertTrue(state.isActive());
        assertEquals("PST feldolgozás", state.getCurrentOperation());
        assertEquals(100, state.getTotalItems());
        assertEquals(0, state.getProcessedItems());
        assertEquals(0, state.getPercentage());
    }

    @Test
    void increment_updatesProcessedAndPercentage() {
        tracker.startOperation("Test", 10);

        tracker.increment();
        ProgressState state = tracker.getProgress();
        assertEquals(1, state.getProcessedItems());
        assertEquals(10, state.getPercentage());

        tracker.increment();
        state = tracker.getProgress();
        assertEquals(2, state.getProcessedItems());
        assertEquals(20, state.getPercentage());
    }

    @Test
    void increment_whenInactive_doesNothing() {
        // Never called startOperation
        tracker.increment();

        ProgressState state = tracker.getProgress();
        assertEquals(0, state.getProcessedItems());
    }

    @Test
    void increment_fullProgress_reaches100() {
        tracker.startOperation("Test", 4);

        for (int i = 0; i < 4; i++) {
            tracker.increment();
        }

        ProgressState state = tracker.getProgress();
        assertEquals(4, state.getProcessedItems());
        assertEquals(100, state.getPercentage());
    }

    @Test
    void stopOperation_setsInactiveAnd100Percent() {
        tracker.startOperation("Test", 10);
        tracker.increment();
        tracker.increment();

        tracker.stopOperation();

        ProgressState state = tracker.getProgress();
        assertFalse(state.isActive());
        assertEquals(100, state.getPercentage());
        assertEquals(10, state.getProcessedItems()); // set to total
    }

    @Test
    void stopOperation_withZeroTotal_setsProcessedToZero() {
        tracker.startOperation("Empty", 0);
        tracker.stopOperation();

        ProgressState state = tracker.getProgress();
        assertFalse(state.isActive());
        assertEquals(100, state.getPercentage());
        assertEquals(0, state.getProcessedItems());
    }

    @Test
    void startOperation_resetsState() {
        tracker.startOperation("First", 50);
        tracker.increment();
        tracker.increment();

        tracker.startOperation("Second", 200);

        ProgressState state = tracker.getProgress();
        assertEquals("Second", state.getCurrentOperation());
        assertEquals(200, state.getTotalItems());
        assertEquals(0, state.getProcessedItems());
        assertEquals(0, state.getPercentage());
        assertTrue(state.isActive());
    }

    @Test
    void getProgress_returnsCopy() {
        tracker.startOperation("Test", 10);

        ProgressState copy = tracker.getProgress();
        copy.setProcessedItems(999);
        copy.setCurrentOperation("Modified");

        ProgressState actual = tracker.getProgress();
        assertEquals(0, actual.getProcessedItems());
        assertEquals("Test", actual.getCurrentOperation());
    }

    @Test
    void concurrentIncrements_areThreadSafe() throws InterruptedException {
        int totalItems = 1000;
        tracker.startOperation("Concurrent", totalItems);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(totalItems);

        for (int i = 0; i < totalItems; i++) {
            executor.submit(() -> {
                tracker.increment();
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        ProgressState state = tracker.getProgress();
        assertEquals(totalItems, state.getProcessedItems());
        assertEquals(100, state.getPercentage());
    }

    @Test
    void percentageCalculation_roundsDown() {
        tracker.startOperation("Test", 3);

        tracker.increment(); // 1/3 = 33%
        assertEquals(33, tracker.getProgress().getPercentage());

        tracker.increment(); // 2/3 = 66%
        assertEquals(66, tracker.getProgress().getPercentage());

        tracker.increment(); // 3/3 = 100%
        assertEquals(100, tracker.getProgress().getPercentage());
    }
}
