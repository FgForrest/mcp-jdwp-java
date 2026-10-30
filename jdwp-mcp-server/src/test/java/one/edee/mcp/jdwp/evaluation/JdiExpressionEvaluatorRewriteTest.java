package one.edee.mcp.jdwp.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JdiExpressionEvaluator#rewriteThisFieldReferences(String, Set, Set)}.
 * Verifies that bare references to fields of {@code this} get prefixed with {@code _this.},
 * but qualified references, shadowed names, and substring matches are left alone.
 */
class JdiExpressionEvaluatorRewriteTest {

	private static final Set<String> NO_SHADOWS = Set.of();

	@Test
	@DisplayName("rewrites a bare field reference to _this.field")
	void shouldRewriteBareFieldReference() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"sessions.containsKey(session)",
			Set.of("sessions"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("_this.sessions.containsKey(session)");
	}

	@Test
	@DisplayName("rewrites multiple distinct fields in the same expression")
	void shouldRewriteMultipleFields() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"count + total - remaining",
			Set.of("count", "total", "remaining"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("_this.count + _this.total - _this.remaining");
	}

	@Test
	@DisplayName("rewrites all occurrences of the same field")
	void shouldRewriteAllOccurrences() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"sessions != null && sessions.size() > 0",
			Set.of("sessions"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("_this.sessions != null && _this.sessions.size() > 0");
	}

	@Test
	@DisplayName("does NOT rewrite a field that is shadowed by a local of the same name")
	void shouldNotRewriteShadowedField() {
		// `sessions` is BOTH a field on `this` AND a local in the current frame — the local
		// shadows the field, so the rewriter must leave it alone. `session` is just a local
		// (not in the field set) and is not touched either.
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"sessions.containsKey(session)",
			Set.of("sessions"),
			Set.of("sessions"));

		assertThat(result).isEqualTo("sessions.containsKey(session)");
	}

	@Test
	@DisplayName("does NOT rewrite a qualified reference like obj.sessions")
	void shouldNotRewriteQualifiedReference() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"someObject.sessions.size()",
			Set.of("sessions"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("someObject.sessions.size()");
	}

	@Test
	@DisplayName("does NOT rewrite identifiers that merely contain the field name as a substring")
	void shouldNotRewriteSubstringMatches() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"sessions2.foo() + mySessions",
			Set.of("sessions"),
			NO_SHADOWS);

		// Neither "sessions2" nor "mySessions" should be touched
		assertThat(result).isEqualTo("sessions2.foo() + mySessions");
	}

	@Test
	@DisplayName("returns the input unchanged when the field set is empty")
	void shouldReturnUnchangedForEmptyFieldSet() {
		String input = "sessions.containsKey(session)";

		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(input, Set.of(), NO_SHADOWS);

		assertThat(result).isSameAs(input);
	}

	@Test
	@DisplayName("rewrites a field reference at the start of the expression")
	void shouldRewriteAtStart() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"count > 0",
			Set.of("count"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("_this.count > 0");
	}

	@Test
	@DisplayName("rewrites a field reference inside a method argument")
	void shouldRewriteInsideMethodArgument() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"java.util.Objects.hash(userId, role)",
			Set.of("userId", "role"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("java.util.Objects.hash(_this.userId, _this.role)");
	}

	@Test
	@DisplayName("does NOT rewrite a field name that already follows a dot (already qualified)")
	void shouldNotRewriteAlreadyDotted() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"this.sessions.size()",
			Set.of("sessions"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("this.sessions.size()");
	}

	// ── Tokenizer-aware tests: string / char / text-block literals ──────────────────────

	@Test
	@DisplayName("does NOT rewrite a field name that appears inside a string literal")
	void shouldNotRewriteInsideStringLiteral() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"\"name=\" + name",
			Set.of("name"),
			NO_SHADOWS);

		// The "name=" inside the string must be untouched; the bare `name` after must rewrite
		assertThat(result).isEqualTo("\"name=\" + _this.name");
	}

	@Test
	@DisplayName("does NOT rewrite a field name that appears inside a char literal")
	void shouldNotRewriteInsideCharLiteral() {
		// 'a' is a char literal — the rewriter must not touch it. The bare `a` after rewrites.
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"'a' + a",
			Set.of("a"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("'a' + _this.a");
	}

	@Test
	@DisplayName("does NOT rewrite a field name surrounded by escaped quotes inside a string")
	void shouldNotRewriteInsideStringWithEscapes() {
		// The string contains \"name\" — escapes must not terminate the string early
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"\"prefix=\\\"name\\\" suffix\" + name",
			Set.of("name"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("\"prefix=\\\"name\\\" suffix\" + _this.name");
	}

	@Test
	@DisplayName("does NOT rewrite a field name inside a Java text block")
	void shouldNotRewriteInsideTextBlock() {
		// Text block "..."""  spans multiple lines and contains the field name
		String input = "\"\"\"\nname is the field\n\"\"\" + name";

		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			input,
			Set.of("name"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("\"\"\"\nname is the field\n\"\"\" + _this.name");
	}

	@Test
	@DisplayName("rewrites multiple field references between consecutive string literals")
	void shouldRewriteFieldsBetweenMultipleStrings() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"\"a\" + name + \"b\" + count + \"c\"",
			Set.of("name", "count"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("\"a\" + _this.name + \"b\" + _this.count + \"c\"");
	}

	@Test
	@DisplayName("rewrites a field reference immediately after a string literal closes")
	void shouldRewriteIdentifierImmediatelyAfterString() {
		// No whitespace between the closing " and the identifier
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"\"x\".concat(name)",
			Set.of("name"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("\"x\".concat(_this.name)");
	}

	@Test
	@DisplayName("rewrites a qualified-form expression that contains a method call with a field arg")
	void shouldRewriteFieldUsedAsMethodArgument() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"someObject.method(name).other(count)",
			Set.of("name", "count"),
			NO_SHADOWS);

		// "someObject" is preceded by nothing → not in the field set, untouched
		// "method"/"other" follow a dot → not rewritten
		// "name" and "count" are bare identifiers → rewritten
		assertThat(result).isEqualTo("someObject.method(_this.name).other(_this.count)");
	}

	@Test
	@DisplayName("tolerates an unterminated string literal without throwing")
	void shouldTolerateUnterminatedString() {
		// Pathological input: opening quote without a close. The tokenizer must not throw or hang.
		String input = "\"unterminated";

		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			input,
			Set.of("name"),
			NO_SHADOWS);

		// Whatever the tokenizer decides to do with the unterminated literal, it must not throw
		// and must not corrupt the input by inserting `_this.` somewhere weird.
		assertThat(result).isEqualTo("\"unterminated");
	}

	@Test
	@DisplayName("respects whitespace between qualifying dot and identifier")
	void shouldNotRewriteWithWhitespaceQualifiedReference() {
		// "obj . sessions" — even with spaces around the dot, the identifier is qualified
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"obj . sessions",
			Set.of("sessions"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("obj . sessions");
	}

	@Test
	@DisplayName("rewrites a field after a comparison operator (not preceded by dot)")
	void shouldRewriteAfterComparisonOperator() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"x == name && y != count",
			Set.of("name", "count"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("x == _this.name && y != _this.count");
	}

	@Test
	@DisplayName("treats backslash-escaped char literal as opaque")
	void shouldHandleBackslashCharLiteral() {
		// '\n' is a newline char literal. The tokenizer must skip it without confusion.
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"'\\n' + name",
			Set.of("name"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("'\\n' + _this.name");
	}

	@Test
	@DisplayName("string and char literals can both appear in the same expression")
	void shouldHandleMixedLiteralKinds() {
		String result = JdiExpressionEvaluator.rewriteThisFieldReferences(
			"\"name\" + 'n' + name",
			Set.of("name"),
			NO_SHADOWS);

		assertThat(result).isEqualTo("\"name\" + 'n' + _this.name");
	}

	// ── rewriteThisKeyword: bare `this` → `_this`, tokenizer-aware ───────────────────────

	@Test
	@DisplayName("rewriteThisKeyword: rewrites a bare `this` to `_this`")
	void shouldRewriteBareThisKeyword() {
		String result = JdiExpressionEvaluator.rewriteThisKeyword("this");

		assertThat(result).isEqualTo("_this");
	}

	@Test
	@DisplayName("rewriteThisKeyword: rewrites `this.foo` to `_this.foo`")
	void shouldRewriteThisDotFoo() {
		String result = JdiExpressionEvaluator.rewriteThisKeyword("this.foo");

		assertThat(result).isEqualTo("_this.foo");
	}

	@Test
	@DisplayName("rewriteThisKeyword: rewrites multiple bare `this` references")
	void shouldRewriteMultipleBareThis() {
		String result = JdiExpressionEvaluator.rewriteThisKeyword("this == null || this.foo > 0");

		assertThat(result).isEqualTo("_this == null || _this.foo > 0");
	}

	@Test
	@DisplayName("rewriteThisKeyword: does NOT rewrite identifiers that contain `this` as a substring")
	void shouldNotRewriteSubstringMatchOfThis() {
		String result = JdiExpressionEvaluator.rewriteThisKeyword("myThis + thisFoo + barThis");

		assertThat(result).isEqualTo("myThis + thisFoo + barThis");
	}

	@Test
	@DisplayName("rewriteThisKeyword: does NOT rewrite `this` inside a string literal")
	void shouldNotRewriteThisInsideStringLiteral() {
		// Guards against a regression where a naive `replaceAll` would rewrite the `this`
		// substring inside the string literal. The token-aware rewrite must leave string contents
		// alone and only rewrite `this` outside string boundaries.
		String result = JdiExpressionEvaluator.rewriteThisKeyword("\"this is a test\" + this");

		assertThat(result).isEqualTo("\"this is a test\" + _this");
	}

	@Test
	@DisplayName("rewriteThisKeyword: does NOT rewrite `this` inside a char literal sequence")
	void shouldNotRewriteThisInsideCharContext() {
		// 't' is a char literal — the tokenizer must skip past it without rewriting the
		// surrounding `this` token... wait, the literal does not contain `this`. Use a
		// pathological char literal that LOOKS like the start of `this`.
		String result = JdiExpressionEvaluator.rewriteThisKeyword("'t' + this");

		assertThat(result).isEqualTo("'t' + _this");
	}

	@Test
	@DisplayName("rewriteThisKeyword: does NOT rewrite `this` inside a string with escapes")
	void shouldNotRewriteThisInsideStringWithEscapes() {
		String result = JdiExpressionEvaluator.rewriteThisKeyword(
			"\"prefix=\\\"this\\\" suffix\" + this");

		assertThat(result).isEqualTo("\"prefix=\\\"this\\\" suffix\" + _this");
	}

	@Test
	@DisplayName("rewriteThisKeyword: does NOT rewrite `this` inside a Java text block")
	void shouldNotRewriteThisInsideTextBlock() {
		String input = "\"\"\"\nthis is a multiline\nthis literal\n\"\"\" + this";

		String result = JdiExpressionEvaluator.rewriteThisKeyword(input);

		assertThat(result).isEqualTo("\"\"\"\nthis is a multiline\nthis literal\n\"\"\" + _this");
	}

	@Test
	@DisplayName("rewriteThisKeyword: returns the input unchanged when no `this` token is present")
	void shouldReturnUnchangedWhenNoThis() {
		String input = "foo + bar.baz()";

		String result = JdiExpressionEvaluator.rewriteThisKeyword(input);

		assertThat(result).isEqualTo("foo + bar.baz()");
	}

	@Test
	@DisplayName("rewriteThisKeyword: handles bare `this` adjacent to operators")
	void shouldRewriteThisAdjacentToOperators() {
		String result = JdiExpressionEvaluator.rewriteThisKeyword("(this)+(this)");

		assertThat(result).isEqualTo("(_this)+(_this)");
	}

	@Test
	@DisplayName("rewriteThisKeyword: tolerates an unterminated string literal without throwing")
	void shouldTolerateUnterminatedStringInThisKeyword() {
		String input = "this + \"unterminated";

		String result = JdiExpressionEvaluator.rewriteThisKeyword(input);

		// The bare `this` before the literal should still be rewritten
		assertThat(result).isEqualTo("_this + \"unterminated");
	}

	// ── isBlockMode: detect `{ ... }` block input vs expression input ────────────────────

	@Test
	@DisplayName("isBlockMode: plain expression is NOT a block")
	void shouldNotDetectBlockForPlainExpression() {
		assertThat(JdiExpressionEvaluator.isBlockMode("foo.bar() + baz")).isFalse();
	}

	@Test
	@DisplayName("isBlockMode: `{ stmt; }` IS a block")
	void shouldDetectSingleStatementBlock() {
		assertThat(JdiExpressionEvaluator.isBlockMode("{ return x; }")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: leading/trailing whitespace is allowed")
	void shouldDetectBlockWithSurroundingWhitespace() {
		assertThat(JdiExpressionEvaluator.isBlockMode("  \n  { return x; }\n  ")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: nested braces are matched correctly")
	void shouldDetectBlockWithNestedBraces() {
		assertThat(JdiExpressionEvaluator.isBlockMode(
			"{ if (x > 0) { return x; } else { return 0; } }")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: braces inside a string literal do NOT terminate the outer block")
	void shouldNotConfuseBracesInsideStringLiteral() {
		// The `}` inside the string would mislead a naive matcher; the tokenizer must skip it.
		assertThat(JdiExpressionEvaluator.isBlockMode("{ return \"}\"; }")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: `{x}.foo()` is NOT a block (trailing `}` is not the outer closer)")
	void shouldNotDetectBlockWhenClosingBraceIsMidExpression() {
		// `{x}` opens-and-closes before `.foo()`, so the trailing closer of the trimmed input
		// is `)` — block mode should not apply.
		assertThat(JdiExpressionEvaluator.isBlockMode("{x}.foo()")).isFalse();
	}

	@Test
	@DisplayName("isBlockMode: brace-balanced internals followed by expression text are NOT a block")
	void shouldNotDetectBlockWhenInternalBraceClosesEarly() {
		// `{a;}+b;}` — input both starts and ends with a brace (so it clears the cheap
		// first/last-char guard), but the FIRST `}` at i=3 drops depth to 0 before the trailing
		// `}` at i=7. That trailing brace is therefore a separate closer, not the match for the
		// opening one, so this exercises the early `depth == 0` rejection branch — not the
		// end-char guard. Block detection must reject it.
		assertThat(JdiExpressionEvaluator.isBlockMode("{a;}+b;}")).isFalse();
	}

	@Test
	@DisplayName("isBlockMode: an empty input is NOT a block")
	void shouldNotDetectBlockForEmptyInput() {
		assertThat(JdiExpressionEvaluator.isBlockMode("")).isFalse();
		assertThat(JdiExpressionEvaluator.isBlockMode("   ")).isFalse();
	}

	@Test
	@DisplayName("isBlockMode: a lone `{}` is a (trivial, empty) block")
	void shouldDetectEmptyBlock() {
		assertThat(JdiExpressionEvaluator.isBlockMode("{}")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: `}` inside a `/* ... */` block comment does NOT close the outer block")
	void shouldNotConfuseBracesInsideBlockComment() {
		// Without comment-skip, the `}` inside the comment would drop depth to 0 and the
		// outer block would be rejected as non-block input.
		assertThat(JdiExpressionEvaluator.isBlockMode("{ /* close: } */ return 1; }")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: `}` inside a `//` line comment does NOT close the outer block")
	void shouldNotConfuseBracesInsideLineComment() {
		assertThat(JdiExpressionEvaluator.isBlockMode("{ // }\n return 1; }")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: `/* nested } */` followed by real `}` still balances")
	void shouldHandleBlockCommentWithBraceAtVariousDepths() {
		// Nested braces in code, plus a `}` hidden inside a comment, plus the real closer.
		assertThat(JdiExpressionEvaluator.isBlockMode(
			"{ if (x) { /* not a closer: } */ return 1; } return 0; }")).isTrue();
	}

	@Test
	@DisplayName("isBlockMode: unterminated block comment is tolerated, treats remainder as comment")
	void shouldTolerateUnterminatedBlockComment() {
		// `/* …` without a closing `*/` swallows the rest of the input including the trailing `}`.
		// The outer brace then never closes → not block-mode. Must not throw.
		assertThat(JdiExpressionEvaluator.isBlockMode("{ /* unterminated }")).isFalse();
	}

	@Test
	@DisplayName("rewrites an unqualified call of a method of this, not a qualified one or a constructor (#50)")
	void shouldRewriteUnqualifiedMethodCalls() {
		final String result = JdiExpressionEvaluator.rewriteThisMemberReferences(
			"sold() + office.sold() + new sold().size() + seats ( )",
			Set.of(), Set.of("sold", "seats"), NO_SHADOWS);

		assertThat(result).isEqualTo("_this.sold() + office.sold() + new sold().size() + _this.seats ( )");
	}

	@Test
	@DisplayName("a field name followed by a parenthesis is a call: rewritten only when it is also a method")
	void shouldTellCallsFromFields() {
		assertThat(JdiExpressionEvaluator.rewriteThisMemberReferences("sold(1) + sold", Set.of("sold"), Set.of(),
			NO_SHADOWS)).isEqualTo("sold(1) + _this.sold");
		assertThat(JdiExpressionEvaluator.rewriteThisMemberReferences("sold() + sold.get()", Set.of("sold"),
			Set.of("sold"), NO_SHADOWS)).isEqualTo("_this.sold() + _this.sold.get()");
	}

	@Test
	@DisplayName("a call inside a string literal is left alone")
	void shouldNotRewriteCallsInLiterals() {
		assertThat(JdiExpressionEvaluator.rewriteThisMemberReferences("\"sold()\" + sold()", Set.of(),
			Set.of("sold"), NO_SHADOWS)).isEqualTo("\"sold()\" + _this.sold()");
	}
}
