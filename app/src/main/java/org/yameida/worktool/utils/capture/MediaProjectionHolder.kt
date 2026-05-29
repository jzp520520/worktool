// Copyright 2024-2026 WorkTool
// Licensed under the Apache License, Version 2.0
// SPDX-License-Identifier: Apache-2.0

package org.yameida.worktool.utils.capture

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/**
 * Created by Gallon on 2019/8/4.
 */
class MediaProjectionHolder {

    companion object {
        var mMediaProjection: MediaProjection? = null

        @Synchronized
        fun setMediaProjection(mediaProjection: MediaProjection) {
            if (mMediaProjection == null) {
                mMediaProjection = mediaProjection
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mMediaProjection = null
                    }
                }, Handler(Looper.getMainLooper()))
            }
        }
    }

}