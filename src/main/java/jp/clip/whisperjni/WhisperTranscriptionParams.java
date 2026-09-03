package jp.clip.whisperjni;

/**
 * 文字起こし（{@link WhisperJNI#transcribe}）実行時のパラメータ。whisper.cpp の
 * {@code struct whisper_full_params} に対応します。
 *
 * <p>
 * C 構造体をそのまま写した「値の入れ物」なので、意図的に public フィールドにしています。
 * 各フィールドの Javadoc に対応する {@code whisper_full_params} のメンバー名を併記してあります。
 * </p>
 *
 * <p>
 * 既定値は原則として whisper.cpp の {@code whisper_full_default_params} と同じですが、
 * このラッパーが独自に変えているものが 4 つあります（元の whisper-jni から引き継いだ値で、
 * テストの期待値がこれらに依存しています）:
 * {@link #suppressNonSpeechTokens}（true / whisper.cpp は false）、
 * {@link #temperatureIncrement}（0.4 / whisper.cpp は 0.2）、
 * {@link #greedyBestOf}（-1 / whisper.cpp は 5）、
 * {@link #beamSize}（2 / whisper.cpp は 5）。
 * </p>
 *
 * <p>
 * <b>フィールド名は C++ 側が {@code GetFieldID} で参照しています。</b>名前や型を変えるときは
 * {@code jp_clip_whisperjni_WhisperJNI.cpp} の {@code readTranscriptionParams} も同時に変えてください。
 * 不一致があると実行時に {@link NoSuchFieldError} になります。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public class WhisperTranscriptionParams
{
	// ------------------------------------------------------------------------
	// 戦略
	// ------------------------------------------------------------------------

	/**
	 * デコード戦略の whisper.cpp 側の値。{@code whisper_full_params.strategy}。
	 *
	 * <p>
	 * C++ 側が直接読むため int で保持しています。Java 側からは {@link #strategy()} を使ってください。
	 * </p>
	 */
	private final int strategy;

	// ------------------------------------------------------------------------
	// 基本
	// ------------------------------------------------------------------------

	/** 使用スレッド数。0 なら whisper.cpp の既定に任せる。{@code n_threads}。 */
	public int threads = 0;

	/** エンコーダの音声コンテキスト長を上書きする。0 で既定。{@code audio_ctx}。 */
	public int audioContextSize = 0;

	/** デコーダのプロンプトとして使う過去テキストの最大トークン数。{@code n_max_text_ctx}。 */
	public int maxTextContextTokens = 16384;

	/** 処理を開始する位置（ミリ秒）。{@code offset_ms}。 */
	public int offsetMs = 0;

	/** 処理する長さ（ミリ秒）。0 で末尾まで。{@code duration_ms}。 */
	public int durationMs = 0;

	/** 英語へ翻訳するかどうか。{@code translate}。 */
	public boolean translate = false;

	/** タイムスタンプを生成しない。{@code no_timestamps}。 */
	public boolean noTimestamps = false;

	/** 言語を自動判定する。{@code detect_language}。 */
	public boolean detectLanguage = false;

	/** 言語コード（例 {@code "ja"}）。{@code language}。 */
	public String language = "en";

	/** 初期プロンプト。固有名詞の認識精度を上げるのに使う。null なら無し。{@code initial_prompt}。 */
	public String initialPrompt = null;

	/** 直前の文字起こし結果をプロンプトに使わない。{@code no_context}。 */
	public boolean noContext = true;

	/** 出力を 1 セグメントに強制する（ストリーミング向け）。{@code single_segment}。 */
	public boolean singleSegment = false;

	// ------------------------------------------------------------------------
	// whisper.cpp 側の標準出力への表示
	// ------------------------------------------------------------------------

	/** 特殊トークンを表示する。{@code print_special}。 */
	public boolean printSpecial = false;

	/** 進捗を表示する。{@code print_progress}。 */
	public boolean printProgress = true;

	/** 結果を whisper.cpp 側でリアルタイム表示する（通常は不要）。{@code print_realtime}。 */
	public boolean printRealtime = false;

	/** リアルタイム表示時にタイムスタンプも表示する。{@code print_timestamps}。 */
	public boolean printTimestamps = true;

	// ------------------------------------------------------------------------
	// デコーダ調整
	// ------------------------------------------------------------------------

	/** 空白トークンを抑制する。{@code suppress_blank}。 */
	public boolean suppressBlank = true;

	/** 非音声トークン（効果音の記述など）を抑制する。{@code suppress_nst}。 */
	public boolean suppressNonSpeechTokens = true;

	/** 初期温度。{@code temperature}。 */
	public float temperature = 0.0f;

	/** 先頭タイムスタンプの上限（秒）。{@code max_initial_ts}。 */
	public float maxInitialTimestampSeconds = 1.0f;

	/** 長さペナルティ。負値で無効。{@code length_penalty}。 */
	public float lengthPenalty = -1.0f;

	/** フォールバック時の温度増分。{@code temperature_inc}。 */
	public float temperatureIncrement = 0.4f;

	/** エントロピーしきい値（これを超えるとフォールバック）。{@code entropy_thold}。 */
	public float entropyThreshold = 2.4f;

	/** 対数確率しきい値（これを下回るとフォールバック）。{@code logprob_thold}。 */
	public float logProbabilityThreshold = -1.0f;

	/** 無音判定のしきい値。{@code no_speech_thold}。 */
	public float noSpeechThreshold = 0.6f;

	/** GREEDY 専用: 候補数。{@code greedy.best_of}。 */
	public int greedyBestOf = -1;

	/** BEAM_SEARCH 専用: ビーム幅。{@code beam_search.beam_size}。 */
	public int beamSize = 2;

	/** BEAM_SEARCH 専用: patience。{@code beam_search.patience}。 */
	public float beamPatience = -1.0f;

	// ------------------------------------------------------------------------
	// 文法
	// ------------------------------------------------------------------------

	/** 解析済み GBNF 文法。null なら文法制約なし。{@code grammar_rules} / {@code i_start_rule}。 */
	public WhisperGrammar grammar = null;

	/** 文法から外れたトークンへのペナルティ。{@code grammar_penalty}。 */
	public float grammarPenalty = 100.0f;

	// ------------------------------------------------------------------------
	// VAD（無音区間の除去）
	// ------------------------------------------------------------------------

	/** VAD を有効にする。{@code vad}。 */
	public boolean vadEnabled = false;

	/** VAD モデル（silero）のパス。{@link #vadEnabled} が true のとき必須。{@code vad_model_path}。 */
	public String vadModelPath = null;

	/** VAD の詳細設定。{@code vad_params}。C++ 側は null を想定していないので常に非 null。 */
	public final VadParams vadParams = new VadParams();

	// ------------------------------------------------------------------------
	// コンストラクタ
	// ------------------------------------------------------------------------

	/**
	 * デコード戦略を指定して生成します。他のフィールドは whisper.cpp の既定値になります。
	 *
	 * @param strategy デコード戦略
	 */
	public WhisperTranscriptionParams(WhisperSamplingStrategy strategy)
	{
		this.strategy = strategy.nativeValue();
	}

	/**
	 * {@link WhisperSamplingStrategy#GREEDY} で生成します。
	 */
	public WhisperTranscriptionParams()
	{
		this(WhisperSamplingStrategy.GREEDY);
	}

	/**
	 * デコード戦略を返します。
	 *
	 * @return デコード戦略
	 */
	public WhisperSamplingStrategy strategy()
	{
		return WhisperSamplingStrategy.fromNativeValue(this.strategy);
	}

	/**
	 * VAD の詳細設定。whisper.cpp の {@code struct whisper_vad_params} に対応します。
	 *
	 * <p>
	 * <b>フィールド名は C++ 側が {@code GetFieldID} で参照しています。</b>
	 * </p>
	 */
	public static class VadParams
	{
		/** 音声と判定する確率のしきい値（0.0〜1.0）。{@code threshold}。 */
		public float threshold = 0.5f;

		/** 音声区間として採用する最小長（ミリ秒）。{@code min_speech_duration_ms}。 */
		public int minSpeechDurationMs = 250;

		/** 区間を分割する最小無音長（ミリ秒）。{@code min_silence_duration_ms}。 */
		public int minSilenceDurationMs = 100;

		/** 1 区間あたりの最大長（秒）。{@code max_speech_duration_s}。 */
		public float maxSpeechDurationSeconds = Float.MAX_VALUE;

		/** 区間の前後に付けるパディング（ミリ秒）。{@code speech_pad_ms}。 */
		public int speechPadMs = 30;

		/** 区間同士の重なり（秒）。{@code samples_overlap}。 */
		public float samplesOverlap = 0.1f;

		/**
		 * 既定値のパラメータを生成します。
		 */
		public VadParams()
		{
			// 既定値はフィールド初期化子で与える
		}
	}
}
