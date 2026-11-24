"""Wake phrase, adversarial phrases and phonemization for "Hey Atom".

No GPL phonemizer (espeak-ng) is used: target phrases are written by hand in the
espeak-style IPA that Piper voices were trained on, and all other text is phonemized
with our own ARPAbet->IPA mapping over the CMU Pronouncing Dictionary (BSD-2-Clause
data file, read directly; the GPL `cmudict` Python wrapper is NOT used).
"""
import random
import re

# ----------------------------------------------------------------------------------
# Positive realisations of "hey atom" (all are the SAME wake phrase, different accents /
# speaking styles). Weights are relative sampling frequencies.
#   US voices flap the t (ˈæɾəm); careful speech has a clear t; GB uses 'a' for TRAP and
#   sometimes a glottal stop; ˈɛtəm / ˈeːʈəm approximate common Indian-English vowels and
#   retroflex t; "hey, atom" (pause) is a POSITIVE variant.
# ----------------------------------------------------------------------------------
POS_US = [("hˈeɪ ˈæɾəm", 5), ("hˈeɪ ˈætəm", 3), ("hˈeɪ, ˈæɾəm", 1), ("hˈeɪ, ˈætəm", 1),
          ("hˈeɪ ˈæɾʌm", 1), ("hˈeɪ ˈɛtəm", 1), ("hˈeː ˈætəm", 1), ("hˈeɪ ˈætʌm", 1),
          ("hˈeː ˈeːʈəm", 1), ("hˈeɪ ˈæʈəm", 1)]
POS_GB = [("hˈeɪ ˈatəm", 5), ("hˈeɪ ˈætəm", 2), ("hˈeɪ, ˈatəm", 1), ("hˈeɪ ˈaʔəm", 1),
          ("hˈeɪ ˈɛtəm", 1), ("hˈeː ˈatəm", 1), ("hˈeɪ ˈatʌm", 1), ("hˈeː ˈeːʈəm", 1),
          ("hˈeɪ ˈaʈəm", 1)]
END_PUNCT = [("", 6), (".", 2), ("!", 1), ("?", 1)]

# ----------------------------------------------------------------------------------
# Adversarial negatives (text, weight). "hey adam" / "atom" / "adam" are the most
# important confusers. Phrases that merely EXTEND the wake phrase ("hey atoms") are
# deliberately excluded: a streaming detector fires at the end of "atom" and cannot be
# expected to wait for a following "s".
# ----------------------------------------------------------------------------------
ADVERSARIAL = [
    ("hey adam", 10), ("hey, adam", 4), ("adam", 6), ("atom", 8), ("the atom", 3), ("an atom", 3),
    ("atoms", 2), ("atomic", 3), ("anatomy", 2), ("hey atomic", 2), ("okay atom", 2), ("say atom", 2),
    ("hey tom", 4), ("hey autumn", 3), ("hey item", 3), ("hey at home", 3), ("hey madam", 3),
    ("hey adams", 2), ("hey anthem", 2), ("hey totem", 2), ("hey bottom", 2), ("hey edam", 2),
    ("hey add them", 2), ("hey at them", 2), ("hey adder", 1), ("hey adam smith", 2), ("hey atlas", 2),
    ("hey alexa", 2), ("hey google", 2), ("hey siri", 2), ("hey there", 2), ("hey man", 2),
    ("hey mom", 2), ("hey anna", 1), ("hey sam", 1), ("hey tim", 1), ("hey aman", 2),
    ("hey ahmed", 1), ("hey amit", 2), ("hey adele", 1), ("hey atari", 1), ("hey ottoman", 1),
    ("a tom", 2), ("hey", 4), ("hey hey", 1), ("hey a", 1), ("they had them", 1), ("pay adam", 1),
    ("hey, what time is it", 1), ("may i have them", 1), ("hey at", 1), ("hey hat", 1),
    ("eight of them", 1), ("hey, i'm adam", 2), ("i'm adam", 1), ("madam", 1), ("adamant", 1),
    ("hey attempt", 1), ("hey custom", 1), ("hey phantom", 1), ("hey random", 1), ("hey stadium", 1),
]

COMMON_WORDS = """the be to of and a in that have i it for not on with he as you do at this but his by from
they we say her she or an will my one all would there their what so up out if about who get which go me
when make can like time no just him know take people into year your good some could them see other than
then now look only come its over think also back after use two how our work first well way even new want
because any these give day most us call phone music play stop open message weather today tomorrow morning
night please thanks okay yes no maybe home office car light timer alarm remind set turn volume battery
camera photo video news song radio map route traffic email text chat friend mother father brother sister
water coffee tea lunch dinner breakfast meeting school college class exam study notes lecture market price
money bank train bus ticket hotel city country india delhi mumbai cricket match team score
""".split()

# ----------------------------------------------------------------------------------
# ARPAbet -> espeak-style IPA
# ----------------------------------------------------------------------------------
_V = {"AA": "ɑː", "AE": "æ", "AH": "ʌ", "AO": "ɔː", "AW": "aʊ", "AY": "aɪ", "EH": "ɛ", "ER": "ɜː",
      "EY": "eɪ", "IH": "ɪ", "IY": "iː", "OW": "oʊ", "OY": "ɔɪ", "UH": "ʊ", "UW": "uː"}
_V0 = {"AH": "ə", "ER": "ɚ", "IY": "i"}
_C = {"B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "F": "f", "G": "ɡ", "HH": "h", "JH": "dʒ", "K": "k",
      "L": "l", "M": "m", "N": "n", "NG": "ŋ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ", "T": "t",
      "TH": "θ", "V": "v", "W": "w", "Y": "j", "Z": "z", "ZH": "ʒ"}


def load_cmudict(path):
    d = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if not line:
                continue
            w, *ph = line.split()
            w = re.sub(r"\(\d+\)$", "", w)
            d.setdefault(w, []).append(ph)
    return d


def arpabet_to_ipa(phones, gb=False):
    out = []
    for p in phones:
        m = re.match(r"([A-Z]+)([012])?$", p)
        base, stress = m.group(1), m.group(2)
        if stress is None:
            out.append(_C[base])
            continue
        v = _V0.get(base, _V[base]) if stress == "0" else _V[base]
        if gb:
            v = {"æ": "a", "oʊ": "əʊ", "ɚ": "ə", "ɑː": "ɑː"}.get(v, v)
        out.append(("ˈ" if stress == "1" else "ˌ" if stress == "2" else "") + v)
    return "".join(out)


class Phonemizer:
    def __init__(self, cmudict_path):
        self.d = load_cmudict(cmudict_path)
        self.words = [w for w, prs in self.d.items() if re.fullmatch(r"[a-z]{2,12}", w)]

    def word(self, w, gb=False, rng=random):
        prs = self.d.get(w.lower())
        if not prs:
            raise KeyError(w)
        return arpabet_to_ipa(rng.choice(prs), gb=gb)

    def text(self, text, gb=False, rng=random):
        toks = re.findall(r"[a-zA-Z']+|[,.!?]", text)
        parts = []
        for t in toks:
            if t in ",.!?":
                parts[-1] = parts[-1] + t if parts else t
            else:
                parts.append(self.word(t, gb=gb, rng=rng))
        return " ".join(parts)


def weighted(rng, items):
    tot = sum(w for _, w in items)
    r = rng.uniform(0, tot)
    for x, w in items:
        r -= w
        if r <= 0:
            return x
    return items[-1][0]


def positive_ipa(rng, gb):
    return weighted(rng, POS_GB if gb else POS_US) + weighted(rng, END_PUNCT)


_NEAR = re.compile(r"ˈ[æaɛ]ɾ?[tdɾʈʔ]?[əʌ]m")  # anything that sounds like "atom"/"adam"


def random_negative(rng, ph, gb):
    """Random word sequence (sometimes 'hey <word>'), never containing an atom-like word."""
    for _ in range(100):
        n = rng.choice([1, 2, 2, 3, 3, 4, 5])
        pool = COMMON_WORDS if rng.random() < 0.6 else ph.words
        words = [rng.choice(pool) for _ in range(n)]
        if rng.random() < 0.3:
            words = ["hey"] + words[:3]
        try:
            ipa = " ".join(ph.word(w, gb=gb, rng=rng) for w in words)
        except KeyError:
            continue
        if _NEAR.search(ipa):
            continue
        return " ".join(words), ipa + weighted(rng, END_PUNCT)
    raise RuntimeError("could not sample a negative phrase")


def adversarial_negative(rng, ph, gb):
    text = weighted(rng, ADVERSARIAL)
    ipa = ph.text(text, gb=gb, rng=rng)
    if "adam" in text and not gb and rng.random() < 0.5:
        ipa = ipa.replace("ˈædəm", "ˈædʌm")  # slight vowel variety, keep a clear /d/
    return text, ipa + weighted(rng, END_PUNCT)
