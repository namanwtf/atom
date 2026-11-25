package atom.core.markdown

/** A parsed TeX formula, ready for native layout. Unknown commands degrade to their name, never to an error. */
sealed interface MathNode {
    data class Row(val children: List<MathNode>) : MathNode
    data class Sym(val text: String, val kind: SymKind) : MathNode
    data class Frac(val num: MathNode, val den: MathNode, val line: Boolean = true) : MathNode
    data class Sqrt(val body: MathNode, val index: MathNode? = null) : MathNode
    data class Scripts(val base: MathNode, val sup: MathNode?, val sub: MathNode?) : MathNode

    /** Big operators (sum, integral, lim...). [limits] puts the scripts above and below in display style. */
    data class BigOp(val symbol: String, val sup: MathNode?, val sub: MathNode?, val limits: Boolean, val text: Boolean = false) : MathNode
    data class Accent(val body: MathNode, val accent: AccentKind) : MathNode
    data class Fenced(val left: String, val body: MathNode, val right: String) : MathNode
    data class Matrix(val rows: List<List<MathNode>>, val left: String, val right: String, val align: MatrixAlign = MatrixAlign.Center) : MathNode
    data class Styled(val body: MathNode, val style: MathStyle) : MathNode
    data class Space(val em: Float) : MathNode

    /** [over] and [under] centred above and below [base] (overset, underset, stackrel). */
    data class Stack(val base: MathNode, val over: MathNode?, val under: MathNode?) : MathNode
}

enum class SymKind { Ord, Var, Num, Op, Bin, Rel, Open, Close, Punct, Text }

enum class AccentKind { Hat, Bar, Vec, Dot, DDot, Tilde, Overline, Underline, OverArrow }

enum class MathStyle { Bold, Roman, Blackboard, Calligraphic, Fraktur, Text, SansSerif, Mono }

enum class MatrixAlign { Center, Left, AlignPairs }

object MathParser {
    fun parse(tex: String): MathNode = try {
        Parser(tex.trim()).parseAll()
    } catch (e: Exception) {
        MathNode.Sym(tex, SymKind.Text)
    } catch (e: StackOverflowError) {
        MathNode.Sym(tex, SymKind.Text)
    }

    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0

        fun parseAll(): MathNode = row(stopAt = { false })

        private fun row(stopAt: (Char) -> Boolean, stopCommands: Set<String> = emptySet()): MathNode {
            depth++
            if (depth > 60) throw IllegalStateException("too deep")
            val items = ArrayList<MathNode>()
            while (i < s.length) {
                val c = s[i]
                if (stopAt(c)) break
                if (c == '\\' && stopCommands.isNotEmpty()) {
                    val name = peekCommand()
                    if (name in stopCommands) break
                }
                when {
                    c.isWhitespace() -> i++
                    c == '^' || c == '_' -> {
                        i++
                        val base = items.removeLastOrNull() ?: MathNode.Row(emptyList())
                        items += attachScript(base, c)
                    }
                    c == '\'' -> {
                        var primes = 0
                        while (i < s.length && s[i] == '\'') {
                            primes++
                            i++
                        }
                        val base = items.removeLastOrNull() ?: MathNode.Row(emptyList())
                        items += addSup(base, MathNode.Sym("′".repeat(primes), SymKind.Ord))
                    }
                    else -> atom()?.let { items += it }
                }
            }
            depth--
            return if (items.size == 1) items[0] else MathNode.Row(items)
        }

        private fun attachScript(base: MathNode, kind: Char): MathNode {
            val arg = argument()
            return when (base) {
                is MathNode.BigOp -> if (kind == '^') base.copy(sup = arg) else base.copy(sub = arg)
                is MathNode.Scripts -> if (kind == '^') {
                    if (base.sup == null) base.copy(sup = arg) else MathNode.Scripts(base, arg, null)
                } else {
                    if (base.sub == null) base.copy(sub = arg) else MathNode.Scripts(base, null, arg)
                }
                else -> if (kind == '^') MathNode.Scripts(base, arg, null) else MathNode.Scripts(base, null, arg)
            }
        }

        private fun addSup(base: MathNode, sup: MathNode): MathNode = when (base) {
            is MathNode.Scripts -> base.copy(sup = base.sup?.let { MathNode.Row(listOf(it, sup)) } ?: sup)
            else -> MathNode.Scripts(base, sup, null)
        }

        /** One script or command argument: a group or a single token. */
        private fun argument(): MathNode {
            skipSpaces()
            if (i >= s.length) return MathNode.Row(emptyList())
            if (s[i] == '{') return group()
            return atom() ?: MathNode.Row(emptyList())
        }

        private fun group(): MathNode {
            i++
            val inner = row(stopAt = { it == '}' })
            if (i < s.length && s[i] == '}') i++
            return inner
        }

        private fun atom(): MathNode? {
            val c = s[i]
            return when {
                c == '{' -> group()
                c == '}' -> {
                    i++
                    null
                }
                c == '\\' -> command()
                c == '&' -> {
                    i++
                    MathNode.Space(1f)
                }
                c == '~' -> {
                    i++
                    MathNode.Space(0.33f)
                }
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || (s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()))) i++
                    MathNode.Sym(s.substring(start, i), SymKind.Num)
                }
                c.isLetter() -> {
                    i++
                    MathNode.Sym(c.toString(), SymKind.Var)
                }
                else -> {
                    i++
                    MathNode.Sym(
                        when (c) {
                            '-' -> "−"
                            '*' -> "∗"
                            else -> c.toString()
                        },
                        when (c) {
                            '+', '-', '*', '/' -> if (c == '/') SymKind.Ord else SymKind.Bin
                            '=', '<', '>', ':' -> SymKind.Rel
                            '(', '[' -> SymKind.Open
                            ')', ']' -> SymKind.Close
                            ',', ';' -> SymKind.Punct
                            '|' -> SymKind.Ord
                            else -> SymKind.Ord
                        },
                    )
                }
            }
        }

        private fun peekCommand(): String {
            var k = i + 1
            if (k >= s.length) return ""
            if (!s[k].isLetter()) return s[k].toString()
            while (k < s.length && s[k].isLetter()) k++
            return s.substring(i + 1, k)
        }

        private fun readCommand(): String {
            val name = peekCommand()
            i += 1 + name.length
            return name
        }

        private fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun textArgument(): String {
            skipSpaces()
            if (i >= s.length) return ""
            if (s[i] != '{') return s[i++].toString()
            var level = 0
            val start = i + 1
            while (i < s.length) {
                when (s[i]) {
                    '{' -> level++
                    '}' -> {
                        level--
                        if (level == 0) {
                            i++
                            return s.substring(start, i - 1)
                        }
                    }
                    '\\' -> i++
                }
                i++
            }
            return s.substring(start)
        }

        private fun optionalArgument(): MathNode? {
            skipSpaces()
            if (i >= s.length || s[i] != '[') return null
            i++
            val inner = row(stopAt = { it == ']' })
            if (i < s.length && s[i] == ']') i++
            return inner
        }

        private fun delimiter(): String {
            skipSpaces()
            if (i >= s.length) return ""
            if (s[i] == '\\') {
                val name = readCommand()
                return DELIMITERS[name] ?: if (name == ".") "" else name
            }
            val c = s[i++]
            return if (c == '.') "" else c.toString()
        }

        private fun command(): MathNode? {
            val name = readCommand()
            if (name.isEmpty()) return null
            SPACES[name]?.let { return MathNode.Space(it) }
            when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> return MathNode.Frac(argument(), argument())
                "binom", "dbinom", "tbinom" -> return MathNode.Fenced("(", MathNode.Frac(argument(), argument(), line = false), ")")
                "sqrt" -> {
                    val index = optionalArgument()
                    return MathNode.Sqrt(argument(), index)
                }
                "left" -> {
                    val left = delimiter()
                    val body = row(stopAt = { false }, stopCommands = setOf("right"))
                    var right = ""
                    if (peekCommand() == "right" && i < s.length) {
                        readCommand()
                        right = delimiter()
                    }
                    return MathNode.Fenced(left, body, right)
                }
                "right" -> {
                    delimiter()
                    return null
                }
                "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr", "middle" -> {
                    val d = delimiter()
                    return MathNode.Sym(d, if (name.endsWith("r")) SymKind.Close else SymKind.Open)
                }
                "text", "textrm", "textnormal", "mbox", "hbox", "textit", "textsf", "texttt" ->
                    return MathNode.Styled(MathNode.Sym(textArgument(), SymKind.Text), MathStyle.Text)
                "textbf" -> return MathNode.Styled(MathNode.Sym(textArgument(), SymKind.Text), MathStyle.Bold)
                "operatorname" -> {
                    val star = i < s.length && s[i] == '*'
                    if (star) i++
                    return MathNode.BigOp(textArgument(), null, null, limits = star, text = true)
                }
                "mathbf", "boldsymbol", "bm", "pmb" -> return MathNode.Styled(argument(), MathStyle.Bold)
                "mathrm", "rm" -> return MathNode.Styled(argument(), MathStyle.Roman)
                "mathbb" -> return MathNode.Styled(argument(), MathStyle.Blackboard)
                "mathcal", "mathscr" -> return MathNode.Styled(argument(), MathStyle.Calligraphic)
                "mathfrak" -> return MathNode.Styled(argument(), MathStyle.Fraktur)
                "mathsf" -> return MathNode.Styled(argument(), MathStyle.SansSerif)
                "mathtt" -> return MathNode.Styled(argument(), MathStyle.Mono)
                "mathit", "mathnormal" -> return argument()
                "boxed", "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "mathop", "mathrel", "mathbin", "mathord" ->
                    return if (name in NO_ARG_SWITCHES) null else argument()
                "color", "textcolor" -> {
                    textArgument()
                    return argument()
                }
                "hat", "widehat" -> return MathNode.Accent(argument(), AccentKind.Hat)
                "bar" -> return MathNode.Accent(argument(), AccentKind.Bar)
                "overline" -> return MathNode.Accent(argument(), AccentKind.Overline)
                "underline" -> return MathNode.Accent(argument(), AccentKind.Underline)
                "vec" -> return MathNode.Accent(argument(), AccentKind.Vec)
                "overrightarrow" -> return MathNode.Accent(argument(), AccentKind.OverArrow)
                "dot" -> return MathNode.Accent(argument(), AccentKind.Dot)
                "ddot" -> return MathNode.Accent(argument(), AccentKind.DDot)
                "tilde", "widetilde" -> return MathNode.Accent(argument(), AccentKind.Tilde)
                "overset", "stackrel" -> {
                    val over = argument()
                    return MathNode.Stack(argument(), over, null)
                }
                "underset" -> {
                    val under = argument()
                    return MathNode.Stack(argument(), null, under)
                }
                "begin" -> return environment(textArgument())
                "end" -> {
                    textArgument()
                    return null
                }
                "\\" -> return MathNode.Space(1f)
                "not" -> {
                    val next = argument()
                    return if (next is MathNode.Sym) MathNode.Sym(next.text + "\u0338", SymKind.Rel) else next
                }
            }
            BIG_OPS[name]?.let { (symbol, limits) -> return MathNode.BigOp(symbol, null, null, limits) }
            if (name in FUNCTIONS) return MathNode.BigOp(name, null, null, limits = name in LIMIT_FUNCTIONS, text = true)
            SYMBOLS[name]?.let { (text, kind) -> return MathNode.Sym(text, kind) }
            DELIMITERS[name]?.let { return MathNode.Sym(it, if (name.startsWith("r") || name == "}") SymKind.Close else SymKind.Open) }
            if (name.length == 1 && !name[0].isLetter()) return MathNode.Sym(name, SymKind.Ord)
            return MathNode.Sym(name, SymKind.Text)
        }

        private fun environment(name: String): MathNode {
            val base = name.removeSuffix("*")
            if (base == "array") textArgument()
            val rows = ArrayList<List<MathNode>>()
            var cells = ArrayList<MathNode>()
            while (i < s.length) {
                val cell = row(stopAt = { it == '&' }, stopCommands = setOf("\\", "end", "cr"))
                cells += cell
                if (i >= s.length) break
                if (s[i] == '&') {
                    i++
                    continue
                }
                val cmd = readCommand()
                if (cmd == "\\" || cmd == "cr") {
                    optionalArgument()
                    rows += cells
                    cells = ArrayList()
                    continue
                }
                if (cmd == "end") {
                    textArgument()
                    break
                }
            }
            if (cells.isNotEmpty() && !(cells.size == 1 && (cells[0] as? MathNode.Row)?.children?.isEmpty() == true)) rows += cells
            val (left, right) = when (base) {
                "pmatrix" -> "(" to ")"
                "bmatrix" -> "[" to "]"
                "Bmatrix" -> "{" to "}"
                "vmatrix" -> "|" to "|"
                "Vmatrix" -> "‖" to "‖"
                "cases", "dcases" -> "{" to ""
                "rcases" -> "" to "}"
                else -> "" to ""
            }
            val align = when (base) {
                "cases", "dcases", "rcases" -> MatrixAlign.Left
                "align", "aligned", "alignat", "alignedat", "split", "eqnarray" -> MatrixAlign.AlignPairs
                else -> MatrixAlign.Center
            }
            if (base in SINGLE_COLUMN && rows.all { it.size <= 1 } && rows.size == 1) return rows[0].firstOrNull() ?: MathNode.Row(emptyList())
            return MathNode.Matrix(rows, left, right, align)
        }
    }

    private val SINGLE_COLUMN = setOf("equation", "gather", "gathered", "multline")
    private val NO_ARG_SWITCHES = setOf("displaystyle", "textstyle", "scriptstyle", "limits", "nolimits")

    private val SPACES = mapOf(
        "," to 0.17f, ":" to 0.22f, ">" to 0.22f, ";" to 0.28f, "!" to -0.17f, " " to 0.33f,
        "quad" to 1f, "qquad" to 2f, "enspace" to 0.5f, "thinspace" to 0.17f,
    )

    private val BIG_OPS = mapOf(
        "sum" to ("∑" to true), "prod" to ("∏" to true), "coprod" to ("∐" to true),
        "int" to ("∫" to false), "iint" to ("∬" to false), "iiint" to ("∭" to false), "oint" to ("∮" to false),
        "bigcup" to ("⋃" to true), "bigcap" to ("⋂" to true), "bigoplus" to ("⨁" to true),
        "bigotimes" to ("⨂" to true), "bigvee" to ("⋁" to true), "bigwedge" to ("⋀" to true), "bigsqcup" to ("⨆" to true),
    )

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "log", "ln", "lg", "exp", "lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "dim", "ker",
        "deg", "gcd", "lcm", "arg", "Pr", "hom", "mod", "bmod", "argmax", "argmin", "sgn", "tr", "rank",
    )
    private val LIMIT_FUNCTIONS = setOf("lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "gcd", "Pr", "argmax", "argmin")

    private val DELIMITERS = mapOf(
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
        "{" to "{", "}" to "}", "|" to "‖", "vert" to "|", "Vert" to "‖", "lvert" to "|", "rvert" to "|",
        "lVert" to "‖", "rVert" to "‖", "lbrace" to "{", "rbrace" to "}", "lbrack" to "[", "rbrack" to "]",
    )

    private fun ord(t: String) = t to SymKind.Ord
    private fun rel(t: String) = t to SymKind.Rel
    private fun bin(t: String) = t to SymKind.Bin

    private val SYMBOLS: Map<String, Pair<String, SymKind>> = buildMap {
        val greek = mapOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε",
            "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
            "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ",
            "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
            "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        )
        greek.forEach { (k, v) -> put(k, v to SymKind.Var) }
        val upper = mapOf(
            "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
            "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        )
        upper.forEach { (k, v) -> put(k, v to SymKind.Ord) }
        listOf(
            "times" to "×", "cdot" to "⋅", "pm" to "±", "mp" to "∓", "div" to "÷", "ast" to "∗", "star" to "⋆",
            "circ" to "∘", "bullet" to "∙", "cap" to "∩", "cup" to "∪", "wedge" to "∧", "land" to "∧", "vee" to "∨",
            "lor" to "∨", "oplus" to "⊕", "ominus" to "⊖", "otimes" to "⊗", "odot" to "⊙", "setminus" to "∖",
            "sqcup" to "⊔", "sqcap" to "⊓", "dagger" to "†", "amalg" to "⨿",
        ).forEach { (k, v) -> put(k, bin(v)) }
        listOf(
            "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
            "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "in" to "∈",
            "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
            "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "Rightarrow" to "⇒",
            "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦",
            "implies" to "⟹", "iff" to "⟺", "impliedby" to "⟸", "longrightarrow" to "⟶", "longleftarrow" to "⟵",
            "Longrightarrow" to "⟹", "uparrow" to "↑", "downarrow" to "↓", "nearrow" to "↗", "searrow" to "↘",
            "ll" to "≪", "gg" to "≫", "perp" to "⊥", "parallel" to "∥", "mid" to "∣", "nmid" to "∤",
            "prec" to "≺", "succ" to "≻", "preceq" to "⪯", "succeq" to "⪰", "vdash" to "⊢", "models" to "⊨",
            "asymp" to "≍", "doteq" to "≐", "leqslant" to "⩽", "geqslant" to "⩾", "lesssim" to "≲", "gtrsim" to "≳",
            "coloneqq" to "≔", "triangleq" to "≜", "rightleftharpoons" to "⇌", "hookrightarrow" to "↪",
        ).forEach { (k, v) -> put(k, rel(v)) }
        listOf(
            "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
            "emptyset" to "∅", "varnothing" to "∅", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
            "aleph" to "ℵ", "angle" to "∠", "triangle" to "△", "degree" to "°", "prime" to "′", "dots" to "…",
            "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱", "neg" to "¬", "lnot" to "¬",
            "top" to "⊤", "bot" to "⊥", "checkmark" to "✓", "square" to "□", "blacksquare" to "■", "Box" to "□",
            "diamond" to "⋄", "clubsuit" to "♣", "heartsuit" to "♡", "spadesuit" to "♠", "wp" to "℘",
            "imath" to "ı", "jmath" to "ȷ", "dag" to "†", "S" to "§", "P" to "¶", "copyright" to "©",
            "therefore" to "∴", "because" to "∵", "qed" to "∎", "sharp" to "♯", "flat" to "♭", "natural" to "♮",
            "backslash" to "∖", "colon" to ":", "%" to "%", "#" to "#", "$" to "$", "&" to "&", "_" to "_",
        ).forEach { (k, v) -> put(k, ord(v)) }
    }
}
