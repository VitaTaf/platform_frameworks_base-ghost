/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.graphics.drawable;

import android.annotation.DrawableRes;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.IllegalArgumentException;
import java.lang.Override;

/**
 * An umbrella container for several serializable graphics representations, including Bitmaps,
 * compressed bitmap images (e.g. JPG or PNG), and drawable resources (including vectors).
 *
 * <a href="https://developer.android.com/training/displaying-bitmaps/index.html">Much ink</a>
 * has been spilled on the best way to load images, and many clients may have different needs when
 * it comes to threading and fetching. This class is therefore focused on encapsulation rather than
 * behavior.
 */

public final class Icon implements Parcelable {
    private static final String TAG = "Icon";

    private static final int TYPE_BITMAP   = 1;
    private static final int TYPE_RESOURCE = 2;
    private static final int TYPE_DATA     = 3;
    private static final int TYPE_URI      = 4;

    private final int mType;

    // To avoid adding unnecessary overhead, we have a few basic objects that get repurposed
    // based on the value of mType.

    // TYPE_BITMAP: Bitmap
    // TYPE_RESOURCE: Resources
    // TYPE_DATA: DataBytes
    private Object          mObj1;

    // TYPE_RESOURCE: package name
    // TYPE_URI: uri string
    private String          mString1;

    // TYPE_RESOURCE: resId
    // TYPE_DATA: data length
    private int             mInt1;

    // TYPE_DATA: data offset
    private int             mInt2;

    // Internal accessors for different mType variants
    private Bitmap getBitmap() {
        if (mType != TYPE_BITMAP) {
            throw new IllegalStateException("called getBitmap() on " + this);
        }
        return (Bitmap) mObj1;
    }

    private int getDataLength() {
        if (mType != TYPE_DATA) {
            throw new IllegalStateException("called getDataLength() on " + this);
        }
        synchronized (this) {
            return mInt1;
        }
    }

    private int getDataOffset() {
        if (mType != TYPE_DATA) {
            throw new IllegalStateException("called getDataOffset() on " + this);
        }
        synchronized (this) {
            return mInt2;
        }
    }

    private byte[] getDataBytes() {
        if (mType != TYPE_DATA) {
            throw new IllegalStateException("called getDataBytes() on " + this);
        }
        synchronized (this) {
            return (byte[]) mObj1;
        }
    }

    private Resources getResources() {
        if (mType != TYPE_RESOURCE) {
            throw new IllegalStateException("called getResources() on " + this);
        }
        return (Resources) mObj1;
    }

    private String getResPackage() {
        if (mType != TYPE_RESOURCE) {
            throw new IllegalStateException("called getResPackage() on " + this);
        }
        return mString1;
    }

    private int getResId() {
        if (mType != TYPE_RESOURCE) {
            throw new IllegalStateException("called getResId() on " + this);
        }
        return mInt1;
    }

    private String getUriString() {
        if (mType != TYPE_URI) {
            throw new IllegalStateException("called getUriString() on " + this);
        }
        return mString1;
    }

    private Uri getUri() {
        return Uri.parse(getUriString());
    }

    // Convert a int32 into a four-char string
    private static final String typeToString(int x) {
        switch (x) {
            case TYPE_BITMAP: return "BITMAP";
            case TYPE_DATA: return "DATA";
            case TYPE_RESOURCE: return "RESOURCE";
            case TYPE_URI: return "URI";
            default: return "UNKNOWN";
        }
    }

    /**
     * Invokes {@link #loadDrawable(Context)} on the given {@link android.os.Handler Handler}
     * and then sends <code>andThen</code> to the same Handler when finished.
     *
     * @param context {@link android.content.Context Context} in which to load the drawable; see
     *                {@link #loadDrawable(Context)}
     * @param andThen {@link android.os.Message} to send to its target once the drawable
     *                is available. The {@link android.os.Message#obj obj}
     *                property is populated with the Drawable.
     */
    public void loadDrawableAsync(Context context, Message andThen) {
        if (andThen.getTarget() == null) {
            throw new IllegalArgumentException("callback message must have a target handler");
        }
        new LoadDrawableTask(context, andThen).runAsync();
    }

    /**
     * Invokes {@link #loadDrawable(Context)} on a background thread
     * and then runs <code>andThen</code> on the UI thread when finished.
     *
     * @param context {@link android.content.Context Context} in which to load the drawable; see
     *                {@link #loadDrawable(Context)}
     * @param handler {@link android.os.Handler} on which to run <code>andThen</code>.
     * @param listener a callback to run on the provided
     *                 Handler once the drawable is available.
     */
    public void loadDrawableAsync(Context context, Handler handler,
            final OnDrawableLoadedListener listener) {
        new LoadDrawableTask(context, handler, listener).runAsync();
    }

    /**
     * Returns a Drawable that can be used to draw the image inside this Icon, constructing it
     * if necessary. Depending on the type of image, this may not be something you want to do on
     * the UI thread, so consider using
     * {@link #loadDrawableAsync(Context, Message) loadDrawableAsync} instead.
     *
     * @param context {@link android.content.Context Context} in which to load the drawable; used
     *                to access {@link android.content.res.Resources Resources}, for example.
     * @return A fresh instance of a drawable for this image, yours to keep.
     */
    public Drawable loadDrawable(Context context) {
        switch (mType) {
            case TYPE_BITMAP:
                return new BitmapDrawable(context.getResources(), getBitmap());
            case TYPE_RESOURCE:
                if (getResources() == null) {
                    if (getResPackage() == null || "android".equals(getResPackage())) {
                        mObj1 = Resources.getSystem();
                    } else {
                        final PackageManager pm = context.getPackageManager();
                        try {
                            mObj1 = pm.getResourcesForApplication(getResPackage());
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG,
                                    String.format("Unable to find package '%s'", getResPackage()),
                                    e);
                            break;
                        }
                    }
                }
                return getResources().getDrawable(getResId(), context.getTheme());
            case TYPE_DATA:
                return new BitmapDrawable(context.getResources(),
                    BitmapFactory.decodeByteArray(getDataBytes(), getDataOffset(), getDataLength())
                );
            case TYPE_URI:
                final Uri uri = getUri();
                final String scheme = uri.getScheme();
                InputStream is = null;
                if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                        || ContentResolver.SCHEME_FILE.equals(scheme)) {
                    try {
                        is = context.getContentResolver().openInputStream(uri);
                    } catch (Exception e) {
                        Log.w(TAG, "Unable to load image from URI: " + uri, e);
                    }
                } else {
                    try {
                        is = new FileInputStream(new File(mString1));
                    } catch (FileNotFoundException e) {
                        Log.w(TAG, "Unable to load image from path: " + uri, e);
                    }
                }
                if (is != null) {
                    return new BitmapDrawable(context.getResources(),
                            BitmapFactory.decodeStream(is));
                }
                break;
        }
        return null;
    }

    private Icon(int mType) {
        this.mType = mType;
    }

    /**
     * Create a Icon pointing to a drawable resource.
     * @param res Resources for a package containing the resource in question
     * @param resid ID of the drawable resource
     */
    public static Icon createWithResource(Resources res, @DrawableRes int resid) {
        final Icon rep = new Icon(TYPE_RESOURCE);
        rep.mObj1 = res;
        rep.mInt1 = resid;
        rep.mString1 = res.getResourcePackageName(resid);
        return rep;
    }

    /**
     * Create a Icon pointing to a bitmap in memory.
     * @param bits A valid {@link android.graphics.Bitmap} object
     */
    public static Icon createWithBitmap(Bitmap bits) {
        final Icon rep = new Icon(TYPE_BITMAP);
        rep.mObj1 = bits;
        return rep;
    }

    /**
     * Create a Icon pointing to a compressed bitmap stored in a byte array.
     * @param data Byte array storing compressed bitmap data of a type that
     *             {@link android.graphics.BitmapFactory}
     *             can decode (see {@link android.graphics.Bitmap.CompressFormat}).
     * @param offset Offset into <code>data</code> at which the bitmap data starts
     * @param length Length of the bitmap data
     */
    public static Icon createWithData(byte[] data, int offset, int length) {
        final Icon rep = new Icon(TYPE_DATA);
        rep.mObj1 = data;
        rep.mInt1 = length;
        rep.mInt2 = offset;
        return rep;
    }

    /**
     * Create a Icon pointing to a content specified by URI.
     *
     * @param uri A uri referring to local content:// or file:// image data.
     */
    public static Icon createWithContentUri(String uri) {
        final Icon rep = new Icon(TYPE_URI);
        rep.mString1 = uri;
        return rep;
    }

    /**
     * Create a Icon pointing to a content specified by URI.
     *
     * @param uri A uri referring to local content:// or file:// image data.
     */
    public static Icon createWithContentUri(Uri uri) {
        final Icon rep = new Icon(TYPE_URI);
        rep.mString1 = uri.toString();
        return rep;
    }

    /**
     * Create a Icon pointing to
     *
     * @param path A path to a file that contains compressed bitmap data of
     *           a type that {@link android.graphics.BitmapFactory} can decode.
     */
    public static Icon createWithFilePath(String path) {
        final Icon rep = new Icon(TYPE_URI);
        rep.mString1 = path;
        return rep;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Icon(typ=").append(typeToString(mType));
        switch (mType) {
            case TYPE_BITMAP:
                sb.append(" size=")
                        .append(getBitmap().getWidth())
                        .append("x")
                        .append(getBitmap().getHeight());
                break;
            case TYPE_RESOURCE:
                sb.append(" pkg=")
                        .append(getResPackage())
                        .append(" id=")
                        .append(String.format("%08x", getResId()));
                break;
            case TYPE_DATA:
                sb.append(" len=").append(getDataLength());
                if (getDataOffset() != 0) {
                    sb.append(" off=").append(getDataOffset());
                }
                break;
            case TYPE_URI:
                sb.append(" uri=").append(getUriString());
                break;
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Parcelable interface
     */
    public int describeContents() {
        return (mType == TYPE_BITMAP || mType == TYPE_DATA)
                ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
    }

    // ===== Parcelable interface ======

    private Icon(Parcel in) {
        this(in.readInt());
        switch (mType) {
            case TYPE_BITMAP:
                final Bitmap bits = Bitmap.CREATOR.createFromParcel(in);
                mObj1 = bits;
                break;
            case TYPE_RESOURCE:
                final String pkg = in.readString();
                final int resId = in.readInt();
                mString1 = pkg;
                mInt1 = resId;
                break;
            case TYPE_DATA:
                final int len = in.readInt();
                final byte[] a = in.readBlob();
                if (len != a.length) {
                    throw new RuntimeException("internal unparceling error: blob length ("
                            + a.length + ") != expected length (" + len + ")");
                }
                mInt1 = len;
                mObj1 = a;
                break;
            case TYPE_URI:
                final String uri = in.readString();
                mString1 = uri;
                break;
            default:
                throw new RuntimeException("invalid "
                        + this.getClass().getSimpleName() + " type in parcel: " + mType);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        switch (mType) {
            case TYPE_BITMAP:
                final Bitmap bits = getBitmap();
                dest.writeInt(TYPE_BITMAP);
                getBitmap().writeToParcel(dest, flags);
                break;
            case TYPE_RESOURCE:
                dest.writeInt(TYPE_RESOURCE);
                dest.writeString(getResPackage());
                dest.writeInt(getResId());
                break;
            case TYPE_DATA:
                dest.writeInt(TYPE_DATA);
                dest.writeInt(getDataLength());
                dest.writeBlob(getDataBytes(), getDataOffset(), getDataLength());
                break;
            case TYPE_URI:
                dest.writeInt(TYPE_URI);
                dest.writeString(getUriString());
                break;
        }
    }

    public static final Parcelable.Creator<Icon> CREATOR
            = new Parcelable.Creator<Icon>() {
        public Icon createFromParcel(Parcel in) {
            return new Icon(in);
        }

        public Icon[] newArray(int size) {
            return new Icon[size];
        }
    };

    /**
     * Implement this interface to receive notification when
     * {@link #loadDrawableAsync(Context, Handler, OnDrawableLoadedListener) loadDrawableAsync}
     * is finished and your Drawable is ready.
     */
    public interface OnDrawableLoadedListener {
        void onDrawableLoaded(Drawable d);
    }

    /**
     * Wrapper around loadDrawable that does its work on a pooled thread and then
     * fires back the given (targeted) Message.
     */
    private class LoadDrawableTask implements Runnable {
        final Context mContext;
        final Message mMessage;

        public LoadDrawableTask(Context context, final Handler handler,
                final OnDrawableLoadedListener listener) {
            mContext = context;
            mMessage = Message.obtain(handler, new Runnable() {
                    @Override
                    public void run() {
                        listener.onDrawableLoaded((Drawable) mMessage.obj);
                    }
                });
        }

        public LoadDrawableTask(Context context, Message message) {
            mContext = context;
            mMessage = message;
        }

        @Override
        public void run() {
            mMessage.obj = loadDrawable(mContext);
            mMessage.sendToTarget();
        }

        public void runAsync() {
            AsyncTask.THREAD_POOL_EXECUTOR.execute(this);
        }
    }
}
