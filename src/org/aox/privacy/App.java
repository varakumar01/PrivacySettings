package org.aox.privacy;

import android.app.Application;

/** Persistent process: owns the long-lived watchers (auto reboot, radio timeouts). */
public class App extends Application {
    private Controller mController;

    @Override
    public void onCreate() {
        super.onCreate();
        mController = new Controller(this);
        mController.start();
    }
}
