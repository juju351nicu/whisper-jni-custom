package jp.clip.whisperjni;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * GBNF 文法の検証を行うユーティリティ。
 *
 * <p>
 * whisper.cpp に渡す前に、Java 側だけで文法の妥当性を確認できます。ネイティブライブラリを
 * 読み込んでいなくても使えます（純 Java 実装です）。
 * </p>
 *
 * <p>
 * 検証内容:
 * </p>
 *
 * <ul>
 * <li>{@code root} 式が存在すること</li>
 * <li>すべての部分式が解決できること（未定義の参照が無いこと）</li>
 * <li>部分式が循環参照していないこと</li>
 * <li>文字列・正規表現・グループの開閉が対応していること</li>
 * <li>埋め込まれた正規表現が Java で構文解析できること</li>
 * <li>root の解決がピリオドで終端すること</li>
 * </ul>
 *
 * <p>
 * 使用例:
 * </p>
 *
 * <pre>
 * GbnfGrammarValidator.assertValid(Path.of("my_grammar.gbnf"));
 * WhisperGrammar grammar = whisper.parseGrammar(Path.of("my_grammar.gbnf"));
 * </pre>
 *
 * <p>
 * 元は {@code WhisperGrammar} の static メソッド群でしたが、ネイティブポインタの管理とは
 * 無関係な責務なので別クラスへ分離しました。
 * </p>
 *
 * <p>
 * 分離時に、元の実装の不具合を 2 件修正しています。
 * </p>
 * <ul>
 * <li>グループ検証: {@code ((piece | pawn | king)} のように 1 トークンで複数のグループを開く場合に
 * "(" が 1 つしか剥がれず、その結果として生じる不整合を末尾の判定が {@code onGroup > 1} で
 * 見逃していました。両方を直したことで未閉鎖グループを正しく検出できるようになっています。</li>
 * <li>終端検証: 「root はピリオドで終わること」の判定が、閉じ引用符を含むトークンでは一度も
 * 実行されていませんでした（閉じ引用符の分岐で先に {@code continue} していたため）。
 * {@code root ::= " hello"} が通ってしまっていたのを、正しく拒否するようにしています。</li>
 * </ul>
 *
 * <p>
 * なお、これはあくまで事前チェックです。最終的な妥当性は whisper.cpp 側の
 * {@code grammar_parser::parse} が判断します（{@code WhisperJNI#parseGrammar} が
 * 失敗時に例外を投げます）。
 * </p>
 *
 * @author Miguel Alvarez Díez - Initial contribution
 */
public final class GbnfGrammarValidator {
	
	private GbnfGrammarValidator()
	{
		// ユーティリティクラスなのでインスタンス化しない
	}
	
	/**
	 * GBNF 文法ファイルが whisper.cpp で使用可能かを検証します。
	 *
	 * @param grammar GBNF 文法ファイルの {@link Path}
	 * @throws ParseException 文法が不正な場合
	 * @throws IOException    ファイルの読み込みに失敗した場合
	 */
	public static void assertValid(Path grammar) throws ParseException, IOException
	{
		if(!Files.exists(grammar))
		{
			throw new ParseException("Grammar file does not exists.", 0);
		}
		assertValid(Files.readString(grammar));
	}
	
	/**
	 * GBNF 文法テキストが whisper.cpp で使用可能かを検証します。
	 *
	 * @param grammarText GBNF 文法のテキスト
	 * @throws ParseException 文法が不正な場合
	 */
	public static void assertValid(String grammarText) throws ParseException
	{
		if(grammarText.isBlank())
		{
			throw new ParseException("Empty grammar.", 0);
		}
		
		Map<String, String> expressions = parseExpressionsText(grammarText);
		String rootExpression = expressions.get("root");
		
		if(rootExpression == null)
		{
			throw new ParseException("Missing root expression.", 0);
		}
		assertValidExpression(expressions, rootExpression, new ArrayList<>(), new HashSet<>(), true);
	}
	
	/**
	 * 文法テキストを式名 → 式本文のマップに分解します。
	 *
	 * @param gbnfGrammar GBNF 文法のテキスト
	 * @return 式名をキーとしたマップ
	 * @throws ParseException 式の分解に失敗した場合
	 */
	private static Map<String, String> parseExpressionsText(String gbnfGrammar) throws ParseException
	{
		String currentExpressionName = "";
		StringBuilder currentExpression = new StringBuilder();
		HashMap<String, String> expressions = new HashMap<>();
		String assignSign = "::=";
		String[] split = gbnfGrammar.split("\n");
		
		for(int i = 0; i < split.length; i++)
		{
			String line = split[i];
			boolean isLast = i == split.length - 1;
			
			// 空行とコメント行は読み飛ばす
			if(line.isBlank() || line.trim().startsWith("#"))
			{
				continue;
			}
			
			boolean start = line.contains(assignSign);
			if(!start && currentExpressionName.isEmpty())
			{
				throw new ParseException("Grammar should start with an expression", 0);
			}
			if(start)
			{
				if(!currentExpressionName.isBlank())
				{
					expressions.put(currentExpressionName, currentExpression.toString());
				}
				String[] parts = line.split(assignSign);
				currentExpressionName = parts[0].trim();
				if(currentExpressionName.isEmpty())
				{
					throw new ParseException("Missed expression name: " + line, 0);
				}
				currentExpression = new StringBuilder(parts[1].trim());
				if(expressions.containsKey(currentExpressionName))
				{
					throw new ParseException("Duplicated expression: " + currentExpressionName, 0);
				}
				continue;
			}
			
			// 継続行を今の式に連結する
			currentExpression.append(" ").append(line.trim());
			if(isLast)
			{
				expressions.put(currentExpressionName, requireNonBlankExpression(currentExpression, currentExpressionName));
			}
		}
		
		// 最終行が継続行でなかった場合、最後の式がまだ登録されていない
		if(!expressions.containsKey(currentExpressionName))
		{
			expressions.put(currentExpressionName, requireNonBlankExpression(currentExpression, currentExpressionName));
		}
		return expressions;
	}
	
	private static String requireNonBlankExpression(StringBuilder expression, String expressionName) throws ParseException
	{
		String text = expression.toString();
		if(text.isBlank())
		{
			throw new ParseException("Missed expression value for: " + expressionName, 0);
		}
		return text;
	}
	
	/**
	 * 与えられた式が解決可能な GBNF 式であることを検証します。部分式を辿るために再帰します。
	 *
	 * @param expressions       利用可能な式のマップ
	 * @param expressionText    検証する GBNF 式
	 * @param parentExpressions 循環参照検出用の呼び出し履歴
	 * @param validExpressions  検証済みの式（再検証を避けるため）
	 * @param shouldTerminate   この式が終端（ピリオド）で終わるべきか
	 * @throws ParseException 式が不正な場合
	 */
	private static void assertValidExpression(Map<String, String> expressions, String expressionText,
			ArrayList<String> parentExpressions, HashSet<String> validExpressions, boolean shouldTerminate)
			throws ParseException
	{
		boolean onText = false;
		boolean onRegex = false;
		int startRegexIndex = 0;
		int onGroup = 0;
		StringBuilder groupExpression = new StringBuilder();
		String[] tokens = expressionText.trim().split("\\s+");
		
		// 循環参照の検出
		if(tokens.length == 1 && expressions.containsKey(tokens[0]))
		{
			if(parentExpressions.contains(tokens[0]))
			{
				throw new ParseException("Cyclic resolution of expression: " + tokens[0], 0);
			}
			parentExpressions = new ArrayList<>(parentExpressions);
			parentExpressions.add(tokens[0]);
		}
		
		for(int i = 0; i < tokens.length; i++)
		{
			String token = tokens[i];
			boolean isLast = i == tokens.length - 1;
			if(token.isBlank())
			{
				continue;
			}
			
			// グループ ( ... ) の開閉
			if(!onText && !onRegex)
			{
				boolean isGroupStart = token.startsWith("(");
				boolean isOptionalGroupEnd = token.endsWith(")?");
				boolean isGroupEnd = token.endsWith(")") || isOptionalGroupEnd;
				int openedHere = 0;
				if(isGroupStart)
				{
					while(token.substring(openedHere).startsWith("("))
					{
						onGroup += 1;
						openedHere++;
					}
				}
				if(isGroupEnd)
				{
					int index = token.length();
					String tmpToken = token.substring(0, index);
					while(tmpToken.endsWith(")") || tmpToken.endsWith(")?"))
					{
						onGroup -= 1;
						index -= tmpToken.endsWith("?") ? 2 : 1;
						tmpToken = token.substring(0, index);
					}
					if(onGroup < 0)
					{
						throw new ParseException("Missing group open", 0);
					}
					if(token.length() > 1)
					{
						groupExpression.append(" ").append(token, isGroupStart ? 1 : 0,
								token.length() - (isOptionalGroupEnd ? 2 : 1));
					}
					assertValidExpression(expressions, groupExpression.toString(), parentExpressions, validExpressions,
							isLast && shouldTerminate);
					groupExpression = new StringBuilder();
					continue;
				}
				else if(onGroup > 0)
				{
					if(isGroupStart)
					{
						// 1 トークンで複数のグループを開くことがある（例 "((piece"）。
						// substring(1) では "(" が 1 つしか剥がれず、再帰に不整合な文字列が渡ってしまう。
						groupExpression.append(token.substring(openedHere));
					}
					else
					{
						groupExpression.append(" ").append(token);
					}
					continue;
				}
			}
			
			// 文字列リテラル " ... "
			if(!onText && !onRegex && token.startsWith("\""))
			{
				onText = true;
				if(token.length() == 1)
				{
					continue;
				}
			}
			if(!onRegex && onGroup == 0 && (token.endsWith("\"") || token.endsWith("\"?")))
			{
				if(!onText)
				{
					throw new ParseException("Missing string open on segment: " + expressionText, 0);
				}
				onText = false;
				// 閉じ引用符を含むトークンが root の末尾なら、引用符の直前がピリオドでなければならない。
				// （以前はこの分岐で continue していたため、下の終端チェックが閉じ引用符のトークンでは
				// 一度も動かず、"root ::= \" hello\"" のような文法が通ってしまっていた）
				if(isLast && shouldTerminate && !stripQuotes(token).endsWith("."))
				{
					throw new ParseException("Root expression resolution should end with a dot.", 0);
				}
				continue;
			}
			if(onText)
			{
				if(isLast && shouldTerminate && !token.endsWith("."))
				{
					throw new ParseException("Root expression resolution should end with a dot.", 0);
				}
				continue;
			}
			
			// 正規表現 [ ... ]
			if(!onRegex && onGroup == 0 && token.startsWith("["))
			{
				onRegex = true;
				startRegexIndex = i;
				if(token.length() == 1)
				{
					continue;
				}
			}
			if(onGroup == 0 && (token.endsWith("]") || token.endsWith("]?") || token.endsWith("]+")
					|| token.endsWith("]*")))
			{
				if(!onRegex)
				{
					throw new ParseException("Missing regex open on segment: ", 0);
				}
				onRegex = false;
				if(isLast && shouldTerminate)
				{
					throw new ParseException("Root expression resolution should end with a dot.", 0);
				}
				String regexExpression = String.join(" ", Arrays.copyOfRange(tokens, startRegexIndex, i + 1));
				try
				{
					String regexText = regexExpression.substring(1, regexExpression.lastIndexOf("]"));
					Pattern.compile(regexText);
				} catch(PatternSyntaxException e)
				{
					throw new ParseException("Invalid regex expression: " + regexExpression, 0);
				}
				continue;
			}
			if(onRegex)
			{
				continue;
			}
			
			// 選択 |
			if(token.equals("|"))
			{
				assertValidExpression(expressions, String.join(" ", Arrays.copyOfRange(tokens, i + 1, tokens.length)),
						parentExpressions, validExpressions, shouldTerminate);
				break;
			}
			
			// 部分式の参照
			String subExpression = token;
			if(subExpression.endsWith("?"))
			{
				subExpression = subExpression.substring(0, subExpression.length() - 1);
			}
			String subExpressionValue = expressions.get(subExpression);
			if(subExpressionValue == null)
			{
				throw new ParseException("Unable to resolve expression: " + subExpression, 0);
			}
			if((!isLast || !shouldTerminate) && validExpressions.contains(subExpression))
			{
				continue;
			}
			parentExpressions = new ArrayList<>(parentExpressions);
			parentExpressions.add(subExpression);
			assertValidExpression(expressions, subExpressionValue, parentExpressions, validExpressions,
					isLast && shouldTerminate);
			if(!isLast || !shouldTerminate)
			{
				validExpressions.add(subExpression);
			}
		}
		
		if(onText)
		{
			throw new ParseException("Unclosed text at: " + expressionText, 0);
		}
		if(onRegex)
		{
			throw new ParseException("Unclosed regex at: " + expressionText, 0);
		}
		if(onGroup > 0)
		{
			throw new ParseException("Unclosed group at: " + expressionText, 0);
		}
	}

	/**
	 * トークンの先頭の引用符と、末尾の引用符（{@code "} または {@code "?}）を取り除きます。
	 */
	private static String stripQuotes(String token)
	{
		String result = token.startsWith("\"") ? token.substring(1) : token;
		if(result.endsWith("\"?"))
		{
			return result.substring(0, result.length() - 2);
		}
		if(result.endsWith("\""))
		{
			return result.substring(0, result.length() - 1);
		}
		return result;
	}
}
