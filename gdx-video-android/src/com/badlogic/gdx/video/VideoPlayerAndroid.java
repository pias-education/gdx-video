/*******************************************************************************
 * Copyright 2014 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.video;

import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Null;

import java.io.FileNotFoundException;

/** Android implementation of the VideoPlayer class.
 *
 * @author Rob Bogie &lt;rob.bogie@codepoke.net&gt; */
public class VideoPlayerAndroid extends AbstractVideoPlayer implements VideoPlayer, OnFrameAvailableListener {
	//@off
	static final String vertexShaderCode =
			"#define highp\n" +
					"attribute highp vec4 a_position; \n" +
					"attribute highp vec2 a_texCoord0;" +
					"uniform highp mat4 u_projModelView;" +
					"varying highp vec2 v_texCoord0;" +
					"void main() \n" +
					"{ \n" +
					" gl_Position = u_projModelView * a_position; \n" +
					" v_texCoord0 = a_texCoord0;\n" +
					"} \n";

	static final String fragmentShaderCode =
			"#extension GL_OES_EGL_image_external : require\n" +
					"uniform samplerExternalOES u_sampler0;" +
					"varying highp vec2 v_texCoord0;" +
					"void main()                 \n" +
					"{                           \n" +
					"  gl_FragColor = texture2D(u_sampler0, v_texCoord0);    \n" +
					"}";
	//@on

	private void setupRenderer () {
		shader = new ShaderProgram(vertexShaderCode, fragmentShaderCode);
		renderer = new ImmediateModeRenderer20(4, false, false, 1, shader);
		transform = new Matrix4().setToOrtho2D(0, 0, 1, 1);
	}

	private void queueSetup() {
		Gdx.app.postRunnable(this::setupRenderer);
	}

	private ShaderProgram shader;
	private Matrix4 transform;
	private final int[] textures = new int[1];
	private SurfaceTexture videoTexture;
	private Surface surface;
	private FrameBuffer fbo;
	private Texture frame;
	private ImmediateModeRenderer20 renderer;

	private volatile int videoWidth;
	private volatile int videoHeight;
	private volatile boolean isLooping;
	private volatile boolean isPlaying;
	private volatile int currentTimestamp;

	private volatile boolean initialized = false;
	private ExoPlayer player;
	private boolean prepared = false;
	private boolean stopped = false;
	private boolean pauseRequested = false;
	private volatile boolean frameAvailable = false;
	/** If the external should be drawn to the fbo and make it available thru {@link #getTexture()} */
	public boolean renderToFbo = true;

	VideoSizeListener sizeListener;
	CompletionListener completionListener;
	private volatile float currentVolume = 1.0f;

	/** Used for sending mediaplayer tasks to the Main Looper */
	private static Handler handler;

	public VideoPlayerAndroid () {
		initializeMediaPlayer();
		queueSetup();
	}

	private void initializeMediaPlayer () {
		if (handler == null) handler = new Handler(Looper.getMainLooper());

		handler.post(() -> {
			player = new ExoPlayer.Builder(((AndroidApplication)Gdx.app).getContext()).build();
			videoTexture = new SurfaceTexture(textures[0]);
			videoTexture.setOnFrameAvailableListener(this);
			surface = new Surface(videoTexture);
			player.setVideoSurface(surface);
			initialized = true;
		});
	}

	private void internalPlay(FileHandle file) {
		// If we haven't finished loading the media player yet, wait without blocking.
		if (!initialized) {
			postPlay(file);
			return;
		}

		prepared = false;
		frame = null;
		stopped = false;

		player.stop();
		player.removeMediaItem(0);

		player.addListener(new Player.Listener() {
			@Override
			public void onPlayerError(PlaybackException error) {
				error.printStackTrace();
			}

			private void onPrepared (ExoPlayer mp) {
				prepared = true;
				Format format = mp.getVideoFormat();
				videoWidth = format.width;
				videoHeight = format.height;
				if (sizeListener != null) {
					sizeListener.onVideoSize(videoWidth, videoHeight);
				}
				Gdx.app.postRunnable(() -> {
					if (fbo != null && (fbo.getWidth() != videoWidth || fbo.getHeight() != videoHeight)) {
						fbo.dispose();
						fbo = null;
					}
					if (fbo == null) {
						fbo = new FrameBuffer(Pixmap.Format.RGB888, videoWidth, videoHeight, false);
					}
					prepared = true;
				});

				mp.play();
				if (pauseRequested) {
					mp.pause();
				}
			}

			public void onCompletion (ExoPlayer mp) {
				if (isLooping()) return;
				prepared = false;
				if (completionListener != null) {
					completionListener.onCompletionListener(file);
				}
			}

			@Override
			public void onPlaybackStateChanged(int playbackState) {
				switch (playbackState) {
					case Player.STATE_READY:
						onPrepared(player);
						break;
					case Player.STATE_ENDED:
						onCompletion(player);
						break;
					default:
				}
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				VideoPlayerAndroid.this.isPlaying = isPlaying;
			}
		});

		String uri;
		if (file.type() == FileType.Classpath || (file.type() == FileType.Internal && !file.file().exists())) {
			uri = "file:///android_asset/" + file.path();
		} else {
			uri = "file://" + file.file().getAbsolutePath();
		}
		MediaItem item = MediaItem.fromUri(uri);
		player.addMediaItem(item);
		player.prepare();
	}

	private void postPlay(final FileHandle file) {
		handler.post(() -> internalPlay(file));
	}

	@Override
	public boolean play (final FileHandle file) throws FileNotFoundException {
		if (!file.exists()) {
			throw new FileNotFoundException("Could not find file: " + file.path());
		}

		postPlay(file);

		return true;
	}

	/** Get external texture directly without framebuffer
	 * @return texture handle to be used with external OES target, -1 if texture is not available */
	public int getTextureExternal () {
		if (prepared) {
			return textures[0];
		}
		return -1;
	}

	@Override
	public boolean update () {
		if (surface == null || !frameAvailable || (fbo == null && renderToFbo)) return false;

		frameAvailable = false;
		videoTexture.updateTexImage();

		if(!renderToFbo) return true;

		fbo.begin();
		shader.bind();

		Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
		Gdx.gl.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, getTextureExternal());
		shader.setUniformi("u_sampler0", 0);
		renderer.begin(transform, GL20.GL_TRIANGLE_STRIP);
		renderer.texCoord(0, 0);
		renderer.vertex(0, 0, 0);
		renderer.texCoord(1, 0);
		renderer.vertex(1, 0, 0);
		renderer.texCoord(0, 1);
		renderer.vertex(0, 1, 0);
		renderer.texCoord(1, 1);
		renderer.vertex(1, 1, 0);
		renderer.end();
		fbo.end();
		if (frame == null) {
			frame = fbo.getColorBufferTexture();
			frame.setFilter(minFilter, magFilter);
		}

		return true;
	}

	@Override
	@Null
	public Texture getTexture () {
		return frame;
	}

	/** For android, this will return whether the prepareAsync method of the android MediaPlayer is done with preparing.
	 *
	 * @return whether the buffer is filled. */
	@Override
	public boolean isBuffered () {
		return prepared;
	}

	@Override
	public void stop () {
		if (player != null) {
			handler.post(() -> player.stop());
		}
		stopped = true;
		prepared = false;
	}

	@Override
	public void onFrameAvailable (SurfaceTexture surfaceTexture) {
		frameAvailable = true;
		currentTimestamp = (int)player.getCurrentPosition();
	}

	@Override
	public void pause () {
		// If it is running
		if (prepared) {
			handler.post(() -> player.pause());
		} else {
			pauseRequested = true;
		}
	}

	@Override
	public void resume () {
		// If it is running
		if (prepared) {
			handler.post(() -> player.play());
		}
		pauseRequested = false;
	}

	@Override
	public void dispose () {
		stop();
		surface = null;
		handler.post(new Runnable() {
			@Override
			public void run () {
				player.release();
				player = null;
			}
		});

		if (videoTexture != null) {
			videoTexture.detachFromGLContext();
			GLES20.glDeleteTextures(1, textures, 0);
		}

		if (fbo != null) fbo.dispose();
		frame = null;
		if (renderer != null) {
			renderer.dispose();
			shader.dispose();
		}
	}

	@Override
	public void setOnVideoSizeListener (VideoSizeListener listener) {
		sizeListener = listener;
	}

	@Override
	public void setOnCompletionListener (CompletionListener listener) {
		completionListener = listener;
	}

	@Override
	public int getVideoWidth () {
		return videoWidth;
	}

	@Override
	public int getVideoHeight () {
		return videoHeight;
	}

	@Override
	public boolean isPlaying () {
		return isPlaying;
	}

	@Override
	public void setVolume (float volume) {
		currentVolume = volume;
		handler.post(() -> player.setVolume(volume));
	}

	@Override
	public float getVolume () {
		return currentVolume;
	}

	@Override
	public void setLooping (boolean looping) {
		isLooping = looping;
		int repeatMode = looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF;
		handler.post(() -> player.setRepeatMode(repeatMode));
	}

	@Override
	public boolean isLooping () {
		return isLooping;
	}

	@Override
	public int getCurrentTimestamp () {
		return currentTimestamp;
	}
}
