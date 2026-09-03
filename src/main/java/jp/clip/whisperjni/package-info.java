/**
 * whisper.cpp を JNI 経由で呼び出す低レイヤのブリッジ。whisper.cpp の関数・構造体とほぼ 1 対 1 に対応します。
 *
 * <p>
 * 通常は高水準 API の {@link jp.clip.whisper.WhisperEngine} を使ってください。このパッケージは、
 * ネイティブハンドル（{@link jp.clip.whisperjni.WhisperContext} など）の生存管理を呼び出し側に委ねます。
 * ハンドルはすべて {@link java.lang.AutoCloseable} なので try-with-resources で確実に閉じてください。
 * </p>
 *
 * <table class="striped">
 * <caption>主なクラスと whisper.cpp 側の対応</caption>
 * <tr><th>Java</th><th>whisper.cpp</th></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperJNI}</td><td>関数群（各メソッドの Javadoc に関数名を併記）</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperContext}</td><td>{@code whisper_context}</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperState}</td><td>{@code whisper_state}</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperGrammar}</td><td>{@code grammar_parser::parse_state}</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperContextParams}</td><td>{@code whisper_context_params}</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperTranscriptionParams}</td><td>{@code whisper_full_params}</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperSamplingStrategy}</td><td>{@code whisper_sampling_strategy}</td></tr>
 * <tr><td>{@link jp.clip.whisperjni.WhisperToken}</td><td>{@code whisper_token_data}</td></tr>
 * </table>
 *
 * <p>
 * 時刻は whisper.cpp と同じ<b>センチ秒（10 ミリ秒）</b>単位です。ミリ秒への変換は高水準 API が行います。
 * </p>
 *
 * <p>
 * ネイティブライブラリの読み込みは {@link jp.clip.whisperjni.WhisperJNI#loadBundledLibraries()}（jar 同梱）
 * または {@link jp.clip.whisperjni.NativeLibraryLoader}（任意のディレクトリ）で行います。
 * Java と C++ の結びつき（シンボル名・クラス名・フィールド名）を変更する手順は {@code CLAUDE.md} を参照してください。
 * </p>
 */
package jp.clip.whisperjni;
