package jp.clip.whisper;

import jp.clip.whisperjni.WhisperSamplingStrategy;

/**
 * デコード時のサンプリング戦略。
 *
 * <p>
 * 低レイヤの {@link WhisperSamplingStrategy} と 1 対 1 で対応しますが、利用側が
 * {@code jp.clip.whisperjni} パッケージを import しなくて済むようにこの層で定義しています。
 * </p>
 */
public enum SamplingStrategy
{
	/** 貪欲法。高速で、多くの用途ではこちらで十分です。 */
	GREEDY(WhisperSamplingStrategy.GREEDY),

	/** ビームサーチ。GREEDY より低速ですが、句読点などの精度が上がることがあります。 */
	BEAM_SEARCH(WhisperSamplingStrategy.BEAM_SEARCH);

	private final WhisperSamplingStrategy bridgeValue;

	SamplingStrategy(WhisperSamplingStrategy bridgeValue)
	{
		this.bridgeValue = bridgeValue;
	}

	/**
	 * 対応する低レイヤの列挙値を返します。
	 *
	 * @return {@link WhisperSamplingStrategy}
	 */
	public WhisperSamplingStrategy toBridge()
	{
		return this.bridgeValue;
	}
}
