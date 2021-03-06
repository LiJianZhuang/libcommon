package com.serenegiant.mediastore;
/*
 * libcommon
 * utility/helper classes for myself
 *
 * Copyright (c) 2014-2020 saki t_saki@serenegiant.com
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

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.util.Log;

import com.serenegiant.common.BuildConfig;
import com.serenegiant.graphics.BitmapHelper;
import com.serenegiant.io.DiskLruCache;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LruCache;

/**
 * サムネイルキャッシュ
 * FIXME groupIdは無視...groupIdは削除するかも
 */
public class ThumbnailCache {
	private static final boolean DEBUG = false;	// FIXME 実働時はfalseにすること
	private static final String TAG = ThumbnailCache.class.getSimpleName();

	private static final int DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB
	private static final String DISK_CACHE_SUBDIR = "thumbnails";
	private static final int DISK_CACHE_INDEX = 0;

	// for thumbnail cache(in memory)
	// rate of memory usage for cache, 'CACHE_RATE = 8' means use 1/8 of available memory for image cache
	private static final Object sSync = new Object();
	private static final int CACHE_RATE = 8;
	private static LruCache<String, Bitmap> sThumbnailCache;
	private static DiskLruCache sDiskLruCache;
	private static int sCacheSize;

	private static void prepareThumbnailCache(@NonNull final Context context) {
		synchronized (sSync) {
			if (sThumbnailCache == null) {
				if (DEBUG) Log.v(TAG, "prepareThumbnailCache:");
				final int memClass = ((ActivityManager)context
					.getSystemService(Context.ACTIVITY_SERVICE))
					.getMemoryClass();
				// use 1/CACHE_RATE of available memory as memory cache
				sCacheSize = (1024 * 1024 * memClass) / CACHE_RATE;	// [MB] => [bytes]
				sThumbnailCache = new LruCache<String, Bitmap>(sCacheSize) {
					@Override
					protected int sizeOf(@NonNull String key, @NonNull Bitmap bitmap) {
						// control memory usage instead of bitmap counts
						return bitmap.getRowBytes() * bitmap.getHeight();	// [bytes]
					}
				};
				try {
					final File cacheDir = getDiskCacheDir(context);
					if (!cacheDir.exists()) {
						//noinspection ResultOfMethodCallIgnored
						cacheDir.mkdirs();
					}
					if (!cacheDir.canWrite()) {
						Log.w(TAG, "unable to write to cache dir!!");
					}
					if (DEBUG) Log.v(TAG, "prepareThumbnailCache:dir=" + cacheDir);
					sDiskLruCache = DiskLruCache.open(cacheDir,
						BuildConfig.VERSION_CODE, 1, DISK_CACHE_SIZE);
				} catch (final IOException e) {
					sDiskLruCache = null;
					Log.w(TAG, e);
				}
			}
		}
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private static File getDiskCacheDir(@NonNull final Context context) throws IOException {
		File cacheDir = null;
		cacheDir = context.getExternalCacheDir();
		cacheDir.mkdirs();
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			// 内部ストレージのキャッシュディレクトリを試みる
			cacheDir = context.getCacheDir();
			cacheDir.mkdirs();
		}
		if ((cacheDir == null) || !cacheDir.canWrite()) {
			throw new IOException("can't write cache dir");
		}
		if (DEBUG) Log.v(TAG, "getDiskCacheDir:" + cacheDir);
		return cacheDir;
	}

	/**
	 * コンストラクタ
	 * @param context
	 */
	public ThumbnailCache(@NonNull final Context context) {
		prepareThumbnailCache(context);
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			synchronized (sSync) {
				sThumbnailCache.trimToSize(sCacheSize);
			}
		} finally {
			super.finalize();
		}
	}

	/**
	 * 指定したhashCode/idに対応するキャッシュを取得する
	 * 存在しなければnull
	 * @param groupId
	 * @param id
	 * @return
	 */
	@Nullable
	public Bitmap get(final long id) {
		return get(getKey(id));
	}

	/**
	 * 指定したキーに対応するキャッシュを取得する
	 * 存在しなければnull
	 * @param key
	 * @return
	 */
	@Nullable
	public Bitmap get(@NonNull final String key) {
		Bitmap result;
		synchronized (sSync) {
			result = sThumbnailCache.get(key);
			if ((result == null) && (sDiskLruCache != null)) {
				InputStream in = null;
				try {
					final DiskLruCache.Snapshot snapshot = sDiskLruCache.get(key);
					if (snapshot != null) {
//						if (DEBUG) Log.v(TAG, "get:disk cache hit!");
						in = snapshot.getInputStream(DISK_CACHE_INDEX);
						if (in != null) {
							final FileDescriptor fd = ((FileInputStream) in).getFD();
							// Decode bitmap, but we don't want to sample so give
							// MAX_VALUE as the target dimensions
							result = BitmapHelper.asBitmap(fd,
								Integer.MAX_VALUE, Integer.MAX_VALUE);
						}
					}
				} catch (final IOException e) {
					if (DEBUG) Log.w(TAG, e);
				} finally {
					try {
						if (in != null) {
							in.close();
						}
					} catch (final IOException e) {
						// ignore
					}
				}
			}
		}
		return result;
	}

	/**
	 * 指定したキーに対応するビットマップをキャッシュに追加する
	 * @param key
	 * @param bitmap
	 */
	public void put(@NonNull final String key, @NonNull final Bitmap bitmap) {
		if (DEBUG) Log.v(TAG, "put:key=" + key);
		synchronized (sSync) {
			sThumbnailCache.put(key, bitmap);
			if (sDiskLruCache != null) {
				// ディスクキャッシュへの追加処理
				OutputStream out = null;
				try {
					DiskLruCache.Snapshot snapshot = sDiskLruCache.get(key);
					if (snapshot == null) {
						// ディスクキャッシュにエントリーが存在していない
						final DiskLruCache.Editor editor = sDiskLruCache.edit(key);
						if (editor != null) {
							out = editor.newOutputStream(DISK_CACHE_INDEX);
							bitmap.compress(
								Bitmap.CompressFormat.JPEG, 90, out);
							editor.commit();
							out.close();
						}
					} else {
						// ディスクキャッシュに既にエントリーが存在している
						snapshot.getInputStream(DISK_CACHE_INDEX).close();
					}
				} catch (final IOException e) {
					if (DEBUG) Log.w(TAG, e);
				} catch (final Exception e) {
					if (DEBUG) Log.w(TAG, e);
				} finally {
					try {
						if (out != null) {
							out.close();
						}
					} catch (final IOException e) {
						if (DEBUG) Log.w(TAG, e);
					}
				}
			}
		}
	}

	/**
	 * メモリーキャッシュをクリアする
	 */
	public void clear() {
		synchronized (sSync) {
			sThumbnailCache.evictAll();
		}
	}

	/**
	 * 静止画のサムネイルを取得する
	 * 可能であればキャッシュから取得する
	 * @param cr
	 * @param id
	 * @param requestWidth
	 * @param requestHeight
	 * @return
	 * @throws IOException
	 */
	public Bitmap getImageThumbnail(
		@NonNull final ContentResolver cr, final long id,
		final int requestWidth, final int requestHeight)
			throws IOException {

		// try to get from internal thumbnail cache(in memory), this may be redundant
		final String key = getKey(id);
		Bitmap result;
		synchronized (sSync) {
			result = get(key);
			if (result == null) {
				if ((requestWidth <= 0) || (requestHeight <= 0)) {
					result = BitmapHelper.asBitmap(cr, id, requestWidth, requestHeight);
				} else {
					int kind = MediaStore.Images.Thumbnails.MICRO_KIND;
					if ((requestWidth > 96) || (requestHeight > 96) || (requestWidth * requestHeight > 128 * 128))
						kind = MediaStore.Images.Thumbnails.MINI_KIND;
					try {
						result = MediaStore.Images.Thumbnails.getThumbnail(cr, id, kind, null);
					} catch (final Exception e) {
						if (DEBUG) Log.w(TAG, e);
					}
				}
				if (result != null) {
					final int orientation = BitmapHelper.getOrientation(cr, id);
					if (orientation != 0) {
						final Bitmap newBitmap = BitmapHelper.rotateBitmap(result, orientation);
						result.recycle();
						result = newBitmap;
					}
					if (DEBUG) Log.v(TAG, String.format("getImageThumbnail:id=%d(%d,%d)",
						id, result.getWidth(), result.getHeight()));
					// add to internal thumbnail cache(in memory)
					put(key, result);
				}

			}
		}
		return result;
	}

	/**
	 * 動画のサムネイルを取得する
	 * 可能であればキャッシュから取得する
	 * @param cr
	 * @param id
	 * @param requestWidth
	 * @param requestHeight
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	public Bitmap getVideoThumbnail(
		@NonNull final ContentResolver cr, final long id,
		final int requestWidth, final int requestHeight)
			throws FileNotFoundException, IOException {

		// try to get from internal thumbnail cache(in memory), this may be redundant
		final String key = getKey(id);
		Bitmap result;
		synchronized (sSync) {
			result = sThumbnailCache.get(key);
			if (result == null) {
				int kind = MediaStore.Video.Thumbnails.MICRO_KIND;
				if ((requestWidth > 96) || (requestHeight > 96) || (requestWidth * requestHeight > 128 * 128))
					kind = MediaStore.Video.Thumbnails.MINI_KIND;
				try {
					result = MediaStore.Video.Thumbnails.getThumbnail(cr, id, kind, null);
				} catch (final Exception e) {
					if (DEBUG) Log.w(TAG, e);
				}
				if (result != null) {
					if (DEBUG) Log.v(TAG, String.format("getVideoThumbnail:id=%d(%d,%d)",
						id, result.getWidth(), result.getHeight()));
					// XXX 動画はExifが無いはずなのとAndroid10未満だとorientationフィールドが無い可能性が高いので実際には回転しないかも
					final int orientation = BitmapHelper.getOrientation(cr, id);
					if (orientation != 0) {
						final Bitmap newBitmap = BitmapHelper.rotateBitmap(result, orientation);
						result.recycle();
						result = newBitmap;
					}
					// add to internal thumbnail cache(in memory)
					put(key, result);
				} else {
					Log.w(TAG, "failed to get video thumbnail ofr id=" + id);
				}

			}
		}
		return result;
	}

	/**
	 * キャッシュエントリー用のキー文字列生成
	 * @param id
	 * @return
	 */
	private static String getKey(final long id) {
		return String.format(Locale.US, "%x", id);
	}

}
