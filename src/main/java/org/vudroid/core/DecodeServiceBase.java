package org.vudroid.core;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import org.vudroid.core.codec.CodecContext;
import org.vudroid.core.codec.CodecDocument;
import org.vudroid.core.codec.CodecPage;
import org.vudroid.core.utils.PathFromUri;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DecodeServiceBase implements DecodeService
{
    private final CodecContext codecContext;

    private View containerView;
    private CodecDocument document;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    public static final String DECODE_SERVICE = "ViewDroidDecodeService";
    private final Map<Object, Future<?>> decodingFutures = new ConcurrentHashMap<Object, Future<?>>();
    private final HashMap<Integer, SoftReference<CodecPage>> pages = new HashMap<Integer, SoftReference<CodecPage>>();
    private ContentResolver contentResolver;

    public DecodeServiceBase(CodecContext codecContext)
    {
        this.codecContext = codecContext;
    }

    public void setContentResolver(ContentResolver contentResolver)
    {
        this.contentResolver = contentResolver;
        codecContext.setContentResolver(contentResolver);
    }

    public void setContainerView(View containerView)
    {
        this.containerView = containerView;
    }

    public void open(Uri fileUri)
    {
        document = codecContext.openDocument(PathFromUri.retrieve(contentResolver, fileUri));
    }

    public void decodePage(Object decodeKey, int pageNum, final DecodeCallback decodeCallback, float zoom, RectF pageSliceBounds)
    {
        final DecodeTask decodeTask = new DecodeTask(pageNum, decodeCallback, zoom, decodeKey, pageSliceBounds);
        synchronized (decodingFutures)
        {
            final Future<?> future = executorService.submit(new Runnable()
            {
                public void run()
                {
                    try
                    {
                        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                        performDecode(decodeTask);
                    }
                    catch (IOException e)
                    {
                        Log.e(DECODE_SERVICE, "Decode fail", e);
                    }
                }
            });
            final Future<?> removed = decodingFutures.put(decodeKey, future);
            if (removed != null)
            {
                removed.cancel(false);
            }
        }
    }

    public void stopDecoding(Object decodeKey)
    {
        final Future<?> future = decodingFutures.remove(decodeKey);
        if (future != null)
        {
            future.cancel(false);
        }
    }

    private void performDecode(DecodeTask currentDecodeTask)
            throws IOException
    {
        if (isTaskDead(currentDecodeTask))
        {
            Log.d(DECODE_SERVICE, "Skipping decode task for page " + currentDecodeTask.pageNumber);
            return;
        }
        Log.d(DECODE_SERVICE, "Starting decode of page: " + currentDecodeTask.pageNumber);
        CodecPage vuPage = getPage(currentDecodeTask.pageNumber);
        preloadNextPage(currentDecodeTask.pageNumber);

        while (vuPage.isDecoding())
        {
            if (isTaskDead(currentDecodeTask))
            {
                break;
            }
            waitForDecode(vuPage);
        }
        if (isTaskDead(currentDecodeTask))
        {
            return;
        }
        Log.d(DECODE_SERVICE, "Start converting map to bitmap");
        float scale = calculateScale(vuPage) * currentDecodeTask.zoom;
        final Bitmap bitmap = vuPage.renderBitmap(getScaledWidth(currentDecodeTask, vuPage, scale), getScaledHeight(currentDecodeTask, vuPage, scale), currentDecodeTask.pageSliceBounds);
        Log.d(DECODE_SERVICE, "Converting map to bitmap finished");
        if (isTaskDead(currentDecodeTask))
        {
            bitmap.recycle();
            return;
        }
        finishDecoding(currentDecodeTask, bitmap);
    }

    private int getScaledHeight(DecodeTask currentDecodeTask, CodecPage vuPage, float scale)
    {
        return Math.round(getScaledHeight(vuPage, scale) * currentDecodeTask.pageSliceBounds.height());
    }

    private int getScaledWidth(DecodeTask currentDecodeTask, CodecPage vuPage, float scale)
    {
        return Math.round(getScaledWidth(vuPage, scale) * currentDecodeTask.pageSliceBounds.width());
    }

    private int getScaledHeight(CodecPage vuPage, float scale)
    {
        return (int) (scale * vuPage.getHeight());
    }

    private int getScaledWidth(CodecPage vuPage, float scale)
    {
        return (int) (scale * vuPage.getWidth());
    }

    private float calculateScale(CodecPage codecPage)
    {
        return 1.0f * getTargetWidth() / codecPage.getWidth();
    }

    private void finishDecoding(DecodeTask currentDecodeTask, Bitmap bitmap)
    {
        updateImage(currentDecodeTask, bitmap);
        stopDecoding(currentDecodeTask.pageNumber);
    }

    private void preloadNextPage(int pageNumber) throws IOException
    {
        final int nextPage = pageNumber + 1;
        if (nextPage >= getPageCount())
        {
            return;
        }
        getPage(nextPage);
    }

    private CodecPage getPage(int pageIndex)
    {
        if (!pages.containsKey(pageIndex) || pages.get(pageIndex).get() == null)
        {
            pages.put(pageIndex, new SoftReference<CodecPage>(document.getPage(pageIndex)));
        }
        return pages.get(pageIndex).get();
    }

    private void waitForDecode(CodecPage vuPage)
    {
        vuPage.waitForDecode();
    }

    private int getTargetWidth()
    {
        return containerView.getWidth();
    }

    public int getEffectivePagesWidth()
    {
        final CodecPage page = getPage(0);
        return getScaledWidth(page, calculateScale(page));
    }

    public int getEffectivePagesHeight()
    {
        final CodecPage page = getPage(0);
        return getScaledHeight(page, calculateScale(page));
    }

    public int getPageWidth(int pageIndex)
    {
        return getPage(pageIndex).getWidth();
    }

    public int getPageHeight(int pageIndex)
    {
        return getPage(pageIndex).getHeight();
    }

    private void updateImage(final DecodeTask currentDecodeTask, Bitmap bitmap)
    {
        currentDecodeTask.decodeCallback.decodeComplete(bitmap);
    }

    private boolean isTaskDead(DecodeTask currentDecodeTask)
    {
        synchronized (decodingFutures)
        {
            return !decodingFutures.containsKey(currentDecodeTask.decodeKey);
        }
    }

    public int getPageCount()
    {
        return document.getPageCount();
    }

    private class DecodeTask
    {
        private final Object decodeKey;
        private final int pageNumber;
        private final float zoom;
        private final DecodeCallback decodeCallback;
        private final RectF pageSliceBounds;

        private DecodeTask(int pageNumber, DecodeCallback decodeCallback, float zoom, Object decodeKey, RectF pageSliceBounds)
        {
            this.pageNumber = pageNumber;
            this.decodeCallback = decodeCallback;
            this.zoom = zoom;
            this.decodeKey = decodeKey;
            this.pageSliceBounds = pageSliceBounds;
        }
    }

}
