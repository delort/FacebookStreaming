package net.ossrs.yasea;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;

import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterFactory;
import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGLUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by Leo Ma on 2016/2/25.
 */
public class SrsCameraView2 extends GLSurfaceView implements GLSurfaceView.Renderer {

    private GPUImageFilter magicFilter;
    private SurfaceTexture surfaceTexture;
    private int mOESTextureId = OpenGLUtils.NO_TEXTURE;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mPreviewWidth;
    private int mPreviewHeight;
    private volatile boolean mIsEncoding;
    private boolean mIsTorchOn = false;
    private float mInputAspectRatio;
    private float mOutputAspectRatio;
    private float[] mProjectionMatrix = new float[16];
    private float[] mSurfaceMatrix = new float[16];
    private float[] mTransformMatrix = new float[16];

    private ByteBuffer mGLPreviewBuffer;
    private int mCamId = -1;
    private int mPreviewRotation = 90;
    private int mPreviewOrientation = Configuration.ORIENTATION_PORTRAIT;

    private Thread worker;
    private final Object writeLock = new Object();
    private ConcurrentLinkedQueue<IntBuffer> mGLIntBufferCache = new ConcurrentLinkedQueue<>();
    private PreviewCallback mPrevCb;

    public SrsCameraView2(Context context) {
        this(context, null);
    }

    public SrsCameraView2(Context context, AttributeSet attrs) {
        super(context, attrs);

        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {

        Log.e("TAG", "onSurfaceCreated");
        GLES20.glDisable(GL10.GL_DITHER);
        GLES20.glClearColor(0, 0, 0, 0);

        magicFilter = new GPUImageFilter(MagicFilterType.NONE);
        magicFilter.init(getContext().getApplicationContext());
        magicFilter.onInputSizeChanged(mPreviewWidth, mPreviewHeight);

        mOESTextureId = OpenGLUtils.getExternalOESTextureID();
        surfaceTexture = new SurfaceTexture(mOESTextureId);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                requestRender();
            }
        });

    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.e("TAG", "onSurfaceChanged");

        GLES20.glViewport(0, 0, width, height);
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        magicFilter.onDisplaySizeChanged(width, height);

        mOutputAspectRatio = width > height ? (float) width / height : (float) height / width;
        float aspectRatio = mOutputAspectRatio / mInputAspectRatio;
        if (width > height) {
            Matrix.orthoM(mProjectionMatrix, 0, -1.0f, 1.0f, -aspectRatio, aspectRatio, -1.0f, 1.0f);
        } else {
            Matrix.orthoM(mProjectionMatrix, 0, -aspectRatio, aspectRatio, -1.0f, 1.0f, -1.0f, 1.0f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        Log.e("TAG", "onDrawFrame");

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        surfaceTexture.updateTexImage();

        surfaceTexture.getTransformMatrix(mSurfaceMatrix);
        Matrix.multiplyMM(mTransformMatrix, 0, mSurfaceMatrix, 0, mProjectionMatrix, 0);
        magicFilter.setTextureTransformMatrix(mTransformMatrix);
        magicFilter.onDrawFrame(mOESTextureId);

        if (mIsEncoding) {
            mGLIntBufferCache.add(magicFilter.getGLFboBuffer());
            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }
    }

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
    }


    private void deleteTextures() {
        if (mOESTextureId != OpenGLUtils.NO_TEXTURE) {
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    GLES20.glDeleteTextures(1, new int[]{ mOESTextureId }, 0);
                    mOESTextureId = OpenGLUtils.NO_TEXTURE;
                }
            });
        }
    }

    public int[] setPreviewResolution(int width, int height) {
        getHolder().setFixedSize(width, height);

        mPreviewWidth = width;
        mPreviewHeight = height;

        mGLPreviewBuffer = ByteBuffer.allocateDirect(mPreviewWidth * mPreviewHeight * 4);
        mInputAspectRatio = mPreviewWidth > mPreviewHeight ?
                (float) mPreviewWidth / mPreviewHeight : (float) mPreviewHeight / mPreviewWidth;

        return new int[] { mPreviewWidth, mPreviewHeight };
    }


    public void enableEncoding() {
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    while (!mGLIntBufferCache.isEmpty()) {
                        IntBuffer picture = mGLIntBufferCache.poll();
                        mGLPreviewBuffer.asIntBuffer().put(picture.array());
                        mPrevCb.onGetRgbaFrame(mGLPreviewBuffer.array(), mPreviewWidth, mPreviewHeight);
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();
        mIsEncoding = true;
    }

    public void disableEncoding() {
        mIsEncoding = false;
        mGLIntBufferCache.clear();

        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            worker = null;
        }
    }

    public interface PreviewCallback {

        void onGetRgbaFrame(byte[] data, int width, int height);
    }
}
