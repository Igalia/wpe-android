package com.wpe.wpe.NetworkProcess;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.wpe.wpe.NetworkProcess.WPENetworkProcessGlue;
import com.wpe.wpe.WPEService;

public class Service extends WPEService {

    @Override
    protected void initializeService(ParcelFileDescriptor[] fds)
    {

        Log.i("WPENetworkProcess", "initializeService(), got " + fds.length + " fds");
        for (int i = 0; i < fds.length; ++i) {
            Log.i("WPENetworkProcess", " [" + i + "] fd " + fds[i].toString() + " native value " + fds[i].getFd());
        }
        WPENetworkProcessGlue.initializeMain(fds[0].detachFd());
    }

}
