package com.serenegiant.glutils;
/*
 * libcommon
 * utility/helper classes for myself
 *
 * Copyright (c) 2014-2019 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
*/

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.glutils.es2.GLHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import static com.serenegiant.glutils.ShaderConst.*;

public class OverlayRendererHolder extends AbstractRendererHolder {
	private static final boolean DEBUG = true;	// FIXME 実働時はfalseにすること
	private static final String TAG = OverlayRendererHolder.class.getSimpleName();

	/**
	 * コンストラクタ
	 * @param width
	 * @param height
	 * @param callback
	 */
	public OverlayRendererHolder(final int width, final int height,
		@Nullable final RenderHolderCallback callback) {

		this(width, height,
			3, null, EglTask.EGL_FLAG_RECORDABLE,
			callback);
	}

	/**
	 * コンストラクタ
	 * @param width
	 * @param height
	 * @param maxClientVersion
	 * @param sharedContext
	 * @param flags
	 * @param callback
	 */
	public OverlayRendererHolder(final int width, final int height,
		final int maxClientVersion,
		@Nullable final EGLBase.IContext sharedContext, final int flags,
		@Nullable final RenderHolderCallback callback) {

		super(width, height, maxClientVersion, sharedContext, flags, callback);
		setOverlay(0, null);
	}

	/**
	 * 描画タスクを生成
	 * @param width
	 * @param height
	 * @param maxClientVersion
	 * @param sharedContext
	 * @param flags
	 * @return
	 */
	@NonNull
	@Override
	protected BaseRendererTask createRendererTask(
		final int width, final int height,
		final int maxClientVersion,
		@Nullable final EGLBase.IContext sharedContext, final int flags) {

		return new MyRendererTask(this, width, height,
			maxClientVersion, sharedContext, flags);
	}

	public void setOverlay(final int id, @Nullable final Bitmap overlay) {
		((MyRendererTask)mRendererTask).setOverlay(id, overlay);
	}

	private static final String FRAGMENT_SHADER_BASE = SHADER_VERSION +
		"%s" +
		"precision highp float;\n" +
		"varying       vec2 vTextureCoord;\n" +
		"uniform %s    sTexture;\n" +	// 入力テクスチャA
		"uniform %s    sTexture2;\n" +	// 入力テクスチャB
		"void main() {\n" +
		"    highp vec4 tex1 = texture2D(sTexture, vTextureCoord);\n" +
		"    highp vec4 tex2 = texture2D(sTexture2, vTextureCoord);\n" +
		"    gl_FragColor = vec4(mix(tex1.rgb, tex2.rgb, tex2.a), tex1.a);\n" +
		"}\n";
	private static final String MY_FRAGMENT_SHADER_EXT
		= String.format(FRAGMENT_SHADER_BASE, HEADER_OES,
			SAMPLER_OES, SAMPLER_OES);

	private static final int REQUEST_UPDATE_OVERLAY = 100;

	/**
	 * 描画タスク
	 */
	private class MyRendererTask extends BaseRendererTask {

		private final float[] mTexMatrixOverlay = new float[16];
		private int mOverlayTexId;
		private SurfaceTexture mOverlayTexture;
		private Surface mOverlaySurface;
		private volatile boolean mOverlayChanged;

		public MyRendererTask(@NonNull final AbstractRendererHolder parent,
			final int width, final int height,
			final int maxClientVersion,
			@Nullable final EGLBase.IContext sharedContext, final int flags) {

			super(parent, width, height, maxClientVersion, sharedContext, flags);
			if (DEBUG) Log.v(TAG, String.format("MyRendererTask(%dx%d)", width, height));
		}

		public void setOverlay(final int id, @Nullable final Bitmap overlay) {
			checkFinished();
			offer(REQUEST_UPDATE_OVERLAY, id, 0, overlay);
		}

//================================================================================
// ワーカースレッド上での処理
//================================================================================
		/**
		 * ワーカースレッド開始時の処理(ここはワーカースレッド上)
		 */
		@WorkerThread
		@Override
		protected void internalOnStart() {
			super.internalOnStart();
			if (DEBUG) Log.v(TAG, "internalOnStart:" + mDrawer);
			if (mDrawer instanceof IShaderDrawer2d) {
				final IShaderDrawer2d drawer = (IShaderDrawer2d)mDrawer;
				drawer.updateShader(MY_FRAGMENT_SHADER_EXT);
				final int sTex1 = drawer.glGetUniformLocation("sTexture");
				GLES20.glUniform1i(sTex1, 0);

				final int sTex2 = drawer.glGetUniformLocation("sTexture2");
				mOverlayTexId = GLHelper.initTex(
					GL_TEXTURE_EXTERNAL_OES,
					GLES20.GL_TEXTURE1,
					GLES20.GL_LINEAR, GLES20.GL_LINEAR,
					GLES20.GL_CLAMP_TO_EDGE);
				mOverlayTexture = new SurfaceTexture(mOverlayTexId);
				mOverlayTexture.setDefaultBufferSize(width(), height());
				mOverlaySurface = new Surface(mOverlayTexture);
				GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
				GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mOverlayTexId);
				GLES20.glUniform1i(sTex2, 1);
			}
		}

		@WorkerThread
		@Override
		protected void internalOnStop() {
			if (DEBUG) Log.v(TAG, "internalOnStop:");
			if (mOverlayTexture != null) {
				mOverlayTexture.release();
				mOverlayTexture = null;
			}
			mOverlaySurface = null;
			if (mOverlayTexId >= 0) {
				GLHelper.deleteTex(mOverlayTexId);
				mOverlayTexId = NO_TEXTURE;
			}
			super.internalOnStop();
		}

		@WorkerThread
		@Override
		protected Object processRequest(final int request,
			final int arg1, final int arg2, final Object obj) {

			Object result = null;
			if (request == REQUEST_UPDATE_OVERLAY) {
				handleUpdateOverlay(arg1, (Bitmap)obj);
			} else {
				result = super.processRequest(request, arg1, arg2, obj);
			}
			return result;
		}

		@Override
		protected void handleUpdateTexture() {
			super.handleUpdateTexture();
			if (mOverlayChanged) {
				mOverlayTexture.updateTexImage();
				mOverlayTexture.getTransformMatrix(mTexMatrixOverlay);
				mOverlayChanged = false;
			}
		}

		@SuppressLint("NewApi")
		@WorkerThread
		private void handleUpdateOverlay(final int targetId, @NonNull final Bitmap overlay) {
			if (DEBUG) Log.v(TAG, "handleUpdateOverlay:" + overlay);
			GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
			GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mOverlayTexId);
			try {
				final Canvas canvas = mOverlaySurface.lockCanvas(null);
				try {
					if (overlay != null) {
						canvas.drawBitmap(overlay, 0, 0, null);
					} else if (DEBUG) {
						// DEBUGフラグtrueでオーバーレイ映像が設定されていないときは全面を薄赤色にする
						canvas.drawColor(0x7fff0000);	// ARGB
					} else {
						// DEBUGフラグfalseでオーバーレイ映像が設定されていなければ全面透過
						canvas.drawColor(0x00000000);	// ARGB
					}
				} finally {
					mOverlaySurface.unlockCanvasAndPost(canvas);
				}
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
			GLES20.glFlush();
			mOverlayChanged = true;
			requestFrame();
		}
	}

}