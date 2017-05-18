package cn.garymb.ygomobile.core.loader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.widget.ImageView;

import com.bumptech.glide.DrawableTypeRequest;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.model.ImageVideoWrapper;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.load.resource.bitmap.GlideBitmapDrawable;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.load.resource.gifbitmap.GifBitmapWrapper;
import com.bumptech.glide.load.resource.gifbitmap.GifBitmapWrapperResource;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.GlideDrawableImageViewTarget;
import com.bumptech.glide.signature.StringSignature;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import cn.garymb.ygomobile.Constants;
import cn.garymb.ygomobile.core.AppsSettings;
import cn.garymb.ygomobile.core.IrrlichtBridge;
import cn.garymb.ygomobile.lite.R;
import cn.garymb.ygomobile.utils.BitmapUtil;
import cn.garymb.ygomobile.utils.IOUtils;
import cn.garymb.ygomobile.utils.MD5Util;
import cn.garymb.ygomobile.utils.NetUtils;

import static cn.garymb.ygomobile.Constants.CORE_SKIN_BG_SIZE;
import static com.bumptech.glide.Glide.with;

public class ImageLoader implements Closeable {
    private static final String TAG = ImageLoader.class.getSimpleName();
    private ZipFile mZipFile;
    private LruBitmapPool mLruBitmapPool;
    private ExecutorService mExecutorService = Executors.newSingleThreadExecutor();
    private boolean isClose = false;
    private Context mContext;

    public ImageLoader(Context context) {
        mContext = context;
        mLruBitmapPool = new LruBitmapPool(100);
    }

    private class BpgResourceDecoder implements ResourceDecoder<ImageVideoWrapper, GifBitmapWrapper> {
        String id;

        private BpgResourceDecoder(String id) {
            this.id = id;
        }

        @Override
        public Resource<GifBitmapWrapper> decode(ImageVideoWrapper source, int width, int height) throws IOException {
//            Log.i("kk", "decode source:"+source);
            Bitmap bitmap = IrrlichtBridge.getBpgImage(source.getStream(), Bitmap.Config.RGB_565);
//            Log.i("kk", "decode bitmap:"+bitmap);
            BitmapResource resource = new BitmapResource(bitmap, mLruBitmapPool);
            return new GifBitmapWrapperResource(new GifBitmapWrapper(resource, null));
        }

        @Override
        public String getId() {
            return id;
        }
    }

    @Override
    public void close() throws IOException {
        isClose = true;
//        if (!mExecutorService.isShutdown()) {
//            mExecutorService.shutdown();
//        }
    }

    private Bitmap loadImage(String path, int w, int h) {
        File file = new File(path);
        if (file.exists()) {
            return BitmapUtil.getBitmapFromFile(file.getAbsolutePath(), CORE_SKIN_BG_SIZE[0], CORE_SKIN_BG_SIZE[1]);
        }
        return null;
    }

    private void bind(byte[] data, ImageView imageview, boolean isbpg, long code, Drawable pre, boolean isBig) {
        DrawableTypeRequest<byte[]> resource = with(mContext).load(data);
        if (pre != null) {
            resource.placeholder(pre);
        } else {
//            resource.placeholder(R.drawable.unknown);
        }
        resource.animate(R.anim.push_in);
//        if(isbpg){
//            resource.override(Constants.CORE_SKIN_CARD_COVER_SIZE[0], Constants.CORE_SKIN_CARD_COVER_SIZE[1]);
//        }
        resource.signature(new StringSignature(MD5Util.getStringMD5(data.length + "_" + code + "_" + isBig)));
        if (isbpg) {
            resource.decoder(new BpgResourceDecoder("bpg@" + code));
        }
        resource.into(imageview);
    }

    public void bind(final File file, ImageView imageview, boolean isbpg, long code, Drawable pre, boolean isBig) {
        try {
            DrawableTypeRequest<File> resource = with(mContext).load(file);
            if (pre != null) {
                resource.placeholder(pre);
            } else {
//                resource.placeholder(R.drawable.unknown);
            }
            resource.animate(R.anim.push_in);
//            if(isbpg){
//                resource.override(Constants.CORE_SKIN_CARD_COVER_SIZE[0], Constants.CORE_SKIN_CARD_COVER_SIZE[1]);
//            }
            resource.signature(new StringSignature(MD5Util.getStringMD5(file.length() + code + "_" + isBig)));
            if (isbpg) {
                resource.decoder(new BpgResourceDecoder("bpg@" + code));
            }
            resource.into(imageview);
        } catch (Exception e) {
            Log.e(TAG, "bind", e);
        }
    }

    private void bind(final String url, ImageView imageview, long code, Drawable pre, boolean isBig) {
        DrawableTypeRequest<Uri> resource = with(mContext).load(Uri.parse(url));
        if (pre != null) {
            resource.placeholder(pre);
        } else {
            resource.placeholder(R.drawable.unknown);
        }
        resource.error(R.drawable.unknown);
        resource.override(Constants.CORE_SKIN_CARD_COVER_SIZE[0], Constants.CORE_SKIN_CARD_COVER_SIZE[1]);
        resource.signature(new StringSignature(code + "_" + isBig));
//
        resource.into(new GlideDrawableImageViewTarget(imageview) {
            @Override
            public void onResourceReady(GlideDrawable resource, GlideAnimation<? super GlideDrawable> animation) {
                super.onResourceReady(resource, animation);
                if (resource != null && !isClose) {
                    if (resource instanceof GlideBitmapDrawable) {
                        GlideBitmapDrawable glideBitmapDrawable = (GlideBitmapDrawable) resource;
                        Bitmap bitmap = glideBitmapDrawable.getBitmap();
                        if (bitmap != null) {
                            File file = new File(AppsSettings.get().getResourcePath(), Constants.CORE_IMAGE_PATH + "/" + code + ".jpg");
                            if (!file.exists()) {
                                File tmp = new File(AppsSettings.get().getResourcePath(), Constants.CORE_IMAGE_PATH + "/" + code + ".tmp");
                                if (!tmp.exists()) {
                                    if (!mExecutorService.isShutdown()) {
                                        mExecutorService.submit(() -> {
                                            File dir = file.getParentFile();
                                            if (!dir.exists()) {
                                                dir.mkdirs();
                                            }
                                            try {
                                                file.createNewFile();
                                                FileOutputStream outputStream = new FileOutputStream(file);
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                                                outputStream.flush();
                                                outputStream.close();
                                                tmp.renameTo(file);
                                            } catch (Exception e) {
                                            }

                                        });
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    public void bindImage(ImageView imageview, long code) {
        bindImage(imageview, code, null);
    }

    public void bindImage(ImageView imageview, long code, Drawable pre) {
        bindImage(imageview, code, pre, false);
    }

    public void bindImage(ImageView imageview, long code, Drawable pre, boolean isBig) {
        String name = Constants.CORE_IMAGE_PATH + "/" + code;
        String path = AppsSettings.get().getResourcePath();
        boolean bind = false;
        File zip = new File(path, Constants.CORE_PICS_ZIP);
        for (String ex : Constants.IMAGE_EX) {
            File file = new File(AppsSettings.get().getResourcePath(), name + ex);
            if (file.exists()) {
                bind(file, imageview, Constants.BPG.equals(ex), code, pre, isBig);
                bind = true;
                return;
            }
        }
        if (zip.exists()) {
            ZipEntry entry = null;
            InputStream inputStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                if (mZipFile == null) {
                    mZipFile = new ZipFile(zip);
                }
                for (String ex : Constants.IMAGE_EX) {
                    entry = mZipFile.getEntry(name + ex);
                    if (entry != null) {
                        inputStream = mZipFile.getInputStream(entry);
                        outputStream = new ByteArrayOutputStream();
                        IOUtils.copy(inputStream, outputStream);
                        bind(outputStream.toByteArray(), imageview, Constants.BPG.equals(ex), code, pre, isBig);
                        bind = true;
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                IOUtils.close(inputStream);
            }
        }
        if (!bind) {
            imageview.setImageResource(R.drawable.unknown);
            if (NetUtils.isWifiConnected(imageview.getContext())) {
                bind(String.format(Constants.IMAGE_URL, "" + code), imageview, code, pre, isBig);
            }
        }
    }
}
