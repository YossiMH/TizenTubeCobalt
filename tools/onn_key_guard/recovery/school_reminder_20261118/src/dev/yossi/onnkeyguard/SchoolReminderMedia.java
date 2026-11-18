package dev.yossi.onnkeyguard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Random;
import org.json.JSONException;
import org.json.JSONObject;

/** One overlay's local media. Existing SchoolBannerRuntime owns authorization and audio focus. */
final class SchoolReminderMedia implements TextureView.SurfaceTextureListener {
    interface Guard { boolean authorized(); }
    private static final String TAG = "SchoolReminderMedia";
    private final Context context;
    private final Guard guard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final TextureView texture;
    private final ImageView first;
    private final ImageView last;
    private final View scrim;
    private final TextView text;
    private final SchoolReminderTimeline timeline;
    private SchoolReminderAssets.Loaded assets;
    private Surface surface;
    private MediaPlayer music;
    private MediaPlayer video;
    private Thread loader;
    private boolean closed;
    private boolean failed;
    private boolean focusHeld;
    private boolean musicPrepared;
    private boolean videoPrepared;
    private boolean musicPlaying;
    private boolean videoPlaying;
    private boolean videoReady;
    private boolean videoFrameVisible;
    private boolean ready;
    private int videoWidth;
    private int videoHeight;
    private float appliedMusicVolume = -1f;
    private String error = "";
    private SchoolReminderTimeline.Frame frame;

    SchoolReminderMedia(Context context, Guard guard, final boolean boundedDiagnostic) {
        this.context = context.getApplicationContext();
        this.guard = guard;
        final Random random = new Random();
        timeline = new SchoolReminderTimeline(new SchoolReminderTimeline.HoldSource() {
            @Override public long nextHoldMs() {
                return boundedDiagnostic ? 30000L : 30000L + random.nextInt(15001);
            }
        });
        timeline.suspend(true, SystemClock.elapsedRealtime());
        root = new FrameLayout(context);
        root.setBackgroundColor(Color.rgb(18, 32, 52));
        texture = new TextureView(context);
        texture.setSurfaceTextureListener(this);
        // Keep the TextureView drawable so its Surface exists during preparation.
        // The opaque endpoint image covers it throughout holds and the return.
        texture.setAlpha(1f);
        root.addView(texture, fill());
        last = new ImageView(context);
        last.setScaleType(ImageView.ScaleType.FIT_CENTER);
        last.setAlpha(0f);
        root.addView(last, fill());
        first = new ImageView(context);
        first.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(first, fill());
        scrim = new View(context);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0.30f);
        root.addView(scrim, fill());
        text = new TextView(context);
        text.setText(SchoolBannerPolicy.MESSAGE);
        text.setContentDescription(SchoolBannerPolicy.MESSAGE);
        text.setTextColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(8f, 1.12f);
        text.setShadowLayer(5f, 1f, 2f, Color.BLACK);
        int padding = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.08f);
        text.setPadding(padding, padding / 2, padding, padding / 2);
        root.addView(text, fill());
        root.setContentDescription(SchoolBannerPolicy.MESSAGE);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    View view() { return root; }

    void start() {
        if (closed || loader != null) return;
        main.postDelayed(preparationTimeout, 15000L);
        loader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final SchoolReminderAssets.Loaded loaded = SchoolReminderAssets.load(context);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (closed || failed || !guard.authorized()) {
                                loaded.closeDescriptors(); return;
                            }
                            assets = loaded;
                            root.setBackgroundColor(Color.BLACK);
                            first.setImageBitmap(loaded.first);
                            last.setImageBitmap(loaded.last);
                            prepareIfPossible();
                        }
                    });
                } catch (final Exception unavailable) {
                    main.post(new Runnable() {
                        @Override public void run() { fail("assets:" + unavailable.getMessage()); }
                    });
                }
            }
        }, "SchoolReminderAssets");
        loader.setPriority(Thread.NORM_PRIORITY - 1);
        loader.start();
    }

    void setAudioFocusHeld(boolean held) {
        if (closed || failed) return;
        focusHeld = held;
        timeline.suspend(!held, SystemClock.elapsedRealtime());
        if (!held) pausePlayers();
        scheduleNow();
    }

    private void prepareIfPossible() {
        if (closed || failed || assets == null || surface == null || video != null) return;
        try {
            music = new MediaPlayer();
            music.setAudioAttributes(attributes(AudioAttributes.CONTENT_TYPE_MUSIC));
            music.setLooping(true);
            music.setVolume(0f, 0f);
            music.setDataSource(assets.music.getFD());
            music.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer player) {
                    if (closed || failed || player != music) return;
                    musicPrepared = true; allPrepared();
                }
            });
            music.setOnErrorListener(mediaError);

            video = new MediaPlayer();
            video.setAudioAttributes(attributes(AudioAttributes.CONTENT_TYPE_MOVIE));
            video.setLooping(false);
            video.setSurface(surface);
            video.setDataSource(assets.video.getFD());
            video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer player) {
                    if (closed || failed || player != video) return;
                    videoPrepared = true; videoReady = true;
                    videoWidth = player.getVideoWidth(); videoHeight = player.getVideoHeight();
                    fitVideo(); allPrepared();
                }
            });
            video.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer player, int width, int height) {
                    if (closed || failed || player != video) return;
                    videoWidth = width; videoHeight = height; fitVideo();
                }
            });
            video.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override public void onSeekComplete(MediaPlayer player) {
                    if (closed || failed || player != video) return;
                    videoReady = true; scheduleNow();
                }
            });
            video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer player) {
                    if (closed || failed || player != video || !guard.authorized()) return;
                    videoPlaying = false;
                    if (!timeline.videoCompleted(SystemClock.elapsedRealtime())) return;
                    videoFrameVisible = false;
                    videoReady = false;
                    // Cover the video synchronously before an asynchronous seek can draw frame one.
                    last.setAlpha(1f);
                    first.setAlpha(0f);
                    // The supplied exact last-frame image covers the seek to the next first frame.
                    try { player.seekTo(0L, MediaPlayer.SEEK_CLOSEST); }
                    catch (RuntimeException seekFailed) { fail("video_seek"); return; }
                    scheduleNow();
                }
            });
            video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer player, int what, int extra) {
                    if (!closed && !failed && player == video && videoPlaying
                            && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        videoFrameVisible = true; scheduleNow();
                    }
                    return false;
                }
            });
            video.setOnErrorListener(mediaError);
            assets.closeDescriptors();
            music.prepareAsync();
            video.prepareAsync();
        } catch (Exception unavailable) { fail("prepare:" + unavailable.getClass().getSimpleName()); }
    }

    private final MediaPlayer.OnErrorListener mediaError = new MediaPlayer.OnErrorListener() {
        @Override public boolean onError(MediaPlayer player, int what, int extra) {
            if (!closed && !failed && (player == music || player == video))
                fail("player:" + what + ":" + extra);
            return true;
        }
    };

    private void allPrepared() {
        if (!musicPrepared || !videoPrepared || ready || closed || failed) return;
        if (!guard.authorized()) { close(); return; }
        ready = true;
        main.removeCallbacks(preparationTimeout);
        timeline.ready(SystemClock.elapsedRealtime());
        scheduleNow();
    }

    private final Runnable preparationTimeout = new Runnable() {
        @Override public void run() { if (!ready) fail("preparation_timeout"); }
    };

    private void scheduleNow() {
        main.removeCallbacks(animate);
        if (!closed && !failed) main.post(animate);
    }

    private final Runnable animate = new Runnable() {
        @Override public void run() {
            if (closed || failed) return;
            // Every callback checks the original calendar/phone/observer/cutoff authority.
            if (!guard.authorized()) { close(); return; }
            if (!ready) return;
            frame = timeline.frame(SystemClock.elapsedRealtime());
            try {
                applyPlayers(frame);
                text.setAlpha(frame.textAlpha);
                scrim.setAlpha(frame.textAlpha * 0.30f);
                last.setAlpha(frame.lastVisible ? 1f : 0f);
                first.setAlpha(frame.phase == SchoolReminderTimeline.Phase.VIDEO
                        && !videoFrameVisible ? 1f : frame.firstAlpha);
            } catch (RuntimeException invalidState) { fail("playback_state"); return; }
            boolean changing = frame.phase == SchoolReminderTimeline.Phase.FADE_OUT
                    || frame.phase == SchoolReminderTimeline.Phase.RETURN
                    || (frame.cycle == 0 && frame.phase == SchoolReminderTimeline.Phase.HOLD
                        && frame.elapsedMs < SchoolReminderTimeline.MUSIC_FADE_MS);
            main.postDelayed(this, changing && !frame.suspended ? 16L : 100L);
        }
    };

    private void applyPlayers(SchoolReminderTimeline.Frame state) {
        float volume = focusHeld ? state.musicVolume : 0f;
        if (volume != appliedMusicVolume) {
            music.setVolume(volume, volume);
            appliedMusicVolume = volume;
        }
        boolean wantMusic = focusHeld && state.musicPlaying;
        boolean wantVideo = focusHeld && state.videoPlaying && videoReady;
        // Pause first so the two sources never overlap, even on a late frame callback.
        if (!wantMusic && musicPlaying) { music.pause(); musicPlaying = false; }
        if (!wantVideo && videoPlaying) { video.pause(); videoPlaying = false; }
        if (wantMusic && !musicPlaying) { music.start(); musicPlaying = true; }
        if (wantVideo && !videoPlaying) {
            video.setVolume(1f, 1f);
            video.start(); videoPlaying = true;
        }
    }

    private void pausePlayers() {
        try { if (musicPlaying && music != null) music.pause(); }
        catch (RuntimeException ignored) { }
        try { if (videoPlaying && video != null) video.pause(); }
        catch (RuntimeException ignored) { }
        musicPlaying = false; videoPlaying = false;
    }

    void close() {
        if (closed) return;
        closed = true;
        timeline.stop();
        cancelAndRelease();
        first.setImageDrawable(null);
        last.setImageDrawable(null);
        assets = null;
    }

    private void fail(String reason) {
        if (closed || failed) return;
        failed = true; error = reason;
        timeline.stop();
        cancelAndRelease();
        first.setAlpha(1f); last.setAlpha(0f); texture.setAlpha(0f);
        text.setAlpha(1f); scrim.setAlpha(0.30f);
        Log.w(TAG, "Rich reminder retained static text: " + reason);
    }

    private void cancelAndRelease() {
        main.removeCallbacks(animate);
        main.removeCallbacks(preparationTimeout);
        if (loader != null) loader.interrupt();
        pausePlayers();
        MediaPlayer oldMusic = music, oldVideo = video;
        music = null; video = null;
        if (oldMusic != null) try { oldMusic.release(); } catch (RuntimeException ignored) { }
        if (oldVideo != null) try { oldVideo.release(); } catch (RuntimeException ignored) { }
        if (surface != null) { surface.release(); surface = null; }
        if (assets != null) assets.closeDescriptors();
    }

    JSONObject status() {
        JSONObject out = new JSONObject();
        put(out, "sample_wall_ms", System.currentTimeMillis());
        put(out, "sample_elapsed_ms", SystemClock.elapsedRealtime());
        put(out, "phase", closed ? "stopped" : failed ? "static_fallback" : !ready ? "loading"
                : timeline.frame(SystemClock.elapsedRealtime()).phase.name().toLowerCase(java.util.Locale.ROOT));
        put(out, "ready", ready && !closed && !failed);
        put(out, "error", error);
        put(out, "music_playing", musicPlaying);
        put(out, "video_playing", videoPlaying);
        put(out, "focus_held", focusHeld);
        if (frame != null) {
            put(out, "cycle", frame.cycle);
            put(out, "hold_ms", frame.holdMs);
            put(out, "phase_elapsed_ms", frame.elapsedMs);
            put(out, "text_alpha", frame.textAlpha);
            put(out, "first_frame_alpha", frame.firstAlpha);
            put(out, "frame_crossfade_alpha", frame.phase == SchoolReminderTimeline.Phase.RETURN
                    ? frame.firstAlpha : 0f);
            put(out, "music_volume", frame.musicVolume);
        }
        if (!closed && !failed) {
            try { if (musicPrepared) put(out, "music_position_ms", music.getCurrentPosition()); }
            catch (RuntimeException ignored) { }
            try { if (videoPrepared) put(out, "video_position_ms", video.getCurrentPosition()); }
            catch (RuntimeException ignored) { }
        }
        return out;
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture value, int width, int height) {
        if (closed || failed) return;
        surface = new Surface(value);
        fitVideo(); prepareIfPossible();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture value, int width, int height) { fitVideo(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture value) {
        if (!closed && !failed) fail("video_surface_lost");
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture value) {
        if (!closed && !failed && videoPlaying && !videoFrameVisible) {
            videoFrameVisible = true; scheduleNow();
        }
    }

    private void fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        float width = texture.getWidth(), height = texture.getHeight();
        float scale = Math.min(width / videoWidth, height / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f);
        texture.setTransform(matrix);
    }
    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
    }
    private static AudioAttributes attributes(int content) {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(content).build();
    }
    private static void put(JSONObject out, String key, Object value) {
        try { out.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
