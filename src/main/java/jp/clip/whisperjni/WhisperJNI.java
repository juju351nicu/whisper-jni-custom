package jp.clip.whisperjni;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * whisper.cpp を JNI 経由で呼び出す低レイヤのブリッジ。
 *
 * <p>
 * 各メソッドは whisper.cpp の関数にほぼ 1 対 1 で対応します。対応する関数名は各 Javadoc に
 * 併記してあります（whisper.cpp を更新するときの影響調査に使ってください）。
 * </p>
 *
 * <p>
 * <b>通常は {@code jp.clip.whisper.WhisperEngine} を使ってください。</b>このクラスはネイティブ
 * ハンドルの生存管理を呼び出し側に委ねています。
 * </p>
 *
 * <p>
 * ネイティブメソッドを呼ぶ前に必ず {@link #loadBundledLibraries()} または
 * {@link NativeLibraryLoader#load(Logger, Path)} でネイティブライブラリを読み込んでください。
 * </p>
 *
 * <p>
 * native 宣言を増減・変更したときは {@code gradlew generateHeaders} でヘッダを再生成し、
 * {@code src/main/native/jni/jp_clip_whisperjni_WhisperJNI.cpp} を合わせてください
 * （手順は {@code CLAUDE.md}）。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public class WhisperJNI
{
	// ------------------------------------------------------------------------
	// native 宣言（実装は src/main/native/jni/jp_clip_whisperjni_WhisperJNI.cpp）
	// 戻り値の int ハンドルは、失敗時に -1。
	// ------------------------------------------------------------------------

	private native int init(String modelPath, WhisperContextParams params);

	private native int initFromInputStream(InputStream inputStream, WhisperContextParams params, boolean initState);

	private native int initNoState(String modelPath, WhisperContextParams params);

	private native int initState(int contextRef);

	private native int loadGrammar(String grammarText);

	private native void initOpenVINOEncoder(int contextRef, String device);

	private native boolean isMultilingual(int contextRef);

	private native int full(int contextRef, WhisperTranscriptionParams params, float[] samples, int numSamples);

	private native int fullWithState(int contextRef, int stateRef, WhisperTranscriptionParams params, float[] samples, int numSamples);

	private native int fullNSegments(int contextRef);

	private native int fullNSegmentsFromState(int stateRef);

	private native long fullGetSegmentTimestamp0(int contextRef, int segment);

	private native long fullGetSegmentTimestamp1(int contextRef, int segment);

	private native String fullGetSegmentText(int contextRef, int segment);

	private native long fullGetSegmentTimestamp0FromState(int stateRef, int segment);

	private native long fullGetSegmentTimestamp1FromState(int stateRef, int segment);

	private native String fullGetSegmentTextFromState(int stateRef, int segment);

	private native WhisperToken[] getSegmentTokens(int contextRef, int segment);

	private native WhisperToken[] getSegmentTokensFromState(int contextRef, int stateRef, int segment);

	private native String printSystemInfo();

	// 解放系は NativeHandle のサブクラスから呼ぶためパッケージプライベート
	native void freeContext(int contextRef);

	native void freeState(int stateRef);

	native void freeGrammar(int grammarRef);

	/**
	 * whisper.cpp のログ出力先を設定します。対応する whisper.cpp 関数は {@code whisper_log_set}。
	 *
	 * <p>
	 * whisper.cpp / ggml のログレベルは SLF4J の error / warn / info / debug にそのまま対応させます。
	 * このメソッドを呼ぶ前にネイティブライブラリを読み込んでおく必要があります。
	 * </p>
	 *
	 * @param logger SLF4J の {@link Logger}
	 */
	public static native void setLogger(Logger logger);

	/**
	 * ブリッジのインスタンスを生成します。状態は持たないので、1 つを使い回して構いません。
	 */
	public WhisperJNI()
	{
		// native メソッドの呼び出し口として使うだけ
	}

	// ------------------------------------------------------------------------
	// ライブラリの読み込み
	// ------------------------------------------------------------------------

	/**
	 * jar に同梱されたネイティブライブラリを読み込みます。
	 *
	 * <p>
	 * 同梱ネイティブは jar 内の {@code <os>-<arch>}（例 {@code windows-x64}）に置かれており、
	 * 一時ディレクトリへ取り出してから読み込みます。Vulkan / CUDA 版など自前のネイティブを使いたい
	 * 場合は {@link NativeLibraryLoader#load(Logger, Path)} を直接使ってください。
	 * </p>
	 *
	 * @param logger 読み込みの経過を記録する SLF4J {@link Logger}
	 * @throws IOException 同梱ネイティブの取り出し・読み込みに失敗した場合
	 */
	public static void loadBundledLibraries(Logger logger) throws IOException
	{
		Path extracted = BundledResources.extractDirectory(logger, Platform.current().nativeLibraryDirectoryName());
		NativeLibraryLoader.load(logger, extracted);
	}

	/**
	 * jar に同梱されたネイティブライブラリを既定のロガーで読み込みます。
	 *
	 * @throws IOException 同梱ネイティブの取り出し・読み込みに失敗した場合
	 */
	public static void loadBundledLibraries() throws IOException
	{
		loadBundledLibraries(LoggerFactory.getLogger(WhisperJNI.class));
	}

	// ------------------------------------------------------------------------
	// コンテキストと状態の生成
	// ------------------------------------------------------------------------

	/**
	 * whisper コンテキストを生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_from_file_with_params}。
	 *
	 * @param model ggml モデルファイルの {@link Path}
	 * @return 生成された {@link WhisperContext}
	 * @throws IOException モデルが存在しない、または読み込みに失敗した場合
	 */
	public WhisperContext createContext(Path model) throws IOException
	{
		return createContext(model, null);
	}

	/**
	 * whisper コンテキストを生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_from_file_with_params}。
	 *
	 * @param model  ggml モデルファイルの {@link Path}
	 * @param params 初期化パラメータ。null なら既定値を使う
	 * @return 生成された {@link WhisperContext}
	 * @throws IOException モデルが存在しない、または読み込みに失敗した場合
	 */
	public WhisperContext createContext(Path model, WhisperContextParams params) throws IOException
	{
		assertModelExists(model);
		int nativeId = init(model.toAbsolutePath().toString(), orDefault(params));
		assertInitialized(nativeId, "モデルの読み込みに失敗しました: " + model.toAbsolutePath());
		return new WhisperContext(this, nativeId);
	}

	/**
	 * {@link InputStream} から whisper コンテキストを生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_with_params}。
	 *
	 * @param inputStream ggml モデルを読み出せる {@link InputStream}。読み終わっても close はしません
	 * @return 生成された {@link WhisperContext}
	 * @throws IOException モデルの読み込みに失敗した場合
	 */
	public WhisperContext createContext(InputStream inputStream) throws IOException
	{
		return createContext(inputStream, null, true);
	}

	/**
	 * {@link InputStream} から whisper コンテキストを生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_with_params}（{@code initState} が false のときは
	 * {@code whisper_init_with_params_no_state}）。
	 *
	 * <p>
	 * ストリームの内容はいったんネイティブ側のメモリへ全部読み込まれます。大きなモデルでは
	 * モデルサイズ分のメモリを一時的に余分に消費するので、ファイルがあるなら
	 * {@link #createContext(Path, WhisperContextParams)} の方を使ってください。
	 * </p>
	 *
	 * @param inputStream ggml モデルを読み出せる {@link InputStream}。読み終わっても close はしません
	 * @param params      初期化パラメータ。null なら既定値を使う
	 * @param initState   state も同時に初期化するかどうか
	 * @return 生成された {@link WhisperContext}
	 * @throws IOException モデルの読み込みに失敗した場合
	 */
	public WhisperContext createContext(InputStream inputStream, WhisperContextParams params, boolean initState) throws IOException
	{
		int nativeId = initFromInputStream(inputStream, orDefault(params), initState);
		assertInitialized(nativeId, "InputStream からのモデル読み込みに失敗しました。");
		return new WhisperContext(this, nativeId);
	}

	/**
	 * state を持たない whisper コンテキストを生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_from_file_with_params_no_state}。
	 *
	 * @param model ggml モデルファイルの {@link Path}
	 * @return 生成された {@link WhisperContext}
	 * @throws IOException モデルが存在しない、または読み込みに失敗した場合
	 */
	public WhisperContext createContextWithoutState(Path model) throws IOException
	{
		return createContextWithoutState(model, null);
	}

	/**
	 * state を持たない whisper コンテキストを生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_from_file_with_params_no_state}。
	 *
	 * @param model  ggml モデルファイルの {@link Path}
	 * @param params 初期化パラメータ。null なら既定値を使う
	 * @return 生成された {@link WhisperContext}
	 * @throws IOException モデルが存在しない、または読み込みに失敗した場合
	 */
	public WhisperContext createContextWithoutState(Path model, WhisperContextParams params) throws IOException
	{
		assertModelExists(model);
		int nativeId = initNoState(model.toAbsolutePath().toString(), orDefault(params));
		assertInitialized(nativeId, "モデルの読み込みに失敗しました: " + model.toAbsolutePath());
		return new WhisperContext(this, nativeId);
	}

	/**
	 * 指定コンテキスト用の state を生成します。対応する whisper.cpp 関数は
	 * {@code whisper_init_state}。
	 *
	 * <p>
	 * state を分けることで 1 つのコンテキスト（= 1 つのモデル）を複数スレッドで共有できます。
	 * </p>
	 *
	 * @param context この state が属する {@link WhisperContext}
	 * @return 生成された {@link WhisperState}
	 * @throws IllegalStateException state の生成に失敗した場合、またはコンテキストが解放済みの場合
	 */
	public WhisperState createState(WhisperContext context)
	{
		context.assertAvailable();
		int nativeId = initState(context.nativeId);
		if(nativeId == -1)
		{
			throw new IllegalStateException("whisper state の生成に失敗しました。");
		}
		return new WhisperState(this, nativeId);
	}

	/**
	 * GBNF 文法ファイルを読み込んで whisper.cpp 側で解析します。
	 *
	 * <p>
	 * 解析前に文法の妥当性を Java 側で確認したい場合は
	 * {@link GbnfGrammarValidator#assertValid(Path)} を使ってください。
	 * </p>
	 *
	 * @param grammarPath GBNF 文法ファイルの {@link Path}
	 * @return 生成された {@link WhisperGrammar}
	 * @throws IOException ファイルが存在しない、または文法の解析に失敗した場合
	 */
	public WhisperGrammar parseGrammar(Path grammarPath) throws IOException
	{
		if(!Files.isRegularFile(grammarPath))
		{
			throw new FileNotFoundException("Grammar file not found: " + grammarPath);
		}
		return parseGrammar(Files.readString(grammarPath));
	}

	/**
	 * GBNF 文法テキストを whisper.cpp 側で解析します。対応する whisper.cpp 関数は
	 * {@code grammar_parser::parse}（examples/grammar-parser.cpp）。
	 *
	 * @param grammarText GBNF 文法のテキスト
	 * @return 生成された {@link WhisperGrammar}
	 * @throws IOException 文法が空、または解析に失敗した場合
	 */
	public WhisperGrammar parseGrammar(String grammarText) throws IOException
	{
		if(grammarText == null || grammarText.isBlank())
		{
			throw new IOException("Grammar text is blank");
		}
		int nativeId = loadGrammar(grammarText);
		assertInitialized(nativeId, "文法の解析に失敗しました。");
		return new WhisperGrammar(this, nativeId);
	}

	// ------------------------------------------------------------------------
	// コンテキストの照会
	// ------------------------------------------------------------------------

	/**
	 * OpenVINO エンコーダを初期化します。対応する whisper.cpp 関数は
	 * {@code whisper_ctx_init_openvino_encoder}。
	 *
	 * <p>
	 * OpenVINO を有効にしてビルドしていない場合、この呼び出しは何もしません。
	 * </p>
	 *
	 * @param context {@link WhisperContext}
	 * @param device  デバイス名（例 {@code "CPU"}）
	 */
	public void initOpenVinoEncoder(WhisperContext context, String device)
	{
		context.assertAvailable();
		initOpenVINOEncoder(context.nativeId, device);
	}

	/**
	 * 読み込んだモデルが多言語対応かどうかを返します。対応する whisper.cpp 関数は
	 * {@code whisper_is_multilingual}。
	 *
	 * @param context 判定対象の {@link WhisperContext}
	 * @return 多言語モデルなら true
	 */
	public boolean isMultilingual(WhisperContext context)
	{
		context.assertAvailable();
		return isMultilingual(context.nativeId);
	}

	/**
	 * whisper.cpp のシステム情報を返します。対応する whisper.cpp 関数は
	 * {@code whisper_print_system_info}。
	 *
	 * @return 有効な CPU 命令セットやバックエンドを示す文字列
	 */
	public String getSystemInfo()
	{
		return printSystemInfo();
	}

	// ------------------------------------------------------------------------
	// 文字起こしの実行
	// ------------------------------------------------------------------------

	/**
	 * 文字起こしを実行します。対応する whisper.cpp 関数は {@code whisper_full}。
	 *
	 * @param context    使用する {@link WhisperContext}
	 * @param params     実行パラメータ
	 * @param samples    16kHz モノラルの f32 サンプル列（-1.0〜1.0）
	 * @param numSamples 与えるサンプル数（通常は {@code samples.length}）
	 * @return 結果コード。0 以外は失敗
	 */
	public int transcribe(WhisperContext context, WhisperTranscriptionParams params, float[] samples, int numSamples)
	{
		context.assertAvailable();
		assertGrammarAvailable(params);
		return full(context.nativeId, params, samples, numSamples);
	}

	/**
	 * state を指定して文字起こしを実行します。対応する whisper.cpp 関数は
	 * {@code whisper_full_with_state}。
	 *
	 * @param context    使用する {@link WhisperContext}
	 * @param state      使用する {@link WhisperState}
	 * @param params     実行パラメータ
	 * @param samples    16kHz モノラルの f32 サンプル列（-1.0〜1.0）
	 * @param numSamples 与えるサンプル数（通常は {@code samples.length}）
	 * @return 結果コード。0 以外は失敗
	 */
	public int transcribeWithState(WhisperContext context, WhisperState state, WhisperTranscriptionParams params, float[] samples, int numSamples)
	{
		context.assertAvailable();
		state.assertAvailable();
		assertGrammarAvailable(params);
		return fullWithState(context.nativeId, state.nativeId, params, samples, numSamples);
	}

	// ------------------------------------------------------------------------
	// 結果の取得（context 版）
	// ------------------------------------------------------------------------

	/**
	 * 得られたセグメント数を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_n_segments}。
	 *
	 * @param context 文字起こしに使った {@link WhisperContext}
	 * @return セグメント数
	 */
	public int segmentCount(WhisperContext context)
	{
		context.assertAvailable();
		return fullNSegments(context.nativeId);
	}

	/**
	 * セグメントの開始時刻を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_get_segment_t0}。
	 *
	 * <p>
	 * <b>単位はセンチ秒（10 ミリ秒）です。</b>例えば 1050 は 10.5 秒を意味します。
	 * ミリ秒が欲しい場合は 10 倍してください（{@code jp.clip.whisper.Segment} は変換済みです）。
	 * </p>
	 *
	 * @param context 文字起こしに使った {@link WhisperContext}
	 * @param segment セグメントの添字
	 * @return 開始時刻（センチ秒）
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public long segmentStartCentiseconds(WhisperContext context, int segment)
	{
		context.assertAvailable();
		return fullGetSegmentTimestamp0(context.nativeId, segment);
	}

	/**
	 * セグメントの終了時刻を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_get_segment_t1}。単位はセンチ秒（10 ミリ秒）。
	 *
	 * @param context 文字起こしに使った {@link WhisperContext}
	 * @param segment セグメントの添字
	 * @return 終了時刻（センチ秒）
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public long segmentEndCentiseconds(WhisperContext context, int segment)
	{
		context.assertAvailable();
		return fullGetSegmentTimestamp1(context.nativeId, segment);
	}

	/**
	 * セグメントの文字列を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_get_segment_text}。
	 *
	 * @param context 文字起こしに使った {@link WhisperContext}
	 * @param segment セグメントの添字
	 * @return セグメントの文字列。whisper.cpp の仕様上、先頭に半角スペースが入ることがある
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public String segmentText(WhisperContext context, int segment)
	{
		context.assertAvailable();
		return fullGetSegmentText(context.nativeId, segment);
	}

	/**
	 * 指定セグメントのトークン列を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_n_tokens}、{@code whisper_full_get_token_data}、
	 * {@code whisper_full_get_token_text}。
	 *
	 * <p>
	 * whisper.cpp はタイムスタンプやセグメント境界を表す特殊トークン
	 * （{@code [_...]} や {@code <|...|>}）も返しますが、それらは除外します。
	 * </p>
	 *
	 * @param context 文字起こしに使った {@link WhisperContext}
	 * @param segment セグメントの添字
	 * @return このセグメントのトークン列
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public WhisperToken[] segmentTokens(WhisperContext context, int segment)
	{
		context.assertAvailable();
		return withoutSpecialTokens(getSegmentTokens(context.nativeId, segment));
	}

	// ------------------------------------------------------------------------
	// 結果の取得（state 版）
	// ------------------------------------------------------------------------

	/**
	 * 得られたセグメント数を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_n_segments_from_state}。
	 *
	 * @param state 文字起こしに使った {@link WhisperState}
	 * @return セグメント数
	 */
	public int segmentCountFromState(WhisperState state)
	{
		state.assertAvailable();
		return fullNSegmentsFromState(state.nativeId);
	}

	/**
	 * セグメントの開始時刻を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_get_segment_t0_from_state}。単位はセンチ秒（10 ミリ秒）。
	 *
	 * @param state   文字起こしに使った {@link WhisperState}
	 * @param segment セグメントの添字
	 * @return 開始時刻（センチ秒）
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public long segmentStartCentisecondsFromState(WhisperState state, int segment)
	{
		state.assertAvailable();
		return fullGetSegmentTimestamp0FromState(state.nativeId, segment);
	}

	/**
	 * セグメントの終了時刻を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_get_segment_t1_from_state}。単位はセンチ秒（10 ミリ秒）。
	 *
	 * @param state   文字起こしに使った {@link WhisperState}
	 * @param segment セグメントの添字
	 * @return 終了時刻（センチ秒）
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public long segmentEndCentisecondsFromState(WhisperState state, int segment)
	{
		state.assertAvailable();
		return fullGetSegmentTimestamp1FromState(state.nativeId, segment);
	}

	/**
	 * セグメントの文字列を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_get_segment_text_from_state}。
	 *
	 * @param state   文字起こしに使った {@link WhisperState}
	 * @param segment セグメントの添字
	 * @return セグメントの文字列
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public String segmentTextFromState(WhisperState state, int segment)
	{
		state.assertAvailable();
		return fullGetSegmentTextFromState(state.nativeId, segment);
	}

	/**
	 * 指定セグメントのトークン列を返します。対応する whisper.cpp 関数は
	 * {@code whisper_full_n_tokens_from_state}、{@code whisper_full_get_token_data_from_state}、
	 * {@code whisper_full_get_token_text_from_state}。
	 *
	 * <p>
	 * 特殊トークンは {@link #segmentTokens(WhisperContext, int)} と同様に除外します。
	 * </p>
	 *
	 * @param context 文字起こしに使った {@link WhisperContext}（トークン文字列の取得に必要）
	 * @param state   文字起こしに使った {@link WhisperState}
	 * @param segment セグメントの添字
	 * @return このセグメントのトークン列
	 * @throws IndexOutOfBoundsException 添字が範囲外の場合
	 */
	public WhisperToken[] segmentTokensFromState(WhisperContext context, WhisperState state, int segment)
	{
		context.assertAvailable();
		state.assertAvailable();
		return withoutSpecialTokens(getSegmentTokensFromState(context.nativeId, state.nativeId, segment));
	}

	// ------------------------------------------------------------------------
	// 内部ユーティリティ
	// ------------------------------------------------------------------------

	/**
	 * whisper.cpp が返す特殊トークンを除外します。
	 *
	 * <p>
	 * トークン ID で判定するのが本来は正しいのですが、whisper.cpp 自身も同様の文字列判定
	 * （{@code text.rfind("[_", 0) == 0}）を行っているため、それに倣っています。
	 * </p>
	 */
	private static WhisperToken[] withoutSpecialTokens(WhisperToken[] tokens)
	{
		return Stream.of(tokens)
				.filter(token -> !token.isSpecial())
				.toArray(WhisperToken[]::new);
	}

	private static void assertGrammarAvailable(WhisperTranscriptionParams params)
	{
		if(params.grammar != null)
		{
			params.grammar.assertAvailable();
		}
	}

	private static WhisperContextParams orDefault(WhisperContextParams params)
	{
		return params == null ? new WhisperContextParams() : params;
	}

	private static void assertModelExists(Path model) throws IOException
	{
		if(!Files.isRegularFile(model))
		{
			throw new IOException("Missing model file: " + model);
		}
	}

	private static void assertInitialized(int nativeId, String message) throws IOException
	{
		if(nativeId == -1)
		{
			throw new IOException(message);
		}
	}
}
