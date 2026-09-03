package jp.clip.whisperjni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// @Disabled
// NOTE: 文字起こし結果の期待値（文字列・タイムスタンプ）は whisper.cpp のバージョンに依存します。
// 現在の値は whisper.cpp v1.9.3 / ggml 0.20.2 での実測値です。
// v1.8.3 では BEAM_SEARCH の結果に "Americans" 後のカンマがありませんでした。
// whisper.cpp を更新した際にこれらが失敗する場合は、まず期待値のズレを疑ってください。
public class WhisperJNITest {
	
	private static Path testModelPath = Path.of("ggml-tiny.bin");
	private static Path samplePath = Path.of("src/main/native/whisper/samples/jfk.wav");
	private static WhisperJNI whisper;
	
	private static Logger logger = LoggerFactory.getLogger(WhisperJNITest.class);
	
	private static Path tempVAD;
	
	@BeforeAll
	public static void beforeAll() throws IOException
	{
		if(!Files.isRegularFile(testModelPath))
		{
			throw new IllegalStateException("Missing model file: " + testModelPath.toAbsolutePath());
		}
		if(!Files.isRegularFile(samplePath))
		{
			throw new IllegalStateException("Missing sample file: " + samplePath.toAbsolutePath());
		}
		
		// 同梱 VAD モデルの取り出しもここで検証する
		tempVAD = Files.createTempFile("tempVAD", ".bin");
		BundledResources.exportVadModel(logger, tempVAD);
		
		whisper = new WhisperJNI();
		
		// ビルドスクリプトの出力があればそこから読む（CI/CD やローカル開発）。無ければ jar 同梱を使う
		Path whisperJNIBuild = Path.of("whisperjni-build");
		if(Files.isDirectory(whisperJNIBuild))
		{
			logger.info("Loading from build dir");
			if(NativeLibraryLoader.loadVulkanRuntimeIfPresent())
			{
				logger.info("Found the Vulkan runtime");
			}
			NativeLibraryLoader.load(logger, whisperJNIBuild);
		}
		else
		{
			logger.info("Build dir not found, loading the bundled natives");
			WhisperJNI.loadBundledLibraries(logger);
		}
		
		WhisperJNI.setLogger(logger);
	}
	
	@Test
	public void testInit() throws IOException
	{
		WhisperContext ctx = whisper.createContext(testModelPath);
		assertNotNull(ctx);
		ctx.close();
	}

    @Test
	public void testInitFromInputStream() throws IOException
	{
		WhisperContext ctxState = whisper.createContext(Files.newInputStream(testModelPath));
		assertNotNull(ctxState);
		ctxState.close();
        WhisperContext ctxNoState = whisper.createContext(Files.newInputStream(testModelPath), null, false);
		assertNotNull(ctxNoState);
		ctxNoState.close();
	}
	
	@Test
	public void testInitNoState() throws IOException
	{
		WhisperContext ctx = whisper.createContextWithoutState(testModelPath);
		assertNotNull(ctx);
		ctx.close();
	}
	
	@Test
	public void testContextIsMultilingual() throws IOException
	{
		WhisperContext ctx = whisper.createContextWithoutState(testModelPath);
		assertNotNull(ctx);
		assertTrue(whisper.isMultilingual(ctx));
		ctx.close();
	}
	
	@Test
	public void testNewState() throws IOException
	{
		try(WhisperContext ctx = whisper.createContextWithoutState(testModelPath))
		{
			assertNotNull(ctx);
			WhisperState state = whisper.createState(ctx);
			assertNotNull(state);
			state.close();
		}
	}
	
	@Test
	public void testSegmentIndexException() throws IOException
	{
		try(WhisperContext ctx = whisper.createContext(testModelPath))
		{
			Exception exception = assertThrows(IndexOutOfBoundsException.class, () -> whisper.segmentText(ctx, 1));
			assertEquals("Index out of range", exception.getMessage());
			// 負の添字も範囲外として扱う
			assertThrows(IndexOutOfBoundsException.class, () -> whisper.segmentStartCentiseconds(ctx, -1));
			assertThrows(IndexOutOfBoundsException.class, () -> whisper.segmentTokens(ctx, 0));
		}
	}
	
	@Test
	public void testPointerUnavailableException() throws UnsupportedAudioFileException, IOException
	{
		WhisperContext ctx = whisper.createContext(testModelPath);
		float[] samples = readFileSamples(samplePath);
		WhisperTranscriptionParams params = new WhisperTranscriptionParams();
		ctx.close();
		assertTrue(ctx.isReleased());
		Exception exception = assertThrows(IllegalStateException.class, () -> whisper.transcribe(ctx, params, samples, samples.length));
		assertEquals("Unavailable pointer, object is closed", exception.getMessage());
		// close は何度呼んでも安全
		ctx.close();
	}
	
	@Test
	public void testNumSamplesLargerThanArrayIsRejected() throws Exception
	{
		try(WhisperContext ctx = whisper.createContext(testModelPath))
		{
			float[] samples = new float[16000];
			WhisperTranscriptionParams params = new WhisperTranscriptionParams();
			assertThrows(IllegalArgumentException.class, () -> whisper.transcribe(ctx, params, samples, samples.length + 1));
		}
	}
	
	@Test
	public void testInvalidGrammarIsRejected()
	{
		// grammar_parser::parse は失敗しても例外を投げず空の結果を返すので、ブリッジ側で検出して IOException にする
		assertThrows(IOException.class, () -> whisper.parseGrammar("this is not ::= a valid ((( grammar"));
		assertThrows(IOException.class, () -> whisper.parseGrammar("   "));
		assertThrows(IOException.class, () -> whisper.parseGrammar("greeting ::= \"hi\""));
	}
	
	@Test
	public void testTokens() throws Exception
	{
		float[] samples = readFileSamples(samplePath);
		try(WhisperContext ctx = whisper.createContext(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.BEAM_SEARCH);
			params.printTimestamps = false;
			int result = whisper.transcribe(ctx, params, samples, samples.length);
			if(result != 0)
			{
				throw new RuntimeException("Transcription failed with code " + result);
			}
			int numSegments = whisper.segmentCount(ctx);
			assertEquals(1, numSegments);
			String text = whisper.segmentText(ctx, 0);
			assertEquals(" And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.", text);
			
			// Grab tokens from each segment
			for(int i = 0; i < numSegments; i++)
			{
				WhisperToken[] tokens = whisper.segmentTokens(ctx, i);
				
				for(WhisperToken token : tokens)
				{
					logger.info("TOKEN: '{}'", token.text);
				}

				assertTrue(tokens.length <= 26);
			}
		}
	}
	
	@Test
	public void testTokensWithState() throws Exception
	{
		float[] samples = readFileSamples(samplePath);
		try(WhisperContext ctx = whisper.createContextWithoutState(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
			params.noTimestamps = true;
			params.printProgress = false;
			params.printRealtime = false;
			params.printSpecial = false;
			try(WhisperState state = whisper.createState(ctx))
			{
				assertNotNull(state);
				int result = whisper.transcribeWithState(ctx, state, params, samples, samples.length);
				if(result != 0)
				{
					throw new RuntimeException("Transcription failed with code " + result);
				}
				int numSegments = whisper.segmentCountFromState(state);
				assertEquals(1, numSegments);
				
				// Grab tokens from each segment
				for(int i = 0; i < numSegments; i++)
				{
					WhisperToken[] tokens = whisper.segmentTokensFromState(ctx, state, i);
					
					for(WhisperToken token : tokens)
					{
						logger.info("TOKEN: '{}'", token.text);
					}
					
					assertTrue(tokens.length >= 23);
				}
			}
		}
	}
	
	@Test
	public void testVADFull() throws Exception
	{
		try(WhisperContext ctx = whisper.createContext(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
			params.vadEnabled = true;
			params.vadModelPath = tempVAD.toAbsolutePath().toString();
			params.vadParams.threshold = 0.995f;
			
			float[] samples = readFileSamples(samplePath);
			int result = whisper.transcribe(ctx, params, samples, samples.length);
			
			if(result != 0)
			{
				throw new RuntimeException("Transcription failed with code " + result);
			}
			
			final int segments = whisper.segmentCount(ctx);
			
			logger.info("{} total segments after VAD filtering", segments);
			
			for(int i = 0; i < segments; i++)
			{
				String text = whisper.segmentText(ctx, i);
				logger.info("VAD #{}: {}", i + 1, text);
				// It should be pretty short (America)
				assertTrue(text.length() < 256);
			}
		}
	}
	
	@Test
	public void testFull() throws Exception
	{
		float[] samples = readFileSamples(samplePath);
		try(WhisperContext ctx = whisper.createContext(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
			int result = whisper.transcribe(ctx, params, samples, samples.length);
			if(result != 0)
			{
				throw new RuntimeException("Transcription failed with code " + result);
			}
			int numSegments = whisper.segmentCount(ctx);
			assertEquals(1, numSegments);
			long startTime = whisper.segmentStartCentiseconds(ctx, 0);
			long endTime = whisper.segmentEndCentiseconds(ctx, 0);
			String text = whisper.segmentText(ctx, 0);
			assertEquals(0, startTime);
			assertEquals(1050, endTime);
			assertEquals(" And so my fellow Americans ask not what your country can do for you, ask what you can do for your country.", text);
		}
	}
	
	@Test
	public void testFullBeamSearch() throws Exception
	{
		float[] samples = readFileSamples(samplePath);
		try(WhisperContext ctx = whisper.createContext(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.BEAM_SEARCH);
			params.printTimestamps = false;
			int result = whisper.transcribe(ctx, params, samples, samples.length);
			if(result != 0)
			{
				throw new RuntimeException("Transcription failed with code " + result);
			}
			int numSegments = whisper.segmentCount(ctx);
			assertEquals(1, numSegments);
			String text = whisper.segmentText(ctx, 0);
			assertEquals(" And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.", text);
		}
	}
	
	@Test
	public void testFullWithState() throws Exception
	{
		float[] samples = readFileSamples(samplePath);
		try(WhisperContext ctx = whisper.createContextWithoutState(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
			try(WhisperState state = whisper.createState(ctx))
			{
				assertNotNull(state);
				int result = whisper.transcribeWithState(ctx, state, params, samples, samples.length);
				if(result != 0)
				{
					throw new RuntimeException("Transcription failed with code " + result);
				}
				int numSegments = whisper.segmentCountFromState(state);
				assertEquals(1, numSegments);
				long startTime = whisper.segmentStartCentisecondsFromState(state, 0);
				long endTime = whisper.segmentEndCentisecondsFromState(state, 0);
				String text = whisper.segmentTextFromState(state, 0);
				assertEquals(0, startTime);
				assertEquals(1050, endTime);
				assertEquals(" And so my fellow Americans ask not what your country can do for you, ask what you can do for your country.", text);
			}
		}
	}
	
	@Test
	public void testFullWithStateBeamSearch() throws Exception
	{
		float[] samples = readFileSamples(samplePath);
		try(WhisperContext ctx = whisper.createContextWithoutState(testModelPath))
		{
			assertNotNull(ctx);
			WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.BEAM_SEARCH);
			params.printTimestamps = false;
			try(WhisperState state = whisper.createState(ctx))
			{
				assertNotNull(state);
				int result = whisper.transcribeWithState(ctx, state, params, samples, samples.length);
				if(result != 0)
				{
					throw new RuntimeException("Transcription failed with code " + result);
				}
				int numSegments = whisper.segmentCountFromState(state);
				assertEquals(1, numSegments);
				String text = whisper.segmentTextFromState(state, 0);
				assertEquals(" And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.", text);
			}
		}
	}
	
	@Test
	public void testFullWithGrammar() throws Exception
	{
		// Init trailing space is important
		String grammarText = "root ::= \" And so, my fellow American, ask not what your country can do for you, ask what you can do for your country.\"";
		float[] samples = readFileSamples(samplePath);
		try(WhisperGrammar grammar = whisper.parseGrammar(grammarText))
		{
			assertNotNull(grammar);
			try(WhisperContext ctx = whisper.createContext(testModelPath))
			{
				assertNotNull(ctx);
				WhisperTranscriptionParams params = new WhisperTranscriptionParams(WhisperSamplingStrategy.GREEDY);
				params.grammar = grammar;
				int result = whisper.transcribe(ctx, params, samples, samples.length);
				if(result != 0)
				{
					throw new RuntimeException("Transcription failed with code " + result);
				}
				int numSegments = whisper.segmentCount(ctx);
				assertEquals(1, numSegments);
				String text = whisper.segmentText(ctx, 0);
				assertEquals(" And so, my fellow American, ask not what your country can do for you, ask what you can do for your country.", text);
			}
		}
	}
	
	@Test
	public void printSystemInfo() throws Exception
	{
		String whisperCPPSystemInfo = whisper.getSystemInfo();
		assertFalse(whisperCPPSystemInfo.isBlank());
		logger.info("whisper.cpp library info: {}", whisperCPPSystemInfo);
	}
	
	@Test
	public void initOpenVINO() throws Exception
	{
		try(WhisperContext ctx = whisper.createContextWithoutState(testModelPath))
		{
			assertNotNull(ctx);
			whisper.initOpenVinoEncoder(ctx, "CPU");
		}
	}
	
	/**
	 * jfk.wav（16kHz / 16bit / モノラル / リトルエンディアン）を -1.0〜1.0 の float 列として読む。
	 * ブリッジ層のテストなので、上位層の AudioFileReader には依存させない。
	 */
	private static float[] readFileSamples(Path samplePath) throws UnsupportedAudioFileException, IOException
	{
		try(InputStream file = new BufferedInputStream(Files.newInputStream(samplePath));
				AudioInputStream audio = AudioSystem.getAudioInputStream(file))
		{
			ShortBuffer pcm = ByteBuffer.wrap(audio.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
			float[] samples = new float[pcm.remaining()];
			IntStream.range(0, samples.length)
					.forEach(i -> samples[i] = Math.max(-1.0f, Math.min((float) pcm.get(i) / (float) Short.MAX_VALUE, 1.0f)));
			return samples;
		}
	}
	
}