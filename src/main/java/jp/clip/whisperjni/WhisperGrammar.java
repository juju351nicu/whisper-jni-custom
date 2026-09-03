package jp.clip.whisperjni;

/**
 * whisper.cpp 側で解析済みの GBNF 文法（{@code grammar_parser::parse_state}）を指すハンドル。
 *
 * <p>
 * {@link WhisperJNI#parseGrammar(String)} が返します。{@link WhisperTranscriptionParams#grammar} に
 * 設定して文字起こしの出力を文法に沿った形へ制約します。ネイティブメモリを保持しているため、
 * 使い終わったら必ず {@link #close()} を呼んでください（try-with-resources 推奨）。
 * </p>
 *
 * <p>
 * 文法テキストの妥当性を事前に確認したい場合は {@link GbnfGrammarValidator} を使ってください。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public final class WhisperGrammar extends NativeHandle
{
	/**
	 * 内部用コンストラクタ。{@link WhisperJNI#parseGrammar(String)} から生成されます。
	 *
	 * @param whisper 生成元のライブラリインスタンス
	 * @param nativeId ネイティブ側が採番した ID
	 */
	WhisperGrammar(WhisperJNI whisper, int nativeId)
	{
		super(whisper, nativeId);
	}

	@Override
	protected void releaseNative()
	{
		this.whisper.freeGrammar(this.nativeId);
	}
}
