/**
 * whisper.cpp による文字起こしの高水準 API。<b>利用側は原則このパッケージだけを使います。</b>
 *
 * <p>
 * 入口は {@link jp.clip.whisper.WhisperEngine} です。設定は {@link jp.clip.whisper.WhisperConfig} の
 * ビルダーで組み立て、結果は {@link jp.clip.whisper.TranscriptionResult}（{@link jp.clip.whisper.Segment} の列）
 * で受け取ります。時刻はすべて<b>ミリ秒</b>です。
 * </p>
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
 * 		.language("ja")
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
 * ネイティブライブラリの読み込み、ネイティブメモリの解放、パラメータの変換はこの層が受け持ちます。
 * whisper.cpp の関数を直接呼びたい場合（state の分離、トークン単位の情報など）は
 * 低レイヤの {@link jp.clip.whisperjni} を使ってください。この層から低レイヤへの依存は一方向で、
 * 逆方向の依存はありません。
 * </p>
 */
package jp.clip.whisper;
