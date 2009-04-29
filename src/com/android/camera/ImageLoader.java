/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import com.android.camera.gallery.IImage;
import com.android.camera.gallery.IImageList;

import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

/**
 * A dedicated decoding thread used by ImageGallery.
 */
public class ImageLoader {
    @SuppressWarnings("unused")
    private static final String TAG = "ImageLoader";

    // queue of work to do in the worker thread
    private final ArrayList<WorkItem> mQueue = new ArrayList<WorkItem>();
    private final ArrayList<WorkItem> mInProgress = new ArrayList<WorkItem>();

    // the worker thread and a done flag so we know when to exit
    private boolean mDone;
    private Thread mDecodeThread;
    private final Handler mHandler;

    // Thumbnail checking will be done when there is no getBitmap requests
    // need to be processed.
    private ThumbnailChecker mThumbnailChecker;

    /**
     * Notify interface of how many thumbnails are processed.
     */
    public interface ThumbCheckCallback {
        public boolean checking(int current, int count);
        public void done();
    }

    public interface LoadedCallback {
        public void run(Bitmap result);
    }

    public boolean cancel(final IImage image) {
        synchronized (mQueue) {
            WorkItem w = new WorkItem(image, 0, null, false);

            int existing = mQueue.indexOf(w);
            if (existing >= 0) {
                mQueue.remove(existing);
                return true;
            }
            return false;
        }
    }

    public void getBitmap(IImage image,
                          int tag,
                          LoadedCallback imageLoadedRunnable,
                          boolean postAtFront,
                          boolean postBack) {
        if (mDecodeThread == null) {
            start();
        }
        synchronized (mQueue) {
            WorkItem w =
                    new WorkItem(image, tag, imageLoadedRunnable, postBack);

            if (mInProgress.contains(w)) return;

            boolean contains = mQueue.contains(w);
            if (contains) {
                if (postAtFront) {
                    // move this item to the front
                    mQueue.remove(w);
                    mQueue.add(0, w);
                }
            } else {
                if (postAtFront) {
                    mQueue.add(0, w);
                } else {
                    mQueue.add(w);
                }
                mQueue.notifyAll();
            }
        }
    }

    private class WorkItem {
        IImage mImage;
        int mTag;
        LoadedCallback mOnLoadedRunnable;
        boolean mPostBack;

        WorkItem(IImage image, int tag, LoadedCallback onLoadedRunnable,
                 boolean postBack) {
            mImage = image;
            mTag = tag;
            mOnLoadedRunnable = onLoadedRunnable;
            mPostBack = postBack;
        }

        @Override
        public boolean equals(Object other) {
            WorkItem otherWorkItem = (WorkItem) other;
            return otherWorkItem.mImage == mImage;
        }

        @Override
        public int hashCode() {
            return mImage.fullSizeImageUri().hashCode();
        }
    }

    public ImageLoader(Handler handler) {
        mHandler = handler;
        mThumbnailChecker = new ThumbnailChecker();
        start();
    }

    private class WorkerThread implements Runnable {
        // pick off items on the queue, one by one, and compute
        // their bitmap. place the resulting bitmap in the cache.
        // then post a notification back to the ui so things can
        // get updated appropriately.
        public void run() {
            while (!mDone) {
                WorkItem workItem = null;
                synchronized (mQueue) {
                    if (mQueue.size() > 0) {
                        workItem = mQueue.remove(0);
                        mInProgress.add(workItem);
                    } else {
                        if (!mThumbnailChecker.hasMoreThumbnailsToCheck()) {
                            try {
                                mQueue.wait();
                            } catch (InterruptedException ex) {
                                // ignore the exception
                            }
                            continue;
                        }
                    }
                }

                // This holds if and only if the above
                // hasMoreThumbnailsToCheck() returns true. (We put the call
                // here because we want to release the lock on mQueue.
                if (workItem == null) {
                    mThumbnailChecker.checkNextThumbnail();
                    continue;
                }

                final Bitmap b = workItem.mImage.miniThumbBitmap();

                synchronized (mQueue) {
                    mInProgress.remove(workItem);
                }

                if (mDone) {
                    break;
                }

                if (workItem.mOnLoadedRunnable != null) {
                    if (workItem.mPostBack) {
                        final WorkItem w = workItem;
                        mHandler.post(new Runnable() {
                            public void run() {
                                w.mOnLoadedRunnable.run(b);
                            }
                        });
                    } else {
                        workItem.mOnLoadedRunnable.run(b);
                    }
                }
            }
        }
    }

    private synchronized void start() {
        if (mDecodeThread != null) {
            return;
        }

        mDone = false;
        Thread t = new Thread(new WorkerThread());
        t.setName("image-loader");
        mDecodeThread = t;
        t.start();
    }

    public synchronized void stop() {
        mDone = true;
        synchronized (mQueue) {
            mQueue.notifyAll();
        }
        if (mDecodeThread != null) {
            try {
                Thread t = mDecodeThread;
                BitmapManager.instance().cancelThreadDecoding(t);
                t.join();
                mDecodeThread = null;
            } catch (InterruptedException ex) {
                // so now what?
            }
        }
        stopCheckingThumbnails();
    }

    // Passthrough to ThumbnailChecker.
    public void startCheckingThumbnails(IImageList imageList,
            ThumbCheckCallback cb) {
        mThumbnailChecker.startCheckingThumbnails(imageList, cb);
        // Kick WorkerThread to start working.
        synchronized (mQueue) {
            mQueue.notifyAll();
        }
    }

    public void stopCheckingThumbnails() {
        mThumbnailChecker.stopCheckingThumbnails();
    }
}

// This is part of ImageLoader which is responsible for checking thumbnails.
//
// The methods of ThumbnailChecker need to be synchronized because the data
// will also be accessed by the WorkerThread. The methods of ThumbnailChecker
// is only called by ImageLoader.
class ThumbnailChecker {
    private static final String TAG = "ThumbnailChecker";

    private IImageList mImageListToCheck;  // The image list we will check.
    private int mTotalToCheck;  // total number of thumbnails to check.
    private int mNextToCheck;  // next thumbnail to check,
                               // -1 if no further checking is needed.
    private ImageLoader.ThumbCheckCallback mThumbCheckCallback;

    ThumbnailChecker() {
        mNextToCheck = -1;
    }

    // Both imageList and cb must be non-null.
    synchronized void startCheckingThumbnails(IImageList imageList,
            ImageLoader.ThumbCheckCallback cb) {
        assert imageList != null;
        assert cb != null;
        mImageListToCheck = imageList;
        mTotalToCheck = imageList.getCount();
        mNextToCheck = 0;
        mThumbCheckCallback = cb;

        if (!ImageManager.hasStorage()) {
            Log.v(TAG, "bailing from the image checker -- no storage");
            stopCheckingThumbnails();
        }
    }

    synchronized void stopCheckingThumbnails() {
        if (mThumbCheckCallback == null) return;  // alreay stopped.
        mThumbCheckCallback.done();
        mImageListToCheck = null;
        mTotalToCheck = 0;
        mNextToCheck = -1;
        mThumbCheckCallback = null;
    }

    synchronized boolean hasMoreThumbnailsToCheck() {
        return mNextToCheck != -1;
    }

    synchronized void checkNextThumbnail() {
        if (mNextToCheck == -1) {
            return;
        }

        if (mNextToCheck >= mTotalToCheck) {
            stopCheckingThumbnails();
            return;
        }

        try {
            mImageListToCheck.checkThumbnail(mNextToCheck);
        } catch (IOException ex) {
            Log.e(TAG, "Failed to check thumbnail..."
                    + " was the sd card removed? - " + ex.getMessage());
            stopCheckingThumbnails();
        }

        if (!mThumbCheckCallback.checking(mNextToCheck, mTotalToCheck)) {
            stopCheckingThumbnails();
        }

        mNextToCheck++;
    }
}
