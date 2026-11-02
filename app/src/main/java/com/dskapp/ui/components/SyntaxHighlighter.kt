package com.dskapp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Token categories that receive distinct colours.
 */
enum class TokenType { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, TYPE }

/** Half-open range [start, end) into the source string. */
data class CodeToken(val start: Int, val end: Int, val type: TokenType)

/**
 * Tiny, allocation-frugal syntax highlighter.
 *
 * The tokenizer is a single left-to-right scan so it stays linear even for long inputs
 * and so comments/strings always win over keywords that appear inside them. Tokenizing is
 * kept free of Compose types so it can be unit tested on the plain JVM.
 */
object SyntaxHighlighter {

    private val ALIASES: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "py" to "python",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash",
        "c++" to "cpp", "cc" to "cpp", "hpp" to "cpp",
        "cs" to "csharp",
        "yml" to "yaml",
        "golang" to "go",
        "rs" to "rust",
        "html" to "xml", "xml" to "xml", "svg" to "xml"
    )

    private val C_LIKE_COMMENTS = setOf(
        "kotlin", "java", "javascript", "typescript", "c", "cpp", "csharp", "go", "rust", "swift", "dart", "php"
    )
    private val HASH_COMMENTS = setOf("python", "bash", "yaml", "ruby")
    private val ANNOTATION_LANGS = setOf("kotlin", "java", "python", "typescript", "dart")
    private val TYPE_LANGS = setOf(
        "kotlin", "java", "javascript", "typescript", "c", "cpp", "csharp", "go", "rust", "swift", "dart", "php", "python"
    )

    private val KEYWORDS: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "val", "var", "fun", "class", "object", "interface", "data", "sealed", "enum", "override",
            "private", "public", "protected", "internal", "return", "if", "else", "when", "for", "while",
            "do", "try", "catch", "finally", "throw", "import", "package", "true", "false", "null", "is", "as", "in"
        ),
        "java" to setOf(
            "class", "interface", "enum", "extends", "implements", "public", "private", "protected", "static",
            "final", "void", "int", "long", "double", "float", "boolean", "char", "return", "if", "else",
            "for", "while", "do", "try", "catch", "finally", "throw", "new", "import", "package", "true", "false", "null"
        ),
        "javascript" to setOf(
            "function", "const", "let", "var", "class", "extends", "return", "if", "else", "for", "while",
            "do", "try", "catch", "finally", "throw", "new", "import", "export", "from", "async", "await",
            "true", "false", "null", "undefined", "this", "typeof"
        ),
        "typescript" to setOf(
            "function", "const", "let", "var", "class", "extends", "implements", "interface", "type", "enum",
            "return", "if", "else", "for", "while", "try", "catch", "finally", "throw", "new", "import", "export",
            "from", "async", "await", "true", "false", "null", "undefined", "public", "private", "readonly"
        ),
        "python" to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "try", "except", "finally", "raise",
            "import", "from", "as", "with", "lambda", "yield", "pass", "break", "continue", "True", "False",
            "None", "and", "or", "not", "in", "is"
        ),
        "c" to setOf(
            "int", "long", "short", "char", "float", "double", "void", "struct", "union", "enum", "typedef",
            "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "static",
            "const", "include", "define", "sizeof"
        ),
        "cpp" to setOf(
            "int", "long", "short", "char", "float", "double", "void", "bool", "class", "struct", "template",
            "namespace", "using", "public", "private", "protected", "return", "if", "else", "for", "while",
            "new", "delete", "const", "auto", "true", "false", "nullptr", "virtual", "override"
        ),
        "csharp" to setOf(
            "class", "struct", "interface", "enum", "namespace", "using", "public", "private", "protected",
            "internal", "static", "readonly", "void", "int", "long", "double", "float", "bool", "string",
            "return", "if", "else", "for", "while", "try", "catch", "finally", "new", "true", "false", "null"
        ),
        "go" to setOf(
            "func", "package", "import", "type", "struct", "interface", "var", "const", "return", "if", "else",
            "for", "range", "switch", "case", "break", "continue", "go", "defer", "map", "chan", "true", "false", "nil"
        ),
        "rust" to setOf(
            "fn", "let", "mut", "struct", "enum", "trait", "impl", "pub", "use", "mod", "return", "if", "else",
            "match", "for", "while", "loop", "break", "continue", "true", "false", "self", "Self", "async", "await"
        ),
        "swift" to setOf(
            "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "import", "return", "if",
            "else", "guard", "for", "while", "switch", "case", "break", "continue", "true", "false", "nil", "self", "init"
        ),
        "sql" to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
            "join", "inner", "left", "right", "on", "group", "by", "order", "having", "limit", "and", "or", "not", "null"
        ),
        "bash" to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
            "return", "export", "local", "echo", "read", "in", "until", "select"
        ),
        "dart" to setOf(
            "class", "enum", "extends", "implements", "with", "import", "export", "return", "if", "else", "for",
            "while", "do", "try", "catch", "finally", "throw", "new", "final", "const", "var", "true", "false", "null", "void"
        ),
        "php" to setOf(
            "function", "class", "interface", "extends", "implements", "public", "private", "protected", "static",
            "return", "if", "else", "elseif", "for", "foreach", "while", "try", "catch", "finally", "throw",
            "new", "true", "false", "null", "echo", "namespace", "use"
        ),
        "ruby" to setOf(
            "def", "end", "class", "module", "if", "elsif", "else", "unless", "for", "while", "until", "do",
            "return", "yield", "begin", "rescue", "ensure", "true", "false", "nil", "and", "or", "not", "then"
        ),
        "yaml" to setOf("true", "false", "null", "yes", "no", "on", "off")
    )

    private val SQL_KEYWORDS: Set<String> = KEYWORDS.getValue("sql")

    /** Colour for each token type on a light background. */
    private val LIGHT_STYLES: Map<TokenType, SpanStyle> = mapOf(
        TokenType.KEYWORD to SpanStyle(color = Color(0xFF0033B3), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        TokenType.STRING to SpanStyle(color = Color(0xFF067D17)),
        TokenType.COMMENT to SpanStyle(color = Color(0xFF8C8C8C), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        TokenType.NUMBER to SpanStyle(color = Color(0xFF1750EB)),
        TokenType.ANNOTATION to SpanStyle(color = Color(0xFF9E880D)),
        TokenType.TYPE to SpanStyle(color = Color(0xFF000080))
    )

    /** Colour for each token type on a dark background. */
    private val DARK_STYLES: Map<TokenType, SpanStyle> = mapOf(
        TokenType.KEYWORD to SpanStyle(color = Color(0xFFCF8E6D), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        TokenType.STRING to SpanStyle(color = Color(0xFF6AAB73)),
        TokenType.COMMENT to SpanStyle(color = Color(0xFF7A7E85), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        TokenType.NUMBER to SpanStyle(color = Color(0xFF2AACB8)),
        TokenType.ANNOTATION to SpanStyle(color = Color(0xFFB3AE60)),
        TokenType.TYPE to SpanStyle(color = Color(0xFF6FAFBD))
    )

    /** Normalises language markers and resolves aliases. */
    private fun normalize(language: String): String {
        val raw = language.trim().lowercase()
        return ALIASES[raw] ?: raw
    }

    /** Tokens to colour, sorted by start, non-overlapping. Unknown language -> generic rules. */
    fun tokenize(code: String, language: String): List<CodeToken> {
        val lang = normalize(language)
        val tokens = ArrayList<CodeToken>()
        val n = code.length
        var i = 0
        val keywords = KEYWORDS[lang]
        // Unknown or unlabelled code still gets // and # line comments coloured.
        val generic = keywords == null && lang != "json" && lang != "xml"
        val cLike = lang in C_LIKE_COMMENTS
        val hash = lang in HASH_COMMENTS || generic
        val annotation = lang in ANNOTATION_LANGS
        val typeLang = lang in TYPE_LANGS
        val isSql = lang == "sql"
        val isXml = lang == "xml"
        val isJson = lang == "json"
        val tripleQuotes = lang == "python" || lang == "kotlin" || lang == "swift"
        val templateLiterals = lang == "javascript" || lang == "typescript"

        while (i < n) {
            val c = code[i]

            // Comments first so they swallow any keyword-like text inside.
            if (cLike && c == '/' && i + 1 < n) {
                val next = code[i + 1]
                if (next == '/') {
                    val end = lineEnd(code, i)
                    tokens.add(CodeToken(i, end, TokenType.COMMENT))
                    i = end
                    continue
                }
                if (next == '*') {
                    val end = blockEnd(code, i + 2)
                    tokens.add(CodeToken(i, end, TokenType.COMMENT))
                    i = end
                    continue
                }
            }
            if ((isJson || generic) && c == '/' && i + 1 < n && code[i + 1] == '/') {
                val end = lineEnd(code, i)
                tokens.add(CodeToken(i, end, TokenType.COMMENT))
                i = end
                continue
            }
            if (hash && c == '#') {
                val end = lineEnd(code, i)
                tokens.add(CodeToken(i, end, TokenType.COMMENT))
                i = end
                continue
            }
            if (isSql && c == '-' && i + 1 < n && code[i + 1] == '-') {
                val end = lineEnd(code, i)
                tokens.add(CodeToken(i, end, TokenType.COMMENT))
                i = end
                continue
            }
            if (isXml && c == '<' && i + 3 < n && code.startsWith("<!--", i)) {
                val close = code.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                tokens.add(CodeToken(i, end, TokenType.COMMENT))
                i = end
                continue
            }

            // Strings (triple quotes / template literals may span lines).
            if (c == '"' || c == '\'') {
                if (tripleQuotes && i + 2 < n && code[i + 1] == c && code[i + 2] == c) {
                    val close = code.indexOf("$c$c$c", i + 3)
                    val end = if (close < 0) n else close + 3
                    tokens.add(CodeToken(i, end, TokenType.STRING))
                    i = end
                } else {
                    val end = quotedEnd(code, i, c)
                    tokens.add(CodeToken(i, end, TokenType.STRING))
                    i = end
                }
                continue
            }
            if (templateLiterals && c == '`') {
                var j = i + 1
                while (j < n) {
                    val cc = code[j]
                    if (cc == '\\') {
                        j += 2
                        continue
                    }
                    if (cc == '`') {
                        j += 1
                        break
                    }
                    j += 1
                }
                val end = if (j > n) n else j
                tokens.add(CodeToken(i, end, TokenType.STRING))
                i = end
                continue
            }

            // Numbers: hex first, then decimal with optional fraction/exponent/suffix.
            if (c.isDigit()) {
                var j = i
                if (c == '0' && j + 1 < n && (code[j + 1] == 'x' || code[j + 1] == 'X')) {
                    j += 2
                    while (j < n && isHex(code[j])) j++
                } else {
                    while (j < n && code[j].isDigit()) j++
                    if (j < n && code[j] == '.' && j + 1 < n && code[j + 1].isDigit()) {
                        j++
                        while (j < n && code[j].isDigit()) j++
                    }
                    if (j < n && (code[j] == 'e' || code[j] == 'E')) {
                        var k = j + 1
                        if (k < n && (code[k] == '+' || code[k] == '-')) k++
                        if (k < n && code[k].isDigit()) {
                            j = k
                            while (j < n && code[j].isDigit()) j++
                        }
                    }
                    if (j < n && (code[j] == 'f' || code[j] == 'F' || code[j] == 'l' || code[j] == 'L' ||
                                code[j] == 'u' || code[j] == 'U')) {
                        j++
                    }
                }
                tokens.add(CodeToken(i, j, TokenType.NUMBER))
                i = j
                continue
            }

            // Annotations / decorators.
            if (annotation && c == '@') {
                var j = i + 1
                while (j < n && isIdent(code[j])) j++
                if (j > i + 1) {
                    tokens.add(CodeToken(i, j, TokenType.ANNOTATION))
                    i = j
                    continue
                }
            }

            // Identifiers: keywords, types, or plain.
            if (isIdentStart(c)) {
                var j = i + 1
                while (j < n && isIdent(code[j])) j++
                val word = code.substring(i, j)
                val type = classifyWord(word, lang, keywords, typeLang)
                if (type != null) tokens.add(CodeToken(i, j, type))
                i = j
                continue
            }

            // XML tag names after '<' or '</'.
            if (isXml && c == '<') {
                var k = i + 1
                if (k < n && code[k] == '/') k++
                if (k < n && isIdentStart(code[k])) {
                    var j = k + 1
                    while (j < n && (isIdent(code[j]) || code[j] == '-' || code[j] == ':')) j++
                    tokens.add(CodeToken(k, j, TokenType.KEYWORD))
                }
                i++
                continue
            }

            i++
        }

        if (isJson) return normalizeJson(code, tokens)
        return tokens
    }

    /**
     * JSON needs context: a string followed (after spaces) by ':' is a key. We post-process only
     * string tokens so the linear scan stays unchanged.
     */
    private fun normalizeJson(code: String, tokens: List<CodeToken>): List<CodeToken> {
        return tokens.map { t ->
            if (t.type == TokenType.STRING) {
                var k = t.end
                while (k < code.length && code[k] == ' ') k++
                if (k < code.length && code[k] == ':') t.copy(type = TokenType.KEYWORD) else t
            } else {
                t
            }
        }
    }

    /** Resolves keyword / type classification for an identifier word. */
    private fun classifyWord(word: String, lang: String, keywords: Set<String>?, typeLang: Boolean): TokenType? {
        if (lang == "sql") {
            if (SQL_KEYWORDS.contains(word.lowercase())) return TokenType.KEYWORD
        } else if (lang == "json") {
            if (word == "true" || word == "false" || word == "null") return TokenType.KEYWORD
        } else if (keywords != null && keywords.contains(word)) {
            return TokenType.KEYWORD
        }
        if (typeLang && word.isNotEmpty() && word[0] in 'A'..'Z') return TokenType.TYPE
        return null
    }

    private fun isIdentStart(c: Char): Boolean = c == '_' || c.isLetter()
    private fun isIdent(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
    private fun isHex(c: Char): Boolean =
        (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

    private fun lineEnd(code: String, from: Int): Int {
        val nl = code.indexOf('\n', from)
        return if (nl < 0) code.length else nl
    }

    private fun blockEnd(code: String, from: Int): Int {
        val close = code.indexOf("*/", from)
        return if (close < 0) code.length else close + 2
    }

    /** Scans a single-line quoted string honouring backslash escapes. */
    private fun quotedEnd(code: String, start: Int, quote: Char): Int {
        var j = start + 1
        val n = code.length
        while (j < n) {
            val cc = code[j]
            if (cc == '\\' && j + 1 < n) {
                j += 2
                continue
            }
            if (cc == '\n') return j
            if (cc == quote) return j + 1
            j++
        }
        return n
    }

    /** Coloured text for a code block; colours chosen for [dark] or light backgrounds. */
    fun highlight(code: String, language: String, dark: Boolean): AnnotatedString {
        val tokens = tokenize(code, language)
        val styles = if (dark) DARK_STYLES else LIGHT_STYLES
        return buildAnnotatedString {
            append(code)
            for (token in tokens) {
                val style = styles[token.type] ?: continue
                addStyle(style, token.start, token.end)
            }
        }
    }
}
