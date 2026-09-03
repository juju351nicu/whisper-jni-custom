package jp.clip.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WhisperEngine} の結合テスト。
 *
 * <p>
 * 低レイヤの {@code WhisperJNITest} が whisper.cpp の出力文字列を完全一致で検証しているのに対し、
 * こちらは <b>API 層の振る舞い</b>（構造・時刻の単位・例外・後始末）を検証します。文字列は
 * 部分一致で確認するので、whisper.cpp を更新しても句読点の揺れで落ちません。
 * </p>
 */
public class WhisperEngineTest
{
	private static final Logger LOG = LoggerFactory.getLogger(WhisperEngineTest.class);

	private static final Path MODEL_PATH = Path.of("ggml-tiny.bin");
	private static final Path SAMPLE_PATH = Path.of("src/main/native/whisper/samples/jfk.wav");

	/** ネイティブの置き場所。ビルドスクリプトの出力があればそこから読む。 */
	private static Path nativeDirectory = null;

	@BeforeAll
	public static void beforeAll()
	{
		if(!Files.isRegularFile(MODEL_PATH))
		{
			throw new IllegalStateException("モデルがありません: " + MODEL_PATH.toAbsolutePath()
					+ " / scripts/download-test-model を先に実行してください。");
		}
		if(!Files.isRegularFile(SAMPLE_PATH))
		{
			throw new IllegalStateException("サンプル音声がありません: " + SAMPLE_PATH.toAbsolutePath());
		}

		Path buildDirectory = Path.of("whisperjni-build");
		if(Files.isDirectory(buildDirectory))
		{
			LOG.info("ビルド出力からネイティブを読み込みます: {}", buildDirectory);
			nativeDirectory = buildDirectory;
		}
		else
		{
			LOG.info("jar 同梱のネイティブを読み込みます");
		}
	}

	private static WhisperConfig.WhisperConfigBuilder baseConfig()
	{
		WhisperConfig.WhisperConfigBuilder builder = WhisperConfig.builder()
				.model(MODEL_PATH)
				.language("en");
		if(nativeDirectory != null)
		{
			builder.nativeLibraryDirectory(nativeDirectory);
		}
		return builder;
	}

	@Test
	public void builderRequiresModel()
	{
		// model は @NonNull。Lombok が生成する null チェックは NullPointerException を投げる
		NullPointerException exception = assertThrows(NullPointerException.class, () -> WhisperConfig.builder().build());
		assertTrue(exception.getMessage().contains("model"), exception.getMessage());
	}

	@Test
	public void toBuilderCopiesEverySetting()
	{
		WhisperConfig original = WhisperConfig.builder().model(MODEL_PATH).language("ja").threads(3).vadEnabled(true).build();
		WhisperConfig derived = original.toBuilder().samplingStrategy(SamplingStrategy.BEAM_SEARCH).build();
		assertEquals("ja", derived.language());
		assertEquals(3, derived.threads());
		assertTrue(derived.vadEnabled());
		assertEquals(SamplingStrategy.BEAM_SEARCH, derived.samplingStrategy());
		assertEquals(SamplingStrategy.GREEDY, original.samplingStrategy(), "元の設定は変わらない");
	}

	@Test
	public void builderKeepsDefaults()
	{
		WhisperConfig config = WhisperConfig.builder().model(MODEL_PATH).build();
		assertEquals("en", config.language());
		assertEquals(SamplingStrategy.GREEDY, config.samplingStrategy());
		assertFalse(config.vadEnabled());
		assertFalse(config.translateToEnglish());
		assertTrue(config.useGpu());
		assertEquals(0, config.threads());
	}

	@Test
	public void openWithMissingModelFails()
	{
		WhisperConfig config = WhisperConfig.builder()
				.model(Path.of("does-not-exist-model.bin"))
				.build();
		assertThrows(WhisperException.class, () -> WhisperEngine.open(config));
	}

	@Test
	public void readAudioSamplesWithMissingFileFails()
	{
		assertThrows(WhisperException.class, () -> AudioFileReader.readSamples(Path.of("no-such.wav")));
	}

	@Test
	public void readAudioSamplesReturnsSixteenKilohertzMono()
	{
		float[] samples = AudioFileReader.readSamples(SAMPLE_PATH);
		assertTrue(samples.length > 0);
		// jfk.wav は約 11 秒。16kHz モノラルなら 16000 * 11 前後になる
		assertTrue(samples.length > WhisperEngine.SAMPLE_RATE * 5,
				"サンプル数が少なすぎます: " + samples.length);
		for(float sample : samples)
		{
			assertTrue(sample >= -1.0f && sample <= 1.0f, "正規化されていません: " + sample);
		}
	}

	@Test
	public void transcribeFromSampleArray()
	{
		float[] samples = AudioFileReader.readSamples(SAMPLE_PATH);
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(samples);
			assertFalse(result.isEmpty(), "セグメントが空です");
			LOG.info("結果: {}", result.text());
			assertTrue(result.text().contains("fellow Americans"),
					"想定した語が含まれていません: " + result.text());
			assertTrue(result.elapsedMs() > 0L);
		}
	}

	@Test
	public void transcribeFromAudioFile()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertFalse(result.isEmpty());
			assertTrue(result.text().contains("fellow Americans"),
					"想定した語が含まれていません: " + result.text());
		}
	}

	@Test
	public void timestampsAreInMilliseconds()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			List<Segment> segments = result.segments();
			assertFalse(segments.isEmpty());

			Segment first = segments.get(0);
			Segment last = segments.get(segments.size() - 1);
			assertEquals(0L, first.startMs());
			// jfk.wav は約 10.5 秒 = 10500 ミリ秒。センチ秒のままなら 1050 になるので単位を検証できる
			assertTrue(last.endMs() > 8000L && last.endMs() < 13000L,
					"終了時刻がミリ秒になっていません: " + last.endMs());
			assertTrue(last.durationMs() > 0L);
			LOG.info("RTF = {}", result.realTimeFactor());
			assertTrue(result.realTimeFactor() > 0.0);
		}
	}

	@Test
	public void transcribeWithBeamSearch()
	{
		try(WhisperEngine engine = WhisperEngine
				.open(baseConfig().samplingStrategy(SamplingStrategy.BEAM_SEARCH).build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertTrue(result.text().contains("fellow Americans"), result.text());
		}
	}

	@Test
	public void transcribeWithVad()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().vadEnabled(true).vadThreshold(0.5f).build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			LOG.info("VAD 有効時のセグメント数: {}", result.segments().size());
			for(Segment segment : result.segments())
			{
				assertTrue(segment.endMs() >= segment.startMs());
			}
		}
	}

	@Test
	public void transcribeWithGrammar()
	{
		String grammar = "root ::= \" And so, my fellow Americans, ask not what your country can do for you,"
				+ " ask what you can do for your country.\"";
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().grammar(grammar).build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertFalse(result.isEmpty());
			LOG.info("文法適用後: {}", result.text());
		}
	}

	@Test
	public void systemInfoIsNotBlank()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			String info = engine.systemInfo();
			assertNotNull(info);
			assertFalse(info.isBlank());
			LOG.info("whisper.cpp: {}", info);
		}
	}

	@Test
	public void modelIsMultilingual()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			assertTrue(engine.isMultilingual());
		}
	}

	@Test
	public void useAfterCloseFails()
	{
		WhisperEngine engine = WhisperEngine.open(baseConfig().build());
		engine.close();
		// close は何度呼んでも安全
		engine.close();
		assertThrows(WhisperException.class, () -> engine.transcribe(new float[] { 0.0f, 0.1f }));
	}

	@Test
	public void emptySamplesReturnEmptyResult()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(new float[0]);
			assertTrue(result.isEmpty());
			assertEquals("", result.text());
		}
	}
}
