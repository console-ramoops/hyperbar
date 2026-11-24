package com.alpha269.hyperisland.island;

import android.graphics.RectF;
import android.view.MotionEvent;

/** Explicit interface between Android presentation and the One UI data controller. */
public interface IslandHost {
    boolean hasMinimized();
    void onCycle(int direction);
    default void onSwipe(int direction){onCycle(direction);}
    default HyperOsPresentationQueue.Layout previewSwipe(int direction){return null;}
    void onExpandedChanged(boolean expanded);
    void onIslandFrame();
    void onItemAction(IslandItem item,IslandItem.Action action);
    void onMinimize(IslandItem item);
    void onOpenItem(IslandItem item,RectF from);
    void onRestore();
    void onShadeDragEvent(MotionEvent event);
    void onShadeDragStart(float x,float y,long time);
    boolean onShadeFade(float alpha,float scale,float x,float y);
    void onWindowHeightNeeded(int height);
}
