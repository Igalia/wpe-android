package com.wpe.wpe;

import android.view.SurfaceView;

public interface PageObserver {
    void onPageSurfaceViewCreated(SurfaceView view);
    void onPageSurfaceViewReady(SurfaceView view);
}
