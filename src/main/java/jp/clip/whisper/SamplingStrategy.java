package jp.clip.whisper;

import jp.clip.whisperjni.WhisperSamplingStrategy;

/**
 * デコード時のサンプリング戦略。
 *
 * <p>
 * 低レイヤの {@link WhisperSamplingStrategy} の int 定数を型安全に包んだものです。
 * </p>
 */
public enum SamplingStrategy
{
	/**
	 * 貪欲法。高速で、多くの用途ではこちらで十分です。
	 */
	GREEDY(WhisperSamplingStrategy.GREEDY),

	/**
	 * ビームサーチ。GREEDY より低速ですが、句読点などの精度が上がることがあります。
	 */
	BEAM_SEARCH(WhisperSamplingStrategy.BEAM_SEARCH);

	private final int nativeValue;

	SamplingStrategy(int nativeValue)
	{
		this.nativeValue = nativeValue;
	}

	/**
	 * whisper.cpp に渡す int 値を返します。
	 *
	 * @return whisper.cpp の sampling strategy 値
	 */
	public int nativeValue()
	{
		return this.nativeValue;
	}
}
