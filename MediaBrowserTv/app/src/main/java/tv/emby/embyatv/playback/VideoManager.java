package tv.emby.embyatv.playback;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.VideoView;

import org.acra.ACRA;
import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;

import java.util.ArrayList;

import tv.emby.embyatv.R;
import tv.emby.embyatv.TvApp;
import tv.emby.embyatv.util.Utils;

/**
 * Created by Eric on 7/11/2015.
 */
public class VideoManager implements IVLCVout.Callback {

    private PlaybackOverlayActivity mActivity;
    private SurfaceHolder mSurfaceHolder;
    private SurfaceView mSurfaceView;
    private FrameLayout mSurfaceFrame;
    private VideoView mVideoView;
    private LibVLC mLibVLC;
    private org.videolan.libvlc.MediaPlayer mVlcPlayer;
    private String mCurrentVideoPath;
    private String mCurrentVideoMRL;
    private Media mCurrentMedia;
    private VlcEventHandler mVlcHandler = new VlcEventHandler();
    private Handler mHandler = new Handler();
    private int mVideoHeight;
    private int mVideoWidth;
    private int mVideoVisibleHeight;
    private int mVideoVisibleWidth;
    private int mSarNum;
    private int mSarDen;

    private long mForcedTime = -1;
    private long mLastTime = -1;
    private long mMetaDuration = -1;

    private boolean nativeMode = false;
    private boolean mSurfaceReady = false;

    public VideoManager(PlaybackOverlayActivity activity, View view, int buffer) {
        mActivity = activity;
        mSurfaceView = (SurfaceView) view.findViewById(R.id.player_surface);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(mSurfaceCallback);
        mSurfaceFrame = (FrameLayout) view.findViewById(R.id.player_surface_frame);
        mVideoView = (VideoView) view.findViewById(R.id.videoView);

        createPlayer(buffer);

    }

    public void setNativeMode(boolean value) {
        nativeMode = value;
        if (nativeMode) {
            mVideoView.setVisibility(View.VISIBLE);
        } else {
            mVideoView.setVisibility(View.GONE);
        }
    }

    public boolean isNativeMode() { return nativeMode; }

    public void setMetaDuration(long duration) {
        mMetaDuration = duration;
    }

    public long getDuration() {
        return nativeMode ? mVideoView.getDuration() : mVlcPlayer.getLength() > 0 ? mVlcPlayer.getLength() : mMetaDuration;
    }

    public long getCurrentPosition() {
        if (nativeMode) return mVideoView.getCurrentPosition();

        long time = mVlcPlayer.getTime();
        if (mForcedTime != -1 && mLastTime != -1) {
            /* XXX: After a seek, mLibVLC.getTime can return the position before or after
             * the seek position. Therefore we return mForcedTime in order to avoid the seekBar
             * to move between seek position and the actual position.
             * We have to wait for a valid position (that is after the seek position).
             * to re-init mLastTime and mForcedTime to -1 and return the actual position.
             */
            if (mLastTime > mForcedTime) {
                if (time <= mLastTime && time > mForcedTime)
                    mLastTime = mForcedTime = -1;
            } else {
                if (time > mForcedTime)
                    mLastTime = mForcedTime = -1;
            }
        }
        return mForcedTime == -1 ? time : mForcedTime;
    }

    public boolean isPlaying() {
        return nativeMode ? mVideoView.isPlaying() : mVlcPlayer != null && mVlcPlayer.isPlaying();
    }

    public void start() {
        if (nativeMode) {
            mVideoView.start();
        } else {
            if (!mSurfaceReady) {
                TvApp.getApplication().getLogger().Error("Attempt to play before surface ready");
                return;
            }

            if (!mVlcPlayer.isPlaying()) {
                mVlcPlayer.play();
            }
        }

    }

    public void play() {
        if (nativeMode) {
            mVideoView.start();
        } else {
            mVlcPlayer.play();
            mSurfaceView.setKeepScreenOn(true);
            // work around losing audio when pausing bug
            int sav = mVlcPlayer.getAudioTrack();
            mVlcPlayer.setAudioTrack(-1);
            mVlcPlayer.setAudioTrack(sav);
            //
        }
    }

    public void pause() {
        if (nativeMode) {
            mVideoView.pause();
        } else {
            mVlcPlayer.pause();
            mSurfaceView.setKeepScreenOn(false);
        }

    }

    public void setPlaySpeed(float speed) {
        if (!nativeMode) mVlcPlayer.setRate(speed);
    }

    public void stopPlayback() {
        if (nativeMode) {
            mVideoView.stopPlayback();
        } else {
            mVlcPlayer.stop();
        }
    }

    public long seekTo(long pos) {
        if (nativeMode) {
            Long intPos = pos;
            mVideoView.seekTo(intPos.intValue());
            return pos;
        } else {
            if (mVlcPlayer == null || !mVlcPlayer.isSeekable()) return -1;
            mForcedTime = pos;
            mLastTime = mVlcPlayer.getTime();
            TvApp.getApplication().getLogger().Info("VLC length in seek is: " + mVlcPlayer.getLength());
            try {
                if (getDuration() > 0) mVlcPlayer.setPosition((float)pos / getDuration()); else mVlcPlayer.setTime(pos);

                return pos;

            } catch (Exception e) {
                TvApp.getApplication().getLogger().ErrorException("Error seeking in VLC", e);
                Utils.showToast(mActivity, "Unable to seek");
                return -1;
            }
        }
    }

    public void setVideoPath(String path) {
        mCurrentVideoPath = path;

        if (nativeMode) {
            try {
                mVideoView.setVideoPath(path);
            } catch (IllegalStateException e) {
                TvApp.getApplication().getLogger().ErrorException("Unable to set video path.  Probably backing out.", e);
            }
        } else {
            mSurfaceHolder.setKeepScreenOn(true);

            mCurrentMedia = new Media(mLibVLC, Uri.parse(path));
            mCurrentMedia.parse();
            mVlcPlayer.setMedia(mCurrentMedia);

            mCurrentMedia.release();
        }

    }

    public void setSubtitleTrack(int id) {
        if (!nativeMode) mVlcPlayer.setSpuTrack(id);

    }

    public boolean addSubtitleTrack(String path) {
        return !nativeMode && mVlcPlayer.setSubtitleFile(path);
    }

    public int getAudioTrack() {
        return nativeMode ? -1 : mVlcPlayer.getAudioTrack();
    }

    public void setAudioTrack(int id) {
        if (!nativeMode) mVlcPlayer.setAudioTrack(id);
    }

    public org.videolan.libvlc.MediaPlayer.TrackDescription[] getSubtitleTracks() {
        return nativeMode ? null : mVlcPlayer.getSpuTracks();
    }

    public void destroy() {
        releasePlayer();
    }

    private void createPlayer(int buffer) {
        try {

            // Create a new media player
            ArrayList<String> options = new ArrayList<>(20);
            options.add("--network-caching=" + buffer);
            options.add("--no-audio-time-stretch");
            options.add("--androidwindow-chroma");
            options.add("RV32");
            options.add("-vv");

            mLibVLC = new LibVLC(options);
            TvApp.getApplication().getLogger().Info("Network buffer set to " + buffer);
            LibVLC.setOnNativeCrashListener(new LibVLC.OnNativeCrashListener() {
                @Override
                public void onNativeCrash() {
                    new Exception().printStackTrace();
                    Utils.PutCustomAcraData();
                    ACRA.getErrorReporter().handleException(new Exception("Error in LibVLC"), false);
                    mActivity.finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            });

            mVlcPlayer = new org.videolan.libvlc.MediaPlayer(mLibVLC);
            SharedPreferences prefs = TvApp.getApplication().getPrefs();
            String audioOption = prefs.getString("pref_audio_option","0");
            mVlcPlayer.setAudioOutput("0".equals(audioOption) ? "android_audiotrack" : "opensles_android");
            mVlcPlayer.setAudioOutputDevice("hdmi");


            mSurfaceHolder.addCallback(mSurfaceCallback);
            mVlcPlayer.setEventListener(mVlcHandler);
            mVlcPlayer.getVLCVout().addCallback(this);

        } catch (Exception e) {
            TvApp.getApplication().getLogger().ErrorException("Error creating VLC player", e);
            Utils.showToast(TvApp.getApplication(), TvApp.getApplication().getString(R.string.msg_video_playback_error));
        }
    }

    private void releasePlayer() {
        if (mVlcPlayer == null) return;

        mVlcPlayer.setEventListener(null);
        mVlcPlayer.stop();
        mVlcPlayer.getVLCVout().detachViews();
        mVlcPlayer.release();
        mLibVLC = null;
        mVlcPlayer = null;
        mSurfaceView.setKeepScreenOn(false);

    }

    private void changeSurfaceLayout(int videoWidth, int videoHeight, int videoVisibleWidth, int videoVisibleHeight, int sarNum, int sarDen) {
        int sw;
        int sh;

        // get screen size
        Activity activity = TvApp.getApplication().getCurrentActivity();
        if (activity == null) return; //called during destroy
        sw = activity.getWindow().getDecorView().getWidth();
        sh = activity.getWindow().getDecorView().getHeight();

        double dw = sw, dh = sh;
        boolean isPortrait;

        isPortrait = mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;

        if (sw > sh && isPortrait || sw < sh && !isPortrait) {
            dw = sh;
            dh = sw;
        }

        // sanity check
        if (dw * dh == 0 || videoWidth * videoHeight == 0) {
            TvApp.getApplication().getLogger().Error("Invalid surface size");
            return;
        }

        // compute the aspect ratio
        double ar, vw;
        if (sarDen == sarNum) {
            /* No indication about the density, assuming 1:1 */
            vw = videoVisibleWidth;
            ar = (double)videoVisibleWidth / (double)videoVisibleHeight;
        } else {
            /* Use the specified aspect ratio */
            vw = videoVisibleWidth * (double)sarNum / sarDen;
            ar = vw / videoVisibleHeight;
        }

        // compute the display aspect ratio
        double dar = dw / dh;

        if (dar < ar)
            dh = dw / ar;
        else
            dw = dh * ar;


        // set display size
        ViewGroup.LayoutParams lp = mSurfaceView.getLayoutParams();
        lp.width  = (int) Math.ceil(dw * videoWidth / videoVisibleWidth);
        lp.height = (int) Math.ceil(dh * videoHeight / videoVisibleHeight);
        mSurfaceView.setLayoutParams(lp);
        //subtitlesSurface.setLayoutParams(lp);

        // set frame size (crop if necessary)
        if (mSurfaceFrame != null) {
            lp = mSurfaceFrame.getLayoutParams();
            lp.width = (int) Math.floor(dw);
            lp.height = (int) Math.floor(dh);
            mSurfaceFrame.setLayoutParams(lp);

        }

        TvApp.getApplication().getLogger().Debug("Surface sized "+ mVideoWidth+"x"+mVideoHeight);
        mSurfaceView.invalidate();
//        subtitlesSurface.invalidate();
    }

    public void setOnErrorListener(final PlaybackListener listener) {
        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                listener.onEvent();
                stopProgressLoop();
                return true;
            }
        });

        mVlcHandler.setOnErrorListener(listener);
    }

    public void setOnCompletionListener(final PlaybackListener listener) {
        mVideoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                listener.onEvent();
                stopProgressLoop();
            }
        });

        mVlcHandler.setOnCompletionListener(listener);
    }

    public void setOnPreparedListener(final PlaybackListener listener) {
        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                listener.onEvent();
                startProgressLoop();
            }
        });

        mVlcHandler.setOnPreparedListener(listener);
    }

    public void setOnProgressListener(PlaybackListener listener) {
        progressListener = listener;
        mVlcHandler.setOnProgressListener(listener);
    }

    private PlaybackListener progressListener;
    private Runnable progressLoop;
    private void startProgressLoop() {
        progressLoop = new Runnable() {
            @Override
            public void run() {
                if (progressListener != null) progressListener.onEvent();
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler.post(progressLoop);
    }

    private void stopProgressLoop() {
        if (progressLoop != null) {
            mHandler.removeCallbacks(progressLoop);
        }
    }

    private SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {

        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mVlcPlayer != null) {
                mVlcPlayer.getVLCVout().detachViews();
                mVlcPlayer.getVLCVout().setVideoView(mSurfaceView);
                mVlcPlayer.getVLCVout().attachViews();
                TvApp.getApplication().getLogger().Debug("Surface attached");
                mSurfaceReady = true;
            }

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (mVlcPlayer != null) mVlcPlayer.getVLCVout().detachViews();
            mSurfaceReady = false;

        }
    };

    @Override
    public void onNewLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width * height == 0)
            return;

        // store video size
        mVideoHeight = height;
        mVideoWidth = width;
        mVideoVisibleHeight = visibleHeight;
        mVideoVisibleWidth  = visibleWidth;
        mSarNum = sarNum;
        mSarDen = sarDen;

        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                changeSurfaceLayout(mVideoWidth, mVideoHeight, mVideoVisibleWidth, mVideoVisibleHeight, mSarNum, mSarDen);
            }
        });

    }

    @Override
    public void onSurfacesCreated(IVLCVout ivlcVout) {

    }

    @Override
    public void onSurfacesDestroyed(IVLCVout ivlcVout) {

    }

}
