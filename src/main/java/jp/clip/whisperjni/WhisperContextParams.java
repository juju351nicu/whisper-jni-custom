package jp.clip.whisperjni;

/**
 * コンテキスト生成時のパラメータ。whisper.cpp の {@code struct whisper_context_params} に対応します。
 *
 * <p>
 * <b>フィールド名は C++ 側が {@code GetFieldID} で参照しています。</b>名前や型を変えるときは
 * {@code jp_clip_whisperjni_WhisperJNI.cpp} の {@code readContextParams} も同時に変えてください。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public class WhisperContextParams
{
	/** GPU バックエンドを使うかどうか。{@code whisper_context_params.use_gpu}。 */
	public boolean useGpu = true;

	/**
	 * 既定値のパラメータを生成します。
	 */
	public WhisperContextParams()
	{
		// 既定値はフィールド初期化子で与える
	}
}
