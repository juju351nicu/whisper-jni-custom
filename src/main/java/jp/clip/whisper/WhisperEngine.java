package jp.clip.whisper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import jp.clip.whisperjni.WhisperContext;
import jp.clip.whisperjni.WhisperContextParams;
import jp.clip.whisperjni.WhisperGrammar;
import jp.clip.whisperjni.WhisperJNI;
import jp.clip.whisperjni.WhisperTranscriptionParams;

/**
 * whisper.cpp による文字起こしの入口となるクラス。
 *
 * <p>
 * ネイティブライブラリのロード、コンテキストの生成・破棄、パラメータの組み立てを
 * すべてこのクラスが受け持ちます。利用側は低レイヤの {@code jp.clip.whisperjni}
 * パッケージを直接触る必要がありません。
 * </p>
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
 * 		.language("ja")
 * 		.threads(Runtime.getRuntime().availableProcessors())
 * 		.vadEnabled(true)
 * 		.build();
 *
 * try(WhisperEngine engine = WhisperEngine.open(config))
 * {
 * 	TranscriptionResult result = engine.transcribe(Path.of("input.wav"));
 * 	System.out.println(result.text());
 * }
 * </pre>
 *
 * <p>
 * <b>スレッド安全ではありません。</b>1 つのインスタンスに対して複数スレッドから同時に
 * {@code transcribe} を呼ばないでください。並列に処理したい場合はスレッドごとに
 * インスタンスを生成してください（モデルの分だけメモリを消費します）。
 * </p>
 *
 * <p>
 * whisper.cpp 自身のログは {@code "whisper.cpp"} という名前の SLF4J ロガーへ流れます。
 * 詳細は {@link NativeRuntime#NATIVE_LOGGER_NAME} を参照してください。
 * </p>
 */
public final class WhisperEngine implements AutoCloseable
{
	/** whisper.cpp が要求するサンプリングレート（Hz）。 */
	public static final int SAMPLE_RATE = 16000;

	/** whisper.cpp のタイムスタンプはセンチ秒単位なのでミリ秒へ変換する係数。 */
	private static final long CENTISECONDS_TO_MILLIS = 10L;

	private final WhisperConfig config;
	private final WhisperJNI whisper;
	private final WhisperContext context;
	private final WhisperGrammar grammar;
	private final String vadModelPath;

	private boolean closed = false;

	private WhisperEngine(WhisperConfig config, WhisperJNI whisper, WhisperContext context, WhisperGrammar grammar, String vadModelPath)
	{
		this.config = config;
		this.whisper = whisper;
		this.context = context;
		this.grammar = grammar;
		this.vadModelPath = vadModelPath;
	}

	// ------------------------------------------------------------------------
	// 生成
	// ------------------------------------------------------------------------

	/**
	 * エンジンを生成します。モデルの読み込みが完了した状態で返ります。
	 *
	 * <p>
	 * ネイティブライブラリは JVM 内で 1 度だけロードされます（2 回目以降の呼び出しでは
	 * 再ロードしません）。
	 * </p>
	 *
	 * @param config 設定
	 * @return 使用可能な {@link WhisperEngine}
	 * @throws WhisperException ネイティブのロード、モデルの読み込み、文法の解析に失敗した場合
	 */
	public static WhisperEngine open(WhisperConfig config)
	{
		if(config == null)
		{
			throw new WhisperException("config が null です。");
		}
		if(!Files.isRegularFile(config.model()))
		{
			throw new WhisperException("モデルファイルが見つかりません: " + config.model().toAbsolutePath());
		}

		NativeRuntime.ensureNativesLoaded(config);

		WhisperJNI whisper = new WhisperJNI();
		WhisperContext context = openContext(whisper, config);
		try
		{
			WhisperGrammar grammar = config.hasGrammar() ? parseGrammar(whisper, config) : null;
			String vadModelPath = config.vadEnabled() ? NativeRuntime.resolveVadModelPath(config) : null;
			return new WhisperEngine(config, whisper, context, grammar, vadModelPath);
		}
		catch(RuntimeException e)
		{
			// 途中で失敗したらコンテキストを道連れにしない
			context.close();
			throw e;
		}
	}

	private static WhisperContext openContext(WhisperJNI whisper, WhisperConfig config)
	{
		WhisperContextParams contextParams = new WhisperContextParams();
		contextParams.useGpu = config.useGpu();
		try
		{
			return whisper.createContext(config.model(), contextParams);
		}
		catch(IOException e)
		{
			throw new WhisperException("モデルの読み込みに失敗しました: " + config.model().toAbsolutePath(), e);
		}
	}

	private static WhisperGrammar parseGrammar(WhisperJNI whisper, WhisperConfig config)
	{
		try
		{
			return whisper.parseGrammar(config.grammar());
		}
		catch(IOException e)
		{
			throw new WhisperException("文法の解析に失敗しました。", e);
		}
	}

	// ------------------------------------------------------------------------
	// 文字起こし
	// ------------------------------------------------------------------------

	/**
	 * 音声ファイルを文字起こしします。
	 *
	 * <p>
	 * 16kHz モノラル 16bit PCM 以外のファイルも、Java の標準変換で対応できる範囲であれば
	 * 自動的に変換します（{@link AudioFileReader} 参照）。
	 * </p>
	 *
	 * @param audioFile 音声ファイル（WAV など Java が読める形式）
	 * @return 文字起こし結果
	 * @throws WhisperException 読み込み・変換・文字起こしに失敗した場合
	 */
	public TranscriptionResult transcribe(Path audioFile)
	{
		return this.transcribe(AudioFileReader.readSamples(audioFile));
	}

	/**
	 * 16kHz モノラルの float サンプル列を文字起こしします。
	 *
	 * <p>
	 * 各サンプルは -1.0f〜1.0f に正規化された値である必要があります。音声のデコードを
	 * 自前で行っている場合はこちらを使ってください。
	 * </p>
	 *
	 * @param samples 16kHz モノラルの正規化済みサンプル列
	 * @return 文字起こし結果。サンプルが空なら空の結果
	 * @throws WhisperException whisper.cpp が失敗した場合
	 */
	public TranscriptionResult transcribe(float[] samples)
	{
		this.assertOpen();
		if(samples == null || samples.length == 0)
		{
			return new TranscriptionResult(List.of(), 0L);
		}

		WhisperTranscriptionParams params = this.buildTranscriptionParams();

		long startedAt = System.nanoTime();
		int code = this.whisper.transcribe(this.context, params, samples, samples.length);
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		if(code != 0)
		{
			throw new WhisperException("whisper.cpp の文字起こしが失敗しました。戻り値=" + code);
		}
		return new TranscriptionResult(this.collectSegments(), elapsedMs);
	}

	private List<Segment> collectSegments()
	{
		int segmentCount = this.whisper.segmentCount(this.context);
		return IntStream.range(0, segmentCount)
				.mapToObj(this::segmentAt)
				.toList();
	}

	private Segment segmentAt(int index)
	{
		long startMs = this.whisper.segmentStartCentiseconds(this.context, index) * CENTISECONDS_TO_MILLIS;
		long endMs = this.whisper.segmentEndCentiseconds(this.context, index) * CENTISECONDS_TO_MILLIS;
		String text = this.whisper.segmentText(this.context, index);
		return new Segment(startMs, endMs, text);
	}

	/**
	 * 設定を whisper.cpp のパラメータへ写します。呼び出しごとに作り直します（使い捨て）。
	 */
	private WhisperTranscriptionParams buildTranscriptionParams()
	{
		WhisperTranscriptionParams params = new WhisperTranscriptionParams(this.config.samplingStrategy().toBridge());

		params.language = this.config.language();
		params.detectLanguage = this.config.detectLanguage();
		params.translate = this.config.translateToEnglish();
		params.threads = this.config.threads();
		params.initialPrompt = this.config.initialPrompt();

		// 結果は Java 側で受け取るので whisper.cpp 側の標準出力は進捗以外すべて止める
		params.printProgress = this.config.printNativeProgress();
		params.printRealtime = false;
		params.printTimestamps = false;
		params.printSpecial = false;

		if(this.grammar != null)
		{
			params.grammar = this.grammar;
			params.grammarPenalty = this.config.grammarPenalty();
		}

		if(this.config.vadEnabled())
		{
			params.vadEnabled = true;
			params.vadModelPath = this.vadModelPath;
			params.vadParams.threshold = this.config.vadThreshold();
			params.vadParams.minSpeechDurationMs = this.config.vadMinSpeechDurationMs();
			params.vadParams.minSilenceDurationMs = this.config.vadMinSilenceDurationMs();
			params.vadParams.maxSpeechDurationSeconds = this.config.vadMaxSpeechDurationSeconds();
			params.vadParams.speechPadMs = this.config.vadSpeechPadMs();
			params.vadParams.samplesOverlap = this.config.vadSamplesOverlap();
		}
		return params;
	}

	// ------------------------------------------------------------------------
	// 照会と後始末
	// ------------------------------------------------------------------------

	/**
	 * 読み込んだモデルが多言語対応かどうかを返します。
	 *
	 * @return 多言語モデルなら true
	 */
	public boolean isMultilingual()
	{
		this.assertOpen();
		return this.whisper.isMultilingual(this.context);
	}

	/**
	 * whisper.cpp のシステム情報（有効な CPU 命令セットやバックエンド）を返します。
	 *
	 * @return システム情報の文字列
	 */
	public String systemInfo()
	{
		this.assertOpen();
		return this.whisper.getSystemInfo();
	}

	/**
	 * このエンジンの設定を返します。
	 *
	 * @return 設定
	 */
	public WhisperConfig config()
	{
		return this.config;
	}

	/**
	 * ネイティブメモリを解放します。以降このインスタンスは使用できません。多重呼び出しは安全です。
	 */
	@Override
	public void close()
	{
		if(this.closed)
		{
			return;
		}
		this.closed = true;
		if(this.grammar != null)
		{
			this.grammar.close();
		}
		this.context.close();
	}

	private void assertOpen()
	{
		if(this.closed)
		{
			throw new WhisperException("この WhisperEngine は既に close されています。");
		}
	}
}
