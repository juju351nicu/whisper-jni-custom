package jp.clip.whisper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.clip.whisperjni.LibraryUtils;
import jp.clip.whisperjni.WhisperContext;
import jp.clip.whisperjni.WhisperContextParams;
import jp.clip.whisperjni.WhisperFullParams;
import jp.clip.whisperjni.WhisperGrammar;
import jp.clip.whisperjni.WhisperJNI;

/**
 * whisper.cpp による文字起こしの入口となるクラス。
 *
 * <p>
 * ネイティブライブラリのロード、コンテキストの生成・破棄、パラメータの組み立てを
 * すべてこのクラスが受け持ちます。利用側は低レイヤの {@code jp.clip.whisperjni}
 * パッケージを直接触る必要がありません。
 * </p>
 *
 * <p>
 * 使用例:
 * </p>
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
 * 		.language("ja")
 * 		.threads(Runtime.getRuntime().availableProcessors())
 * 		.vad(true)
 * 		.build();
 *
 * try (WhisperEngine engine = WhisperEngine.open(config))
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
 */
public final class WhisperEngine implements AutoCloseable
{
	private static final Logger LOG = LoggerFactory.getLogger(WhisperEngine.class);

	/** whisper.cpp が要求するサンプリングレート。 */
	public static final int SAMPLE_RATE = 16000;

	/** whisper.cpp のタイムスタンプはセンチ秒単位なのでミリ秒へ変換する係数。 */
	private static final long CENTISECONDS_TO_MILLIS = 10L;

	private static boolean nativesLoaded = false;
	private static Path extractedVadModel = null;

	private final WhisperConfig config;
	private final WhisperJNI whisper;
	private final WhisperContext context;
	private final WhisperGrammar grammar;
	private final String vadModelPath;

	private boolean closed = false;

	private WhisperEngine(WhisperConfig config, WhisperJNI whisper, WhisperContext context,
			WhisperGrammar grammar, String vadModelPath)
	{
		this.config = config;
		this.whisper = whisper;
		this.context = context;
		this.grammar = grammar;
		this.vadModelPath = vadModelPath;
	}

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
		if (config == null)
		{
			throw new WhisperException("config が null です。");
		}
		if (!Files.isRegularFile(config.model()))
		{
			throw new WhisperException("モデルファイルが見つかりません: " + config.model().toAbsolutePath());
		}

		WhisperJNI whisper = new WhisperJNI();
		loadNatives(whisper, config);

		WhisperContextParams contextParams = new WhisperContextParams();
		contextParams.useGPU = config.useGpu();

		WhisperContext context;
		try
		{
			context = whisper.init(config.model(), contextParams);
		}
		catch (IOException e)
		{
			throw new WhisperException("モデルの読み込みに失敗しました: " + config.model().toAbsolutePath(), e);
		}
		if (context == null)
		{
			throw new WhisperException("モデルの読み込みに失敗しました: " + config.model().toAbsolutePath());
		}

		WhisperGrammar grammar = null;
		String vadModelPath = null;
		try
		{
			if (config.grammar() != null && !config.grammar().isBlank())
			{
				grammar = whisper.parseGrammar(config.grammar());
			}
			if (config.vad())
			{
				vadModelPath = resolveVadModel(config);
			}
		}
		catch (RuntimeException | IOException e)
		{
			context.close();
			if (grammar != null)
			{
				grammar.close();
			}
			if (e instanceof WhisperException whisperException)
			{
				throw whisperException;
			}
			throw new WhisperException("エンジンの初期化に失敗しました。", e);
		}

		return new WhisperEngine(config, whisper, context, grammar, vadModelPath);
	}

	/**
	 * 音声ファイルを文字起こしします。
	 *
	 * <p>
	 * 16kHz モノラル 16bit PCM 以外のファイルも、Java の標準変換で対応できる範囲であれば
	 * 自動的に変換します。対応できない場合は {@link WhisperException} を投げます。
	 * </p>
	 *
	 * @param audioFile 音声ファイル（WAV など Java が読める形式）
	 * @return 文字起こし結果
	 * @throws WhisperException 読み込み・変換・文字起こしに失敗した場合
	 */
	public TranscriptionResult transcribe(Path audioFile)
	{
		return this.transcribe(readAudioSamples(audioFile));
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
	 * @return 文字起こし結果
	 * @throws WhisperException whisper.cpp が失敗した場合
	 */
	public TranscriptionResult transcribe(float[] samples)
	{
		this.assertOpen();
		if (samples == null || samples.length == 0)
		{
			return new TranscriptionResult(List.of(), 0L);
		}

		WhisperFullParams params = this.buildFullParams();

		long startedAt = System.nanoTime();
		int code = this.whisper.full(this.context, params, samples, samples.length);
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		if (code != 0)
		{
			throw new WhisperException("whisper.cpp の文字起こしが失敗しました。戻り値=" + code);
		}

		int segmentCount = this.whisper.fullNSegments(this.context);
		List<Segment> segments = new ArrayList<>(segmentCount);
		for (int i = 0; i < segmentCount; i++)
		{
			long startCentiseconds = this.whisper.fullGetSegmentTimestamp0(this.context, i);
			long endCentiseconds = this.whisper.fullGetSegmentTimestamp1(this.context, i);
			String text = this.whisper.fullGetSegmentText(this.context, i);
			segments.add(new Segment(startCentiseconds * CENTISECONDS_TO_MILLIS,
					endCentiseconds * CENTISECONDS_TO_MILLIS, text));
		}

		return new TranscriptionResult(segments, elapsedMs);
	}

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
	 * ネイティブメモリを解放します。以降このインスタンスは使用できません。
	 */
	@Override
	public void close()
	{
		if (this.closed)
		{
			return;
		}
		this.closed = true;
		if (this.grammar != null)
		{
			this.grammar.close();
		}
		this.context.close();
	}

	/**
	 * 音声ファイルを 16kHz モノラルの正規化済み float サンプル列として読み込みます。
	 *
	 * <p>
	 * {@link #transcribe(Path)} が内部で使っているものと同じ処理です。サンプルを加工したい
	 * 場合などに直接呼べるよう公開しています。
	 * </p>
	 *
	 * @param audioFile 音声ファイル
	 * @return 16kHz モノラルの正規化済みサンプル列
	 * @throws WhisperException 読み込み・変換に失敗した場合
	 */
	public static float[] readAudioSamples(Path audioFile)
	{
		if (audioFile == null || !Files.isRegularFile(audioFile))
		{
			throw new WhisperException("音声ファイルが見つかりません: " + audioFile);
		}

		AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 1, 2,
				SAMPLE_RATE, false);

		try (AudioInputStream source = AudioSystem.getAudioInputStream(audioFile.toFile()))
		{
			AudioFormat sourceFormat = source.getFormat();
			if (!AudioSystem.isConversionSupported(target, sourceFormat)
					&& !sourceFormat.matches(target))
			{
				throw new WhisperException("この音声フォーマットは変換できません: " + sourceFormat
						+ " / 16kHz モノラル 16bit PCM の WAV に変換してから渡してください。");
			}

			try (AudioInputStream converted = sourceFormat.matches(target) ? source
					: AudioSystem.getAudioInputStream(target, source))
			{
				byte[] bytes = converted.readAllBytes();
				ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
				ShortBuffer shortBuffer = byteBuffer.asShortBuffer();
				float[] samples = new float[shortBuffer.remaining()];
				int index = 0;
				while (shortBuffer.hasRemaining())
				{
					float value = (float) shortBuffer.get() / (float) Short.MAX_VALUE;
					samples[index] = Math.max(-1.0f, Math.min(value, 1.0f));
					index++;
				}
				return samples;
			}
		}
		catch (UnsupportedAudioFileException e)
		{
			throw new WhisperException("対応していない音声ファイル形式です: " + audioFile.toAbsolutePath(), e);
		}
		catch (IOException e)
		{
			throw new WhisperException("音声ファイルの読み込みに失敗しました: " + audioFile.toAbsolutePath(), e);
		}
	}

	private WhisperFullParams buildFullParams()
	{
		WhisperFullParams params = new WhisperFullParams(this.config.samplingStrategy().nativeValue());

		params.language = this.config.language();
		params.detectLanguage = this.config.detectLanguage();
		params.translate = this.config.translate();
		params.nThreads = this.config.threads();
		params.initialPrompt = this.config.initialPrompt();

		params.printProgress = this.config.printNativeProgress();
		params.printRealtime = false;
		params.printTimestamps = false;
		params.printSpecial = false;

		if (this.grammar != null)
		{
			params.grammar = this.grammar;
			params.grammarPenalty = this.config.grammarPenalty();
		}

		if (this.config.vad())
		{
			params.vad = true;
			params.vad_model_path = this.vadModelPath;
			params.vadParams.threshold = this.config.vadThreshold();
			params.vadParams.min_speech_duration_ms = this.config.vadMinSpeechDurationMs();
			params.vadParams.min_silence_duration_ms = this.config.vadMinSilenceDurationMs();
			params.vadParams.max_speech_duration_s = this.config.vadMaxSpeechDurationSeconds();
			params.vadParams.speech_pad_ms = this.config.vadSpeechPadMs();
			params.vadParams.samples_overlap = this.config.vadSamplesOverlap();
		}

		return params;
	}

	private static synchronized void loadNatives(WhisperJNI whisper, WhisperConfig config)
	{
		if (nativesLoaded)
		{
			return;
		}
		try
		{
			if (config.nativeLibraryDirectory() != null)
			{
				LOG.info("ネイティブライブラリを {} から読み込みます", config.nativeLibraryDirectory());
				LibraryUtils.loadLibrary(LOG, config.nativeLibraryDirectory());
			}
			else
			{
				whisper.loadLibrary(LOG);
			}
			WhisperJNI.setLogger(LOG);
			nativesLoaded = true;
		}
		catch (IOException e)
		{
			throw new WhisperException("ネイティブライブラリの読み込みに失敗しました。", e);
		}
	}

	private static synchronized String resolveVadModel(WhisperConfig config)
	{
		if (config.vadModel() != null)
		{
			if (!Files.isRegularFile(config.vadModel()))
			{
				throw new WhisperException("VAD モデルが見つかりません: " + config.vadModel().toAbsolutePath());
			}
			return config.vadModel().toAbsolutePath().toString();
		}

		if (extractedVadModel != null && Files.isRegularFile(extractedVadModel))
		{
			return extractedVadModel.toAbsolutePath().toString();
		}

		try
		{
			Path temporary = Files.createTempFile("whisper-vad-", ".bin");
			temporary.toFile().deleteOnExit();
			LibraryUtils.exportVADModel(LOG, temporary);
			extractedVadModel = temporary;
			return temporary.toAbsolutePath().toString();
		}
		catch (IOException | NullPointerException e)
		{
			throw new WhisperException("jar 同梱の VAD モデルを展開できませんでした。"
					+ "WhisperConfig.vadModel(path) で明示的に指定してください。", e);
		}
	}

	private void assertOpen()
	{
		if (this.closed)
		{
			throw new WhisperException("この WhisperEngine は既に close されています。");
		}
	}
}
