package jp.clip.whisper;

import java.nio.file.Path;

/**
 * {@link WhisperEngine} の設定。{@link #builder()} から組み立てます。
 *
 * <p>
 * モデル以外はすべて既定値を持ちます。日本語を扱う場合は {@code language("ja")} を
 * 明示してください（既定は whisper.cpp と同じ {@code "en"} です）。
 * </p>
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
 * 		.language("ja")
 * 		.threads(Runtime.getRuntime().availableProcessors())
 * 		.vad(true)
 * 		.build();
 * </pre>
 */
public final class WhisperConfig
{
	private final Path model;
	private final String language;
	private final boolean detectLanguage;
	private final boolean translate;
	private final int threads;
	private final SamplingStrategy samplingStrategy;
	private final boolean useGpu;
	private final String initialPrompt;

	private final boolean vad;
	private final Path vadModel;
	private final float vadThreshold;
	private final int vadMinSpeechDurationMs;
	private final int vadMinSilenceDurationMs;
	private final float vadMaxSpeechDurationSeconds;
	private final int vadSpeechPadMs;
	private final float vadSamplesOverlap;

	private final String grammar;
	private final float grammarPenalty;

	private final Path nativeLibraryDirectory;
	private final boolean printNativeProgress;

	private WhisperConfig(Builder builder)
	{
		this.model = builder.model;
		this.language = builder.language;
		this.detectLanguage = builder.detectLanguage;
		this.translate = builder.translate;
		this.threads = builder.threads;
		this.samplingStrategy = builder.samplingStrategy;
		this.useGpu = builder.useGpu;
		this.initialPrompt = builder.initialPrompt;
		this.vad = builder.vad;
		this.vadModel = builder.vadModel;
		this.vadThreshold = builder.vadThreshold;
		this.vadMinSpeechDurationMs = builder.vadMinSpeechDurationMs;
		this.vadMinSilenceDurationMs = builder.vadMinSilenceDurationMs;
		this.vadMaxSpeechDurationSeconds = builder.vadMaxSpeechDurationSeconds;
		this.vadSpeechPadMs = builder.vadSpeechPadMs;
		this.vadSamplesOverlap = builder.vadSamplesOverlap;
		this.grammar = builder.grammar;
		this.grammarPenalty = builder.grammarPenalty;
		this.nativeLibraryDirectory = builder.nativeLibraryDirectory;
		this.printNativeProgress = builder.printNativeProgress;
	}

	/**
	 * 新しいビルダーを返します。
	 *
	 * @return ビルダー
	 */
	public static Builder builder()
	{
		return new Builder();
	}

	/** @return ggml モデルファイルのパス */
	public Path model() { return this.model; }

	/** @return 言語コード（例 {@code "ja"}、{@code "en"}） */
	public String language() { return this.language; }

	/** @return true なら言語を自動判定する */
	public boolean detectLanguage() { return this.detectLanguage; }

	/** @return true なら英語に翻訳して出力する */
	public boolean translate() { return this.translate; }

	/** @return 使用スレッド数。0 なら whisper.cpp の既定に任せる */
	public int threads() { return this.threads; }

	/** @return サンプリング戦略 */
	public SamplingStrategy samplingStrategy() { return this.samplingStrategy; }

	/** @return true なら GPU バックエンドを使う（利用可能な場合） */
	public boolean useGpu() { return this.useGpu; }

	/** @return 初期プロンプト。未設定なら null */
	public String initialPrompt() { return this.initialPrompt; }

	/** @return true なら VAD で無音区間を除去する */
	public boolean vad() { return this.vad; }

	/** @return VAD モデルのパス。null なら jar 同梱の silero モデルを使う */
	public Path vadModel() { return this.vadModel; }

	/** @return VAD の音声判定しきい値（0.0〜1.0） */
	public float vadThreshold() { return this.vadThreshold; }

	/** @return VAD が音声区間とみなす最小長（ミリ秒） */
	public int vadMinSpeechDurationMs() { return this.vadMinSpeechDurationMs; }

	/** @return VAD が区間を分割する最小無音長（ミリ秒） */
	public int vadMinSilenceDurationMs() { return this.vadMinSilenceDurationMs; }

	/** @return VAD の 1 区間あたり最大長（秒） */
	public float vadMaxSpeechDurationSeconds() { return this.vadMaxSpeechDurationSeconds; }

	/** @return VAD 区間の前後に付けるパディング（ミリ秒） */
	public int vadSpeechPadMs() { return this.vadSpeechPadMs; }

	/** @return VAD 区間同士の重なり（秒） */
	public float vadSamplesOverlap() { return this.vadSamplesOverlap; }

	/** @return gbnf 文法のテキスト。未設定なら null */
	public String grammar() { return this.grammar; }

	/** @return 文法から外れたトークンへのペナルティ */
	public float grammarPenalty() { return this.grammarPenalty; }

	/** @return ネイティブライブラリを読み込むディレクトリ。null なら jar 同梱を使う */
	public Path nativeLibraryDirectory() { return this.nativeLibraryDirectory; }

	/** @return true なら whisper.cpp の進捗ログを出力する */
	public boolean printNativeProgress() { return this.printNativeProgress; }

	/**
	 * {@link WhisperConfig} のビルダー。
	 */
	public static final class Builder
	{
		private Path model;
		private String language = "en";
		private boolean detectLanguage = false;
		private boolean translate = false;
		private int threads = 0;
		private SamplingStrategy samplingStrategy = SamplingStrategy.GREEDY;
		private boolean useGpu = true;
		private String initialPrompt = null;

		private boolean vad = false;
		private Path vadModel = null;
		private float vadThreshold = 0.5f;
		private int vadMinSpeechDurationMs = 250;
		private int vadMinSilenceDurationMs = 100;
		private float vadMaxSpeechDurationSeconds = Float.MAX_VALUE;
		private int vadSpeechPadMs = 30;
		private float vadSamplesOverlap = 0.1f;

		private String grammar = null;
		private float grammarPenalty = 100.0f;

		private Path nativeLibraryDirectory = null;
		private boolean printNativeProgress = false;

		private Builder()
		{
		}

		/**
		 * ggml モデルファイルを指定します。必須項目です。
		 *
		 * @param model モデルファイルのパス
		 * @return このビルダー
		 */
		public Builder model(Path model)
		{
			this.model = model;
			return this;
		}

		/**
		 * 言語コードを指定します。日本語なら {@code "ja"}。
		 *
		 * @param language 言語コード
		 * @return このビルダー
		 */
		public Builder language(String language)
		{
			this.language = language;
			return this;
		}

		/**
		 * 言語の自動判定を有効にします。
		 *
		 * @param detectLanguage true なら自動判定
		 * @return このビルダー
		 */
		public Builder detectLanguage(boolean detectLanguage)
		{
			this.detectLanguage = detectLanguage;
			return this;
		}

		/**
		 * 英語への翻訳を有効にします。
		 *
		 * @param translate true なら翻訳する
		 * @return このビルダー
		 */
		public Builder translate(boolean translate)
		{
			this.translate = translate;
			return this;
		}

		/**
		 * 使用スレッド数を指定します。0 を渡すと whisper.cpp の既定に任せます。
		 *
		 * @param threads スレッド数
		 * @return このビルダー
		 */
		public Builder threads(int threads)
		{
			this.threads = threads;
			return this;
		}

		/**
		 * サンプリング戦略を指定します。
		 *
		 * @param samplingStrategy サンプリング戦略
		 * @return このビルダー
		 */
		public Builder samplingStrategy(SamplingStrategy samplingStrategy)
		{
			this.samplingStrategy = samplingStrategy;
			return this;
		}

		/**
		 * GPU バックエンドの使用可否を指定します。
		 *
		 * @param useGpu false なら CPU のみを使う
		 * @return このビルダー
		 */
		public Builder useGpu(boolean useGpu)
		{
			this.useGpu = useGpu;
			return this;
		}

		/**
		 * 初期プロンプトを指定します。固有名詞の認識精度を上げるのに使えます。
		 *
		 * @param initialPrompt 初期プロンプト
		 * @return このビルダー
		 */
		public Builder initialPrompt(String initialPrompt)
		{
			this.initialPrompt = initialPrompt;
			return this;
		}

		/**
		 * VAD による無音区間の除去を有効にします。長い録音では処理時間が大きく減ります。
		 *
		 * @param vad true なら VAD を使う
		 * @return このビルダー
		 */
		public Builder vad(boolean vad)
		{
			this.vad = vad;
			return this;
		}

		/**
		 * VAD モデルのパスを指定します。指定しない場合は jar 同梱の silero モデルを使います。
		 *
		 * @param vadModel VAD モデルのパス
		 * @return このビルダー
		 */
		public Builder vadModel(Path vadModel)
		{
			this.vadModel = vadModel;
			return this;
		}

		/**
		 * VAD の音声判定しきい値を指定します。上げると無音と判定されやすくなります。
		 *
		 * @param vadThreshold しきい値（0.0〜1.0）
		 * @return このビルダー
		 */
		public Builder vadThreshold(float vadThreshold)
		{
			this.vadThreshold = vadThreshold;
			return this;
		}

		/**
		 * VAD が音声区間とみなす最小長を指定します。
		 *
		 * @param vadMinSpeechDurationMs 最小長（ミリ秒）
		 * @return このビルダー
		 */
		public Builder vadMinSpeechDurationMs(int vadMinSpeechDurationMs)
		{
			this.vadMinSpeechDurationMs = vadMinSpeechDurationMs;
			return this;
		}

		/**
		 * VAD が区間を分割する最小無音長を指定します。
		 *
		 * @param vadMinSilenceDurationMs 最小無音長（ミリ秒）
		 * @return このビルダー
		 */
		public Builder vadMinSilenceDurationMs(int vadMinSilenceDurationMs)
		{
			this.vadMinSilenceDurationMs = vadMinSilenceDurationMs;
			return this;
		}

		/**
		 * VAD の 1 区間あたり最大長を指定します。
		 *
		 * @param vadMaxSpeechDurationSeconds 最大長（秒）
		 * @return このビルダー
		 */
		public Builder vadMaxSpeechDurationSeconds(float vadMaxSpeechDurationSeconds)
		{
			this.vadMaxSpeechDurationSeconds = vadMaxSpeechDurationSeconds;
			return this;
		}

		/**
		 * VAD 区間の前後に付けるパディングを指定します。語頭・語尾の切れを防ぎます。
		 *
		 * @param vadSpeechPadMs パディング（ミリ秒）
		 * @return このビルダー
		 */
		public Builder vadSpeechPadMs(int vadSpeechPadMs)
		{
			this.vadSpeechPadMs = vadSpeechPadMs;
			return this;
		}

		/**
		 * VAD 区間同士の重なりを指定します。
		 *
		 * @param vadSamplesOverlap 重なり（秒）
		 * @return このビルダー
		 */
		public Builder vadSamplesOverlap(float vadSamplesOverlap)
		{
			this.vadSamplesOverlap = vadSamplesOverlap;
			return this;
		}

		/**
		 * gbnf 文法のテキストを指定します。出力を文法に沿った形に制約できます。
		 *
		 * @param grammar gbnf 文法のテキスト
		 * @return このビルダー
		 */
		public Builder grammar(String grammar)
		{
			this.grammar = grammar;
			return this;
		}

		/**
		 * 文法から外れたトークンへのペナルティを指定します。
		 *
		 * @param grammarPenalty ペナルティ
		 * @return このビルダー
		 */
		public Builder grammarPenalty(float grammarPenalty)
		{
			this.grammarPenalty = grammarPenalty;
			return this;
		}

		/**
		 * ネイティブライブラリを読み込むディレクトリを指定します。
		 *
		 * <p>
		 * Vulkan / CUDA 版のネイティブを使いたい場合に指定します。指定しない場合は
		 * jar 同梱の CPU 版を使います。
		 * </p>
		 *
		 * @param nativeLibraryDirectory ネイティブライブラリのディレクトリ
		 * @return このビルダー
		 */
		public Builder nativeLibraryDirectory(Path nativeLibraryDirectory)
		{
			this.nativeLibraryDirectory = nativeLibraryDirectory;
			return this;
		}

		/**
		 * whisper.cpp の進捗ログ出力を有効にします。既定では無効です。
		 *
		 * @param printNativeProgress true なら進捗を出力する
		 * @return このビルダー
		 */
		public Builder printNativeProgress(boolean printNativeProgress)
		{
			this.printNativeProgress = printNativeProgress;
			return this;
		}

		/**
		 * 設定を確定します。
		 *
		 * @return 生成された設定
		 * @throws WhisperException モデルが指定されていない場合
		 */
		public WhisperConfig build()
		{
			if (this.model == null)
			{
				throw new WhisperException("model は必須です。WhisperConfig.builder().model(path) を指定してください。");
			}
			return new WhisperConfig(this);
		}
	}
}
