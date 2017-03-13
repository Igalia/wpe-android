// IWPEService.aidl
package com.wpe.wpedemo;

import android.os.Bundle;

import com.wpe.wpedemo.external.SurfaceWrapper;

interface IWPEService {
    int connect(in Bundle args);

    void provideSurface(in SurfaceWrapper wrapper);
}
