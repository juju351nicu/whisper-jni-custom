package jp.clip.whisperjni;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

import org.junit.jupiter.api.Test;

/**
 * {@link GbnfGrammarValidator} の単体テスト。
 *
 * <p>
 * 純 Java 実装なのでネイティブライブラリもモデルも不要です。そのため他のテストと違い
 * 単体で高速に動きます。
 * </p>
 */
public class GbnfGrammarValidatorTest
{
	private static final Path GRAMMAR_DIRECTORY = Path.of("src/main/native/whisper/grammars");
	
	@Test
	public void whisperCppSampleGrammarsAreValid() throws ParseException, IOException
	{
		// whisper.cpp 同梱のサンプル文法。これらは必ず有効でなければならない
		GbnfGrammarValidator.assertValid(GRAMMAR_DIRECTORY.resolve("assistant.gbnf"));
		GbnfGrammarValidator.assertValid(GRAMMAR_DIRECTORY.resolve("colors.gbnf"));
		GbnfGrammarValidator.assertValid(GRAMMAR_DIRECTORY.resolve("chess.gbnf"));
	}
	
	@Test
	public void chessGrammarUsesNestedGroupsOnOneToken() throws ParseException, IOException
	{
		// chess.gbnf の move 規則は "((piece | pawn | king)" のように 1 トークンで
		// 2 つのグループを開く。ここが壊れると "Unclosed group" で誤検出されるため、
		// リグレッションテストとして明示的に残す。
		String grammarText = Files.readString(GRAMMAR_DIRECTORY.resolve("chess.gbnf"));
		assertTrue(grammarText.contains("((piece"), "chess.gbnf の内容が変わっています");
		GbnfGrammarValidator.assertValid(grammarText);
	}
	
	@Test
	public void simpleGrammarIsValid() throws ParseException
	{
		GbnfGrammarValidator.assertValid("root ::= \" hello world.\"");
	}
	
	@Test
	public void emptyGrammarIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("   "));
	}
	
	@Test
	public void missingRootExpressionIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("greeting ::= \" hi.\""));
	}
	
	@Test
	public void unresolvableSubExpressionIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("root ::= missing"));
	}
	
	@Test
	public void cyclicExpressionIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("root ::= loop\nloop ::= root"));
	}
	
	@Test
	public void duplicatedExpressionIsRejected()
	{
		assertThrows(ParseException.class,
				() -> GbnfGrammarValidator.assertValid("root ::= \" a.\"\nroot ::= \" b.\""));
	}
	
	@Test
	public void unclosedGroupIsRejected()
	{
		// 以前は末尾判定が onGroup > 1 だったため、グループ 1 つの未閉鎖を見逃していた
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("root ::= ( \"hello\"."));
	}
	
	@Test
	public void unclosedStringIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("root ::= \" hello"));
	}
	
	@Test
	public void rootNotEndingWithDotIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid("root ::= \" hello world\""));
	}
	
	@Test
	public void missingGrammarFileIsRejected()
	{
		assertThrows(ParseException.class, () -> GbnfGrammarValidator.assertValid(Path.of("no-such-grammar.gbnf")));
	}
	
	@Test
	public void commentsAndBlankLinesAreIgnored() throws ParseException
	{
		String grammarText = "# コメント行\n\nroot ::= greeting\n\n# もう一つコメント\ngreeting ::= \" hello.\"";
		GbnfGrammarValidator.assertValid(grammarText);
	}
}
