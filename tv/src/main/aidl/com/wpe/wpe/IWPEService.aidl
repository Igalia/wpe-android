package com.wpe.wpe;

import android.os.Bundle;

import com.wpe.wpe.external.SurfaceWrapper;

interface IWPEService {
    int connect(in Bundle args);

    void provideSurface(in SurfaceWrapper wrapper);
}
