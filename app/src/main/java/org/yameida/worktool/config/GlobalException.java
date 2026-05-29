// Copyright 2024-2026 WorkTool
// Licensed under the Apache License, Version 2.0
// SPDX-License-Identifier: Apache-2.0

package org.yameida.worktool.config;

import android.util.Log;

import com.blankj.utilcode.util.AppUtils;

public class GlobalException implements Thread.UncaughtExceptionHandler {
    private final static GlobalException myCrashHandler = new GlobalException();

    private GlobalException() {
    }

    public static synchronized GlobalException getInstance() {
        return myCrashHandler;
    }

    @Override
    public void uncaughtException(Thread arg0, Throwable arg1) {
        Log.e("GlobalException", "-------------Caught Exception-------------");
        arg1.printStackTrace();
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        AppUtils.relaunchApp(true);
    }
}