package jp.clip.whisperjni;

/**
 * whisper.cpp の {@code whisper_state} を指すハンドル。
 *
 * <p>
 * 1 つのコンテキスト（= 1 つのモデル）を複数スレッドで共有したい場合に、スレッドごとに
 * state を用意します。ネイティブメモリを保持しているため、使い終わったら必ず
 * {@link #close()} を呼んでください（try-with-resources 推奨）。
 * </p>
 *
 * <p>
 * 解放時に呼ばれる whisper.cpp 関数は {@code whisper_free_state}。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public final class WhisperState extends NativeHandle
{
	/**
	 * 内部用コンストラクタ。{@link WhisperJNI#createState(WhisperContext)} から生成されます。
	 *
	 * @param whisper 生成元のライブラリインスタンス
	 * @param nativeId ネイティブ側が採番した ID
	 */
	WhisperState(WhisperJNI whisper, int nativeId)
	{
		super(whisper, nativeId);
	}

	@Override
	protected void releaseNative()
	{
		this.whisper.freeState(this.nativeId);
	}
}
