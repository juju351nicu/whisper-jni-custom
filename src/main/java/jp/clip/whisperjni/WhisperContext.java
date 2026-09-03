package jp.clip.whisperjni;

/**
 * whisper.cpp の {@code whisper_context} を指すハンドル。読み込んだモデルを保持します。
 *
 * <p>
 * ネイティブメモリを保持しているため、使い終わったら必ず {@link #close()} を呼んでください
 * （try-with-resources 推奨）。閉じたあとにこのインスタンスを使うと
 * {@link IllegalStateException} になります。
 * </p>
 *
 * <p>
 * 解放時に呼ばれる whisper.cpp 関数は {@code whisper_free}。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public final class WhisperContext extends NativeHandle
{
	/**
	 * 内部用コンストラクタ。{@link WhisperJNI#createContext(java.nio.file.Path)} などから生成されます。
	 *
	 * @param whisper 生成元のライブラリインスタンス
	 * @param nativeId ネイティブ側が採番した ID
	 */
	WhisperContext(WhisperJNI whisper, int nativeId)
	{
		super(whisper, nativeId);
	}

	@Override
	protected void releaseNative()
	{
		this.whisper.freeContext(this.nativeId);
	}
}
