package jp.clip.whisperjni;

/**
 * whisper.cpp 側のオブジェクトを指すハンドルの基底クラス。
 *
 * <p>
 * ネイティブのポインタ値を Java へ露出させない代わりに、C++ 側が採番した整数 ID
 * （{@link #nativeId}）を保持します。C++ 側は ID からポインタを引き当てるテーブルを持っており、
 * 解放後の ID は無効になります。
 * </p>
 *
 * <p>
 * サブクラスは {@link #releaseNative()} で対応する whisper.cpp の解放関数を呼ぶだけで、
 * 多重 close の防止と解放済みチェックはこのクラスが受け持ちます。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public abstract class NativeHandle implements AutoCloseable
{
	/** テストが文字列一致で検証しているため、このメッセージは変更しないこと。 */
	private static final String CLOSED_MESSAGE = "Unavailable pointer, object is closed";

	/** 生成元のライブラリインスタンス。解放時に使います。 */
	protected final WhisperJNI whisper;

	/** ネイティブ側が採番した ID。C++ 側は {@code GetFieldID(cls, "nativeId", "I")} でこの値を読む。 */
	final int nativeId;

	private boolean released;

	/**
	 * ネイティブ側の構造体を指すハンドルを生成します。
	 *
	 * @param whisper 生成元のライブラリインスタンス
	 * @param nativeId ネイティブ側が採番した ID
	 */
	protected NativeHandle(WhisperJNI whisper, int nativeId)
	{
		this.whisper = whisper;
		this.nativeId = nativeId;
	}

	/**
	 * ネイティブメモリが既に解放済みかどうかを返します。
	 *
	 * @return 解放済みなら true
	 */
	public final boolean isReleased()
	{
		return this.released;
	}

	/**
	 * ネイティブメモリを解放します。多重呼び出しは安全です（2 回目以降は何もしません）。
	 *
	 * <p>
	 * {@link AutoCloseable#close()} が宣言している checked 例外を取り除いているため、
	 * try-with-resources で使っても例外処理が不要です。
	 * </p>
	 */
	@Override
	public final void close()
	{
		if(this.released)
		{
			return;
		}
		this.released = true;
		this.releaseNative();
	}

	/**
	 * 対応する whisper.cpp の解放関数を呼び出します。{@link #close()} から 1 度だけ呼ばれます。
	 */
	protected abstract void releaseNative();

	/**
	 * このハンドルがまだ有効であることを検証します。
	 *
	 * @throws IllegalStateException 既に解放済みの場合
	 */
	final void assertAvailable()
	{
		if(this.released)
		{
			throw new IllegalStateException(CLOSED_MESSAGE);
		}
	}
}
