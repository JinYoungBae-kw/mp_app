package com.moadeal;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.Blob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ProductImages {
    private static final int MAX_EDGE = 768;
    private static final int MAX_BYTES = 120 * 1024;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    static Task<Blob> encode(Context context, String path) {
        if (path == null || path.isEmpty()) return Tasks.forResult(null);
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        return Tasks.call(worker, () -> {
            Bitmap bitmap = read(resolver, Uri.parse(path));
            try {
                Blob jpeg = compress(bitmap);
                if (jpeg != null) return jpeg;
                double scale = Math.min(1.0, 512.0 / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                if (scale < 1.0) {
                    Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (scale * bitmap.getWidth())),
                        Math.max(1, (int) (scale * bitmap.getHeight())), true);
                    try {
                        jpeg = compress(smaller);
                        if (jpeg != null) return jpeg;
                    } finally {
                        smaller.recycle();
                    }
                }
                throw new IOException("사진을 공유할 크기로 줄이지 못했습니다.");
            } finally {
                bitmap.recycle();
            }
        });
    }

    private static Blob compress(Bitmap bitmap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int quality = 85; quality >= 25; quality -= 15) {
            bytes.reset();
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes) && bytes.size() <= MAX_BYTES) {
                return Blob.fromBytes(bytes.toByteArray());
            }
        }
        return null;
    }

    private static Bitmap read(ContentResolver resolver, Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                android.util.Size size = info.getSize();
                double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(size.getWidth(), size.getHeight()));
                decoder.setTargetSize(Math.max(1, (int) (scale * size.getWidth())),
                    Math.max(1, (int) (scale * size.getHeight())));
            });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, options);
        }
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > MAX_EDGE) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        try (InputStream input = resolver.openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IOException("선택한 사진을 읽지 못했습니다.");
            return bitmap;
        }
    }

    static Task<Bitmap> decode(Blob photo) {
        return Tasks.call(worker, () -> {
            if (photo == null) return null;
            byte[] bytes = photo.toBytes();
            if (bytes.length == 0 || bytes.length > MAX_BYTES) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > MAX_EDGE || options.outHeight > MAX_EDGE) {
                return null;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        });
    }
}
