package image;

import android.graphics.Bitmap;
import android.widget.ImageView;

public class ImageLoader {
	private static ImageLoader mImageLoader;
	
	private ImageMemoryCache mMemoryCache;
	private ImageFilesCache mFilesCache;
	private ImageNetCache mNetCache;
	
	private ImageLoader(){}
	
	/**
	 * 采用单例模式获取ImageLoader的实例
	 * @return
	 */
	public static ImageLoader getInstance(){
		if(mImageLoader == null){
			synchronized (ImageLoader.class) {
				if(mImageLoader == null){
					mImageLoader = new ImageLoader();
				}
			}
		}
		return mImageLoader;
	}
	
	/**
	 * 根据图片Url,加载图片到imageView中
	 * 先从内存硬缓存中加载，如果没有则从软缓存中加载
	 * 否则从文件缓存加载，并缓存到内存硬缓存中
	 * 若文件缓存也没有，则从网络获取，并将图片加入到文件缓存以及内存硬缓存中
	 * @param imageView
	 * @param path
	 * @return
	 */
	public boolean loadImage(ImageView imageView, String path){
		Bitmap bitmap = null;
		
		//从内存的缓存中获取图片
		mMemoryCache = new ImageMemoryCache();
		bitmap = mMemoryCache.getBitmap(path);
		if(bitmap != null){
			imageView.setImageBitmap(bitmap);
			return true;
		}
		
		//只有内存缓存中没有取到图片的时候，才去实例化文件缓存对象
		mFilesCache = new ImageFilesCache();
		bitmap = mFilesCache.getBitmap(path);
		if(bitmap != null){
			imageView.setImageBitmap(bitmap);
			//将图片放入内存缓存中
			mMemoryCache.saveBitmapToMemoryCache(path, bitmap);
			return true;
		}
		
		//只有文件缓存中没有取到图片的时候，才去实例化网络缓存对象
		mNetCache = new ImageNetCache();
		bitmap = mNetCache.getBitmap(path);
		if(bitmap != null){
			imageView.setImageBitmap(bitmap);
			//将图片放入内存缓存以及文件缓存中
			mMemoryCache.saveBitmapToMemoryCache(path, bitmap);
			mFilesCache.saveBitmapToLocal(path, bitmap);
			return true;
		}
		
		return false;
	}
}




package image;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

public class ImageNetCache {

	/**
	 * 根据图片Url从网络加载图片
	 * @param path
	 * @return
	 */
	public Bitmap getBitmap(String path) {
		HttpsURLConnection conn = null;
		InputStream is = null;
		try {
			conn = (HttpsURLConnection) new URL(path).openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			conn.connect();

			if (conn.getResponseCode() == 200) {
				BitmapFactory.Options options = new BitmapFactory.Options();
				// 宽高压缩为原来的1/2
				options.inSampleSize = 2;
				options.inPreferredConfig = Bitmap.Config.ARGB_4444;
				is = conn.getInputStream();
				Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
				return bitmap;
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return null;
	}
}



package image;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

public class ImageFilesCache {

	private static final String CACHDIR = "ImgCach";
	private static final String WHOLESALE_CONV = ".cach";

	private static final int MB = 1024 * 1024;
	private static final int CACHE_SIZE = 10 * MB;
	private static final int FREE_SD_SPACE_NEEDED_TO_CACHE = 10;

	public ImageFilesCache() {
		// 清理文件缓存
		removeCache(getDirectory());
	}

	/**
	 * 从缓存中获取图片
	 * @param url
	 * @return
	 */
	public Bitmap getBitmap(final String url) {
		File file = getFileForKey(url);
		if (file.exists()) {
			String path = getFilename(url);
			Bitmap bmp = BitmapFactory.decodeFile(path);
			if (bmp == null) {
				file.delete();
			} else {
				updateFileTime(path);
				return bmp;
			}
		}
		return null;
	}

	/**
	 * 将图片存入文件缓存
	 * @param url
	 * @param bm
	 */
	public void saveBitmapToLocal(String url, Bitmap bm) {
		if (bm == null) {
			return;
		}
		// 判断SDcard上的空间
		if (FREE_SD_SPACE_NEEDED_TO_CACHE > freeSpaceOnSd()) {
			// SD空间不足
			return;
		}
		String filename = convertUrlToFileName(url);
		String dir = getDirectory();
		File dirFile = new File(dir);
		if (!dirFile.exists())
			dirFile.mkdirs();
		File file = new File(dir + "/" + filename);
		try {
			file.createNewFile();
			OutputStream outStream = new FileOutputStream(file);
			bm.compress(Bitmap.CompressFormat.JPEG, 100, outStream);
			outStream.flush();
			outStream.close();
		} catch (FileNotFoundException e) {
			Log.w("ImageFileCache", "FileNotFoundException");
		} catch (IOException e) {
			Log.w("ImageFileCache", "IOException");
		}
	}

	/**
	 * 计算存储目录下的文件大小，
	 * 当文件总大小大于规定的CACHE_SIZE或者SDcard剩余空间小于FREE_SD_SPACE_NEEDED_TO_CACHE的规定
	 * 那么删除40%最近没有被使用的文件
	 * @param dirPath
	 * @return
	 */
	private boolean removeCache(String dirPath) {
		File dir = new File(dirPath);
		File[] files = dir.listFiles();
		if (files == null) {
			return true;
		}
		if (!android.os.Environment.getExternalStorageState().equals(
				android.os.Environment.MEDIA_MOUNTED)) {
			return false;
		}

		int dirSize = 0;
		for (int i = 0; i < files.length; i++) {
			if (files[i].getName().contains(WHOLESALE_CONV)) {
				dirSize += files[i].length();
			}
		}

		if (dirSize > CACHE_SIZE
				|| FREE_SD_SPACE_NEEDED_TO_CACHE > freeSpaceOnSd()) {
			int removeFactor = (int) ((0.4 * files.length) + 1);
			Arrays.sort(files, new FileLastModifSort());
			for (int i = 0; i < removeFactor; i++) {
				if (files[i].getName().contains(WHOLESALE_CONV)) {
					files[i].delete();
				}
			}
		}

		if (freeSpaceOnSd() <= CACHE_SIZE) {
			return false;
		}

		return true;
	}

	/**
	 * 修改文件的最后修改时间
	 * @param path
	 */
	public void updateFileTime(String path) {
		File file = new File(path);
		long newModifiedTime = System.currentTimeMillis();
		file.setLastModified(newModifiedTime);
	}

	/**
	 * 计算sdcard上的剩余空间
	 * @return
	 */
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
	private int freeSpaceOnSd() {
		StatFs stat = new StatFs(Environment.getExternalStorageDirectory()
				.getPath());
		double sdFreeMB = (double)( stat.getAvailableBlocksLong() * stat
				.getBlockSizeLong()) / MB;
		return (int) sdFreeMB;
	}

	/**
	 * 根据文件的最后修改时间进行排序
	 * @author zhangsj-fnst
	 *
	 */
	private class FileLastModifSort implements Comparator<File> {
		public int compare(File arg0, File arg1) {
			if (arg0.lastModified() > arg1.lastModified()) {
				return 1;
			} else if (arg0.lastModified() == arg1.lastModified()) {
				return 0;
			} else {
				return -1;
			}
		}
	}

	/**
	 * 根据图片Url，生成独一无二的文件名
	 * @param path
	 * @return
	 */
	private String getFilename(String path) {
        int firstHalfLength = path.length() / 2;
        String localFilename = String.valueOf(path.substring(0, firstHalfLength).hashCode());
        localFilename += String.valueOf(path.substring(firstHalfLength).hashCode());
        return localFilename;
    }

    /**
     * 根据图片Url，获取缓存中文件
     * @param path
     * @return
     */
    private File getFileForKey(String path) {
        return new File(getDirectory(), getFilename(path));
    }
	
	/**
	 * 把图片的url进行加密处理，转换为文件名
	 * 
	 * @param path
	 * @return
	 */
	private String convertUrlToFileName(String path) {
		byte[] hash;
		try {
			hash = MessageDigest.getInstance("MD5").digest(
					path.getBytes("UTF-8"));
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("MD5 should be supported?", e);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("UTF-8 should be supported?", e);
		}

		StringBuilder hex = new StringBuilder(hash.length * 2);
		for (byte b : hash) {
			if ((b & 0xFF) < 0x10)
				hex.append("0");
			hex.append(Integer.toHexString(b & 0xFF));
		}
		return hex.toString();
	}

	/**
	 * 获取缓存目录
	 * @return
	 */
	private String getDirectory() {
		String path = getSDPath() + "/" + CACHDIR;
		File file = new File(path);
		if (!file.exists()) {
			file.mkdir();
		}
		return path;
	}

	/**
	 * 获取SD卡路径
	 * @return
	 */
	private String getSDPath() {
		File sdDir = null;
		// 判断SD卡是否存在
		boolean isSDCardExist = Environment.getExternalStorageState().equals(
				android.os.Environment.MEDIA_MOUNTED);
		if (isSDCardExist) {
			// 获取根目录
			sdDir = Environment.getExternalStorageDirectory();
		}
		if (sdDir != null) {
			return sdDir.toString();
		}
		// TODO SD卡不存在的情况
		// else {
		// return mContext.getFilesDir().getPath();
		// }
		return null;
	}
}



package image;

import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;

import android.graphics.Bitmap;
import android.util.LruCache;

public class ImageMemoryCache {
	
    private static final int SOFT_CACHE_SIZE = 15;  //软引用缓存容量
    private static LruCache<String, Bitmap> mLruCache;  //硬引用缓存
    private static LinkedHashMap<String, SoftReference<Bitmap>> mSoftCache;  //软引用缓存

    /**
     * 从内存读取数据速度是最快的，为了更大限度使用内存，这里使用了两层缓存。
     * 硬引用缓存不会轻易被回收，用来保存常用数据，不常用的转入软引用缓存。
     */
    public ImageMemoryCache() {
    	//硬引用缓存容量，为系统可用内存的1/8
    	long maxMemory = Runtime.getRuntime().maxMemory() / 8; 
        mLruCache = new LruCache<String, Bitmap>((int)maxMemory) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                if (value != null)
                    return value.getRowBytes() * value.getHeight();
                else
                    return 0;
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != null)
                    // 硬引用缓存容量满的时候，会根据LRU算法把最近没有被使用的图片转入此软引用缓存
                    mSoftCache.put(key, new SoftReference<Bitmap>(oldValue));
            }
        };
        //软引用缓存
        mSoftCache = new LinkedHashMap<String, SoftReference<Bitmap>>(SOFT_CACHE_SIZE, 0.75f, true) {
			private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(Entry<String, SoftReference<Bitmap>> eldest) {
                if (size() > SOFT_CACHE_SIZE){    
                    return true;  
                }  
                return false; 
            }
        };
    }

    /**
     * 从缓存中获取图片
     * @param path
     * @return
     */
    public Bitmap getBitmap(String path) {
        Bitmap bitmap;
        //先从硬引用缓存中获取
        synchronized (ImageMemoryCache.class) {
            bitmap = mLruCache.get(path);
            if (bitmap != null) {
                //如果找到的话，把元素移到LinkedHashMap的最前面，从而保证在LRU算法中是最后被删除
                mLruCache.remove(path);
                mLruCache.put(path, bitmap);
                return bitmap;
            }
        }
        //如果硬引用缓存中找不到，到软引用缓存中找
        synchronized (ImageMemoryCache.class) { 
            SoftReference<Bitmap> bitmapReference = mSoftCache.get(path);
            if (bitmapReference != null) {
                bitmap = bitmapReference.get();
                if (bitmap != null) {
                    //将图片移回硬缓存
                    mLruCache.put(path, bitmap);
                    mSoftCache.remove(path);
                    return bitmap;
                } else {
                    mSoftCache.remove(path);
                }
            }
        }
        return null;
    } 

    /**
     * 添加图片到缓存
     * @param path
     * @param bitmap
     */
    public void saveBitmapToMemoryCache(String path, Bitmap bitmap) {
        if (bitmap != null) {
            synchronized (ImageMemoryCache.class) {
                mLruCache.put(path, bitmap);
            }
        }
    }

    /**
     * 清除软缓存
     */
    public void clearCache() {
        mSoftCache.clear();
    }
}


