package jp.clip.whisperjni;

/**
 * 文字起こし結果を構成する 1 トークンの情報。whisper.cpp の {@code whisper_token_data} に
 * トークン文字列（{@code whisper_full_get_token_text}）を加えたものです。
 *
 * <p>
 * {@link WhisperJNI#segmentTokens(WhisperContext, int)} が返します。トークン単位の時刻
 * （{@link #startCentiseconds} / {@link #endCentiseconds}）は<b>センチ秒（10 ミリ秒）</b>単位で、
 * {@code token_timestamps} を有効にしていない場合は意味のある値になりません（-1）。
 * </p>
 *
 * <p>
 * 各フィールドの Javadoc に対応する {@code whisper_token_data} のメンバー名を併記してあります。
 * </p>
 *
 * @author Sullbeans
 */
public final class WhisperToken
{
	/** トークンの文字列。{@code whisper_full_get_token_text} の結果。 */
	public final String text;

	/** トークン ID。{@code id}。 */
	public final int id;

	/** 強制タイムスタンプトークンの ID。{@code tid}。 */
	public final int timestampTokenId;

	/** このトークンの確率（0.0〜1.0）。{@code p}。 */
	public final float probability;

	/** このトークンの対数確率。{@code plog}。 */
	public final float logProbability;

	/** タイムスタンプトークンの確率。{@code pt}。 */
	public final float timestampProbability;

	/** すべてのタイムスタンプトークンの確率の総和。{@code ptsum}。 */
	public final float timestampProbabilitySum;

	/** トークンの開始時刻（センチ秒）。{@code t0}。トークン単位のタイムスタンプを計算していない場合は -1。 */
	public final long startCentiseconds;

	/** トークンの終了時刻（センチ秒）。{@code t1}。トークン単位のタイムスタンプを計算していない場合は -1。 */
	public final long endCentiseconds;

	/**
	 * DTW によるトークン単位タイムスタンプ（センチ秒、実験的機能）。{@code t_dtw}。
	 *
	 * <p>
	 * そのトークンが音声中で出力された時点に概ね対応します。DTW を有効にしていない場合は -1。
	 * </p>
	 */
	public final long dtwCentiseconds;

	/** トークンの音声長。{@code vlen}。 */
	public final float voiceLength;

	/**
	 * 内部用コンストラクタ。JNI 側から呼び出されます。
	 *
	 * <p>
	 * <b>引数の順序と型を変更しないでください。</b>JNI 側がシグネチャ
	 * {@code (Ljava/lang/String;IIFFFFJJJF)V} でこのコンストラクタを直接呼び出しています
	 * （{@code jp_clip_whisperjni_WhisperJNI.cpp} の {@code newWhisperToken}）。
	 * </p>
	 *
	 * @param text                    トークンの文字列
	 * @param id                      トークン ID
	 * @param timestampTokenId        強制タイムスタンプトークンの ID
	 * @param probability             確率
	 * @param logProbability          対数確率
	 * @param timestampProbability    タイムスタンプトークンの確率
	 * @param timestampProbabilitySum タイムスタンプトークンの確率の総和
	 * @param startCentiseconds       開始時刻（センチ秒）
	 * @param endCentiseconds         終了時刻（センチ秒）
	 * @param dtwCentiseconds         DTW によるタイムスタンプ（センチ秒）
	 * @param voiceLength             音声長
	 */
	WhisperToken(String text, int id, int timestampTokenId, float probability, float logProbability, float timestampProbability,
			float timestampProbabilitySum, long startCentiseconds, long endCentiseconds, long dtwCentiseconds, float voiceLength)
	{
		this.text = text;
		this.id = id;
		this.timestampTokenId = timestampTokenId;
		this.probability = probability;
		this.logProbability = logProbability;
		this.timestampProbability = timestampProbability;
		this.timestampProbabilitySum = timestampProbabilitySum;
		this.startCentiseconds = startCentiseconds;
		this.endCentiseconds = endCentiseconds;
		this.dtwCentiseconds = dtwCentiseconds;
		this.voiceLength = voiceLength;
	}

	/**
	 * タイムスタンプやセグメント境界を表す特殊トークン（{@code [_...]} / {@code <|...|>}）かどうかを返します。
	 *
	 * <p>
	 * whisper.cpp 自身も同じ文字列判定（{@code text.rfind("[_", 0) == 0}）を行っているため、
	 * それに倣っています。
	 * </p>
	 *
	 * @return 特殊トークンなら true
	 */
	public boolean isSpecial()
	{
		return this.text.startsWith("[_") || this.text.startsWith("<|");
	}

	/**
	 * デバッグしやすい形式で内容を返します。
	 *
	 * @return {@code WhisperToken[text=' And', id=400, p=0.782, t0=-1, t1=-1]} のような文字列
	 */
	@Override
	public String toString()
	{
		return String.format("WhisperToken[text='%s', id=%d, p=%.3f, t0=%d, t1=%d]", this.text, this.id, this.probability,
				this.startCentiseconds, this.endCentiseconds);
	}
}
