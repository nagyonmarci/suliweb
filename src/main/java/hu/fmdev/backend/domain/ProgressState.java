package hu.fmdev.backend.domain;

import lombok.Data;

@Data
public class ProgressState {
    private String currentOperation;
    private int totalItems;
    private int processedItems;
    private int percentage;
    private boolean active;

    public ProgressState() {
        this.currentOperation = "";
        this.totalItems = 0;
        this.processedItems = 0;
        this.percentage = 0;
        this.active = false;
    }
}
