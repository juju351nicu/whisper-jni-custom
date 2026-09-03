package jp.clip.whisperjni;

import java.util.stream.Stream;

/**
 * whisper.cpp のデコード戦略。{@code enum whisper_sampling_strategy} に対応します。
 *
 * <p>
 * {@link #nativeValue()} が whisper.cpp 側の列挙値です。C++ 側は {@link WhisperTranscriptionParams}
 * の int フィールド経由でこの値を受け取るため、値を変更しないでください。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public enum WhisperSamplingStrategy
{
	/** 貪欲法。{@code WHISPER_SAMPLING_GREEDY}。OpenAI の GreedyDecoder に相当します。 */
	GREEDY(0),

	/** ビームサーチ。{@code WHISPER_SAMPLING_BEAM_SEARCH}。OpenAI の BeamSearchDecoder に相当します。 */
	BEAM_SEARCH(1);

	private final int nativeValue;

	WhisperSamplingStrategy(int nativeValue)
	{
		this.nativeValue = nativeValue;
	}

	/**
	 * whisper.cpp 側の列挙値を返します。
	 *
	 * @return {@code whisper_sampling_strategy} の値
	 */
	public int nativeValue()
	{
		return this.nativeValue;
	}

	/**
	 * whisper.cpp 側の列挙値から対応する定数を返します。
	 *
	 * @param nativeValue {@code whisper_sampling_strategy} の値
	 * @return 対応する定数
	 * @throws IllegalArgumentException 未知の値の場合
	 */
	public static WhisperSamplingStrategy fromNativeValue(int nativeValue)
	{
		return Stream.of(values())
				.filter(strategy -> strategy.nativeValue == nativeValue)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown whisper_sampling_strategy: " + nativeValue));
	}
}
