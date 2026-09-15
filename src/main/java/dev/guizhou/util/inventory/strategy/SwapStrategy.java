package dev.guizhou.util.inventory.strategy;

import dev.guizhou.util.inventory.Result;

public interface SwapStrategy {
    boolean swap(Result result);

    boolean swapBack(int last, Result result);
}
